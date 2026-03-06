package com.lynxeye;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.media.audiofx.Equalizer;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import java.io.DataInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.Socket;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;

public class MonitorActivity extends AppCompatActivity {

    private static final int PORT_AUDIO = 9999;
    private static final int PORT_VIDEO = 9998;
    private static final int SAMPLE_RATE = 44100;

    // Queue sizes small = low latency
    private static final int AUDIO_QUEUE_SIZE = 4;
    private static final int VIDEO_QUEUE_SIZE = 2;

    private String deviceName, deviceIp;

    private TextView tvStatus, tvDeviceName, tvRecTime;
    private ImageView ivVideo;
    private ImageButton btnRecord, btnSwitchCam, btnEq;
    private SeekBar seekVolume;
    private View layoutEq;

    private AudioTrack audioTrack;
    private Equalizer equalizer;

    private volatile boolean running = false;
    private volatile boolean isRecording = false;

    // Queues to decouple network from playback
    private BlockingQueue<byte[]> audioQueue = new ArrayBlockingQueue<>(AUDIO_QUEUE_SIZE);
    private BlockingQueue<byte[]> videoQueue  = new ArrayBlockingQueue<>(VIDEO_QUEUE_SIZE);

    // Threads
    private Thread audioReceiveThread, audioPlayThread;
    private Thread videoReceiveThread, videoRenderThread;

    // Recording
    private FileOutputStream recordingStream;
    private long recordingStart;
    private Handler uiHandler = new Handler();
    private Runnable recTimeUpdater;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(R.layout.activity_monitor);

        deviceName = getIntent().getStringExtra("name");
        deviceIp   = getIntent().getStringExtra("ip");

        tvStatus     = findViewById(R.id.tvStatus);
        tvDeviceName = findViewById(R.id.tvDeviceName);
        tvRecTime    = findViewById(R.id.tvRecTime);
        ivVideo      = findViewById(R.id.ivVideo);
        btnRecord    = findViewById(R.id.btnRecord);
        btnSwitchCam = findViewById(R.id.btnSwitchCam);
        btnEq        = findViewById(R.id.btnEq);
        seekVolume   = findViewById(R.id.seekVolume);
        layoutEq     = findViewById(R.id.layoutEq);

        tvDeviceName.setText(deviceName);
        setStatus("CONNECTING...", 0xFFFFAA00);

        setupAudioTrack();
        setupEqualizer();
        setupVolumeControl();
        setupButtons();

