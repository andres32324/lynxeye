package com.lynxeye;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.media.audiofx.Equalizer;
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

    private static final int PORT_AUDIO = 9999;
    private static final int PORT_VIDEO  = 9998;
    private static final int SAMPLE_RATE = 44100;
    private static final int AUDIO_QUEUE_SIZE = 3;
    private static final int VIDEO_QUEUE_SIZE  = 2;

    private String deviceName, deviceIp;

    private TextView    tvStatus, tvDeviceName, tvRecTime;
    private ImageView   ivVideo;
    private ImageButton btnRecord, btnSwitchCam, btnEq;
    private SeekBar     seekVolume;
    private View        layoutEq;

    private AudioTrack audioTrack;
    private Equalizer  equalizer;

    private volatile boolean running        = false;
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
        int minBuf = AudioTrack.getMinBufferSize(SAMPLE_RATE,
                AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
        audioTrack = new AudioTrack.Builder()
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
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
                    byte[] buffer = new byte[2048];
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
                        audioTrack.write(chunk, 0, chunk.length);
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
        try {
            equalizer = new Equalizer(0, audioTrack.getAudioSessionId());
            equalizer.setEnabled(true);
            int[]    seekIds  = {R.id.seekBand0,R.id.seekBand1,R.id.seekBand2,R.id.seekBand3,R.id.seekBand4};
            int[]    labelIds = {R.id.tvBand0,  R.id.tvBand1,  R.id.tvBand2,  R.id.tvBand3,  R.id.tvBand4};
            String[] labels   = {"60Hz","230Hz","910Hz","3.6k","14k"};
            short[]  range    = equalizer.getBandLevelRange();
            short min = range[0], max = range[1];
            for (int i = 0; i < 5 && i < equalizer.getNumberOfBands(); i++) {
                ((TextView) findViewById(labelIds[i])).setText(labels[i]);
                SeekBar sb = findViewById(seekIds[i]);
                sb.setMax(max - min);
                sb.setProgress(equalizer.getBandLevel((short) i) - min);
                final int band = i; final short bMin = min;
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

    private void setupButtons() {
        btnRecord.setOnClickListener(v -> { if (isRecording) stopRecording(); else startRecording(); });

        btnSwitchCam.setOnClickListener(v -> {
            new Thread(() -> {
                try {
                    Socket cmd = new Socket();
                    cmd.connect(new InetSocketAddress(deviceIp, 9997), 2000);
                    cmd.getOutputStream().write("SWITCH_CAM\n".getBytes());
                    cmd.getOutputStream().flush();
                    cmd.close();
                    runOnUiThread(() -> Toast.makeText(MonitorActivity.this, "Cámara cambiada", Toast.LENGTH_SHORT).show());
                } catch (Exception e) {
                    runOnUiThread(() -> Toast.makeText(MonitorActivity.this, "Error al cambiar cámara", Toast.LENGTH_SHORT).show());
                }
            }).start();
        });

        btnEq.setOnClickListener(v ->
                layoutEq.setVisibility(layoutEq.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE));
    }

    // Usa carpeta privada de la app - no necesita permisos
    private File getRecordingsDir() {
        File dir = new File(getExternalFilesDir(null), "Recordings");
        if (!dir.exists()) dir.mkdirs();
        return dir;
    }

    private void startRecording() {
        try {
            File dir = getRecordingsDir();
            String filename = "LynxEye_" +
                    new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.getDefault()).format(new Date()) + ".wav";
            currentRecordingFile = new File(dir, filename);
            recordingStream = new FileOutputStream(currentRecordingFile);
            writeWavHeader(recordingStream, 0);
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
            Toast.makeText(this, "Grabando en:\n" + dir.getAbsolutePath(), Toast.LENGTH_LONG).show();
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
                updateWavHeader(currentRecordingFile, totalAudioBytes);
                Toast.makeText(this, "Guardado: " + currentRecordingFile.getName(), Toast.LENGTH_LONG).show();
            }
        } catch (Exception e) { e.printStackTrace(); }
    }

    private void writeWavHeader(FileOutputStream out, long audioBytes) throws IOException {
        long total = audioBytes + 36;
        byte[] h = new byte[44];
        h[0]='R';h[1]='I';h[2]='F';h[3]='F';
        h[4]=(byte)total;h[5]=(byte)(total>>8);h[6]=(byte)(total>>16);h[7]=(byte)(total>>24);
        h[8]='W';h[9]='A';h[10]='V';h[11]='E';
        h[12]='f';h[13]='m';h[14]='t';h[15]=' ';
        h[16]=16;h[17]=0;h[18]=0;h[19]=0;
        h[20]=1;h[21]=0;h[22]=1;h[23]=0;
        h[24]=(byte)SAMPLE_RATE;h[25]=(byte)(SAMPLE_RATE>>8);h[26]=(byte)(SAMPLE_RATE>>16);h[27]=(byte)(SAMPLE_RATE>>24);
        long br=SAMPLE_RATE*2L;
        h[28]=(byte)br;h[29]=(byte)(br>>8);h[30]=(byte)(br>>16);h[31]=(byte)(br>>24);
        h[32]=2;h[33]=0;h[34]=16;h[35]=0;
        h[36]='d';h[37]='a';h[38]='t';h[39]='a';
        h[40]=(byte)audioBytes;h[41]=(byte)(audioBytes>>8);h[42]=(byte)(audioBytes>>16);h[43]=(byte)(audioBytes>>24);
        out.write(h);
    }

    private void updateWavHeader(File file, long audioBytes) throws IOException {
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
        running = false;
        audioQueue.clear();
        videoQueue.clear();
        if (isRecording) stopRecording();
        if (equalizer != null) { equalizer.setEnabled(false); equalizer.release(); }
        if (audioTrack != null) { audioTrack.stop(); audioTrack.release(); }
    }
}
