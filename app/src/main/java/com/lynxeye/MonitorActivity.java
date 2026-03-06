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
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.net.Socket;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

public class MonitorActivity extends AppCompatActivity {

    private static final int PORT_AUDIO = 9999;
    private static final int PORT_VIDEO = 9998;
    private static final int SAMPLE_RATE = 44100;
    private static final int AUDIO_QUEUE_SIZE = 3;
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

    private final BlockingQueue<byte[]> audioQueue = new ArrayBlockingQueue<>(AUDIO_QUEUE_SIZE);
    private final BlockingQueue<byte[]> videoQueue  = new ArrayBlockingQueue<>(VIDEO_QUEUE_SIZE);

    // Recording
    private File currentRecordingFile;
    private FileOutputStream recordingStream;
    private long recordingStart;
    private long totalAudioBytes = 0;
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

    // ─── AUDIO TRACK ──────────────────────────────────────
    private void setupAudioTrack() {
        int minBuf = AudioTrack.getMinBufferSize(SAMPLE_RATE,
                AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);

        audioTrack = new AudioTrack.Builder()
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AppSettings.isMixAudio(this)
                                ? AudioAttributes.USAGE_MEDIA
                                : AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build())
                .setAudioFormat(new AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build())
                .setBufferSizeInBytes(minBuf * 2)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build();

