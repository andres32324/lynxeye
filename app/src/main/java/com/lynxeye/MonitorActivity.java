package com.lynxeye;

import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.net.ConnectivityManager;
import android.net.wifi.WifiManager;
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

import java.io.BufferedReader;
import java.io.DataInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.PrintWriter;
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
    private static final int VIDEO_TIMEOUT_MS = 20000;
    private static final int CONNECT_TIMEOUT  = 15000;
    private static final int SO_TIMEOUT       = 20000;
    private static final int CMD_TIMEOUT      = 5000;
    private static final int RECONNECT_DELAY  = 4000;
    private static final int PING_INTERVAL    = 60000; // 60s heartbeat

    private String  deviceName, deviceIp;
    private int     sampleRate, audioMode;
    private boolean videoEnabled;

    // UI
    private TextView    tvStatus, tvDeviceName, tvRecTime;
    private ImageView   ivVideo;
    private ImageButton btnRecord, btnSwitchCam, btnScreenshot, btnVideoToggle, btnAudioToggle;
    private android.widget.Button btnEq;
    private ImageButton btnNight;
    private View        layoutEq;
    private volatile boolean nightMode = false;

    // Service
    private AudioService audioService;
    private boolean      serviceBound = false;

    // Video
    private volatile boolean running        = false;
    private volatile boolean videoConnected = false;
    private volatile long    lastFrameTime  = 0;
    private android.graphics.Bitmap lastFrame = null;
    private final BlockingQueue<byte[]> videoQueue = new ArrayBlockingQueue<>(VIDEO_QUEUE_SIZE);
    private volatile Socket videoSocket = null;

    // Comando persistente
    private volatile Socket      cmdSocket = null;
    private volatile PrintWriter cmdWriter = null;
    private Handler  uiHandler   = new Handler();
    private Runnable pingRunnable;
    private Runnable videoWatchdog;

    // Network / Screen
    private ConnectivityManager.NetworkCallback networkCallback;
    private WifiManager.WifiLock wifiLock;
    private BroadcastReceiver screenReceiver;

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
        ivVideo        = findViewById(R.id.ivVideo);
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
        startCommandConnection();

        if (videoEnabled) {
            ivVideo.setBackgroundColor(0xFF111111);
            startVideoReceiver();
            startVideoRenderer();
            startVideoWatchdog();
        } else {
            ivVideo.setBackgroundColor(0xFF0A1A0A);
        }

        registerNetworkCallback();
        registerScreenReceiver();
        acquireWifiLock();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        running = false;
        videoQueue.clear();
        stopPing();
        stopVideoWatchdog();
        closeCmdSocket();
        Socket vs = videoSocket;
        if (vs != null) { try { vs.close(); } catch (Exception ignored) {} videoSocket = null; }
        unregisterNetworkCallback();
        unregisterScreenReceiver();
        if (wifiLock != null && wifiLock.isHeld()) wifiLock.release();
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

    // ─── Conexión de comando persistente ─────────────────
    private void startCommandConnection() {
        new Thread(() -> {
            while (running) {
                try {
                    Socket s = new Socket();
                    s.connect(new InetSocketAddress(deviceIp, PORT_COMMAND), CONNECT_TIMEOUT);
                    s.setTcpNoDelay(true);
                    s.setSoTimeout(60000);
                    cmdSocket = s;
                    cmdWriter = new PrintWriter(s.getOutputStream(), true);
                    BufferedReader reader = new BufferedReader(
                            new InputStreamReader(s.getInputStream()));

                    // Enviar configuración inicial
                    sendCmdDirect(audioMode == 1 ? "AUDIO_STEREO" : "AUDIO_MONO");
                    sendCmdDirect("SR_" + sampleRate);
                    sendCmdDirect(videoEnabled ? "VIDEO_ON" : "VIDEO_OFF");
                    if (audioService != null && audioService.audioEnabled) sendCmdDirect("START_AUDIO");
                    if (videoEnabled) sendCmdDirect("START_CAMERA");

                    // Arrancar heartbeat
                    startPing();
                    runOnUiThread(() -> setStatus("CONNECTED", 0xFF00E676));

                    // Leer respuestas (PONG)
                    String line;
                    while (running && (line = reader.readLine()) != null) {
                        // PONG recibido → conexión viva
                    }
                } catch (Exception ignored) {
                } finally {
                    stopPing();
                    cmdWriter = null;
                    closeCmdSocket();
                    if (running) {
                        runOnUiThread(() -> setStatus("RECONNECTING...", 0xFFFFAA00));
                        try { Thread.sleep(RECONNECT_DELAY); } catch (InterruptedException e) { break; }
                    }
                }
            }
        }, "CmdConnection").start();
    }

    private void sendCmdDirect(String cmd) {
        new Thread(() -> {
            PrintWriter w = cmdWriter;
            if (w != null) w.println(cmd);
        }).start();
    }

    private void closeCmdSocket() {
        try { if (cmdSocket != null) cmdSocket.close(); } catch (Exception ignored) {}
        cmdSocket = null;
    }

    // ─── Heartbeat PING/PONG ──────────────────────────────
    private void startPing() {
        stopPing();
        pingRunnable = new Runnable() {
            @Override public void run() {
                sendCmdDirect("PING");
                if (running) uiHandler.postDelayed(this, PING_INTERVAL);
            }
        };
        uiHandler.postDelayed(pingRunnable, PING_INTERVAL);
    }

    private void stopPing() {
        if (pingRunnable != null) uiHandler.removeCallbacks(pingRunnable);
        pingRunnable = null;
    }

    // ─── Screen Monitor ───────────────────────────────────
    private void acquireWifiLock() {
        try {
            WifiManager wm = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
            wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "LynxEye::WifiLock");
            wifiLock.acquire();
        } catch (Exception ignored) {}
    }

    private void registerScreenReceiver() {
        screenReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context ctx, Intent intent) {
                String action = intent.getAction();
                if (Intent.ACTION_SCREEN_OFF.equals(action)) {
                    if (videoEnabled && running) {
                        videoQueue.clear(); lastFrame = null;
                        Socket vs = videoSocket;
                        if (vs != null) { try { vs.close(); } catch (Exception ignored) {} videoSocket = null; }
                        runOnUiThread(() -> { ivVideo.setImageBitmap(null); ivVideo.setBackgroundColor(0xFF0A1A0A); });
                        stopVideoWatchdog();
                    }
                } else if (Intent.ACTION_SCREEN_ON.equals(action)) {
                    if (videoEnabled && running) {
                        runOnUiThread(() -> ivVideo.setBackgroundColor(0xFF111111));
                        startVideoReceiver();
                        startVideoRenderer();
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
        try { if (screenReceiver != null) unregisterReceiver(screenReceiver); } catch (Exception ignored) {}
    }

    // ─── Network Monitor ──────────────────────────────────
    private void registerNetworkCallback() {
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
            NetworkRequest req = new NetworkRequest.Builder()
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build();
            networkCallback = new ConnectivityManager.NetworkCallback() {
                @Override public void onAvailable(Network network) {
                    if (running) runOnUiThread(() -> setStatus("RECONNECTING...", 0xFFFFAA00));
                }
                @Override public void onLost(Network network) {
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
                    Socket vs = videoSocket;
                    if (vs != null) { try { vs.close(); } catch (Exception ignored) {} videoSocket = null; }
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

    // ─── Video ────────────────────────────────────────────
    private void startVideoReceiver() {
        new Thread(() -> {
            while (running) {
                if (!videoEnabled) { try { Thread.sleep(500); } catch (InterruptedException e) { break; } continue; }
                Socket socket = null;
                try {
                    socket = new Socket();
                    socket.connect(new InetSocketAddress(deviceIp, PORT_VIDEO), CONNECT_TIMEOUT);
                    socket.setTcpNoDelay(true);
                    socket.setSoTimeout(SO_TIMEOUT);
                    socket.setKeepAlive(true);
                    videoSocket = socket;
                    DataInputStream in = new DataInputStream(socket.getInputStream());
                    videoConnected = true;
                    lastFrameTime  = System.currentTimeMillis();
                    runOnUiThread(() -> { tvStatus.setText("⬤  CONNECTED"); tvStatus.setTextColor(0xFF00E676); });
                    while (running && !socket.isClosed()) {
                        int len = in.readInt();
                        if (len <= 0 || len > 3_000_000) continue;
                        byte[] frame = new byte[len];
                        int total = 0;
                        while (total < len) { int r = in.read(frame, total, len - total); if (r < 0) break; total += r; }
                        lastFrameTime = System.currentTimeMillis();
                        if (!videoQueue.offer(frame)) { videoQueue.poll(); videoQueue.offer(frame); }
                    }
                } catch (Exception ignored) {
                } finally {
                    videoConnected = false;
                    videoSocket = null;
                    videoQueue.clear();
                    if (socket != null) try { socket.close(); } catch (Exception ignored2) {}
                    if (running && videoEnabled) {
                        runOnUiThread(() -> setStatus("RECONNECTING...", 0xFFFFAA00));
                        try { Thread.sleep(RECONNECT_DELAY); } catch (InterruptedException e) { break; }
                    }
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
                        if (bmp != null) {
                            if (nightMode) bmp = applyNightFilter(bmp);
                            lastFrame = bmp;
                            final android.graphics.Bitmap display = bmp;
                            runOnUiThread(() -> ivVideo.setImageBitmap(display));
                        }
                    } else if (lastFrame != null) {
                        final android.graphics.Bitmap keep = lastFrame;
                        runOnUiThread(() -> ivVideo.setImageBitmap(keep));
                    }
                } catch (InterruptedException e) { break; }
            }
        }, "VideoRenderer").start();
    }

    // ─── Night Filter ─────────────────────────────────────
    private android.graphics.Bitmap applyNightFilter(android.graphics.Bitmap src) {
        if (src == null) return null;
        android.graphics.Bitmap out = src.copy(android.graphics.Bitmap.Config.RGB_565, true);
        android.graphics.Canvas canvas = new android.graphics.Canvas(out);
        float scale = 1.4f, translate = (-(1.4f - 1) * 128f) + 80f;
        android.graphics.ColorMatrix cm = new android.graphics.ColorMatrix(new float[]{
            scale,0,0,0,translate, 0,scale,0,0,translate, 0,0,scale,0,translate, 0,0,0,1,0});
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

        btnSwitchCam.setOnClickListener(v -> sendCmdDirect("SWITCH_CAM"));

        btnVideoToggle.setOnClickListener(v -> {
            boolean nw = !AppSettings.isVideoEnabled(this);
            AppSettings.setVideoEnabled(this, nw);
            videoEnabled = nw;
            sendCmdDirect(nw ? "START_CAMERA" : "STOP_CAMERA");
            btnVideoToggle.setColorFilter(nw ? 0xFF00E676 : 0xFFFF3D3D);
            if (!nw) {
                videoQueue.clear(); lastFrame = null;
                Socket vs = videoSocket;
                if (vs != null) { try { vs.close(); } catch (Exception ignored) {} videoSocket = null; }
                ivVideo.setImageBitmap(null);
                ivVideo.setBackgroundColor(0xFF0A1A0A);
                stopVideoWatchdog();
            } else {
                ivVideo.setBackgroundColor(0xFF111111);
                startVideoReceiver();
                startVideoRenderer();
                startVideoWatchdog();
            }
        });
        btnVideoToggle.setColorFilter(videoEnabled ? 0xFF00E676 : 0xFFFF3D3D);

        btnAudioToggle.setOnClickListener(v -> {
            if (audioService == null) return;
            boolean nw = !audioService.audioEnabled;
            audioService.setAudioEnabled(nw);
            // Notificar a Menu Claro para abrir/cerrar mic
            sendCmdDirect(nw ? "START_AUDIO" : "STOP_AUDIO");
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
            btnNight.setColorFilter(nightMode ? 0xFFFFFF00 : 0xFF00E676);
            lastFrame = null;
            runOnUiThread(() -> ivVideo.setImageBitmap(null));
            Toast.makeText(this, nightMode ? "Modo noche ON" : "Modo noche OFF", Toast.LENGTH_SHORT).show();
        });
        btnNight.setColorFilter(0xFF00E676);

        btnEq.setOnClickListener(v ->
                layoutEq.setVisibility(layoutEq.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE));
    }
}
