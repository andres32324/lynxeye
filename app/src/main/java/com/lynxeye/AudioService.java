package com.lynxeye;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.media.audiofx.NoiseSuppressor;
import android.os.Binder;
import android.os.Build;
import android.os.IBinder;
import androidx.core.app.NotificationCompat;

import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

public class AudioService extends Service {

    private static final String NOTIF_CHANNEL = "lynxeye_audio";
    private static final int    NOTIF_ID      = 77;
    private static final int    PORT_AUDIO    = 9999;
    private static final int    QUEUE_SIZE    = 3;

    public class AudioBinder extends Binder {
        public AudioService getService() { return AudioService.this; }
    }
    private final IBinder binder = new AudioBinder();

    private String  deviceIp;
    private String  deviceName;
    private int     sampleRate;
    private int     audioMode;

    private volatile boolean running        = false;
    public  volatile boolean audioEnabled   = true;
    public  volatile boolean audioConnected = false;
    private volatile boolean isRecording    = false;

    private AudioTrack        audioTrack;
    private DspEqualizer      dspEq;
    private NoiseSuppressor   noiseSuppressor;
    private AudioManager      audioManager;
    private AudioFocusRequest audioFocusRequest;

    private final BlockingQueue<byte[]> audioQueue = new ArrayBlockingQueue<>(QUEUE_SIZE);
    // Referencia al socket activo para poder cerrarlo desde setAudioEnabled
    private volatile Socket audioSocket = null;

    private File             recordingFile;
    private FileOutputStream recordingStream;
    private long             totalAudioBytes = 0;

    public interface Callback {
        void onAudioConnected(boolean connected);
        void onAudioLevel(float level);
    }
    private Callback callback;

