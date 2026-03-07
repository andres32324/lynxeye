package com.lynxeye;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.media.audiofx.NoiseSuppressor;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.NotificationCompat;

import java.io.DataInputStream;
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

public class MonitorActivity extends AppCompatActivity {

    private static final int PORT_AUDIO   = 9999;
    private static final int PORT_VIDEO   = 9998;
    private static final int PORT_COMMAND = 9997;
    private static final int AUDIO_QUEUE_SIZE = 3;
    private static final int VIDEO_QUEUE_SIZE  = 2;
    private static final String NOTIF_CHANNEL = "lynxeye_monitor";
    private static final int NOTIF_ID = 42;

    private String deviceName, deviceIp;
    private int sampleRate;
    private int audioMode;
    private boolean videoEnabled;

    private TextView    tvStatus, tvDeviceName, tvRecTime;
    private ImageView   ivVideo;
    private ImageButton btnRecord, btnSwitchCam, btnScreenshot, btnVideoToggle, btnAudioToggle;
    private android.widget.Button btnEq;
    private View        layoutEq;
    private AudioVisualizerView visualizer;

    private AudioTrack      audioTrack;
    private DspEqualizer    dspEq;
    private NoiseSuppressor noiseSuppressor;

    private volatile boolean running        = false;
    private volatile boolean audioEnabled   = true;
    private volatile boolean isRecording    = false;
    private volatile boolean audioConnected = false;
    private volatile boolean videoConnected = false;

    private android.graphics.Bitmap lastFrame = null;

    private final BlockingQueue<byte[]> audioQueue = new ArrayBlockingQueue<>(AUDIO_QUEUE_SIZE);
    private final BlockingQueue<byte[]> videoQueue  = new ArrayBlockingQueue<>(VIDEO_QUEUE_SIZE);

    private File             currentRecordingFile;
    private FileOutputStream recordingStream;
    private long             recordingStart;
    private long             totalAudioBytes = 0;
    private Handler          uiHandler = new Handler();
    private Runnable         recTimeUpdater;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(R.layout.activity_monitor);

        deviceName   = getIntent().getStringExtra("name");
        deviceIp     = getIntent().getStringExtra("ip");
        sampleRate   = AppSettings.getSampleRate(this);
        audioMode    = AppSettings.getAudioMode(this);
        videoEnabled = AppSettings.isVideoEnabled(this);

        tvStatus        = findViewById(R.id.tvStatus);
        tvDeviceName    = findViewById(R.id.tvDeviceName);
        tvRecTime       = findViewById(R.id.tvRecTime);
        ivVideo         = findViewById(R.id.ivVideo);
        btnRecord       = findViewById(R.id.btnRecord);
        btnSwitchCam    = findViewById(R.id.btnSwitchCam);
        btnScreenshot   = findViewById(R.id.btnScreenshot);
        btnVideoToggle  = findViewById(R.id.btnVideoToggle);
        btnAudioToggle  = findViewById(R.id.btnAudioToggle);
        btnEq           = findViewById(R.id.btnEq);
        layoutEq        = findViewById(R.id.layoutEq);
        visualizer      = findViewById(R.id.visualizer);

        tvDeviceName.setText(deviceName);
        setStatus("CONNECTING...", 0xFFFFAA00);

        setupAudioTrack();
        setupNoiseSuppressor();
        setupEqualizer();
        setupButtons();

        running = true;
        sendInitialSettings();
        startAudioReceiver();
        startAudioPlayer();
        if (videoEnabled) {
            startVideoReceiver();
            startVideoRenderer();
            ivVideo.setBackgroundColor(0xFF111111);
        } else {
            ivVideo.setBackgroundColor(0xFF0A1A0A);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        cancelNotification();
    }

    @Override
    protected void onPause() {
        super.onPause();
        // Keep audio running in background with notification
        if (running && audioEnabled) showBackgroundNotification();
    }

