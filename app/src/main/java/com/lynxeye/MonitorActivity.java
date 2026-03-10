package com.lynxeye;

import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Paint;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkRequest;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.view.View;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.InputDeviceCompat;
import com.lynxeye.AudioService;
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

/* JADX INFO: loaded from: classes3.dex */
public class MonitorActivity extends AppCompatActivity implements AudioService.Callback {
    private static final int PORT_COMMAND = 9997;
    private static final int PORT_VIDEO = 9998;
    private static final int VIDEO_QUEUE_SIZE = 2;
    private static final int VIDEO_TIMEOUT_MS = 8000;
    private int audioMode;
    private AudioService audioService;
    private ImageButton btnAudioToggle;
    private Button btnEq;
    private ImageButton btnNight;
    private ImageButton btnRecord;
    private ImageButton btnScreenshot;
    private ImageButton btnSwitchCam;
    private ImageButton btnVideoToggle;
    private String deviceIp;
    private String deviceName;
    private ImageView ivVideo;
    private View layoutEq;
    private ConnectivityManager.NetworkCallback networkCallback;
    private Runnable recTimeUpdater;
    private long recordingStart;
    private int sampleRate;
    private BroadcastReceiver screenReceiver;
    private TextView tvDeviceName;
    private TextView tvRecTime;
    private TextView tvStatus;
    private boolean videoEnabled;
    private Runnable videoWatchdog;
    private volatile boolean nightMode = false;
    private boolean serviceBound = false;
    private volatile boolean running = false;
    private volatile boolean videoConnected = false;
    private volatile long lastFrameTime = 0;
    private Bitmap lastFrame = null;
    private final BlockingQueue<byte[]> videoQueue = new ArrayBlockingQueue(2);
    private Handler uiHandler = new Handler();
    private boolean isRecording = false;
    private final ServiceConnection serviceConn = new ServiceConnection() { // from class: com.lynxeye.MonitorActivity.1
        @Override // android.content.ServiceConnection
        public void onServiceConnected(ComponentName name, IBinder binder) {
            MonitorActivity.this.audioService = ((AudioService.AudioBinder) binder).getService();
            MonitorActivity.this.serviceBound = true;
            MonitorActivity.this.audioService.setCallback(MonitorActivity.this);
            if (!MonitorActivity.this.audioService.isRunning() || !MonitorActivity.this.deviceIp.equals(MonitorActivity.this.audioService.getDeviceIp())) {
                MonitorActivity.this.audioService.startMonitoring(MonitorActivity.this.deviceIp, MonitorActivity.this.deviceName, MonitorActivity.this.sampleRate, MonitorActivity.this.audioMode, AppSettings.isNoiseSuppression(MonitorActivity.this));
                MonitorActivity.this.sendInitialSettings();
            }
            MonitorActivity.this.restoreEqState();
            MonitorActivity.this.btnAudioToggle.setColorFilter(MonitorActivity.this.audioService.audioEnabled ? -16718218 : -49859);
        }

        @Override // android.content.ServiceConnection
        public void onServiceDisconnected(ComponentName name) {
            MonitorActivity.this.serviceBound = false;
            MonitorActivity.this.audioService = null;
        }
    };