        audioTrack.play();
    }

    // ─── AUDIO RECEIVE ────────────────────────────────────
    private void startAudioReceiver() {
        new Thread(() -> {
            while (running) {
                try {
                    Socket socket = new Socket(deviceIp, PORT_AUDIO);
                    socket.setTcpNoDelay(true);
                    socket.setSoTimeout(4000);
                    InputStream in = socket.getInputStream();
                    byte[] buffer = new byte[2048];

                    setStatus("CONNECTED", 0xFF00E676);

                    while (running && !socket.isClosed()) {
                        int read = in.read(buffer, 0, buffer.length);
                        if (read > 0) {
                            byte[] chunk = new byte[read];
                            System.arraycopy(buffer, 0, chunk, 0, read);
                            // Drop oldest if full → no lag buildup
                            if (!audioQueue.offer(chunk)) {
                                audioQueue.poll();
                                audioQueue.offer(chunk);
                            }
                        }
                    }
                    socket.close();
                } catch (Exception e) {
                    setStatus("LOST SIGNAL", 0xFFFF3D3D);
                    audioQueue.clear();
                    try { Thread.sleep(1500); } catch (InterruptedException ie) { break; }
                }
            }
        }, "AudioReceiver").start();
    }

    // ─── AUDIO PLAYER ─────────────────────────────────────
    private void startAudioPlayer() {
        new Thread(() -> {
            while (running) {
                try {
                    byte[] chunk = audioQueue.poll(500, TimeUnit.MILLISECONDS);
                    if (chunk != null) {
                        audioTrack.write(chunk, 0, chunk.length);
                        // Write raw PCM to recording file
                        if (isRecording && recordingStream != null) {
                            try {
                                recordingStream.write(chunk, 0, chunk.length);
                                totalAudioBytes += chunk.length;
                            } catch (Exception ignored) {}
                        }
                    }
                } catch (InterruptedException e) { break; }
            }
        }, "AudioPlayer").start();
    }

    // ─── VIDEO RECEIVE ────────────────────────────────────
    private void startVideoReceiver() {
        new Thread(() -> {
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
                        // Always show latest frame only
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
        }, "VideoReceiver").start();
    }

    // ─── VIDEO RENDERER ───────────────────────────────────
    private void startVideoRenderer() {
        new Thread(() -> {
            while (running) {
                try {
                    byte[] frame = videoQueue.poll(500, TimeUnit.MILLISECONDS);
                    if (frame != null) {
                        android.graphics.BitmapFactory.Options opts = new android.graphics.BitmapFactory.Options();
                        opts.inPreferredConfig = android.graphics.Bitmap.Config.RGB_565;
                        final android.graphics.Bitmap bmp =
                                android.graphics.BitmapFactory.decodeByteArray(frame, 0, frame.length, opts);
                        if (bmp != null) runOnUiThread(() -> ivVideo.setImageBitmap(bmp));
                    }
                } catch (InterruptedException e) { break; }
            }
        }, "VideoRenderer").start();
    }

    // ─── EQUALIZER ────────────────────────────────────────
    private void setupEqualizer() {
        try {
            equalizer = new Equalizer(0, audioTrack.getAudioSessionId());
            equalizer.setEnabled(true);
            int[] seekIds  = {R.id.seekBand0, R.id.seekBand1, R.id.seekBand2, R.id.seekBand3, R.id.seekBand4};
            int[] labelIds = {R.id.tvBand0,   R.id.tvBand1,   R.id.tvBand2,   R.id.tvBand3,   R.id.tvBand4};
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
                Toast.makeText(this, "Coming soon", Toast.LENGTH_SHORT).show());

        btnEq.setOnClickListener(v ->
                layoutEq.setVisibility(
                        layoutEq.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE));
    }

    // ─── RECORDING (WAV) ──────────────────────────────────
    // Saves proper WAV file with header — plays in any app
    private void startRecording() {
        try {
            File dir = new File(Environment.getExternalStorageDirectory(), "LynxEye/Recordings");
            if (!dir.exists()) dir.mkdirs();

            String filename = "LynxEye_" +
                    new SimpleDateFormat("yyyy-MM-dd_HH-mm", Locale.getDefault()).format(new Date()) + ".wav";
            currentRecordingFile = new File(dir, filename);
            recordingStream = new FileOutputStream(currentRecordingFile);

            // Write placeholder WAV header — will be updated when recording stops
            writeWavHeader(recordingStream, 0);

            totalAudioBytes = 0;
            isRecording = true;
            recordingStart = System.currentTimeMillis();

            btnRecord.setImageResource(android.R.drawable.ic_media_pause);
            tvRecTime.setVisibility(View.VISIBLE);

            recTimeUpdater = new Runnable() {
                @Override public void run() {
                    long s = (System.currentTimeMillis() - recordingStart) / 1000;
                    tvRecTime.setText(String.format(Locale.getDefault(), "⏺ %02d:%02d", s / 60, s % 60));
                    if (isRecording) uiHandler.postDelayed(this, 1000);
                }
            };
            uiHandler.post(recTimeUpdater);
            Toast.makeText(this, "Recording started", Toast.LENGTH_SHORT).show();

        } catch (Exception e) {
            Toast.makeText(this, "Error starting recording: " + e.getMessage(), Toast.LENGTH_LONG).show();
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
            // Update WAV header with correct file size
            if (currentRecordingFile != null && currentRecordingFile.exists()) {
                updateWavHeader(currentRecordingFile, totalAudioBytes);
                Toast.makeText(this, "Saved: " + currentRecordingFile.getName(), Toast.LENGTH_LONG).show();
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    // Write a 44-byte WAV header
    private void writeWavHeader(FileOutputStream out, long audioBytes) throws IOException {
        long totalSize = audioBytes + 36;
        byte[] header = new byte[44];
        // RIFF chunk
        header[0]='R'; header[1]='I'; header[2]='F'; header[3]='F';
        header[4]=(byte)(totalSize);       header[5]=(byte)(totalSize>>8);
        header[6]=(byte)(totalSize>>16);   header[7]=(byte)(totalSize>>24);
        header[8]='W'; header[9]='A'; header[10]='V'; header[11]='E';
        // fmt chunk
        header[12]='f'; header[13]='m'; header[14]='t'; header[15]=' ';
        header[16]=16; header[17]=0; header[18]=0; header[19]=0; // chunk size
        header[20]=1; header[21]=0;  // PCM format
        header[22]=1; header[23]=0;  // mono
        // sample rate 44100
        header[24]=(byte)(SAMPLE_RATE);       header[25]=(byte)(SAMPLE_RATE>>8);
        header[26]=(byte)(SAMPLE_RATE>>16);   header[27]=(byte)(SAMPLE_RATE>>24);
        // byte rate = sampleRate * channels * bitsPerSample/8
        long byteRate = SAMPLE_RATE * 2L;
        header[28]=(byte)(byteRate);       header[29]=(byte)(byteRate>>8);
        header[30]=(byte)(byteRate>>16);   header[31]=(byte)(byteRate>>24);
        header[32]=2; header[33]=0;  // block align
        header[34]=16; header[35]=0; // bits per sample
        // data chunk
        header[36]='d'; header[37]='a'; header[38]='t'; header[39]='a';
        header[40]=(byte)(audioBytes);       header[41]=(byte)(audioBytes>>8);
        header[42]=(byte)(audioBytes>>16);   header[43]=(byte)(audioBytes>>24);
        out.write(header);
    }

    // Go back and fix the WAV header with real sizes
    private void updateWavHeader(File file, long audioBytes) throws IOException {
        RandomAccessFile raf = new RandomAccessFile(file, "rw");
        long totalSize = audioBytes + 36;
        // Update RIFF chunk size
        raf.seek(4);
        raf.write((byte)(totalSize));      raf.write((byte)(totalSize>>8));
        raf.write((byte)(totalSize>>16));  raf.write((byte)(totalSize>>24));
        // Update data chunk size
        raf.seek(40);
        raf.write((byte)(audioBytes));     raf.write((byte)(audioBytes>>8));
        raf.write((byte)(audioBytes>>16)); raf.write((byte)(audioBytes>>24));
        raf.close();
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
