package com.lynxeye;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.media.audiofx.NoiseSuppressor;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import androidx.core.app.NotificationCompat;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

/* JADX INFO: loaded from: classes3.dex */
public class AudioService extends Service {
    private static final String NOTIF_CHANNEL = "lynxeye_audio";
    private static final int NOTIF_ID = 77;
    private static final int PORT_AUDIO = 9999;
    private static final int QUEUE_SIZE = 3;
    private AudioFocusRequest audioFocusRequest;
    private AudioManager audioManager;
    private int audioMode;
    private AudioTrack audioTrack;
    private Callback callback;
    private String deviceIp;
    private String deviceName;
    private DspEqualizer dspEq;
    private NoiseSuppressor noiseSuppressor;
    private File recordingFile;
    private FileOutputStream recordingStream;
    private int sampleRate;
    private final IBinder binder = new AudioBinder();
    private volatile boolean running = false;
    public volatile boolean audioEnabled = true;
    public volatile boolean audioConnected = false;
    private volatile boolean isRecording = false;
    private final BlockingQueue<byte[]> audioQueue = new ArrayBlockingQueue(3);
    private volatile Socket audioSocket = null;
    private long totalAudioBytes = 0;

    public interface Callback {
        void onAudioConnected(boolean z);
    }

    public class AudioBinder extends Binder {
        public AudioBinder() {
        }

        public AudioService getService() {
            return AudioService.this;
        }
    }

    @Override // android.app.Service
    public IBinder onBind(Intent intent) {
        return this.binder;
    }

