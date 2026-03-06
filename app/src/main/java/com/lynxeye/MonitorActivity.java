package com.lynxeye;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.media.audiofx.Equalizer;
import android.media.audiofx.LoudnessEnhancer;
import android.media.audiofx.NoiseSuppressor;
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
import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class MonitorActivity extends AppCompatActivity {

    private static final int PORT_AUDIO = 9999;
    private static final int PORT_VIDEO = 9998;
    private static final int SAMPLE_RATE = 44100;
    private static final int BUFFER_SIZE = 4096;

    private String deviceName, deviceIp, deviceCode;

    private TextView tvStatus, tvDeviceName, tvRecTime;
    private ImageView ivVideo;
    private ImageButton btnRecord, btnSwitchCam, btnFullscreen, btnEq;
    private SeekBar seekVolume;
    private View layoutEq;

    private AudioTrack audioTrack;
    private Equalizer equalizer;
    private Thread audioThread, videoThread;
    private volatile boolean running = false;
    private volatile boolean isRecording = false;

    // Recording
    private FileOutputStream recordingStream;
    private long recordingStart;
    private Handler uiHandler = new Handler();
    private Runnable recTimeUpdater;

    // Video switch command
    private volatile boolean switchCamPending = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(R.layout.activity_monitor);

        deviceName = getIntent().getStringExtra("name");
        deviceIp   = getIntent().getStringExtra("ip");
        deviceCode = getIntent().getStringExtra("code");

        tvStatus     = findViewById(R.id.tvStatus);
        tvDeviceName = findViewById(R.id.tvDeviceName);
        tvRecTime    = findViewById(R.id.tvRecTime);
        ivVideo      = findViewById(R.id.ivVideo);
        btnRecord    = findViewById(R.id.btnRecord);
        btnSwitchCam = findViewById(R.id.btnSwitchCam);
        btnFullscreen= findViewById(R.id.btnFullscreen);
        btnEq        = findViewById(R.id.btnEq);
        seekVolume   = findViewById(R.id.seekVolume);
        layoutEq     = findViewById(R.id.layoutEq);

        tvDeviceName.setText(deviceName);
        tvStatus.setText("⬤  CONNECTING...");
        tvStatus.setTextColor(0xFFFFAA00);

        setupAudioTrack();
        setupEqualizer();
        setupVolumeControl();
        setupButtons();

        startStreaming();
    }

    private void setupAudioTrack() {
        int minBuf = AudioTrack.getMinBufferSize(SAMPLE_RATE,
                AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);

        AudioAttributes attrs = new AudioAttributes.Builder()
                .setUsage(AppSettings.isMixAudio(this)
                        ? AudioAttributes.USAGE_MEDIA
                        : AudioAttributes.USAGE_VOICE_COMMUNICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build();

        audioTrack = new AudioTrack.Builder()
                .setAudioAttributes(attrs)
                .setAudioFormat(new AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build())
                .setBufferSizeInBytes(minBuf * 4)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build();

        audioTrack.play();
    }

    private void setupEqualizer() {
        equalizer = new Equalizer(0, audioTrack.getAudioSessionId());
        equalizer.setEnabled(true);

        // Setup 5 band sliders
        int[] seekIds = {R.id.seekBand0, R.id.seekBand1, R.id.seekBand2, R.id.seekBand3, R.id.seekBand4};
        int[] labelIds = {R.id.tvBand0, R.id.tvBand1, R.id.tvBand2, R.id.tvBand3, R.id.tvBand4};
        String[] bandLabels = {"60Hz", "230Hz", "910Hz", "3.6kHz", "14kHz"};

        short[] range = equalizer.getBandLevelRange();
        short min = range[0];
        short max = range[1];

        for (int i = 0; i < 5 && i < equalizer.getNumberOfBands(); i++) {
            ((TextView) findViewById(labelIds[i])).setText(bandLabels[i]);
            SeekBar sb = findViewById(seekIds[i]);
            sb.setMax(max - min);
            sb.setProgress((equalizer.getBandLevel((short)i) - min));
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
    }

    private void setupVolumeControl() {
        AudioManager am = (AudioManager) getSystemService(AUDIO_SERVICE);
        int max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
        int cur = am.getStreamVolume(AudioManager.STREAM_MUSIC);
        seekVolume.setMax(max);
        seekVolume.setProgress(cur);
        seekVolume.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean u) {
                am.setStreamVolume(AudioManager.STREAM_MUSIC, p, 0);
            }
            @Override public void onStartTrackingTouch(SeekBar s) {}
            @Override public void onStopTrackingTouch(SeekBar s) {}
        });
    }

    private void setupButtons() {
        btnRecord.setOnClickListener(v -> {
            if (isRecording) stopRecording();
            else startRecording();
        });

        btnSwitchCam.setOnClickListener(v -> {
            switchCamPending = true;
            Toast.makeText(this, "Switching camera...", Toast.LENGTH_SHORT).show();
        });

        btnEq.setOnClickListener(v -> {
            if (layoutEq.getVisibility() == View.VISIBLE) {
                layoutEq.setVisibility(View.GONE);
            } else {
                layoutEq.setVisibility(View.VISIBLE);
            }
        });

        btnFullscreen.setOnClickListener(v -> {
            if (ivVideo.getLayoutParams().height == -1) {
                ivVideo.getLayoutParams().height = (int)(getResources().getDisplayMetrics().density * 240);
            } else {
                ivVideo.getLayoutParams().height = -1;
            }
            ivVideo.requestLayout();
        });

        // Close EQ when tapping outside
        layoutEq.setOnClickListener(null);
    }

    // ─── STREAMING ───────────────────────────────────────
    private void startStreaming() {
        running = true;
        startAudioThread();
        startVideoThread();
    }

    private void startAudioThread() {
        audioThread = new Thread(() -> {
            while (running) {
                try {
                    Socket socket = new Socket(deviceIp, PORT_AUDIO);
                    socket.setSoTimeout(5000);
                    InputStream in = socket.getInputStream();
                    byte[] buffer = new byte[BUFFER_SIZE];

                    runOnUiThread(() -> {
                        tvStatus.setText("⬤  CONNECTED");
                        tvStatus.setTextColor(0xFF00E676);
                    });

                    while (running && !socket.isClosed()) {
                        int read = in.read(buffer, 0, buffer.length);
                        if (read > 0) {
                            audioTrack.write(buffer, 0, read);
                            if (isRecording && recordingStream != null) {
                                try { recordingStream.write(buffer, 0, read); }
                                catch (Exception e) { e.printStackTrace(); }
                            }
                        }
                    }
                    socket.close();
                } catch (Exception e) {
                    runOnUiThread(() -> {
                        tvStatus.setText("⬤  LOST SIGNAL");
                        tvStatus.setTextColor(0xFFFF3D3D);
                    });
                    try { Thread.sleep(2000); } catch (InterruptedException ie) { break; }
                }
            }
        });
        audioThread.setDaemon(true);
        audioThread.start();
    }

    private void startVideoThread() {
        videoThread = new Thread(() -> {
            while (running) {
                try {
                    Socket socket = new Socket(deviceIp, PORT_VIDEO);
                    DataInputStream in = new DataInputStream(socket.getInputStream());

                    while (running && !socket.isClosed()) {
                        // Read 4-byte frame length header
                        int len = in.readInt();
                        if (len <= 0 || len > 5_000_000) continue;

                        byte[] frameData = new byte[len];
                        int totalRead = 0;
                        while (totalRead < len) {
                            int r = in.read(frameData, totalRead, len - totalRead);
                            if (r < 0) break;
                            totalRead += r;
                        }

                        final byte[] frameCopy = frameData;
                        runOnUiThread(() -> {
                            try {
                                android.graphics.Bitmap bmp = android.graphics.BitmapFactory
                                        .decodeByteArray(frameCopy, 0, frameCopy.length);
                                if (bmp != null) ivVideo.setImageBitmap(bmp);
                            } catch (Exception ignored) {}
                        });
                    }
                    socket.close();
                } catch (Exception e) {
                    try { Thread.sleep(2000); } catch (InterruptedException ie) { break; }
                }
            }
        });
        videoThread.setDaemon(true);
        videoThread.start();
    }

    // ─── RECORDING ───────────────────────────────────────
    private void startRecording() {
        try {
            File dir = new File(Environment.getExternalStorageDirectory(), "LynxEye/Recordings");
            if (!dir.exists()) dir.mkdirs();
            String filename = "LynxEye_" + new SimpleDateFormat("yyyy-MM-dd_HH-mm", Locale.getDefault()).format(new Date()) + ".m4a";
            File outFile = new File(dir, filename);

            recordingStream = new FileOutputStream(outFile);
            isRecording = true;
            recordingStart = System.currentTimeMillis();
            btnRecord.setImageResource(android.R.drawable.ic_media_pause);
            tvRecTime.setVisibility(View.VISIBLE);

            recTimeUpdater = new Runnable() {
                @Override public void run() {
                    long elapsed = (System.currentTimeMillis() - recordingStart) / 1000;
                    long m = elapsed / 60, s = elapsed % 60;
                    tvRecTime.setText(String.format(Locale.getDefault(), "⏺ %02d:%02d", m, s));
                    if (isRecording) uiHandler.postDelayed(this, 1000);
                }
            };
            uiHandler.post(recTimeUpdater);
            Toast.makeText(this, "Recording: " + filename, Toast.LENGTH_SHORT).show();

        } catch (Exception e) {
            Toast.makeText(this, "Error starting recording", Toast.LENGTH_SHORT).show();
        }
    }

    private void stopRecording() {
        isRecording = false;
        uiHandler.removeCallbacks(recTimeUpdater);
        tvRecTime.setVisibility(View.GONE);
        btnRecord.setImageResource(android.R.drawable.ic_btn_speak_now);
        try {
            if (recordingStream != null) {
                recordingStream.flush();
                recordingStream.close();
                recordingStream = null;
            }
        } catch (Exception e) { e.printStackTrace(); }
        Toast.makeText(this, "Recording saved to LynxEye/Recordings", Toast.LENGTH_LONG).show();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        running = false;
        if (isRecording) stopRecording();
        if (equalizer != null) { equalizer.setEnabled(false); equalizer.release(); }
        if (audioTrack != null) { audioTrack.stop(); audioTrack.release(); }
    }

    // Needed for fullscreen button click
}
