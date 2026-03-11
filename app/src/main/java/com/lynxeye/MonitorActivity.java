package com.lynxeye;

import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.media.MediaCodec;
import android.media.MediaFormat;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import java.io.BufferedInputStream;
import java.util.concurrent.ArrayBlockingQueue;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
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
import java.net.InetSocketAddress;
import java.net.Socket;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

public class MonitorActivity extends AppCompatActivity implements AudioService.Callback {

    private static final int PORT_VIDEO       = 9998;
    private static final int PORT_COMMAND     = 9997;
    private static final int VIDEO_TIMEOUT_MS = 8000;

    private String  deviceName, deviceIp;
    private int     sampleRate, audioMode;
    private boolean videoEnabled;

    // UI
    private TextView    tvStatus, tvDeviceName, tvRecTime;
    private SurfaceView videoSurface;
    private ImageButton btnRecord, btnSwitchCam, btnScreenshot, btnVideoToggle, btnAudioToggle;
    private android.widget.Button btnEq;
    private ImageButton btnNight;
    private View        layoutEq;
    private volatile boolean nightMode = false;

    // Service
    private AudioService audioService;
    private boolean      serviceBound = false;

    // Video
    private volatile boolean running       = false;
    private volatile boolean videoConnected = false;
    private volatile long    lastFrameTime  = 0;
    private VideoReceiver    videoReceiver  = null;

    // Network / Screen
    private ConnectivityManager.NetworkCallback networkCallback;
    private BroadcastReceiver screenReceiver;
    private Handler  uiHandler    = new Handler();
    private Runnable videoWatchdog;

    // Recording UI
    private boolean  isRecording = false;
    private long     recordingStart;
    private Runnable recTimeUpdater;