    @Override
    public void onBackPressed() {
        // Go back to MainActivity keeping audio alive
        Intent intent = new Intent(this, MainActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        startActivity(intent);
    }

    private void showBackgroundNotification() {
        createNotifChannel();
        Intent intent = new Intent(this, MonitorActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pi = PendingIntent.getActivity(this, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Intent stopIntent = new Intent(this, MonitorActivity.class);
        stopIntent.setAction("STOP_AUDIO");
        PendingIntent stopPi = PendingIntent.getActivity(this, 1, stopIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification notif = new NotificationCompat.Builder(this, NOTIF_CHANNEL)
                .setContentTitle("LynxEye — " + deviceName)
                .setContentText("Audio activo en segundo plano")
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setContentIntent(pi)
                .addAction(android.R.drawable.ic_delete, "Detener", stopPi)
                .setOngoing(true)
                .build();

        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        nm.notify(NOTIF_ID, notif);
    }

    private void cancelNotification() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        nm.cancel(NOTIF_ID);
        // Check if came back from stop action
        if (getIntent() != null && "STOP_AUDIO".equals(getIntent().getAction())) {
            stopAudio();
            finish();
        }
    }

    private void createNotifChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(
                    NOTIF_CHANNEL, "LynxEye Monitor", NotificationManager.IMPORTANCE_LOW);
            ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).createNotificationChannel(ch);
        }
    }

    private void sendInitialSettings() {
        new Thread(() -> {
            try {
                Thread.sleep(1000);
                Socket cmd = new Socket();
                cmd.connect(new InetSocketAddress(deviceIp, PORT_COMMAND), 2000);
                StringBuilder commands = new StringBuilder();
                if (audioMode == 1) commands.append("AUDIO_STEREO\n");
                else commands.append("AUDIO_MONO\n");
                commands.append("SR_").append(sampleRate).append("\n");
                commands.append(videoEnabled ? "VIDEO_ON\n" : "VIDEO_OFF\n");
                cmd.getOutputStream().write(commands.toString().getBytes());
                cmd.getOutputStream().flush();
                cmd.close();
            } catch (Exception ignored) {}
        }).start();
    }

    private void updateStatus() {
        runOnUiThread(() -> {
            if (audioConnected || videoConnected) {
                tvStatus.setText("⬤  CONNECTED");
                tvStatus.setTextColor(0xFF00E676);
            } else {
                tvStatus.setText("⬤  LOST SIGNAL");
                tvStatus.setTextColor(0xFFFF3D3D);
            }
        });
    }

    private void setStatus(String text, int color) {
        runOnUiThread(() -> {
            tvStatus.setText("⬤  " + text);
            tvStatus.setTextColor(color);
        });
    }