    @Override // androidx.fragment.app.FragmentActivity, androidx.activity.ComponentActivity, androidx.core.app.ComponentActivity, android.app.Activity
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(128);
        setContentView(R.layout.activity_monitor);
        this.deviceName = getIntent().getStringExtra("name");
        this.deviceIp = getIntent().getStringExtra("ip");
        this.sampleRate = AppSettings.getSampleRate(this);
        this.audioMode = AppSettings.getAudioMode(this);
        this.videoEnabled = AppSettings.isVideoEnabled(this);
        this.tvStatus = (TextView) findViewById(R.id.tvStatus);
        this.tvDeviceName = (TextView) findViewById(R.id.tvDeviceName);
        this.tvRecTime = (TextView) findViewById(R.id.tvRecTime);
        this.ivVideo = (ImageView) findViewById(R.id.ivVideo);
        this.btnRecord = (ImageButton) findViewById(R.id.btnRecord);
        this.btnSwitchCam = (ImageButton) findViewById(R.id.btnSwitchCam);
        this.btnScreenshot = (ImageButton) findViewById(R.id.btnScreenshot);
        this.btnVideoToggle = (ImageButton) findViewById(R.id.btnVideoToggle);
        this.btnAudioToggle = (ImageButton) findViewById(R.id.btnAudioToggle);
        this.btnEq = (Button) findViewById(R.id.btnEq);
        this.btnNight = (ImageButton) findViewById(R.id.btnNight);
        this.layoutEq = findViewById(R.id.layoutEq);
        this.tvDeviceName.setText(this.deviceName);
        setStatus("CONNECTING...", -22016);
        setupEqualizer();
        setupButtons();
        Intent svcIntent = new Intent(this, (Class<?>) AudioService.class);
        startService(svcIntent);
        bindService(svcIntent, this.serviceConn, 1);
        this.running = true;
        if (this.videoEnabled) {
            this.ivVideo.setBackgroundColor(-15658735);
            startVideoReceiver();
            startVideoRenderer();
            startVideoWatchdog();
        } else {
            this.ivVideo.setBackgroundColor(-16115190);
        }
        registerNetworkCallback();
        registerScreenReceiver();
    }

    @Override // androidx.appcompat.app.AppCompatActivity, androidx.fragment.app.FragmentActivity, android.app.Activity
    protected void onDestroy() {
        super.onDestroy();
        this.running = false;
        this.videoQueue.clear();
        stopVideoWatchdog();
        unregisterNetworkCallback();
        unregisterScreenReceiver();
        if (this.serviceBound) {
            this.audioService.setCallback(null);
            unbindService(this.serviceConn);
            this.serviceBound = false;
        }
    }

    @Override // androidx.activity.ComponentActivity, android.app.Activity
    public void onBackPressed() {
        Intent intent = new Intent(this, (Class<?>) MainActivity.class);
        intent.setFlags(603979776);
        startActivity(intent);
        finish();
    }

    /* JADX INFO: renamed from: com.lynxeye.MonitorActivity$2, reason: invalid class name */
    class AnonymousClass2 extends BroadcastReceiver {
        AnonymousClass2() {
        }

        @Override // android.content.BroadcastReceiver
        public void onReceive(Context ctx, Intent intent) {
            String action = intent.getAction();
            if ("android.intent.action.SCREEN_OFF".equals(action)) {
                if (MonitorActivity.this.videoEnabled && MonitorActivity.this.running) {
                    MonitorActivity.this.videoQueue.clear();
                    MonitorActivity.this.lastFrame = null;
                    MonitorActivity.this.runOnUiThread(new Runnable() { // from class: com.lynxeye.MonitorActivity$2$$ExternalSyntheticLambda0
                        @Override // java.lang.Runnable
                        public final void run() {
                            this.f$0.m169lambda$onReceive$0$comlynxeyeMonitorActivity$2();
                        }
                    });
                    MonitorActivity.this.stopVideoWatchdog();
                    return;
                }
                return;
            }
            if ("android.intent.action.SCREEN_ON".equals(action) && MonitorActivity.this.videoEnabled && MonitorActivity.this.running) {
                MonitorActivity.this.runOnUiThread(new Runnable() { // from class: com.lynxeye.MonitorActivity$2$$ExternalSyntheticLambda1
                    @Override // java.lang.Runnable
                    public final void run() {
                        this.f$0.m170lambda$onReceive$1$comlynxeyeMonitorActivity$2();
                    }
                });
                MonitorActivity.this.startVideoReceiver();
                MonitorActivity.this.startVideoRenderer();
                MonitorActivity.this.startVideoWatchdog();
            }
        }

        /* JADX INFO: renamed from: lambda$onReceive$0$com-lynxeye-MonitorActivity$2, reason: not valid java name */
        /* synthetic */ void m169lambda$onReceive$0$comlynxeyeMonitorActivity$2() {
            MonitorActivity.this.ivVideo.setImageBitmap(null);
            MonitorActivity.this.ivVideo.setBackgroundColor(-16115190);
        }

        /* JADX INFO: renamed from: lambda$onReceive$1$com-lynxeye-MonitorActivity$2, reason: not valid java name */
        /* synthetic */ void m170lambda$onReceive$1$comlynxeyeMonitorActivity$2() {
            MonitorActivity.this.ivVideo.setBackgroundColor(-15658735);
        }
    }

    private void registerScreenReceiver() {
        this.screenReceiver = new AnonymousClass2();
        IntentFilter filter = new IntentFilter();
        filter.addAction("android.intent.action.SCREEN_OFF");
        filter.addAction("android.intent.action.SCREEN_ON");
        registerReceiver(this.screenReceiver, filter);
    }

    private void unregisterScreenReceiver() {
        try {
            BroadcastReceiver broadcastReceiver = this.screenReceiver;
            if (broadcastReceiver != null) {
                unregisterReceiver(broadcastReceiver);
            }
        } catch (Exception e) {
        }
    }

    private void registerNetworkCallback() {
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService("connectivity");
            NetworkRequest req = new NetworkRequest.Builder().addCapability(12).build();
            AnonymousClass3 anonymousClass3 = new AnonymousClass3();
            this.networkCallback = anonymousClass3;
            cm.registerNetworkCallback(req, anonymousClass3);
        } catch (Exception e) {
        }
    }

    /* JADX INFO: renamed from: com.lynxeye.MonitorActivity$3, reason: invalid class name */
    class AnonymousClass3 extends ConnectivityManager.NetworkCallback {
        AnonymousClass3() {
        }

        @Override // android.net.ConnectivityManager.NetworkCallback
        public void onAvailable(Network network) {
            if (MonitorActivity.this.running && MonitorActivity.this.videoEnabled) {
                MonitorActivity.this.videoQueue.clear();
                MonitorActivity.this.runOnUiThread(new Runnable() { // from class: com.lynxeye.MonitorActivity$3$$ExternalSyntheticLambda1
                    @Override // java.lang.Runnable
                    public final void run() {
                        this.f$0.m171lambda$onAvailable$0$comlynxeyeMonitorActivity$3();
                    }
                });
            }
        }

        /* JADX INFO: renamed from: lambda$onAvailable$0$com-lynxeye-MonitorActivity$3, reason: not valid java name */
        /* synthetic */ void m171lambda$onAvailable$0$comlynxeyeMonitorActivity$3() {
            MonitorActivity.this.setStatus("RECONNECTING...", -22016);
        }

        /* JADX INFO: renamed from: lambda$onLost$1$com-lynxeye-MonitorActivity$3, reason: not valid java name */
        /* synthetic */ void m172lambda$onLost$1$comlynxeyeMonitorActivity$3() {
            MonitorActivity.this.setStatus("NO NETWORK", -49859);
        }

        @Override // android.net.ConnectivityManager.NetworkCallback
        public void onLost(Network network) {
            MonitorActivity.this.runOnUiThread(new Runnable() { // from class: com.lynxeye.MonitorActivity$3$$ExternalSyntheticLambda0
                @Override // java.lang.Runnable
                public final void run() {
                    this.f$0.m172lambda$onLost$1$comlynxeyeMonitorActivity$3();
                }
            });
        }
    }

    private void unregisterNetworkCallback() {
        try {
            if (this.networkCallback != null) {
                ConnectivityManager cm = (ConnectivityManager) getSystemService("connectivity");
                cm.unregisterNetworkCallback(this.networkCallback);
                this.networkCallback = null;
            }
        } catch (Exception e) {
        }
    }

    /* JADX INFO: renamed from: com.lynxeye.MonitorActivity$4, reason: invalid class name */
    class AnonymousClass4 implements Runnable {
        AnonymousClass4() {
        }

        @Override // java.lang.Runnable
        public void run() {
            if (MonitorActivity.this.running && MonitorActivity.this.videoEnabled) {
                long elapsed = System.currentTimeMillis() - MonitorActivity.this.lastFrameTime;
                if (elapsed > 8000 && MonitorActivity.this.videoConnected) {
                    MonitorActivity.this.videoConnected = false;
                    MonitorActivity.this.videoQueue.clear();
                    MonitorActivity.this.runOnUiThread(new Runnable() { // from class: com.lynxeye.MonitorActivity$4$$ExternalSyntheticLambda0
                        @Override // java.lang.Runnable
                        public final void run() {
                            this.f$0.m173lambda$run$0$comlynxeyeMonitorActivity$4();
                        }
                    });
                }
                MonitorActivity.this.uiHandler.postDelayed(this, 3000L);
            }
        }

        /* JADX INFO: renamed from: lambda$run$0$com-lynxeye-MonitorActivity$4, reason: not valid java name */
        /* synthetic */ void m173lambda$run$0$comlynxeyeMonitorActivity$4() {
            MonitorActivity.this.setStatus("VIDEO TIMEOUT - RECONNECTING...", -22016);
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void startVideoWatchdog() {
        this.lastFrameTime = System.currentTimeMillis();
        AnonymousClass4 anonymousClass4 = new AnonymousClass4();
        this.videoWatchdog = anonymousClass4;
        this.uiHandler.postDelayed(anonymousClass4, 3000L);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void stopVideoWatchdog() {
        Runnable runnable = this.videoWatchdog;
        if (runnable != null) {
            this.uiHandler.removeCallbacks(runnable);
        }
    }

    @Override // com.lynxeye.AudioService.Callback
    public void onAudioConnected(final boolean connected) {
        runOnUiThread(new Runnable() { // from class: com.lynxeye.MonitorActivity$$ExternalSyntheticLambda13
            @Override // java.lang.Runnable
            public final void run() {
                this.f$0.m151lambda$onAudioConnected$0$comlynxeyeMonitorActivity(connected);
            }
        });
    }

    /* JADX INFO: renamed from: lambda$onAudioConnected$0$com-lynxeye-MonitorActivity, reason: not valid java name */
    /* synthetic */ void m151lambda$onAudioConnected$0$comlynxeyeMonitorActivity(boolean connected) {
        if (connected || this.videoConnected) {
            this.tvStatus.setText("⬤  CONNECTED");
            this.tvStatus.setTextColor(-16718218);
        } else {
            this.tvStatus.setText("⬤  LOST SIGNAL");
            this.tvStatus.setTextColor(-49859);
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void setStatus(final String text, final int color) {
        runOnUiThread(new Runnable() { // from class: com.lynxeye.MonitorActivity$$ExternalSyntheticLambda8
            @Override // java.lang.Runnable
            public final void run() {
                this.f$0.m155lambda$setStatus$1$comlynxeyeMonitorActivity(text, color);
            }
        });
    }

    /* JADX INFO: renamed from: lambda$setStatus$1$com-lynxeye-MonitorActivity, reason: not valid java name */
    /* synthetic */ void m155lambda$setStatus$1$comlynxeyeMonitorActivity(String text, int color) {
        this.tvStatus.setText("⬤  " + text);
        this.tvStatus.setTextColor(color);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void sendInitialSettings() {
        new Thread(new Runnable() { // from class: com.lynxeye.MonitorActivity$$ExternalSyntheticLambda11
            @Override // java.lang.Runnable
            public final void run() {
                this.f$0.m154lambda$sendInitialSettings$2$comlynxeyeMonitorActivity();
            }
        }).start();
    }

    /* JADX INFO: renamed from: lambda$sendInitialSettings$2$com-lynxeye-MonitorActivity, reason: not valid java name */
    /* synthetic */ void m154lambda$sendInitialSettings$2$comlynxeyeMonitorActivity() {
        try {
            Thread.sleep(1000L);
            Socket cmd = new Socket();
            cmd.connect(new InetSocketAddress(this.deviceIp, PORT_COMMAND), 2000);
            StringBuilder sb = new StringBuilder();
            if (this.audioMode == 1) {
                sb.append("AUDIO_STEREO\n");
            } else {
                sb.append("AUDIO_MONO\n");
            }
            sb.append("SR_").append(this.sampleRate).append("\n");
            sb.append(this.videoEnabled ? "VIDEO_ON\n" : "VIDEO_OFF\n");
            cmd.getOutputStream().write(sb.toString().getBytes());
            cmd.getOutputStream().flush();
            cmd.close();
        } catch (Exception e) {
        }
    }

    private void sendCommand(final String cmd) {
        new Thread(new Runnable() { // from class: com.lynxeye.MonitorActivity$$ExternalSyntheticLambda12
            @Override // java.lang.Runnable
            public final void run() {
                this.f$0.m153lambda$sendCommand$4$comlynxeyeMonitorActivity(cmd);
            }
        }).start();
    }

    /* JADX INFO: renamed from: lambda$sendCommand$4$com-lynxeye-MonitorActivity, reason: not valid java name */
    /* synthetic */ void m153lambda$sendCommand$4$comlynxeyeMonitorActivity(final String cmd) {
        try {
            Socket s = new Socket();
            s.connect(new InetSocketAddress(this.deviceIp, PORT_COMMAND), 2000);
            s.getOutputStream().write((cmd + "\n").getBytes());
            s.getOutputStream().flush();
            s.close();
        } catch (Exception e) {
            runOnUiThread(new Runnable() { // from class: com.lynxeye.MonitorActivity$$ExternalSyntheticLambda9
                @Override // java.lang.Runnable
                public final void run() {
                    this.f$0.m152lambda$sendCommand$3$comlynxeyeMonitorActivity(cmd);
                }
            });
        }
    }

    /* JADX INFO: renamed from: lambda$sendCommand$3$com-lynxeye-MonitorActivity, reason: not valid java name */
    /* synthetic */ void m152lambda$sendCommand$3$comlynxeyeMonitorActivity(String cmd) {
        Toast.makeText(this, "Error: " + cmd, 0).show();
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void startVideoReceiver() {
        new Thread(new Runnable() { // from class: com.lynxeye.MonitorActivity$$ExternalSyntheticLambda17
            @Override // java.lang.Runnable
            public final void run() {
                this.f$0.m165lambda$startVideoReceiver$6$comlynxeyeMonitorActivity();
            }
        }, "VideoReceiver").start();
    }

    /* JADX INFO: renamed from: lambda$startVideoReceiver$6$com-lynxeye-MonitorActivity, reason: not valid java name */
    /* synthetic */ void m165lambda$startVideoReceiver$6$comlynxeyeMonitorActivity() {
        while (this.running) {
            Socket socket = null;
            try {
                try {
                    socket = new Socket();
                    socket.connect(new InetSocketAddress(this.deviceIp, PORT_VIDEO), 5000);
                    socket.setTcpNoDelay(true);
                    socket.setSoTimeout(VIDEO_TIMEOUT_MS);
                    socket.setKeepAlive(true);
                    DataInputStream in = new DataInputStream(socket.getInputStream());
                    this.videoConnected = true;
                    this.lastFrameTime = System.currentTimeMillis();
                    runOnUiThread(new Runnable() { // from class: com.lynxeye.MonitorActivity$$ExternalSyntheticLambda14
                        @Override // java.lang.Runnable
                        public final void run() {
                            this.f$0.m164lambda$startVideoReceiver$5$comlynxeyeMonitorActivity();
                        }
                    });
                    while (this.running && !socket.isClosed()) {
                        int len = in.readInt();
                        if (len > 0 && len <= 3000000) {
                            byte[] frame = new byte[len];
                            int total = 0;
                            while (total < len) {
                                int r = in.read(frame, total, len - total);
                                if (r < 0) {
                                    break;
                                } else {
                                    total += r;
                                }
                            }
                            this.lastFrameTime = System.currentTimeMillis();
                            if (!this.videoQueue.offer(frame)) {
                                this.videoQueue.poll();
                                this.videoQueue.offer(frame);
                            }
                        }
                    }
                    this.videoConnected = false;
                    this.videoQueue.clear();
                    try {
                        socket.close();
                    } catch (Exception e) {
                    }
                    if (this.running) {
                        try {
                            Thread.sleep(2000L);
                        } catch (InterruptedException e2) {
                            return;
                        }
                    }
                } catch (Exception e3) {
                    this.videoConnected = false;
                    this.videoQueue.clear();
                    if (socket != null) {
                        try {
                            socket.close();
                        } catch (Exception e4) {
                        }
                    }
                    if (this.running) {
                        Thread.sleep(2000L);
                    }
                }
            } finally {
            }
        }
    }

    /* JADX INFO: renamed from: lambda$startVideoReceiver$5$com-lynxeye-MonitorActivity, reason: not valid java name */
    /* synthetic */ void m164lambda$startVideoReceiver$5$comlynxeyeMonitorActivity() {
        this.tvStatus.setText("⬤  CONNECTED");
        this.tvStatus.setTextColor(-16718218);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void startVideoRenderer() {
        new Thread(new Runnable() { // from class: com.lynxeye.MonitorActivity$$ExternalSyntheticLambda0
            @Override // java.lang.Runnable
            public final void run() {
                this.f$0.m168lambda$startVideoRenderer$9$comlynxeyeMonitorActivity();
            }
        }, "VideoRenderer").start();
    }

    /* JADX INFO: renamed from: lambda$startVideoRenderer$9$com-lynxeye-MonitorActivity, reason: not valid java name */
    /* synthetic */ void m168lambda$startVideoRenderer$9$comlynxeyeMonitorActivity() {
        while (this.running) {
            try {
                byte[] frame = this.videoQueue.poll(500L, TimeUnit.MILLISECONDS);
                if (frame != null) {
                    BitmapFactory.Options opts = new BitmapFactory.Options();
                    opts.inPreferredConfig = Bitmap.Config.RGB_565;
                    Bitmap bmp = BitmapFactory.decodeByteArray(frame, 0, frame.length, opts);
                    if (bmp != null) {
                        if (this.nightMode) {
                            bmp = applyNightFilter(bmp);
                        }
                        this.lastFrame = bmp;
                        final Bitmap display = bmp;
                        runOnUiThread(new Runnable() { // from class: com.lynxeye.MonitorActivity$$ExternalSyntheticLambda15
                            @Override // java.lang.Runnable
                            public final void run() {
                                this.f$0.m166lambda$startVideoRenderer$7$comlynxeyeMonitorActivity(display);
                            }
                        });
                    }
                } else {
                    final Bitmap keep = this.lastFrame;
                    if (keep != null) {
                        runOnUiThread(new Runnable() { // from class: com.lynxeye.MonitorActivity$$ExternalSyntheticLambda16
                            @Override // java.lang.Runnable
                            public final void run() {
                                this.f$0.m167lambda$startVideoRenderer$8$comlynxeyeMonitorActivity(keep);
                            }
                        });
                    }
                }
            } catch (InterruptedException e) {
                return;
            }
        }
    }

    /* JADX INFO: renamed from: lambda$startVideoRenderer$7$com-lynxeye-MonitorActivity, reason: not valid java name */
    /* synthetic */ void m166lambda$startVideoRenderer$7$comlynxeyeMonitorActivity(Bitmap display) {
        this.ivVideo.setImageBitmap(display);
    }

    /* JADX INFO: renamed from: lambda$startVideoRenderer$8$com-lynxeye-MonitorActivity, reason: not valid java name */
    /* synthetic */ void m167lambda$startVideoRenderer$8$comlynxeyeMonitorActivity(Bitmap keep) {
        this.ivVideo.setImageBitmap(keep);
    }

    private Bitmap applyNightFilter(Bitmap src) {
        if (src == null) {
            return null;
        }
        Bitmap out = src.copy(Bitmap.Config.RGB_565, true);
        Canvas canvas = new Canvas(out);
        float translate = ((-(1.4f - 1.0f)) * 128.0f) + 80.0f;
        ColorMatrix cm = new ColorMatrix(new float[]{1.4f, 0.0f, 0.0f, 0.0f, translate, 0.0f, 1.4f, 0.0f, 0.0f, translate, 0.0f, 0.0f, 1.4f, 0.0f, translate, 0.0f, 0.0f, 0.0f, 1.0f, 0.0f});
        Paint paint = new Paint();
        paint.setColorFilter(new ColorMatrixColorFilter(cm));
        canvas.drawBitmap(src, 0.0f, 0.0f, paint);
        return out;
    }

    private void setupEqualizer() {
        int[] seekIds = {R.id.seekBand0, R.id.seekBand1, R.id.seekBand2, R.id.seekBand3, R.id.seekBand4, R.id.seekBand5, R.id.seekBand6, R.id.seekBand7, R.id.seekBand8, R.id.seekBand9};
        int[] gainIds = {R.id.tvGain0, R.id.tvGain1, R.id.tvGain2, R.id.tvGain3, R.id.tvGain4, R.id.tvGain5, R.id.tvGain6, R.id.tvGain7, R.id.tvGain8, R.id.tvGain9};
        int[] labelIds = {R.id.tvBand0, R.id.tvBand1, R.id.tvBand2, R.id.tvBand3, R.id.tvBand4, R.id.tvBand5, R.id.tvBand6, R.id.tvBand7, R.id.tvBand8, R.id.tvBand9};
        for (int i = 0; i < 10; i++) {
            ((TextView) findViewById(labelIds[i])).setText(DspEqualizer.LABELS[i]);
            SeekBar sb = (SeekBar) findViewById(seekIds[i]);
            sb.setMax(240);
            sb.setProgress(120);
            final int band = i;
            final TextView tvGain = (TextView) findViewById(gainIds[i]);
            sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() { // from class: com.lynxeye.MonitorActivity.5
                @Override // android.widget.SeekBar.OnSeekBarChangeListener
                public void onProgressChanged(SeekBar s, int p, boolean u) {
                    float db = (p - 120) / 10.0f;
                    if (MonitorActivity.this.audioService != null) {
                        MonitorActivity.this.audioService.setGain(band, db);
                    }
                    tvGain.setText(String.format(Locale.getDefault(), "%+.0fdB", Float.valueOf(db)));
                    MonitorActivity monitorActivity = MonitorActivity.this;
                    AppSettings.setEqGain(monitorActivity, monitorActivity.deviceIp, band, db);
                }

                @Override // android.widget.SeekBar.OnSeekBarChangeListener
                public void onStartTrackingTouch(SeekBar s) {
                }

                @Override // android.widget.SeekBar.OnSeekBarChangeListener
                public void onStopTrackingTouch(SeekBar s) {
                }
            });
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void restoreEqState() {
        if (this.audioService == null) {
            return;
        }
        int[] seekIds = {R.id.seekBand0, R.id.seekBand1, R.id.seekBand2, R.id.seekBand3, R.id.seekBand4, R.id.seekBand5, R.id.seekBand6, R.id.seekBand7, R.id.seekBand8, R.id.seekBand9};
        int[] gainIds = {R.id.tvGain0, R.id.tvGain1, R.id.tvGain2, R.id.tvGain3, R.id.tvGain4, R.id.tvGain5, R.id.tvGain6, R.id.tvGain7, R.id.tvGain8, R.id.tvGain9};
        for (int i = 0; i < 10; i++) {
            float db = AppSettings.getEqGain(this, this.deviceIp, i);
            this.audioService.setGain(i, db);
            ((SeekBar) findViewById(seekIds[i])).setProgress((int) ((10.0f * db) + 120.0f));
            ((TextView) findViewById(gainIds[i])).setText(String.format(Locale.getDefault(), "%+.0fdB", Float.valueOf(db)));
        }
    }

    private void setupButtons() {
        this.btnRecord.setOnClickListener(new View.OnClickListener() { // from class: com.lynxeye.MonitorActivity$$ExternalSyntheticLambda1
            @Override // android.view.View.OnClickListener
            public final void onClick(View view) {
                this.f$0.m156lambda$setupButtons$10$comlynxeyeMonitorActivity(view);
            }
        });
        this.btnSwitchCam.setOnClickListener(new View.OnClickListener() { // from class: com.lynxeye.MonitorActivity$$ExternalSyntheticLambda2
            @Override // android.view.View.OnClickListener
            public final void onClick(View view) {
                this.f$0.m157lambda$setupButtons$11$comlynxeyeMonitorActivity(view);
            }
        });
        this.btnVideoToggle.setOnClickListener(new View.OnClickListener() { // from class: com.lynxeye.MonitorActivity$$ExternalSyntheticLambda3
            @Override // android.view.View.OnClickListener
            public final void onClick(View view) {
                this.f$0.m158lambda$setupButtons$12$comlynxeyeMonitorActivity(view);
            }
        });
        this.btnVideoToggle.setColorFilter(this.videoEnabled ? -16718218 : -49859);
        this.btnAudioToggle.setOnClickListener(new View.OnClickListener() { // from class: com.lynxeye.MonitorActivity$$ExternalSyntheticLambda4
            @Override // android.view.View.OnClickListener
            public final void onClick(View view) {
                this.f$0.m159lambda$setupButtons$13$comlynxeyeMonitorActivity(view);
            }
        });
        this.btnAudioToggle.setColorFilter(-16718218);
        this.btnScreenshot.setOnClickListener(new View.OnClickListener() { // from class: com.lynxeye.MonitorActivity$$ExternalSyntheticLambda5
            @Override // android.view.View.OnClickListener
            public final void onClick(View view) {
                this.f$0.m160lambda$setupButtons$14$comlynxeyeMonitorActivity(view);
            }
        });
        this.btnNight.setOnClickListener(new View.OnClickListener() { // from class: com.lynxeye.MonitorActivity$$ExternalSyntheticLambda6
            @Override // android.view.View.OnClickListener
            public final void onClick(View view) {
                this.f$0.m162lambda$setupButtons$16$comlynxeyeMonitorActivity(view);
            }
        });
        this.btnNight.setColorFilter(-16718218);
        this.btnEq.setOnClickListener(new View.OnClickListener() { // from class: com.lynxeye.MonitorActivity$$ExternalSyntheticLambda7
            @Override // android.view.View.OnClickListener
            public final void onClick(View view) {
                this.f$0.m163lambda$setupButtons$17$comlynxeyeMonitorActivity(view);
            }
        });
    }

    /* JADX INFO: renamed from: lambda$setupButtons$10$com-lynxeye-MonitorActivity, reason: not valid java name */
    /* synthetic */ void m156lambda$setupButtons$10$comlynxeyeMonitorActivity(View v) {
        AudioService audioService = this.audioService;
        if (audioService == null) {
            return;
        }
        if (this.isRecording) {
            File saved = audioService.stopRecording();
            this.isRecording = false;
            this.uiHandler.removeCallbacks(this.recTimeUpdater);
            this.tvRecTime.setVisibility(8);
            this.btnRecord.setImageResource(android.R.drawable.ic_btn_speak_now);
            if (saved != null) {
                Toast.makeText(this, "✅ " + saved.getName(), 1).show();
                return;
            }
            return;
        }
        File dir = new File(getExternalFilesDir(null), "Recordings");
        if (!dir.exists()) {
            dir.mkdirs();
        }
        if (this.audioService.startRecording(dir)) {
            this.isRecording = true;
            this.recordingStart = System.currentTimeMillis();
            this.btnRecord.setImageResource(android.R.drawable.ic_media_pause);
            this.tvRecTime.setVisibility(0);
            Runnable runnable = new Runnable() { // from class: com.lynxeye.MonitorActivity.6
                @Override // java.lang.Runnable
                public void run() {
                    long s = (System.currentTimeMillis() - MonitorActivity.this.recordingStart) / 1000;
                    MonitorActivity.this.tvRecTime.setText(String.format(Locale.getDefault(), "⏺ %02d:%02d", Long.valueOf(s / 60), Long.valueOf(s % 60)));
                    if (MonitorActivity.this.isRecording) {
                        MonitorActivity.this.uiHandler.postDelayed(this, 1000L);
                    }
                }
            };
            this.recTimeUpdater = runnable;
            this.uiHandler.post(runnable);
            Toast.makeText(this, "⏺ Grabando " + this.sampleRate + "Hz", 0).show();
        }
    }

    /* JADX INFO: renamed from: lambda$setupButtons$11$com-lynxeye-MonitorActivity, reason: not valid java name */
    /* synthetic */ void m157lambda$setupButtons$11$comlynxeyeMonitorActivity(View v) {
        sendCommand("SWITCH_CAM");
    }

    /* JADX INFO: renamed from: lambda$setupButtons$12$com-lynxeye-MonitorActivity, reason: not valid java name */
    /* synthetic */ void m158lambda$setupButtons$12$comlynxeyeMonitorActivity(View v) {
        boolean cur = AppSettings.isVideoEnabled(this);
        boolean nw = !cur;
        AppSettings.setVideoEnabled(this, nw);
        this.videoEnabled = nw;
        sendCommand(nw ? "VIDEO_ON" : "VIDEO_OFF");
        sendCommand(nw ? "START_CAMERA" : "STOP_CAMERA");
        this.btnVideoToggle.setColorFilter(nw ? -16718218 : -49859);
        if (!nw) {
            this.videoConnected = false;
            this.videoQueue.clear();
            this.lastFrame = null;
            this.ivVideo.setImageBitmap(null);
            this.ivVideo.setBackgroundColor(-16115190);
            stopVideoWatchdog();
            return;
        }
        this.ivVideo.setBackgroundColor(-15658735);
        startVideoReceiver();
        startVideoRenderer();
        startVideoWatchdog();
    }

    /* JADX INFO: renamed from: lambda$setupButtons$13$com-lynxeye-MonitorActivity, reason: not valid java name */
    /* synthetic */ void m159lambda$setupButtons$13$comlynxeyeMonitorActivity(View v) {
        AudioService audioService = this.audioService;
        if (audioService == null) {
            return;
        }
        boolean nw = !audioService.audioEnabled;
        this.audioService.setAudioEnabled(nw);
        this.btnAudioToggle.setColorFilter(nw ? -16718218 : -49859);
    }

    /* JADX INFO: renamed from: lambda$setupButtons$14$com-lynxeye-MonitorActivity, reason: not valid java name */
    /* synthetic */ void m160lambda$setupButtons$14$comlynxeyeMonitorActivity(View v) {
        if (this.lastFrame == null) {
            Toast.makeText(this, "No hay video activo", 0).show();
            return;
        }
        try {
            File dir = new File(getExternalFilesDir(null), "Screenshots");
            if (!dir.exists()) {
                dir.mkdirs();
            }
            String fn = "LynxEye_" + new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.getDefault()).format(new Date()) + ".jpg";
            File file = new File(dir, fn);
            FileOutputStream fos = new FileOutputStream(file);
            this.lastFrame.compress(Bitmap.CompressFormat.JPEG, 95, fos);
            fos.flush();
            fos.close();
            Toast.makeText(this, "📸 " + fn, 1).show();
        } catch (Exception e) {
            Toast.makeText(this, "Error: " + e.getMessage(), 1).show();
        }
    }

    /* JADX INFO: renamed from: lambda$setupButtons$16$com-lynxeye-MonitorActivity, reason: not valid java name */
    /* synthetic */ void m162lambda$setupButtons$16$comlynxeyeMonitorActivity(View v) {
        this.nightMode = !this.nightMode;
        this.btnNight.setColorFilter(this.nightMode ? InputDeviceCompat.SOURCE_ANY : -16718218);
        this.lastFrame = null;
        runOnUiThread(new Runnable() { // from class: com.lynxeye.MonitorActivity$$ExternalSyntheticLambda10
            @Override // java.lang.Runnable
            public final void run() {
                this.f$0.m161lambda$setupButtons$15$comlynxeyeMonitorActivity();
            }
        });
        Toast.makeText(this, this.nightMode ? "Modo noche ON" : "Modo noche OFF", 0).show();
    }

    /* JADX INFO: renamed from: lambda$setupButtons$15$com-lynxeye-MonitorActivity, reason: not valid java name */
    /* synthetic */ void m161lambda$setupButtons$15$comlynxeyeMonitorActivity() {
        this.ivVideo.setImageBitmap(null);
    }

    /* JADX INFO: renamed from: lambda$setupButtons$17$com-lynxeye-MonitorActivity, reason: not valid java name */
    /* synthetic */ void m163lambda$setupButtons$17$comlynxeyeMonitorActivity(View v) {
        View view = this.layoutEq;
        view.setVisibility(view.getVisibility() == 0 ? 8 : 0);
    }
}