    // ─── Service Connection ───────────────────────────────
    private final ServiceConnection serviceConn = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            audioService = ((AudioService.AudioBinder) binder).getService();
            serviceBound = true;
            audioService.setCallback(MonitorActivity.this);
            if (!audioService.isRunning() || !deviceIp.equals(audioService.getDeviceIp())) {
                audioService.startMonitoring(deviceIp, deviceName, sampleRate, audioMode,
                        AppSettings.isNoiseSuppression(MonitorActivity.this));
                sendInitialSettings();
            }
            restoreEqState();
            btnAudioToggle.setColorFilter(audioService.audioEnabled ? 0xFF00E676 : 0xFFFF3D3D);
        }
        @Override
        public void onServiceDisconnected(ComponentName name) {
            serviceBound = false; audioService = null;
        }
    };

    // ─── Lifecycle ────────────────────────────────────────
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

        tvStatus       = findViewById(R.id.tvStatus);
        tvDeviceName   = findViewById(R.id.tvDeviceName);
        tvRecTime      = findViewById(R.id.tvRecTime);
        videoSurface   = findViewById(R.id.videoSurface);
        btnRecord      = findViewById(R.id.btnRecord);
        btnSwitchCam   = findViewById(R.id.btnSwitchCam);
        btnScreenshot  = findViewById(R.id.btnScreenshot);
        btnVideoToggle = findViewById(R.id.btnVideoToggle);
        btnAudioToggle = findViewById(R.id.btnAudioToggle);
        btnEq          = findViewById(R.id.btnEq);
        btnNight       = findViewById(R.id.btnNight);
        layoutEq       = findViewById(R.id.layoutEq);

        tvDeviceName.setText(deviceName);
        setStatus("CONNECTING...", 0xFFFFAA00);

        setupEqualizer();
        setupButtons();

        Intent svcIntent = new Intent(this, AudioService.class);
        startService(svcIntent);
        bindService(svcIntent, serviceConn, Context.BIND_AUTO_CREATE);

        running = true;

        // Setup SurfaceView callback para H264
        videoSurface.getHolder().addCallback(new SurfaceHolder.Callback() {
            @Override
            public void surfaceCreated(SurfaceHolder holder) {
                if (videoEnabled) {
                    startH264Receiver(holder.getSurface());
                    startVideoWatchdog();
                }
            }
            @Override
            public void surfaceDestroyed(SurfaceHolder holder) {
                stopH264Receiver();
            }
            @Override
            public void surfaceChanged(SurfaceHolder holder, int f, int w, int h) {}
        });

        if (!videoEnabled) {
            videoSurface.setVisibility(View.GONE);
        }

        registerNetworkCallback();
        registerScreenReceiver();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        running = false;
        stopH264Receiver();
        stopVideoWatchdog();
        unregisterNetworkCallback();
        unregisterScreenReceiver();
        if (serviceBound) {
            audioService.setCallback(null);
            unbindService(serviceConn);
            serviceBound = false;
        }
    }

    @Override
    public void onBackPressed() {
        Intent intent = new Intent(this, MainActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        startActivity(intent);
        finish();
    }

    private void startH264Receiver(android.view.Surface surface) {
        stopH264Receiver();
        videoReceiver = new VideoReceiver(deviceIp, PORT_VIDEO, surface);
        videoReceiver.start();
        videoConnected = true;
        runOnUiThread(() -> { tvStatus.setText("⬤  CONNECTED"); tvStatus.setTextColor(0xFF00E676); });
    }

    private void stopH264Receiver() {
        if (videoReceiver != null) {
            videoReceiver.stop();
            videoReceiver = null;
        }
        videoConnected = false;
    }

    // ─── Screen Monitor ───────────────────────────────────
    private void registerScreenReceiver() {
        screenReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context ctx, Intent intent) {
                String action = intent.getAction();
                if (Intent.ACTION_SCREEN_OFF.equals(action)) {
                    if (videoEnabled && running) {
                        stopH264Receiver();
                        stopVideoWatchdog();
                    }
                } else if (Intent.ACTION_SCREEN_ON.equals(action)) {
                    if (videoEnabled && running) {
                        SurfaceHolder holder = videoSurface.getHolder();
                        if (holder != null && holder.getSurface() != null && holder.getSurface().isValid()) {
                            startH264Receiver(holder.getSurface());
                            startVideoWatchdog();
                        }
                    }
                }
            }
        };
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_SCREEN_OFF);
        filter.addAction(Intent.ACTION_SCREEN_ON);
        registerReceiver(screenReceiver, filter);
    }

    private void unregisterScreenReceiver() {
        try { if (screenReceiver != null) unregisterReceiver(screenReceiver); }
        catch (Exception ignored) {}
    }

    // ─── Network Monitor ──────────────────────────────────
    private void registerNetworkCallback() {
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
            NetworkRequest req = new NetworkRequest.Builder()
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build();
            networkCallback = new ConnectivityManager.NetworkCallback() {
                @Override
                public void onAvailable(Network network) {
                    if (running && videoEnabled) {
                        runOnUiThread(() -> setStatus("RECONNECTING...", 0xFFFFAA00));
                    }
                }
                @Override
                public void onLost(Network network) {
                    runOnUiThread(() -> setStatus("NO NETWORK", 0xFFFF3D3D));
                }
            };
            cm.registerNetworkCallback(req, networkCallback);
        } catch (Exception ignored) {}
    }

    private void unregisterNetworkCallback() {
        try {
            if (networkCallback != null) {
                ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
                cm.unregisterNetworkCallback(networkCallback);
                networkCallback = null;
            }
        } catch (Exception ignored) {}
    }

    // ─── Video Watchdog ───────────────────────────────────
    private void startVideoWatchdog() {
        lastFrameTime = System.currentTimeMillis();
        videoWatchdog = new Runnable() {
            @Override public void run() {
                if (!running || !videoEnabled) return;
                long elapsed = System.currentTimeMillis() - lastFrameTime;
                if (elapsed > VIDEO_TIMEOUT_MS) {
                    runOnUiThread(() -> setStatus("VIDEO TIMEOUT - RECONNECTING...", 0xFFFFAA00));
                }
                uiHandler.postDelayed(this, 3000);
            }
        };
        uiHandler.postDelayed(videoWatchdog, 3000);
    }

    private void stopVideoWatchdog() {
        if (videoWatchdog != null) uiHandler.removeCallbacks(videoWatchdog);
    }

    // ─── AudioService.Callback ────────────────────────────
    @Override
    public void onAudioLevel(float level) {}

    @Override
    public void onAudioConnected(boolean connected) {
        runOnUiThread(() -> {
            if (connected || videoConnected) {
                tvStatus.setText("⬤  CONNECTED"); tvStatus.setTextColor(0xFF00E676);
            } else {
                tvStatus.setText("⬤  LOST SIGNAL"); tvStatus.setTextColor(0xFFFF3D3D);
            }
        });
    }

    private void setStatus(String text, int color) {
        runOnUiThread(() -> { tvStatus.setText("⬤  " + text); tvStatus.setTextColor(color); });
    }

    // ─── Settings ─────────────────────────────────────────
    private void sendInitialSettings() {
        new Thread(() -> {
            try {
                Thread.sleep(1000);
                Socket cmd = new Socket();
                cmd.connect(new InetSocketAddress(deviceIp, PORT_COMMAND), 2000);
                StringBuilder sb = new StringBuilder();
                if (audioMode == 1) sb.append("AUDIO_STEREO\n"); else sb.append("AUDIO_MONO\n");
                sb.append("SR_").append(sampleRate).append("\n");
                sb.append(videoEnabled ? "VIDEO_ON\n" : "VIDEO_OFF\n");
                if (videoEnabled) sb.append("START_CAMERA\n");
                cmd.getOutputStream().write(sb.toString().getBytes());
                cmd.getOutputStream().flush();
                cmd.close();
            } catch (Exception ignored) {}
        }).start();
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

    // ─── Equalizer ────────────────────────────────────────
    private void setupEqualizer() {
        int[] seekIds  = {R.id.seekBand0,R.id.seekBand1,R.id.seekBand2,R.id.seekBand3,R.id.seekBand4,
                          R.id.seekBand5,R.id.seekBand6,R.id.seekBand7,R.id.seekBand8,R.id.seekBand9};
        int[] gainIds  = {R.id.tvGain0,R.id.tvGain1,R.id.tvGain2,R.id.tvGain3,R.id.tvGain4,
                          R.id.tvGain5,R.id.tvGain6,R.id.tvGain7,R.id.tvGain8,R.id.tvGain9};
        int[] labelIds = {R.id.tvBand0,R.id.tvBand1,R.id.tvBand2,R.id.tvBand3,R.id.tvBand4,
                          R.id.tvBand5,R.id.tvBand6,R.id.tvBand7,R.id.tvBand8,R.id.tvBand9};
        for (int i = 0; i < DspEqualizer.BANDS; i++) {
            ((TextView) findViewById(labelIds[i])).setText(DspEqualizer.LABELS[i]);
            SeekBar sb = findViewById(seekIds[i]);
            sb.setMax(240); sb.setProgress(120);
            final int band = i;
            final TextView tvGain = findViewById(gainIds[i]);
            sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override public void onProgressChanged(SeekBar s, int p, boolean u) {
                    float db = (p - 120) / 10f;
                    if (audioService != null) audioService.setGain(band, db);
                    tvGain.setText(String.format(Locale.getDefault(), "%+.0fdB", db));
                    AppSettings.setEqGain(MonitorActivity.this, deviceIp, band, db);
                }
                @Override public void onStartTrackingTouch(SeekBar s) {}
                @Override public void onStopTrackingTouch(SeekBar s) {}
            });
        }
    }

    private void restoreEqState() {
        if (audioService == null) return;
        int[] seekIds = {R.id.seekBand0,R.id.seekBand1,R.id.seekBand2,R.id.seekBand3,R.id.seekBand4,
                         R.id.seekBand5,R.id.seekBand6,R.id.seekBand7,R.id.seekBand8,R.id.seekBand9};
        int[] gainIds = {R.id.tvGain0,R.id.tvGain1,R.id.tvGain2,R.id.tvGain3,R.id.tvGain4,
                         R.id.tvGain5,R.id.tvGain6,R.id.tvGain7,R.id.tvGain8,R.id.tvGain9};
        for (int i = 0; i < DspEqualizer.BANDS; i++) {
            float db = AppSettings.getEqGain(this, deviceIp, i);
            audioService.setGain(i, db);
            ((SeekBar) findViewById(seekIds[i])).setProgress((int)(db * 10 + 120));
            ((TextView) findViewById(gainIds[i])).setText(
                    String.format(Locale.getDefault(), "%+.0fdB", db));
        }
    }

    // ─── Buttons ──────────────────────────────────────────
    private void setupButtons() {
        btnRecord.setOnClickListener(v -> {
            if (audioService == null) return;
            if (isRecording) {
                File saved = audioService.stopRecording();
                isRecording = false;
                uiHandler.removeCallbacks(recTimeUpdater);
                tvRecTime.setVisibility(View.GONE);
                btnRecord.setImageResource(android.R.drawable.ic_btn_speak_now);
                if (saved != null) Toast.makeText(this, "✅ " + saved.getName(), Toast.LENGTH_LONG).show();
            } else {
                File dir = new File(getExternalFilesDir(null), "Recordings");
                if (!dir.exists()) dir.mkdirs();
                if (audioService.startRecording(dir)) {
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
                }
            }
        });

        btnSwitchCam.setOnClickListener(v -> sendCommand("SWITCH_CAM"));

        btnVideoToggle.setOnClickListener(v -> {
            boolean cur = AppSettings.isVideoEnabled(this);
            boolean nw  = !cur;
            AppSettings.setVideoEnabled(this, nw);
            videoEnabled = nw;
            sendCommand(nw ? "VIDEO_ON" : "VIDEO_OFF");
            sendCommand(nw ? "START_CAMERA" : "STOP_CAMERA");
            btnVideoToggle.setColorFilter(nw ? 0xFF00E676 : 0xFFFF3D3D);
            if (!nw) {
                stopH264Receiver();
                stopVideoWatchdog();
                videoSurface.setVisibility(View.GONE);
            } else {
                videoSurface.setVisibility(View.VISIBLE);
                SurfaceHolder holder = videoSurface.getHolder();
                if (holder != null && holder.getSurface() != null && holder.getSurface().isValid()) {
                    startH264Receiver(holder.getSurface());
                    startVideoWatchdog();
                }
            }
        });
        btnVideoToggle.setColorFilter(videoEnabled ? 0xFF00E676 : 0xFFFF3D3D);

        btnAudioToggle.setOnClickListener(v -> {
            if (audioService == null) return;
            boolean nw = !audioService.audioEnabled;
            audioService.setAudioEnabled(nw);
            sendCommand(nw ? "START_AUDIO" : "STOP_AUDIO");
            btnAudioToggle.setColorFilter(nw ? 0xFF00E676 : 0xFFFF3D3D);
        });
        btnAudioToggle.setColorFilter(0xFF00E676);

        btnScreenshot.setOnClickListener(v ->
            Toast.makeText(this, "Screenshot no disponible en modo H264", Toast.LENGTH_SHORT).show()
        );

        btnNight.setOnClickListener(v -> {
            nightMode = !nightMode;
            btnNight.setColorFilter(nightMode ? 0xFFFFFF00 : 0xFF00E676);
            Toast.makeText(this, nightMode ? "Modo noche ON" : "Modo noche OFF", Toast.LENGTH_SHORT).show();
        });
        btnNight.setColorFilter(0xFF00E676);

        btnEq.setOnClickListener(v ->
                layoutEq.setVisibility(layoutEq.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE));
    }

    // ─── H264 Video Receiver ─────────────────────────────────
    class VideoReceiver {
        private final String host;
        private final int port;
        private android.view.Surface surface;
        private volatile boolean running;
        private java.net.Socket socket;
        private java.io.DataInputStream in;
        private MediaCodec decoder;
        private final ArrayBlockingQueue<byte[]> frameQueue = new ArrayBlockingQueue<>(2);
        private byte[] spspps;
        private long pts = 0;
        private static final long FRAME_TIME = 33333;

        VideoReceiver(String host, int port, android.view.Surface surface) {
            this.host = host; this.port = port; this.surface = surface;
        }

        void start() {
            running = true;
            new Thread(this::connectLoop, "VR-Connect").start();
        }

        void stop() {
            running = false;
            try { if (socket != null) socket.close(); } catch (Exception ignored) {}
            try { if (decoder != null) { decoder.stop(); decoder.release(); } } catch (Exception ignored) {}
        }

        private void connectLoop() {
            while (running) {
                try {
                    connect();
                    startDecoder();
                    Thread reader = new Thread(this::readerLoop, "VR-Reader");
                    Thread dec = new Thread(this::decoderLoop, "VR-Decoder");
                    reader.start(); dec.start();
                    reader.join(); dec.join();
                } catch (Exception e) {
                    sleepRetry();
                }
            }
        }

        private void connect() throws Exception {
            socket = new java.net.Socket();
            socket.connect(new java.net.InetSocketAddress(host, port), 3000);
            socket.setTcpNoDelay(true);
            socket.setReceiveBufferSize(1024 * 1024);
            in = new java.io.DataInputStream(new BufferedInputStream(socket.getInputStream()));
            videoConnected = true;
            lastFrameTime = System.currentTimeMillis();
            runOnUiThread(() -> { tvStatus.setText("⬤  CONNECTED"); tvStatus.setTextColor(0xFF00E676); });
        }

        private void startDecoder() throws Exception {
            decoder = MediaCodec.createDecoderByType("video/avc");
            MediaFormat format = MediaFormat.createVideoFormat("video/avc", 1280, 720);
            decoder.configure(format, surface, null, 0);
            decoder.start();
        }

        private void readerLoop() {
            try {
                while (running && !socket.isClosed()) {
                    int len = in.readInt();
                    int flags = in.readUnsignedByte();
                    byte[] data = new byte[len];
                    in.readFully(data);
                    lastFrameTime = System.currentTimeMillis();
                    if (flags == 1) { spspps = data; offerFrame(data); continue; }
                    if (spspps != null) offerFrame(data);
                }
            } catch (Exception ignored) {
            } finally {
                videoConnected = false;
            }
        }

        private void offerFrame(byte[] frame) {
            if (!frameQueue.offer(frame)) { frameQueue.poll(); frameQueue.offer(frame); }
        }

        private void decoderLoop() {
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            while (running) {
                try {
                    drainDecoder(info);
                    byte[] frame = frameQueue.poll(500, java.util.concurrent.TimeUnit.MILLISECONDS);
                    if (frame != null) queueDecoder(frame);
                } catch (Exception ignored) {}
            }
        }

        private void queueDecoder(byte[] data) {
            try {
                int index = decoder.dequeueInputBuffer(0);
                if (index >= 0) {
                    java.nio.ByteBuffer buf = decoder.getInputBuffer(index);
                    buf.clear(); buf.put(data);
                    decoder.queueInputBuffer(index, 0, data.length, pts, 0);
                    pts += FRAME_TIME;
                }
            } catch (Exception ignored) {}
        }

        private void drainDecoder(MediaCodec.BufferInfo info) {
            while (true) {
                int out = decoder.dequeueOutputBuffer(info, 0);
                if (out < 0) break;
                decoder.releaseOutputBuffer(out, true);
            }
        }

        private void sleepRetry() {
            try { Thread.sleep(2000); } catch (Exception ignored) {}
        }
    }

}
