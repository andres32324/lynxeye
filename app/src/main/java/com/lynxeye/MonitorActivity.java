package com.lynxeye;

import android.content.BroadcastReceiver;
import android.content.ComponentName;
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
import android.media.MediaCodec;
import android.media.MediaFormat;
import android.view.Surface;
import android.view.TextureView;
import java.nio.ByteBuffer;
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
    private static final int VIDEO_QUEUE_SIZE = 2;
    private static final int VIDEO_TIMEOUT_MS = 15000;

    private String  deviceName, deviceIp;
    private int     sampleRate, audioMode;
    private boolean videoEnabled;

    // UI
    private TextView    tvStatus, tvDeviceName, tvRecTime;
    private TextureView textureView;
    private volatile Surface decoderSurface = null;
    private volatile MediaCodec videoDecoder = null;
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
    private android.graphics.Bitmap lastFrame = null;
    private final BlockingQueue<byte[]> videoQueue = new ArrayBlockingQueue<>(VIDEO_QUEUE_SIZE);

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
        textureView = findViewById(R.id.textureView);
        textureView.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override
            public void onSurfaceTextureAvailable(android.graphics.SurfaceTexture st, int w, int h) { decoderSurface = new Surface(st); }
            @Override public void onSurfaceTextureSizeChanged(android.graphics.SurfaceTexture st, int w, int h) {}
            @Override public boolean onSurfaceTextureDestroyed(android.graphics.SurfaceTexture st) { decoderSurface = null; return true; }
            @Override public void onSurfaceTextureUpdated(android.graphics.SurfaceTexture st) { lastFrame = textureView.getBitmap(); }
        });
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
        if (videoEnabled) {
            startVideoReceiver();
            startVideoWatchdog();
        } else {
        }

        registerNetworkCallback();
        registerScreenReceiver();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        running = false;
        videoQueue.clear();
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

    // ─── Screen Monitor ───────────────────────────────────
    private void registerScreenReceiver() {
        screenReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context ctx, Intent intent) {
                String action = intent.getAction();
                if (Intent.ACTION_SCREEN_OFF.equals(action)) {
                    if (videoEnabled && running) {
                        videoQueue.clear();
                        lastFrame = null;
                        runOnUiThread(() -> {
                                        });
                        stopVideoWatchdog();
                    }
                } else if (Intent.ACTION_SCREEN_ON.equals(action)) {
                    if (videoEnabled && running) {
                        startVideoReceiver();
                                    startVideoWatchdog();
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
                        videoQueue.clear();
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
                if (elapsed > VIDEO_TIMEOUT_MS && videoConnected) {
                    videoConnected = false;
                    videoQueue.clear();
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
                sb.append("START_AUDIO\n");
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

    // ─── Video ────────────────────────────────────────────
    private void startVideoReceiver() {
        new Thread(() -> {
            while (running) {
                if (decoderSurface == null) {
                    try { Thread.sleep(200); } catch (InterruptedException e) { break; }
                    continue;
                }
                Socket socket = null;
                MediaCodec decoder = null;
                try {
                    socket = new Socket();
                    Thread.sleep(3000);
                socket.connect(new InetSocketAddress(deviceIp, PORT_VIDEO), 5000);
                    socket.setTcpNoDelay(true);
                    socket.setSoTimeout(VIDEO_TIMEOUT_MS);
                    socket.setKeepAlive(true);
                    DataInputStream in = new DataInputStream(socket.getInputStream());
                    decoder = MediaCodec.createDecoderByType("video/avc");
                    videoDecoder = decoder;
                    boolean configured = false;
                    videoConnected = true;
                    lastFrameTime = System.currentTimeMillis();
                    runOnUiThread(() -> { tvStatus.setText("⬤  CONNECTED"); tvStatus.setTextColor(0xFF00E676); });
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
                        lastFrameTime = System.currentTimeMillis();
                        boolean isConfig = (flags & 1) != 0;
                        if (isConfig && !configured && decoderSurface != null) {
                            int split = -1;
                            for (int i = 1; i < data.length - 3; i++) {
                                if (data[i]==0 && data[i+1]==0 && data[i+2]==0 && data[i+3]==1) {
                                    split = i; break;
                                }
                            }
                            MediaFormat format = MediaFormat.createVideoFormat("video/avc", 1280, 720);
                            if (split > 0) {
                                format.setByteBuffer("csd-0", ByteBuffer.wrap(data, 0, split));
                                format.setByteBuffer("csd-1", ByteBuffer.wrap(data, split, data.length - split));
                            } else {
                                format.setByteBuffer("csd-0", ByteBuffer.wrap(data));
                            }
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
                                        System.nanoTime() / 1000, 0);
                            }
                        }
                        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
                        int outIndex = decoder.dequeueOutputBuffer(info, 0);
                        if (outIndex >= 0) decoder.releaseOutputBuffer(outIndex, true);
                    }
                } catch (Exception ignored) {
                } finally {
                    videoConnected = false;
                    if (decoder != null) {
                        try { decoder.stop(); } catch (Exception ignored) {}
                        try { decoder.release(); } catch (Exception ignored) {}
                        videoDecoder = null;
                    }
                    if (socket != null) try { socket.close(); } catch (Exception ignored) {}
                    if (running) try { Thread.sleep(2000); } catch (InterruptedException e) { break; }
                }
            }
        }, "VideoReceiver").start();
    }



    // ─── Night Filter ─────────────────────────────────────
    private android.graphics.Bitmap applyNightFilter(android.graphics.Bitmap src) {
        if (src == null) return null;
        android.graphics.Bitmap out = src.copy(android.graphics.Bitmap.Config.RGB_565, true);
        android.graphics.Canvas canvas = new android.graphics.Canvas(out);
        float brightness = 80f;
        float contrast   = 1.4f;
        float scale = contrast;
        float translate = (-(contrast - 1) * 128f) + brightness;
        android.graphics.ColorMatrix cm = new android.graphics.ColorMatrix(new float[]{
            scale, 0, 0, 0, translate,
            0, scale, 0, 0, translate,
            0, 0, scale, 0, translate,
            0, 0, 0, 1, 0
        });
        android.graphics.Paint paint = new android.graphics.Paint();
        paint.setColorFilter(new android.graphics.ColorMatrixColorFilter(cm));
        canvas.drawBitmap(src, 0, 0, paint);
        return out;
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
            btnVideoToggle.setColorFilter(nw ? 0xFF00E676 : 0xFFFF3D3D);
            if (!nw) {
                videoQueue.clear(); lastFrame = null;
                    stopVideoWatchdog();
            } else {
                    startVideoReceiver();
                    startVideoWatchdog();
            }
        });
        btnVideoToggle.setColorFilter(videoEnabled ? 0xFF00E676 : 0xFFFF3D3D);

        btnAudioToggle.setOnClickListener(v -> {
            if (audioService == null) return;
            boolean nw = !audioService.audioEnabled;
            audioService.setAudioEnabled(nw);
            btnAudioToggle.setColorFilter(nw ? 0xFF00E676 : 0xFFFF3D3D);
        });
        btnAudioToggle.setColorFilter(0xFF00E676);

        btnScreenshot.setOnClickListener(v -> {
            if (lastFrame == null) { Toast.makeText(this, "No hay video activo", Toast.LENGTH_SHORT).show(); return; }
            try {
                File dir = new File(getExternalFilesDir(null), "Screenshots");
                if (!dir.exists()) dir.mkdirs();
                String fn = "LynxEye_" + new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss",
                        Locale.getDefault()).format(new Date()) + ".jpg";
                File file = new File(dir, fn);
                FileOutputStream fos = new FileOutputStream(file);
                lastFrame.compress(android.graphics.Bitmap.CompressFormat.JPEG, 95, fos);
                fos.flush(); fos.close();
                Toast.makeText(this, "📸 " + fn, Toast.LENGTH_LONG).show();
            } catch (Exception e) {
                Toast.makeText(this, "Error: " + e.getMessage(), Toast.LENGTH_LONG).show();
            }
        });

        btnNight.setOnClickListener(v -> {
            nightMode = !nightMode;
            if (nightMode) {
                android.graphics.ColorMatrix cm = new android.graphics.ColorMatrix(new float[]{1.4f,0,0,0,80f,0,1.4f,0,0,80f,0,0,1.4f,0,80f,0,0,0,1,0});
                textureView.setLayerType(android.view.View.LAYER_TYPE_HARDWARE, null);
                android.graphics.Paint p = new android.graphics.Paint();
                p.setColorFilter(new android.graphics.ColorMatrixColorFilter(cm));
                textureView.setLayerPaint(p);
            } else {
                textureView.setLayerPaint(null);
            }
            btnNight.setColorFilter(nightMode ? 0xFFFFFF00 : 0xFF00E676);
            lastFrame = null;
            Toast.makeText(this, nightMode ? "Modo noche ON" : "Modo noche OFF", Toast.LENGTH_SHORT).show();
        });
        btnNight.setColorFilter(0xFF00E676);

        btnEq.setOnClickListener(v ->
                layoutEq.setVisibility(layoutEq.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE));
    }
}