    // ─── Lifecycle ────────────────────────────────────────
    @Override public IBinder onBind(Intent intent) { return binder; }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && "STOP".equals(intent.getAction())) {
            stopSelf(); return START_NOT_STICKY;
        }
        return START_STICKY;
    }

    @Override public void onDestroy() { super.onDestroy(); stopAudio(); }

    // ─── Public API ───────────────────────────────────────
    public void startMonitoring(String ip, String name, int sr, int mode, boolean noiseSup) {
        if (running && ip.equals(deviceIp)) return;
        if (running) {
            running = false;
            audioQueue.clear();
            try { Thread.sleep(300); } catch (Exception ignored) {}
            if (audioTrack != null) { try { audioTrack.stop(); audioTrack.release(); } catch (Exception ignored) {} audioTrack = null; }
            if (noiseSuppressor != null) { try { noiseSuppressor.release(); } catch (Exception ignored) {} noiseSuppressor = null; }
        }
        this.deviceIp   = ip;
        this.deviceName = name;
        this.sampleRate = sr;
        this.audioMode  = mode;
        setupAudioTrack();
        if (noiseSup) setupNoiseSuppressor();
        dspEq = new DspEqualizer(sr);
        running = true;
        audioEnabled = true; // Siempre arranca con audio habilitado
        startAudioReceiver();
        startAudioPlayer();
        showNotification("Conectando...");
    }

    public void stopMonitoring() { stopSelf(); }
    public void setCallback(Callback cb) { this.callback = cb; }

    public void setAudioEnabled(boolean enabled) {
        audioEnabled = enabled;
        if (!enabled) {
            audioQueue.clear();
            // Cerrar socket activo → Menu Claro detecta desconexión → apaga mic → LED apaga
            Socket s = audioSocket;
            if (s != null && !s.isClosed()) {
                try { s.close(); } catch (Exception ignored) {}
            }
            audioSocket = null;
            updateNotification("Audio pausado");
        } else {
            // AudioReceiver reconecta automáticamente cuando audioEnabled=true
            updateNotification("Reconectando...");
        }
    }

    public void setGain(int band, float db) { if (dspEq != null) dspEq.setGain(band, db); }
    public float getGain(int band) { return dspEq != null ? dspEq.getGain(band) : 0; }
    public boolean isRunning() { return running; }
    public String getDeviceIp() { return deviceIp; }

    // ─── Recording ────────────────────────────────────────
    public boolean startRecording(File dir) {
        try {
            String fn = "LynxEye_" + new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss",
                    Locale.getDefault()).format(new Date()) + ".wav";
            recordingFile   = new File(dir, fn);
            recordingStream = new FileOutputStream(recordingFile);
            int ch = audioMode == 1 ? 2 : 1;
            writeWavHeader(recordingStream, 0, sampleRate, ch);
            totalAudioBytes = 0;
            isRecording = true;
            return true;
        } catch (Exception e) { return false; }
    }

    public File stopRecording() {
        isRecording = false;
        try {
            if (recordingStream != null) { recordingStream.flush(); recordingStream.close(); recordingStream = null; }
            if (recordingFile != null && recordingFile.exists()) {
                int ch = audioMode == 1 ? 2 : 1;
                updateWavHeader(recordingFile, totalAudioBytes, sampleRate, ch);
                return recordingFile;
            }
        } catch (Exception e) { e.printStackTrace(); }
        return null;
    }

    public boolean isRecording() { return isRecording; }

    // ─── Audio Setup ──────────────────────────────────────
    private void requestAudioFocus() {
        audioManager = (AudioManager) getSystemService(AUDIO_SERVICE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            AudioAttributes attrs = new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build();
            audioFocusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                    .setAudioAttributes(attrs)
                    .setAcceptsDelayedFocusGain(true)
                    .setOnAudioFocusChangeListener(focusChange -> {})
                    .build();
            audioManager.requestAudioFocus(audioFocusRequest);
        } else {
            audioManager.requestAudioFocus(null, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN);
        }
    }

    private void setupAudioTrack() {
        requestAudioFocus();
        int ch  = audioMode == 1 ? AudioFormat.CHANNEL_OUT_STEREO : AudioFormat.CHANNEL_OUT_MONO;
        int min = AudioTrack.getMinBufferSize(sampleRate, ch, AudioFormat.ENCODING_PCM_16BIT);
        audioTrack = new AudioTrack.Builder()
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                .setAudioFormat(new AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sampleRate).setChannelMask(ch).build())
                .setBufferSizeInBytes(min * 2)
                .setTransferMode(AudioTrack.MODE_STREAM).build();
        audioTrack.play();
    }

    private void setupNoiseSuppressor() {
        try {
            if (NoiseSuppressor.isAvailable()) {
                noiseSuppressor = NoiseSuppressor.create(audioTrack.getAudioSessionId());
                if (noiseSuppressor != null) noiseSuppressor.setEnabled(true);
            }
        } catch (Exception ignored) {}
    }

    // ─── Audio Receiver ───────────────────────────────────
    private void startAudioReceiver() {
        new Thread(() -> {
            while (running) {
                // Si audio está deshabilitado, esperar sin conectar
                if (!audioEnabled) {
                    try { Thread.sleep(500); } catch (InterruptedException e) { break; }
                    continue;
                }
                Socket socket = null;
                try {
                    socket = new Socket();
                    socket.connect(new InetSocketAddress(deviceIp, PORT_AUDIO), 5000);
                    socket.setTcpNoDelay(true);
                    socket.setSoTimeout(10000);
                    socket.setKeepAlive(true);
                    audioSocket = socket; // Guardar referencia
                    InputStream in = socket.getInputStream();
                    byte[] buf = new byte[4096];
                    audioConnected = true;
                    notifyCallback(true);
                    updateNotification("Audio activo");
                    while (running && !socket.isClosed()) {
                        int r = in.read(buf);
                        if (r < 0) break;
                        if (r > 0) {
                            byte[] chunk = new byte[r];
                            System.arraycopy(buf, 0, chunk, 0, r);
                            if (!audioQueue.offer(chunk)) { audioQueue.poll(); audioQueue.offer(chunk); }
                        }
                    }
                } catch (Exception ignored) {
                } finally {
                    audioConnected = false;
                    audioSocket = null;
                    notifyCallback(false);
                    audioQueue.clear();
                    if (socket != null) try { socket.close(); } catch (Exception ignored2) {}
                    if (running && audioEnabled) {
                        updateNotification("Reconectando...");
                        try { Thread.sleep(2000); } catch (InterruptedException e) { break; }
                    } else {
                        updateNotification("Audio pausado");
                    }
                }
            }
        }, "AudioReceiver").start();
    }

    // ─── Audio Player ─────────────────────────────────────
    private void startAudioPlayer() {
        new Thread(() -> {
            while (running) {
                try {
                    byte[] chunk = audioQueue.poll(500, TimeUnit.MILLISECONDS);
                    if (chunk != null) {
                        if (dspEq != null) chunk = dspEq.process(chunk);
                        if (audioEnabled) audioTrack.write(chunk, 0, chunk.length);
                        notifyLevel(chunk);
                        if (isRecording && recordingStream != null) {
                            try { recordingStream.write(chunk); totalAudioBytes += chunk.length; }
                            catch (Exception ignored) {}
                        }
                    }
                } catch (InterruptedException e) { break; }
            }
        }, "AudioPlayer").start();
    }

    private void notifyLevel(byte[] chunk) {
        if (callback == null) return;
        long sum = 0;
        for (int i = 0; i < chunk.length - 1; i += 2) {
            short s = (short) ((chunk[i + 1] << 8) | (chunk[i] & 0xFF));
            sum += (long) s * s;
        }
        float rms = (float) Math.sqrt(sum / (chunk.length / 2.0));
        float level = Math.min(1.0f, rms / 32768f);
        android.os.Handler h = new android.os.Handler(android.os.Looper.getMainLooper());
        h.post(() -> { if (callback != null) callback.onAudioLevel(level); });
    }

    private void stopAudio() {
        running = false;
        audioQueue.clear();
        Socket s = audioSocket;
        if (s != null) { try { s.close(); } catch (Exception ignored) {} audioSocket = null; }
        if (isRecording) stopRecording();
        if (noiseSuppressor != null) { try { noiseSuppressor.release(); } catch (Exception ignored) {} }
        if (audioTrack != null) { try { audioTrack.stop(); audioTrack.release(); } catch (Exception ignored) {} }
        if (audioManager != null && audioFocusRequest != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioManager.abandonAudioFocusRequest(audioFocusRequest);
        }
        stopForeground(true);
    }

    private void notifyCallback(boolean connected) {
        if (callback != null) {
            new android.os.Handler(android.os.Looper.getMainLooper())
                    .post(() -> callback.onAudioConnected(connected));
        }
    }

    // ─── Notification ─────────────────────────────────────
    private void showNotification(String text) { createChannel(); startForeground(NOTIF_ID, buildNotification(text)); }

    private void updateNotification(String text) {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        nm.notify(NOTIF_ID, buildNotification(text));
    }

    private Notification buildNotification(String text) {
        Intent open = new Intent(this, MainActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent openPi = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Intent stop = new Intent(this, AudioService.class);
        stop.setAction("STOP");
        PendingIntent stopPi = PendingIntent.getService(this, 1, stop,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new NotificationCompat.Builder(this, NOTIF_CHANNEL)
                .setContentTitle("LynxEye — " + deviceName)
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setContentIntent(openPi)
                .addAction(android.R.drawable.ic_delete, "Detener", stopPi)
                .setOngoing(true).build();
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(
                    NOTIF_CHANNEL, "LynxEye Audio", NotificationManager.IMPORTANCE_LOW);
            ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).createNotificationChannel(ch);
        }
    }

    // ─── WAV ──────────────────────────────────────────────
    private void writeWavHeader(FileOutputStream out, long bytes, int sr, int ch) throws IOException {
        long total = bytes + 36;
        byte[] h = new byte[44];
        h[0]='R';h[1]='I';h[2]='F';h[3]='F';
        h[4]=(byte)total;h[5]=(byte)(total>>8);h[6]=(byte)(total>>16);h[7]=(byte)(total>>24);
        h[8]='W';h[9]='A';h[10]='V';h[11]='E';
        h[12]='f';h[13]='m';h[14]='t';h[15]=' ';
        h[16]=16;h[20]=1;h[22]=(byte)ch;
        h[24]=(byte)sr;h[25]=(byte)(sr>>8);h[26]=(byte)(sr>>16);h[27]=(byte)(sr>>24);
        long br = (long)sr * ch * 2;
        h[28]=(byte)br;h[29]=(byte)(br>>8);h[30]=(byte)(br>>16);h[31]=(byte)(br>>24);
        h[32]=(byte)(ch*2);h[34]=16;
        h[36]='d';h[37]='a';h[38]='t';h[39]='a';
        h[40]=(byte)bytes;h[41]=(byte)(bytes>>8);h[42]=(byte)(bytes>>16);h[43]=(byte)(bytes>>24);
        out.write(h);
    }

    private void updateWavHeader(File f, long bytes, int sr, int ch) throws IOException {
        RandomAccessFile raf = new RandomAccessFile(f, "rw");
        long total = bytes + 36;
        raf.seek(4);
        raf.write((byte)total);raf.write((byte)(total>>8));raf.write((byte)(total>>16));raf.write((byte)(total>>24));
        raf.seek(40);
        raf.write((byte)bytes);raf.write((byte)(bytes>>8));raf.write((byte)(bytes>>16));raf.write((byte)(bytes>>24));
        raf.close();
    }
}
