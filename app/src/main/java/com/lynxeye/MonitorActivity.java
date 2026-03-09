package com.lynxeye;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.media.MediaCodec;
import android.media.MediaFormat;
import android.media.audiofx.Equalizer;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.os.Handler;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageButton;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;

import java.io.DataInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.InputStream;
import java.io.PrintWriter;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class MonitorActivity extends AppCompatActivity {

    private static final int PORT_AUDIO    = 9999;
    private static final int PORT_VIDEO    = 9998;
    private static final int PORT_COMMAND  = 9997;
    private static final int SAMPLE_RATE   = 44100;
    private static final int BUFFER_SIZE   = 4096;
    private static final int PING_INTERVAL = 60000;

    private String deviceName, deviceIp, deviceCode;

    private TextView    tvStatus, tvDeviceName, tvRecTime;
    private TextureView textureView;
    private ImageButton btnRecord, btnSwitchCam, btnFullscreen, btnEq;
    private SeekBar     seekVolume;
    private View        layoutEq;

    private AudioTrack audioTrack;
    private Equalizer  equalizer;
    private volatile boolean running          = false;
    private volatile boolean isRecording      = false;
    private volatile boolean cmdConnected     = false; // ← esperamos esto antes de audio/video

    // Recording
    private FileOutputStream recordingStream;
    private long             recordingStart;
    private Handler          uiHandler = new Handler();
    private Runnable         recTimeUpdater;

    // Comando persistente
    private volatile Socket      cmdSocket = null;
    private volatile PrintWriter cmdWriter = null;
    private Runnable             pingRunnable;

    // WifiLock
    private WifiManager.WifiLock wifiLock;

    // H264 decoder
    private volatile MediaCodec videoDecoder   = null;
    private volatile Surface    decoderSurface = null;

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
        textureView  = findViewById(R.id.textureView);
        btnRecord    = findViewById(R.id.btnRecord);
        btnSwitchCam = findViewById(R.id.btnSwitchCam);
        btnFullscreen= findViewById(R.id.btnFullscreen);
        btnEq        = findViewById(R.id.btnEq);
        seekVolume   = findViewById(R.id.seekVolume);
        layoutEq     = findViewById(R.id.layoutEq);

        tvDeviceName.setText(deviceName);
        setStatus("CONNECTING...", 0xFFFFAA00);

        // TextureView → surface para decoder H264
        textureView.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override
            public void onSurfaceTextureAvailable(android.graphics.SurfaceTexture st, int w, int h) {
                decoderSurface = new Surface(st);
            }
            @Override public void onSurfaceTextureSizeChanged(android.graphics.SurfaceTexture st, int w, int h) {}
            @Override public boolean onSurfaceTextureDestroyed(android.graphics.SurfaceTexture st) {
                decoderSurface = null; return true;
            }
            @Override public void onSurfaceTextureUpdated(android.graphics.SurfaceTexture st) {}
        });

        acquireWifiLock();
        setupAudioTrack();
        setupEqualizer();
        setupVolumeControl();
        setupButtons();

        running = true;
        startCommandConnection(); // audio y video arrancan DESDE AQUÍ cuando conecta
    }

    private void acquireWifiLock() {
        try {
            WifiManager wm = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
            wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "LynxEye::WifiLock");
            wifiLock.acquire();
        } catch (Exception ignored) {}
    }

    private void setStatus(String text, int color) {
        runOnUiThread(() -> {
            tvStatus.setText("⬤  " + text);
            tvStatus.setTextColor(color);
        });
    }

    // ─── Comando persistente ──────────────────────────────
    private void startCommandConnection() {
        new Thread(() -> {
            while (running) {
                try {
                    Socket s = new Socket();
                    s.connect(new InetSocketAddress(deviceIp, PORT_COMMAND), 15000);
                    s.setTcpNoDelay(true);
                    s.setSoTimeout(90000);
                    cmdSocket = s;
                    cmdWriter = new PrintWriter(s.getOutputStream(), true);

                    // Primero enviamos los comandos, luego arrancamos audio/video
                    cmdWriter.println("START_AUDIO");
                    cmdWriter.println("START_CAMERA");

                    if (!cmdConnected) {
                        cmdConnected = true;
                        setStatus("CONNECTED", 0xFF00E676);
                        startAudioThread();
                        startVideoThread();
                    }

                    startPing();

                    BufferedReader reader = new BufferedReader(new InputStreamReader(s.getInputStream()));
                    String line;
                    while (running && (line = reader.readLine()) != null) { /* PONG */ }

                } catch (Exception ignored) {
                } finally {
                    stopPing();
                    cmdConnected = false;
                    cmdWriter = null;
                    try { if (cmdSocket != null) cmdSocket.close(); } catch (Exception ignored2) {}
                    cmdSocket = null;
                    if (running) {
                        setStatus("RECONNECTING...", 0xFFFFAA00);
                        try { Thread.sleep(4000); } catch (InterruptedException e) { break; }
                    }
                }
            }
        }, "CmdConnection").start();
    }

    private void sendCmd(String cmd) {
        new Thread(() -> {
            PrintWriter w = cmdWriter;
            if (w != null) w.println(cmd);
        }).start();
    }

    private void startPing() {
        stopPing();
        pingRunnable = new Runnable() {
            @Override public void run() {
                sendCmd("PING");
                if (running) uiHandler.postDelayed(this, PING_INTERVAL);
            }
        };
        uiHandler.postDelayed(pingRunnable, PING_INTERVAL);
    }

    private void stopPing() {
        if (pingRunnable != null) uiHandler.removeCallbacks(pingRunnable);
        pingRunnable = null;
    }

    // ─── Audio ────────────────────────────────────────────
    private void setupAudioTrack() {
        int minBuf = AudioTrack.getMinBufferSize(SAMPLE_RATE,
                AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
        audioTrack = new AudioTrack.Builder()
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                .setAudioFormat(new AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setBufferSizeInBytes(minBuf * 4)
                .setTransferMode(AudioTrack.MODE_STREAM).build();
        audioTrack.play();
    }

    private void setupEqualizer() {
        equalizer = new Equalizer(0, audioTrack.getAudioSessionId());
        equalizer.setEnabled(true);
        int[] seekIds  = {R.id.seekBand0, R.id.seekBand1, R.id.seekBand2, R.id.seekBand3, R.id.seekBand4};
        int[] labelIds = {R.id.tvBand0, R.id.tvBand1, R.id.tvBand2, R.id.tvBand3, R.id.tvBand4};
        String[] labels = {"60Hz", "230Hz", "910Hz", "3.6kHz", "14kHz"};
        short[] range = equalizer.getBandLevelRange();
        short min = range[0], max = range[1];
        for (int i = 0; i < 5 && i < equalizer.getNumberOfBands(); i++) {
            ((TextView) findViewById(labelIds[i])).setText(labels[i]);
            SeekBar sb = findViewById(seekIds[i]);
            sb.setMax(max - min);
            sb.setProgress(equalizer.getBandLevel((short)i) - min);
            final int band = i; final short bMin = min;
            sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override public void onProgressChanged(SeekBar s, int p, boolean u) {
                    equalizer.setBandLevel((short)band, (short)(p + bMin));
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
        seekVolume.setMax(max); seekVolume.setProgress(cur);
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
            if (isRecording) stopRecording(); else startRecording();
        });
        btnSwitchCam.setOnClickListener(v -> sendCmd("SWITCH_CAM"));
        btnEq.setOnClickListener(v ->
                layoutEq.setVisibility(layoutEq.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE));
        btnFullscreen.setOnClickListener(v -> {
            if (textureView.getLayoutParams().height == -1) {
                textureView.getLayoutParams().height =
                        (int)(getResources().getDisplayMetrics().density * 240);
            } else {
                textureView.getLayoutParams().height = -1;
            }
            textureView.requestLayout();
        });
    }

    // ─── Audio Thread ─────────────────────────────────────
    private void startAudioThread() {
        new Thread(() -> {
            while (running) {
                try {
                    Socket socket = new Socket();
                    socket.connect(new InetSocketAddress(deviceIp, PORT_AUDIO), 15000);
                    socket.setSoTimeout(20000);
                    InputStream in = socket.getInputStream();
                    byte[] buffer = new byte[BUFFER_SIZE];
                    setStatus("CONNECTED", 0xFF00E676);
                    while (running && !socket.isClosed()) {
                        int read = in.read(buffer, 0, buffer.length);
                        if (read > 0) {
                            audioTrack.write(buffer, 0, read);
                            if (isRecording && recordingStream != null) {
                                try { recordingStream.write(buffer, 0, read); } catch (Exception ignored) {}
                            }
                        }
                    }
                    socket.close();
                } catch (Exception e) {
                    setStatus("LOST SIGNAL", 0xFFFF3D3D);
                    try { Thread.sleep(3000); } catch (InterruptedException ie) { break; }
                }
            }
        }, "AudioThread").start();
    }

    // ─── Video Thread H264 ────────────────────────────────
    private void startVideoThread() {
        new Thread(() -> {
            while (running) {
                // Esperar surface lista
                if (decoderSurface == null) {
                    try { Thread.sleep(200); } catch (InterruptedException e) { break; }
                    continue;
                }
                Socket socket = null;
                MediaCodec decoder = null;
                try {
                    socket = new Socket();
                    socket.connect(new InetSocketAddress(deviceIp, PORT_VIDEO), 15000);
                    socket.setTcpNoDelay(true);
                    socket.setSoTimeout(20000);
                    DataInputStream in = new DataInputStream(socket.getInputStream());

                    decoder = MediaCodec.createDecoderByType("video/avc");
                    videoDecoder = decoder;
                    boolean configured = false;

                    while (running && !socket.isClosed()) {
                        int len = in.readInt();
                        if (len <= 0 || len > 10_000_000) continue;
                        byte flags = in.readByte();

                        byte[] data = new byte[len];
                        int total = 0;
                        while (total < len) {
                            int r = in.read(data, total, len - total);
                            if (r < 0) break;
                            total += r;
                        }

                        boolean isConfig = (flags & 1) != 0;

                        // SPS/PPS → configurar decoder
                        if (isConfig && !configured && decoderSurface != null) {
                            MediaFormat format = MediaFormat.createVideoFormat("video/avc", 1280, 720);
                            format.setByteBuffer("csd-0", ByteBuffer.wrap(data));
                            decoder.configure(format, decoderSurface, null, 0);
                            decoder.start();
                            configured = true;
                            continue;
                        }

                        if (!configured) continue;

                        int inIndex = decoder.dequeueInputBuffer(10_000);
                        if (inIndex >= 0) {
                            ByteBuffer inBuf = decoder.getInputBuffer(inIndex);
                            if (inBuf != null) {
                                inBuf.clear();
                                inBuf.put(data);
                                decoder.queueInputBuffer(inIndex, 0, data.length,
                                        System.currentTimeMillis() * 1000, 0);
                            }
                        }
                        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
                        int outIndex = decoder.dequeueOutputBuffer(info, 0);
                        if (outIndex >= 0) decoder.releaseOutputBuffer(outIndex, true);
                    }
                } catch (Exception ignored) {
                } finally {
                    if (decoder != null) {
                        try { decoder.stop(); } catch (Exception ignored) {}
                        try { decoder.release(); } catch (Exception ignored) {}
                        videoDecoder = null;
                    }
                    if (socket != null) try { socket.close(); } catch (Exception ignored) {}
                    if (running) try { Thread.sleep(3000); } catch (InterruptedException e) { break; }
                }
            }
        }, "VideoThread").start();
    }

    // ─── Recording ────────────────────────────────────────
    private void startRecording() {
        try {
            File dir = new File(getExternalFilesDir(null), "Recordings");
            if (!dir.exists()) dir.mkdirs();
            String fn = "LynxEye_" + new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss",
                    Locale.getDefault()).format(new Date()) + ".pcm";
            recordingStream = new FileOutputStream(new File(dir, fn));
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
            Toast.makeText(this, "⏺ Grabando...", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(this, "Error al grabar", Toast.LENGTH_SHORT).show();
        }
    }

    private void stopRecording() {
        isRecording = false;
        uiHandler.removeCallbacks(recTimeUpdater);
        tvRecTime.setVisibility(View.GONE);
        btnRecord.setImageResource(android.R.drawable.ic_btn_speak_now);
        try {
            if (recordingStream != null) {
                recordingStream.flush(); recordingStream.close(); recordingStream = null;
            }
        } catch (Exception ignored) {}
        Toast.makeText(this, "✅ Grabación guardada", Toast.LENGTH_LONG).show();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        running = false;
        stopPing();
        try { if (cmdSocket != null) cmdSocket.close(); } catch (Exception ignored) {}
        if (isRecording) stopRecording();
        if (equalizer != null) { equalizer.setEnabled(false); equalizer.release(); }
        if (audioTrack != null) { audioTrack.stop(); audioTrack.release(); }
        if (wifiLock != null && wifiLock.isHeld()) wifiLock.release();
    }
}