    private void setupAudioTrack() {
        int channelMask = (audioMode == 1)
                ? AudioFormat.CHANNEL_OUT_STEREO
                : AudioFormat.CHANNEL_OUT_MONO;
        int minBuf = AudioTrack.getMinBufferSize(sampleRate, channelMask, AudioFormat.ENCODING_PCM_16BIT);
        audioTrack = new AudioTrack.Builder()
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build())
                .setAudioFormat(new AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(channelMask)
                        .build())
                .setBufferSizeInBytes(minBuf * 2)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build();
        audioTrack.play();
    }

    private void setupNoiseSuppressor() {
        try {
            if (AppSettings.isNoiseSuppression(this) && NoiseSuppressor.isAvailable()) {
                noiseSuppressor = NoiseSuppressor.create(audioTrack.getAudioSessionId());
                if (noiseSuppressor != null) noiseSuppressor.setEnabled(true);
            }
        } catch (Exception e) { e.printStackTrace(); }
    }

    private void startAudioReceiver() {
        new Thread(() -> {
            while (running) {
                Socket socket = null;
                try {
                    socket = new Socket();
                    socket.connect(new InetSocketAddress(deviceIp, PORT_AUDIO), 5000);
                    socket.setTcpNoDelay(true);
                    socket.setSoTimeout(10000);
                    socket.setKeepAlive(true);
                    InputStream in = socket.getInputStream();
                    byte[] buffer = new byte[4096];
                    audioConnected = true;
                    updateStatus();
                    while (running && !socket.isClosed()) {
                        int read = in.read(buffer, 0, buffer.length);
                        if (read < 0) break;
                        if (read > 0) {
                            byte[] chunk = new byte[read];
                            System.arraycopy(buffer, 0, chunk, 0, read);
                            if (!audioQueue.offer(chunk)) { audioQueue.poll(); audioQueue.offer(chunk); }
                        }
                    }
                } catch (Exception e) {
                } finally {
                    audioConnected = false;
                    updateStatus();
                    audioQueue.clear();
                    if (socket != null) try { socket.close(); } catch (Exception ignored) {}
                    if (running) try { Thread.sleep(2000); } catch (InterruptedException ie) { break; }
                }
            }
        }, "AudioReceiver").start();
    }

    private void startAudioPlayer() {
        new Thread(() -> {
            while (running) {
                try {
                    byte[] chunk = audioQueue.poll(500, TimeUnit.MILLISECONDS);
                    if (chunk != null) {
                        if (dspEq != null) chunk = dspEq.process(chunk);
                        if (audioEnabled) audioTrack.write(chunk, 0, chunk.length);
                        if (visualizer != null) visualizer.updateAudio(chunk);
                        if (isRecording && recordingStream != null) {
                            try { recordingStream.write(chunk, 0, chunk.length); totalAudioBytes += chunk.length; }
                            catch (Exception ignored) {}
                        }
                    }
                } catch (InterruptedException e) { break; }
            }
        }, "AudioPlayer").start();
    }

    private void startVideoReceiver() {
        new Thread(() -> {
            while (running) {
                Socket socket = null;
                try {
                    socket = new Socket();
                    socket.connect(new InetSocketAddress(deviceIp, PORT_VIDEO), 5000);
                    socket.setTcpNoDelay(true);
                    socket.setSoTimeout(10000);
                    socket.setKeepAlive(true);
                    DataInputStream in = new DataInputStream(socket.getInputStream());
                    videoConnected = true;
                    updateStatus();
                    while (running && !socket.isClosed()) {
                        int len = in.readInt();
                        if (len <= 0 || len > 3_000_000) continue;
                        byte[] frame = new byte[len];
                        int total = 0;
                        while (total < len) { int r = in.read(frame, total, len - total); if (r < 0) break; total += r; }
                        if (!videoQueue.offer(frame)) { videoQueue.poll(); videoQueue.offer(frame); }
                    }
                } catch (Exception e) {
                } finally {
                    videoConnected = false;
                    updateStatus();
                    videoQueue.clear();
                    if (socket != null) try { socket.close(); } catch (Exception ignored) {}
                    if (running) try { Thread.sleep(2000); } catch (InterruptedException ie) { break; }
                }
            }
        }, "VideoReceiver").start();
    }

    private void startVideoRenderer() {
        new Thread(() -> {
            while (running) {
                try {
                    byte[] frame = videoQueue.poll(500, TimeUnit.MILLISECONDS);
                    if (frame != null) {
                        android.graphics.BitmapFactory.Options opts = new android.graphics.BitmapFactory.Options();
                        opts.inPreferredConfig = android.graphics.Bitmap.Config.RGB_565;
                        android.graphics.Bitmap bmp = android.graphics.BitmapFactory.decodeByteArray(frame, 0, frame.length, opts);
                        if (bmp != null) { lastFrame = bmp; runOnUiThread(() -> ivVideo.setImageBitmap(bmp)); }
                    } else if (lastFrame != null) {
                        final android.graphics.Bitmap keep = lastFrame;
                        runOnUiThread(() -> ivVideo.setImageBitmap(keep));
                    }
                } catch (InterruptedException e) { break; }
            }
        }, "VideoRenderer").start();
    }

    private void setupEqualizer() {
        dspEq = new DspEqualizer(sampleRate);
        int[] seekIds  = {R.id.seekBand0,R.id.seekBand1,R.id.seekBand2,R.id.seekBand3,R.id.seekBand4,
                          R.id.seekBand5,R.id.seekBand6,R.id.seekBand7,R.id.seekBand8,R.id.seekBand9};
        int[] gainIds  = {R.id.tvGain0,R.id.tvGain1,R.id.tvGain2,R.id.tvGain3,R.id.tvGain4,
                          R.id.tvGain5,R.id.tvGain6,R.id.tvGain7,R.id.tvGain8,R.id.tvGain9};
        int[] labelIds = {R.id.tvBand0,R.id.tvBand1,R.id.tvBand2,R.id.tvBand3,R.id.tvBand4,
                          R.id.tvBand5,R.id.tvBand6,R.id.tvBand7,R.id.tvBand8,R.id.tvBand9};
        for (int i = 0; i < DspEqualizer.BANDS; i++) {
            ((TextView) findViewById(labelIds[i])).setText(DspEqualizer.LABELS[i]);
            SeekBar sb = findViewById(seekIds[i]);
            sb.setMax(240);
            sb.setProgress(120);
            final int band = i;
            final TextView tvGain = findViewById(gainIds[i]);
            sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override public void onProgressChanged(SeekBar s, int p, boolean u) {
                    float db = (p - 120) / 10f;
                    dspEq.setGain(band, db);
                    tvGain.setText(String.format(Locale.getDefault(), "%+.0fdB", db));
                }
                @Override public void onStartTrackingTouch(SeekBar s) {}
                @Override public void onStopTrackingTouch(SeekBar s) {}
            });
        }
    }

    private void setupButtons() {
        btnRecord.setOnClickListener(v -> { if (isRecording) stopRecording(); else startRecording(); });

        btnSwitchCam.setOnClickListener(v -> sendCommand("SWITCH_CAM"));

        btnVideoToggle.setOnClickListener(v -> {
            boolean current = AppSettings.isVideoEnabled(this);
            boolean newVal  = !current;
            AppSettings.setVideoEnabled(this, newVal);
            sendCommand(newVal ? "VIDEO_ON" : "VIDEO_OFF");
            btnVideoToggle.setColorFilter(newVal ? 0xFF00E676 : 0xFFFF3D3D);
            if (!newVal) {
                videoQueue.clear(); lastFrame = null;
                ivVideo.setImageBitmap(null);
                ivVideo.setBackgroundColor(0xFF0A1A0A);
            } else {
                ivVideo.setBackgroundColor(0xFF111111);
                startVideoReceiver();
                startVideoRenderer();
            }
        });
        btnVideoToggle.setColorFilter(videoEnabled ? 0xFF00E676 : 0xFFFF3D3D);

        // Audio toggle button
        btnAudioToggle.setOnClickListener(v -> {
            audioEnabled = !audioEnabled;
            btnAudioToggle.setColorFilter(audioEnabled ? 0xFF00E676 : 0xFFFF3D3D);
            if (!audioEnabled) audioQueue.clear();
        });
        btnAudioToggle.setColorFilter(0xFF00E676);

        btnScreenshot.setOnClickListener(v -> takeScreenshot());

        btnEq.setOnClickListener(v ->
                layoutEq.setVisibility(layoutEq.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE));
    }

    private void stopAudio() {
        running = false;
        audioQueue.clear();
        videoQueue.clear();
        if (isRecording) stopRecording();
        if (noiseSuppressor != null) { noiseSuppressor.setEnabled(false); noiseSuppressor.release(); }
        if (audioTrack != null) { audioTrack.stop(); audioTrack.release(); }
        cancelNotification();
    }

    private void sendCommand(String cmd) {
        new Thread(() -> {
            try {
                Socket s = new Socket();
                s.connect(new InetSocketAddress(deviceIp, PORT_COMMAND), 2000);
                s.getOutputStream().write((cmd + "\n").getBytes());
                s.getOutputStream().flush();
                s.close();
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(this, "Error: " + cmd, Toast.LENGTH_SHORT).show());
            }
        }).start();
    }

    private void takeScreenshot() {
        if (lastFrame == null) { Toast.makeText(this, "No hay video activo", Toast.LENGTH_SHORT).show(); return; }
        try {
            File dir = new File(getExternalFilesDir(null), "Screenshots");
            if (!dir.exists()) dir.mkdirs();
            String filename = "LynxEye_" + new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.getDefault()).format(new Date()) + ".jpg";
            File file = new File(dir, filename);
            FileOutputStream fos = new FileOutputStream(file);
            lastFrame.compress(android.graphics.Bitmap.CompressFormat.JPEG, 95, fos);
            fos.flush(); fos.close();
            Toast.makeText(this, "📸 " + filename, Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Toast.makeText(this, "Error: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private File getRecordingsDir() {
        File dir = new File(getExternalFilesDir(null), "Recordings");
        if (!dir.exists()) dir.mkdirs();
        return dir;
    }

    private void startRecording() {
        try {
            String filename = "LynxEye_" + new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.getDefault()).format(new Date()) + ".wav";
            currentRecordingFile = new File(getRecordingsDir(), filename);
            recordingStream = new FileOutputStream(currentRecordingFile);
            int channels = (audioMode == 1) ? 2 : 1;
            writeWavHeader(recordingStream, 0, sampleRate, channels);
            totalAudioBytes = 0;
            isRecording = true;
            recordingStart = System.currentTimeMillis();
            btnRecord.setImageResource(android.R.drawable.ic_media_pause);
            tvRecTime.setVisibility(View.VISIBLE);
            recTimeUpdater = new Runnable() {
                @Override public void run() {
                    long s = (System.currentTimeMillis() - recordingStart) / 1000;
                    tvRecTime.setText(String.format(Locale.getDefault(), "⏺ %02d:%02d", s/60, s%60));
                    if (isRecording) uiHandler.postDelayed(this, 1000);
                }
            };
            uiHandler.post(recTimeUpdater);
            Toast.makeText(this, "⏺ Grabando " + sampleRate + "Hz", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(this, "Error: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void stopRecording() {
        isRecording = false;
        uiHandler.removeCallbacks(recTimeUpdater);
        tvRecTime.setVisibility(View.GONE);
        btnRecord.setImageResource(android.R.drawable.ic_btn_speak_now);
        try {
            if (recordingStream != null) { recordingStream.flush(); recordingStream.close(); recordingStream = null; }
            if (currentRecordingFile != null && currentRecordingFile.exists()) {
                int channels = (audioMode == 1) ? 2 : 1;
                updateWavHeader(currentRecordingFile, totalAudioBytes, sampleRate, channels);
                Toast.makeText(this, "✅ " + currentRecordingFile.getName(), Toast.LENGTH_LONG).show();
            }
        } catch (Exception e) { e.printStackTrace(); }
    }

    private void writeWavHeader(FileOutputStream out, long audioBytes, int sr, int channels) throws IOException {
        long total = audioBytes + 36;
        byte[] h = new byte[44];
        h[0]='R';h[1]='I';h[2]='F';h[3]='F';
        h[4]=(byte)total;h[5]=(byte)(total>>8);h[6]=(byte)(total>>16);h[7]=(byte)(total>>24);
        h[8]='W';h[9]='A';h[10]='V';h[11]='E';
        h[12]='f';h[13]='m';h[14]='t';h[15]=' ';
        h[16]=16;h[17]=0;h[18]=0;h[19]=0;h[20]=1;h[21]=0;
        h[22]=(byte)channels;h[23]=0;
        h[24]=(byte)sr;h[25]=(byte)(sr>>8);h[26]=(byte)(sr>>16);h[27]=(byte)(sr>>24);
        long br = sr * channels * 2L;
        h[28]=(byte)br;h[29]=(byte)(br>>8);h[30]=(byte)(br>>16);h[31]=(byte)(br>>24);
        h[32]=(byte)(channels*2);h[33]=0;h[34]=16;h[35]=0;
        h[36]='d';h[37]='a';h[38]='t';h[39]='a';
        h[40]=(byte)audioBytes;h[41]=(byte)(audioBytes>>8);h[42]=(byte)(audioBytes>>16);h[43]=(byte)(audioBytes>>24);
        out.write(h);
    }

    private void updateWavHeader(File file, long audioBytes, int sr, int channels) throws IOException {
        RandomAccessFile raf = new RandomAccessFile(file, "rw");
        long total = audioBytes + 36;
        raf.seek(4);
        raf.write((byte)total);raf.write((byte)(total>>8));raf.write((byte)(total>>16));raf.write((byte)(total>>24));
        raf.seek(40);
        raf.write((byte)audioBytes);raf.write((byte)(audioBytes>>8));raf.write((byte)(audioBytes>>16));raf.write((byte)(audioBytes>>24));
        raf.close();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        cancelNotification();
        stopAudio();
    }
}