        running = true;
        startAudioReceiver();
        startAudioPlayer();
        startVideoReceiver();
        startVideoRenderer();
    }

    // ─── AUDIO TRACK ─────────────────────────────────────
    private void setupAudioTrack() {
        int minBuf = AudioTrack.getMinBufferSize(SAMPLE_RATE,
                AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);

        int usage = AppSettings.isMixAudio(this)
                ? AudioAttributes.USAGE_MEDIA
                : AudioAttributes.USAGE_VOICE_COMMUNICATION;

        audioTrack = new AudioTrack.Builder()
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(usage)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build())
                .setAudioFormat(new AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build())
                .setBufferSizeInBytes(minBuf * 2) // Small buffer = low latency
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build();

        audioTrack.play();
    }

    // ─── AUDIO RECEIVE ────────────────────────────────────
    private void startAudioReceiver() {
        audioReceiveThread = new Thread(() -> {
            while (running) {
                try {
                    Socket socket = new Socket(deviceIp, PORT_AUDIO);
                    socket.setTcpNoDelay(true); // Disable Nagle = less latency
                    socket.setSoTimeout(3000);
                    InputStream in = socket.getInputStream();
                    byte[] buffer = new byte[2048]; // Small chunks = less latency

                    setStatus("CONNECTED", 0xFF00E676);

                    while (running && !socket.isClosed()) {
                        int read = in.read(buffer, 0, buffer.length);
                        if (read > 0) {
                            byte[] chunk = new byte[read];
                            System.arraycopy(buffer, 0, chunk, 0, read);

                            // Drop oldest if queue full = no lag buildup
                            if (!audioQueue.offer(chunk)) {
                                audioQueue.poll(); // discard oldest
                                audioQueue.offer(chunk);
                            }
                        }
                    }
                    socket.close();
                } catch (Exception e) {
                    setStatus("LOST SIGNAL", 0xFFFF3D3D);
                    audioQueue.clear(); // Clear stale audio on disconnect
                    try { Thread.sleep(1500); } catch (InterruptedException ie) { break; }
                }
            }
        });
        audioReceiveThread.setDaemon(true);
        audioReceiveThread.start();
    }

    // ─── AUDIO PLAY ───────────────────────────────────────
    private void startAudioPlayer() {
        audioPlayThread = new Thread(() -> {
            while (running) {
                try {
                    byte[] chunk = audioQueue.poll(500, java.util.concurrent.TimeUnit.MILLISECONDS);
                    if (chunk != null) {
                        audioTrack.write(chunk, 0, chunk.length);
                        // Write to recording if active
                        if (isRecording && recordingStream != null) {
                            try { recordingStream.write(chunk, 0, chunk.length); }
                            catch (Exception ignored) {}
                        }
                    }
                } catch (InterruptedException e) { break; }
            }
        });
        audioPlayThread.setDaemon(true);
        audioPlayThread.start();
    }

    // ─── VIDEO RECEIVE ────────────────────────────────────
    private void startVideoReceiver() {
        videoReceiveThread = new Thread(() -> {
            while (running) {
                try {
                    Socket socket = new Socket(deviceIp, PORT_VIDEO);
                    socket.setTcpNoDelay(true);
                    DataInputStream in = new DataInputStream(socket.getInputStream());

                    while (running && !socket.isClosed()) {
                        int len = in.readInt();
                        if (len <= 0 || len > 3_000_000) continue;

                        byte[] frame = new byte[len];
                        int total = 0;
                        while (total < len) {
                            int r = in.read(frame, total, len - total);
                            if (r < 0) break;
                            total += r;
                        }

                        // Drop oldest frame if queue full = always show latest frame
                        if (!videoQueue.offer(frame)) {
                            videoQueue.poll();
                            videoQueue.offer(frame);
                        }
                    }
                    socket.close();
                } catch (Exception e) {
                    videoQueue.clear();
                    try { Thread.sleep(1500); } catch (InterruptedException ie) { break; }
                }
            }
        });
        videoReceiveThread.setDaemon(true);
        videoReceiveThread.start();
    }

    // ─── VIDEO RENDER ─────────────────────────────────────
    private void startVideoRenderer() {
        videoRenderThread = new Thread(() -> {
            while (running) {
                try {
                    byte[] frame = videoQueue.poll(500, java.util.concurrent.TimeUnit.MILLISECONDS);
                    if (frame != null) {
                        android.graphics.BitmapFactory.Options opts = new android.graphics.BitmapFactory.Options();
                        opts.inPreferredConfig = android.graphics.Bitmap.Config.RGB_565; // Less memory
                        final android.graphics.Bitmap bmp =
                                android.graphics.BitmapFactory.decodeByteArray(frame, 0, frame.length, opts);
                        if (bmp != null) {
                            runOnUiThread(() -> {
                                ivVideo.setImageBitmap(bmp);
                            });
                        }
                    }
                } catch (InterruptedException e) { break; }
            }
        });
        videoRenderThread.setDaemon(true);
        videoRenderThread.start();
    }

    // ─── EQUALIZER ────────────────────────────────────────
    private void setupEqualizer() {
        try {
            equalizer = new Equalizer(0, audioTrack.getAudioSessionId());
            equalizer.setEnabled(true);

            int[] seekIds = {R.id.seekBand0, R.id.seekBand1, R.id.seekBand2, R.id.seekBand3, R.id.seekBand4};
            int[] labelIds = {R.id.tvBand0, R.id.tvBand1, R.id.tvBand2, R.id.tvBand3, R.id.tvBand4};
            String[] labels = {"60Hz", "230Hz", "910Hz", "3.6kHz", "14kHz"};

            short[] range = equalizer.getBandLevelRange();
            short min = range[0], max = range[1];

            for (int i = 0; i < 5 && i < equalizer.getNumberOfBands(); i++) {
                ((TextView) findViewById(labelIds[i])).setText(labels[i]);
                SeekBar sb = findViewById(seekIds[i]);
                sb.setMax(max - min);
                sb.setProgress(equalizer.getBandLevel((short) i) - min);
                final int band = i;
                final short bMin = min;
                sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                    @Override public void onProgressChanged(SeekBar s, int p, boolean u) {
                        equalizer.setBandLevel((short) band, (short)(p + bMin));
                    }
                    @Override public void onStartTrackingTouch(SeekBar s) {}
                    @Override public void onStopTrackingTouch(SeekBar s) {}
                });
            }
        } catch (Exception e) { e.printStackTrace(); }
    }

    // ─── VOLUME ───────────────────────────────────────────
    private void setupVolumeControl() {
        AudioManager am = (AudioManager) getSystemService(AUDIO_SERVICE);
        int max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
        seekVolume.setMax(max);
        seekVolume.setProgress(am.getStreamVolume(AudioManager.STREAM_MUSIC));
        seekVolume.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean u) {
                am.setStreamVolume(AudioManager.STREAM_MUSIC, p, 0);
            }
            @Override public void onStartTrackingTouch(SeekBar s) {}
            @Override public void onStopTrackingTouch(SeekBar s) {}
        });
    }

    // ─── BUTTONS ──────────────────────────────────────────
    private void setupButtons() {
        btnRecord.setOnClickListener(v -> {
            if (isRecording) stopRecording();
            else startRecording();
        });

        btnSwitchCam.setOnClickListener(v ->
            Toast.makeText(this, "Coming soon", Toast.LENGTH_SHORT).show()
        );

        btnEq.setOnClickListener(v ->
            layoutEq.setVisibility(
                layoutEq.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE)
        );
    }

    // ─── RECORDING ────────────────────────────────────────
    private void startRecording() {
        try {
            File dir = new File(Environment.getExternalStorageDirectory(), "LynxEye/Recordings");
            if (!dir.exists()) dir.mkdirs();
            String name = "LynxEye_" +
                    new SimpleDateFormat("yyyy-MM-dd_HH-mm", Locale.getDefault()).format(new Date()) + ".m4a";
            recordingStream = new FileOutputStream(new File(dir, name));
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
            Toast.makeText(this, "Recording started", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(this, "Error: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private void stopRecording() {
        isRecording = false;
        uiHandler.removeCallbacks(recTimeUpdater);
        tvRecTime.setVisibility(View.GONE);
        btnRecord.setImageResource(android.R.drawable.ic_btn_speak_now);
        try {
            if (recordingStream != null) { recordingStream.flush(); recordingStream.close(); recordingStream = null; }
        } catch (Exception e) { e.printStackTrace(); }
        Toast.makeText(this, "Saved to LynxEye/Recordings", Toast.LENGTH_LONG).show();
    }

    // ─── HELPERS ──────────────────────────────────────────
    private void setStatus(String text, int color) {
        runOnUiThread(() -> {
            tvStatus.setText("⬤  " + text);
            tvStatus.setTextColor(color);
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        running = false;
        audioQueue.clear();
        videoQueue.clear();
        if (isRecording) stopRecording();
        if (equalizer != null) { equalizer.setEnabled(false); equalizer.release(); }
        if (audioTrack != null) { audioTrack.stop(); audioTrack.release(); }
    }
}