    @Override // android.app.Service
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && "STOP".equals(intent.getAction())) {
            stopSelf();
            return 2;
        }
        return 1;
    }

    @Override // android.app.Service
    public void onDestroy() {
        super.onDestroy();
        stopAudio();
    }

    public void startMonitoring(String ip, String name, int sr, int mode, boolean noiseSup) {
        if (this.running && ip.equals(this.deviceIp)) {
            return;
        }
        if (this.running) {
            this.running = false;
            this.audioQueue.clear();
            try {
                Thread.sleep(300L);
            } catch (Exception e) {
            }
            AudioTrack audioTrack = this.audioTrack;
            if (audioTrack != null) {
                try {
                    audioTrack.stop();
                    this.audioTrack.release();
                } catch (Exception e2) {
                }
                this.audioTrack = null;
            }
            NoiseSuppressor noiseSuppressor = this.noiseSuppressor;
            if (noiseSuppressor != null) {
                try {
                    noiseSuppressor.release();
                } catch (Exception e3) {
                }
                this.noiseSuppressor = null;
            }
        }
        this.deviceIp = ip;
        this.deviceName = name;
        this.sampleRate = sr;
        this.audioMode = mode;
        setupAudioTrack();
        if (noiseSup) {
            setupNoiseSuppressor();
        }
        this.dspEq = new DspEqualizer(sr);
        this.running = true;
        this.audioEnabled = true;
        startAudioReceiver();
        startAudioPlayer();
        showNotification("Conectando...");
    }

    public void stopMonitoring() {
        stopSelf();
    }

    public void setCallback(Callback cb) {
        this.callback = cb;
    }

    public void setAudioEnabled(boolean enabled) {
        this.audioEnabled = enabled;
        if (!enabled) {
            this.audioQueue.clear();
            Socket s = this.audioSocket;
            if (s != null && !s.isClosed()) {
                try {
                    s.close();
                } catch (Exception e) {
                }
            }
            this.audioSocket = null;
            updateNotification("Audio pausado");
            return;
        }
        updateNotification("Reconectando...");
    }

    public void setGain(int band, float db) {
        DspEqualizer dspEqualizer = this.dspEq;
        if (dspEqualizer != null) {
            dspEqualizer.setGain(band, db);
        }
    }

    public float getGain(int band) {
        DspEqualizer dspEqualizer = this.dspEq;
        if (dspEqualizer != null) {
            return dspEqualizer.getGain(band);
        }
        return 0.0f;
    }

    public boolean isRunning() {
        return this.running;
    }

    public String getDeviceIp() {
        return this.deviceIp;
    }

    public boolean startRecording(File dir) {
        try {
            String fn = "LynxEye_" + new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.getDefault()).format(new Date()) + ".wav";
            this.recordingFile = new File(dir, fn);
            FileOutputStream fileOutputStream = new FileOutputStream(this.recordingFile);
            this.recordingStream = fileOutputStream;
            int ch = this.audioMode == 1 ? 2 : 1;
            writeWavHeader(fileOutputStream, 0L, this.sampleRate, ch);
            this.totalAudioBytes = 0L;
            this.isRecording = true;
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public File stopRecording() {
        this.isRecording = false;
        try {
            FileOutputStream fileOutputStream = this.recordingStream;
            if (fileOutputStream != null) {
                fileOutputStream.flush();
                this.recordingStream.close();
                this.recordingStream = null;
            }
            File file = this.recordingFile;
            if (file != null && file.exists()) {
                int ch = this.audioMode == 1 ? 2 : 1;
                updateWavHeader(this.recordingFile, this.totalAudioBytes, this.sampleRate, ch);
                return this.recordingFile;
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return null;
    }

    public boolean isRecording() {
        return this.isRecording;
    }

    private void requestAudioFocus() {
        this.audioManager = (AudioManager) getSystemService("audio");
        AudioAttributes attrs = new AudioAttributes.Builder().setUsage(1).setContentType(1).build();
        AudioFocusRequest audioFocusRequestBuild = new AudioFocusRequest.Builder(1).setAudioAttributes(attrs).setAcceptsDelayedFocusGain(true).setOnAudioFocusChangeListener(new AudioManager.OnAudioFocusChangeListener() { // from class: com.lynxeye.AudioService$$ExternalSyntheticLambda0
            @Override // android.media.AudioManager.OnAudioFocusChangeListener
            public final void onAudioFocusChange(int i) {
                AudioService.lambda$requestAudioFocus$0(i);
            }
        }).build();
        this.audioFocusRequest = audioFocusRequestBuild;
        this.audioManager.requestAudioFocus(audioFocusRequestBuild);
    }

    static /* synthetic */ void lambda$requestAudioFocus$0(int focusChange) {
    }

    private void setupAudioTrack() {
        requestAudioFocus();
        int ch = this.audioMode == 1 ? 12 : 4;
        int min = AudioTrack.getMinBufferSize(this.sampleRate, ch, 2);
        AudioTrack audioTrackBuild = new AudioTrack.Builder().setAudioAttributes(new AudioAttributes.Builder().setUsage(1).setContentType(1).build()).setAudioFormat(new AudioFormat.Builder().setEncoding(2).setSampleRate(this.sampleRate).setChannelMask(ch).build()).setBufferSizeInBytes(min * 2).setTransferMode(1).build();
        this.audioTrack = audioTrackBuild;
        audioTrackBuild.play();
    }

    private void setupNoiseSuppressor() {
        try {
            if (NoiseSuppressor.isAvailable()) {
                NoiseSuppressor noiseSuppressorCreate = NoiseSuppressor.create(this.audioTrack.getAudioSessionId());
                this.noiseSuppressor = noiseSuppressorCreate;
                if (noiseSuppressorCreate != null) {
                    noiseSuppressorCreate.setEnabled(true);
                }
            }
        } catch (Exception e) {
        }
    }

    private void startAudioReceiver() {
        new Thread(new Runnable() { // from class: com.lynxeye.AudioService$$ExternalSyntheticLambda1
            @Override // java.lang.Runnable
            public final void run() {
                this.f$0.m141lambda$startAudioReceiver$1$comlynxeyeAudioService();
            }
        }, "AudioReceiver").start();
    }

    /* JADX INFO: renamed from: lambda$startAudioReceiver$1$com-lynxeye-AudioService, reason: not valid java name */
    /* synthetic */ void m141lambda$startAudioReceiver$1$comlynxeyeAudioService() {
        int r;
        while (this.running) {
            if (!this.audioEnabled) {
                try {
                    Thread.sleep(500L);
                } catch (InterruptedException e) {
                    return;
                }
            } else {
                Socket socket = null;
                try {
                    socket = new Socket();
                    socket.connect(new InetSocketAddress(this.deviceIp, PORT_AUDIO), 5000);
                    socket.setTcpNoDelay(true);
                    socket.setSoTimeout(10000);
                    socket.setKeepAlive(true);
                    this.audioSocket = socket;
                    InputStream in = socket.getInputStream();
                    byte[] buf = new byte[4096];
                    this.audioConnected = true;
                    notifyCallback(true);
                    updateNotification("Audio activo");
                    while (this.running && !socket.isClosed() && (r = in.read(buf)) >= 0) {
                        if (r > 0) {
                            byte[] chunk = new byte[r];
                            System.arraycopy(buf, 0, chunk, 0, r);
                            if (!this.audioQueue.offer(chunk)) {
                                this.audioQueue.poll();
                                this.audioQueue.offer(chunk);
                            }
                        }
                    }
                    this.audioConnected = false;
                    this.audioSocket = null;
                    notifyCallback(false);
                    this.audioQueue.clear();
                    try {
                        socket.close();
                    } catch (Exception e2) {
                    }
                } catch (Exception e3) {
                    this.audioConnected = false;
                    this.audioSocket = null;
                    notifyCallback(false);
                    this.audioQueue.clear();
                    if (socket != null) {
                        try {
                            socket.close();
                        } catch (Exception e4) {
                        }
                    }
                    if (this.running && this.audioEnabled) {
                        updateNotification("Reconectando...");
                        Thread.sleep(2000L);
                    }
                } catch (Throwable th) {
                    this.audioConnected = false;
                    this.audioSocket = null;
                    notifyCallback(false);
                    this.audioQueue.clear();
                    if (socket != null) {
                        try {
                            socket.close();
                        } catch (Exception e5) {
                        }
                    }
                    if (this.running && this.audioEnabled) {
                        updateNotification("Reconectando...");
                        Thread.sleep(2000L);
                        throw th;
                    }
                    updateNotification("Audio pausado");
                    throw th;
                }
                if (this.running && this.audioEnabled) {
                    updateNotification("Reconectando...");
                    try {
                        Thread.sleep(2000L);
                    } catch (InterruptedException e6) {
                        return;
                    }
                } else {
                    updateNotification("Audio pausado");
                }
            }
        }
    }

    private void startAudioPlayer() {
        new Thread(new Runnable() { // from class: com.lynxeye.AudioService$$ExternalSyntheticLambda2
            @Override // java.lang.Runnable
            public final void run() {
                this.f$0.m140lambda$startAudioPlayer$2$comlynxeyeAudioService();
            }
        }, "AudioPlayer").start();
    }

    /* JADX INFO: renamed from: lambda$startAudioPlayer$2$com-lynxeye-AudioService, reason: not valid java name */
    /* synthetic */ void m140lambda$startAudioPlayer$2$comlynxeyeAudioService() {
        FileOutputStream fileOutputStream;
        while (this.running) {
            try {
                byte[] chunk = this.audioQueue.poll(500L, TimeUnit.MILLISECONDS);
                if (chunk != null) {
                    DspEqualizer dspEqualizer = this.dspEq;
                    if (dspEqualizer != null) {
                        chunk = dspEqualizer.process(chunk);
                    }
                    if (this.audioEnabled) {
                        this.audioTrack.write(chunk, 0, chunk.length);
                    }
                    if (this.isRecording && (fileOutputStream = this.recordingStream) != null) {
                        try {
                            fileOutputStream.write(chunk);
                            this.totalAudioBytes += (long) chunk.length;
                        } catch (Exception e) {
                        }
                    }
                }
            } catch (InterruptedException e2) {
                return;
            }
        }
    }

    private void stopAudio() {
        this.running = false;
        this.audioQueue.clear();
        Socket s = this.audioSocket;
        if (s != null) {
            try {
                s.close();
            } catch (Exception e) {
            }
            this.audioSocket = null;
        }
        if (this.isRecording) {
            stopRecording();
        }
        NoiseSuppressor noiseSuppressor = this.noiseSuppressor;
        if (noiseSuppressor != null) {
            try {
                noiseSuppressor.release();
            } catch (Exception e2) {
            }
        }
        AudioTrack audioTrack = this.audioTrack;
        if (audioTrack != null) {
            try {
                audioTrack.stop();
                this.audioTrack.release();
            } catch (Exception e3) {
            }
        }
        if (this.audioManager != null && this.audioFocusRequest != null) {
            this.audioManager.abandonAudioFocusRequest(this.audioFocusRequest);
        }
        stopForeground(true);
    }

    private void notifyCallback(final boolean connected) {
        if (this.callback != null) {
            new Handler(Looper.getMainLooper()).post(new Runnable() { // from class: com.lynxeye.AudioService$$ExternalSyntheticLambda3
                @Override // java.lang.Runnable
                public final void run() {
                    this.f$0.m139lambda$notifyCallback$3$comlynxeyeAudioService(connected);
                }
            });
        }
    }

    /* JADX INFO: renamed from: lambda$notifyCallback$3$com-lynxeye-AudioService, reason: not valid java name */
    /* synthetic */ void m139lambda$notifyCallback$3$comlynxeyeAudioService(boolean connected) {
        this.callback.onAudioConnected(connected);
    }

    private void showNotification(String text) {
        createChannel();
        startForeground(NOTIF_ID, buildNotification(text));
    }

    private void updateNotification(String text) {
        NotificationManager nm = (NotificationManager) getSystemService("notification");
        nm.notify(NOTIF_ID, buildNotification(text));
    }

    private Notification buildNotification(String text) {
        Intent open = new Intent(this, (Class<?>) MainActivity.class);
        open.setFlags(536870912);
        PendingIntent openPi = PendingIntent.getActivity(this, 0, open, 201326592);
        Intent stop = new Intent(this, (Class<?>) AudioService.class);
        stop.setAction("STOP");
        PendingIntent stopPi = PendingIntent.getService(this, 1, stop, 201326592);
        return new NotificationCompat.Builder(this, NOTIF_CHANNEL).setContentTitle("LynxEye — " + this.deviceName).setContentText(text).setSmallIcon(android.R.drawable.ic_btn_speak_now).setContentIntent(openPi).addAction(android.R.drawable.ic_delete, "Detener", stopPi).setOngoing(true).build();
    }

    private void createChannel() {
        NotificationChannel ch = new NotificationChannel(NOTIF_CHANNEL, "LynxEye Audio", 2);
        ((NotificationManager) getSystemService("notification")).createNotificationChannel(ch);
    }

    private void writeWavHeader(FileOutputStream out, long bytes, int sr, int ch) throws IOException {
        long total = 36 + bytes;
        long br = ((long) sr) * ((long) ch) * 2;
        byte[] h = {82, 73, 70, 70, (byte) total, (byte) (total >> 8), (byte) (total >> 16), (byte) (total >> 24), 87, 65, 86, 69, 102, 109, 116, 32, 16, 0, 0, 0, 1, 0, (byte) ch, 0, (byte) sr, (byte) (sr >> 8), (byte) (sr >> 16), (byte) (sr >> 24), (byte) br, (byte) (br >> 8), (byte) (br >> 16), (byte) (br >> 24), (byte) (ch * 2), 0, 16, 0, 100, 97, 116, 97, (byte) bytes, (byte) (bytes >> 8), (byte) (bytes >> 16), (byte) (bytes >> 24)};
        out.write(h);
    }

    private void updateWavHeader(File f, long bytes, int sr, int ch) throws IOException {
        RandomAccessFile raf = new RandomAccessFile(f, "rw");
        long total = 36 + bytes;
        raf.seek(4L);
        raf.write((byte) total);
        raf.write((byte) (total >> 8));
        raf.write((byte) (total >> 16));
        raf.write((byte) (total >> 24));
        raf.seek(40L);
        raf.write((byte) bytes);
        raf.write((byte) (bytes >> 8));
        raf.write((byte) (bytes >> 16));
        raf.write((byte) (bytes >> 24));
        raf.close();
    }
}
