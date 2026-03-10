package com.lynxeye;

import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ListAdapter;
import android.widget.ListView;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import com.lynxeye.DeviceStorage;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/* JADX INFO: loaded from: classes3.dex */
public class MainActivity extends AppCompatActivity {
    private DeviceAdapter adapter;
    private List<DeviceStorage.Device> devices;
    private HackerVisualizerView hackerVisualizer;
    private Runnable pingRunnable;
    private Handler pingHandler = new Handler();
    private ConcurrentHashMap<String, Boolean> statusMap = new ConcurrentHashMap<>();

    @Override // androidx.fragment.app.FragmentActivity, androidx.activity.ComponentActivity, androidx.core.app.ComponentActivity, android.app.Activity
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        ListView listView = (ListView) findViewById(R.id.listDevices);
        ImageButton btnAdd = (ImageButton) findViewById(R.id.btnAdd);
        ImageButton btnSettings = (ImageButton) findViewById(R.id.btnSettings);
        ImageButton btnRecordings = (ImageButton) findViewById(R.id.btnRecordings);
        this.hackerVisualizer = (HackerVisualizerView) findViewById(R.id.hackerVisualizer);
        this.devices = DeviceStorage.getDevices(this);
        DeviceAdapter deviceAdapter = new DeviceAdapter();
        this.adapter = deviceAdapter;
        listView.setAdapter((ListAdapter) deviceAdapter);
        listView.setOnItemClickListener(new AdapterView.OnItemClickListener() { // from class: com.lynxeye.MainActivity$$ExternalSyntheticLambda0
            @Override // android.widget.AdapterView.OnItemClickListener
            public final void onItemClick(AdapterView adapterView, View view, int i, long j) {
                this.f$0.m142lambda$onCreate$0$comlynxeyeMainActivity(adapterView, view, i, j);
            }
        });
        listView.setOnItemLongClickListener(new AdapterView.OnItemLongClickListener() { // from class: com.lynxeye.MainActivity$$ExternalSyntheticLambda1
            @Override // android.widget.AdapterView.OnItemLongClickListener
            public final boolean onItemLongClick(AdapterView adapterView, View view, int i, long j) {
                return this.f$0.m143lambda$onCreate$1$comlynxeyeMainActivity(adapterView, view, i, j);
            }
        });
        btnAdd.setOnClickListener(new View.OnClickListener() { // from class: com.lynxeye.MainActivity$$ExternalSyntheticLambda2
            @Override // android.view.View.OnClickListener
            public final void onClick(View view) {
                this.f$0.m144lambda$onCreate$2$comlynxeyeMainActivity(view);
            }
        });
        btnSettings.setOnClickListener(new View.OnClickListener() { // from class: com.lynxeye.MainActivity$$ExternalSyntheticLambda3
            @Override // android.view.View.OnClickListener
            public final void onClick(View view) {
                this.f$0.m145lambda$onCreate$3$comlynxeyeMainActivity(view);
            }
        });
        btnRecordings.setOnClickListener(new View.OnClickListener() { // from class: com.lynxeye.MainActivity$$ExternalSyntheticLambda4
            @Override // android.view.View.OnClickListener
            public final void onClick(View view) {
                this.f$0.m146lambda$onCreate$4$comlynxeyeMainActivity(view);
            }
        });
    }

    /* JADX INFO: renamed from: lambda$onCreate$0$com-lynxeye-MainActivity, reason: not valid java name */
    /* synthetic */ void m142lambda$onCreate$0$comlynxeyeMainActivity(AdapterView parent, View view, int position, long id) {
        openMonitor(this.devices.get(position));
    }

    /* JADX INFO: renamed from: lambda$onCreate$1$com-lynxeye-MainActivity, reason: not valid java name */
    /* synthetic */ boolean m143lambda$onCreate$1$comlynxeyeMainActivity(AdapterView parent, View view, int position, long id) {
        showDeleteDialog(position);
        return true;
    }

    /* JADX INFO: renamed from: lambda$onCreate$2$com-lynxeye-MainActivity, reason: not valid java name */
    /* synthetic */ void m144lambda$onCreate$2$comlynxeyeMainActivity(View v) {
        showAddDialog();
    }

    /* JADX INFO: renamed from: lambda$onCreate$3$com-lynxeye-MainActivity, reason: not valid java name */
    /* synthetic */ void m145lambda$onCreate$3$comlynxeyeMainActivity(View v) {
        startActivity(new Intent(this, (Class<?>) SettingsActivity.class));
    }

    /* JADX INFO: renamed from: lambda$onCreate$4$com-lynxeye-MainActivity, reason: not valid java name */
    /* synthetic */ void m146lambda$onCreate$4$comlynxeyeMainActivity(View v) {
        startActivity(new Intent(this, (Class<?>) RecordingsActivity.class));
    }

    @Override // androidx.fragment.app.FragmentActivity, android.app.Activity
    protected void onResume() {
        super.onResume();
        this.devices.clear();
        this.devices.addAll(DeviceStorage.getDevices(this));
        this.adapter.notifyDataSetChanged();
        startPinging();
        if (AppSettings.isVisualizerEnabled(this)) {
            this.hackerVisualizer.setVisibility(0);
            this.hackerVisualizer.start();
        } else {
            this.hackerVisualizer.stop();
            this.hackerVisualizer.setVisibility(8);
        }
    }

    @Override // androidx.fragment.app.FragmentActivity, android.app.Activity
    protected void onPause() {
        super.onPause();
        stopPinging();
        this.hackerVisualizer.stop();
    }

    private void startPinging() {
        Runnable runnable = new Runnable() { // from class: com.lynxeye.MainActivity.1
            @Override // java.lang.Runnable
            public void run() {
                MainActivity.this.pingAllDevices();
                MainActivity.this.pingHandler.postDelayed(this, 5000L);
            }
        };
        this.pingRunnable = runnable;
        this.pingHandler.post(runnable);
    }

    private void stopPinging() {
        Runnable runnable = this.pingRunnable;
        if (runnable != null) {
            this.pingHandler.removeCallbacks(runnable);
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void pingAllDevices() {
        for (DeviceStorage.Device device : this.devices) {
            final String ip = device.ip;
            new Thread(new Runnable() { // from class: com.lynxeye.MainActivity$$ExternalSyntheticLambda7
                @Override // java.lang.Runnable
                public final void run() {
                    this.f$0.m148lambda$pingAllDevices$6$comlynxeyeMainActivity(ip);
                }
            }).start();
        }
    }

    /* JADX INFO: renamed from: lambda$pingAllDevices$6$com-lynxeye-MainActivity, reason: not valid java name */
    /* synthetic */ void m148lambda$pingAllDevices$6$comlynxeyeMainActivity(String ip) {
        boolean online = isOnline(ip);
        this.statusMap.put(ip, Boolean.valueOf(online));
        runOnUiThread(new Runnable() { // from class: com.lynxeye.MainActivity$$ExternalSyntheticLambda8
            @Override // java.lang.Runnable
            public final void run() {
                this.f$0.m147lambda$pingAllDevices$5$comlynxeyeMainActivity();
            }
        });
    }

    /* JADX INFO: renamed from: lambda$pingAllDevices$5$com-lynxeye-MainActivity, reason: not valid java name */
    /* synthetic */ void m147lambda$pingAllDevices$5$comlynxeyeMainActivity() {
        this.adapter.notifyDataSetChanged();
    }

    private boolean isOnline(String ip) {
        try {
            Socket s = new Socket();
            s.connect(new InetSocketAddress(ip, 9999), 1500);
            s.close();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private void showAddDialog() {
        View dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_add_device, (ViewGroup) null);
        final EditText etName = (EditText) dialogView.findViewById(R.id.etName);
        final EditText etIp = (EditText) dialogView.findViewById(R.id.etIp);
        final EditText etCode = (EditText) dialogView.findViewById(R.id.etCode);
        new AlertDialog.Builder(this, R.style.DialogDark).setTitle("ADD DEVICE").setView(dialogView).setPositiveButton("CONNECT", new DialogInterface.OnClickListener() { // from class: com.lynxeye.MainActivity$$ExternalSyntheticLambda6
            @Override // android.content.DialogInterface.OnClickListener
            public final void onClick(DialogInterface dialogInterface, int i) {
                this.f$0.m149lambda$showAddDialog$7$comlynxeyeMainActivity(etName, etIp, etCode, dialogInterface, i);
            }
        }).setNegativeButton("CANCEL", (DialogInterface.OnClickListener) null).show();
    }

    /* JADX INFO: renamed from: lambda$showAddDialog$7$com-lynxeye-MainActivity, reason: not valid java name */
    /* synthetic */ void m149lambda$showAddDialog$7$comlynxeyeMainActivity(EditText etName, EditText etIp, EditText etCode, DialogInterface d, int w) {
        String name = etName.getText().toString().trim();
        String ip = etIp.getText().toString().trim();
        String code = etCode.getText().toString().trim().toUpperCase();
        if (!name.isEmpty() && !ip.isEmpty()) {
            DeviceStorage.Device device = new DeviceStorage.Device(name, ip, code);
            DeviceStorage.saveDevice(this, device);
            openMonitor(device);
        }
    }

    private void showDeleteDialog(final int position) {
        new AlertDialog.Builder(this, R.style.DialogDark).setTitle("DELETE DEVICE").setMessage("Remove " + this.devices.get(position).name + "?").setPositiveButton("DELETE", new DialogInterface.OnClickListener() { // from class: com.lynxeye.MainActivity$$ExternalSyntheticLambda5
            @Override // android.content.DialogInterface.OnClickListener
            public final void onClick(DialogInterface dialogInterface, int i) {
                this.f$0.m150lambda$showDeleteDialog$8$comlynxeyeMainActivity(position, dialogInterface, i);
            }
        }).setNegativeButton("CANCEL", (DialogInterface.OnClickListener) null).show();
    }

    /* JADX INFO: renamed from: lambda$showDeleteDialog$8$com-lynxeye-MainActivity, reason: not valid java name */
    /* synthetic */ void m150lambda$showDeleteDialog$8$comlynxeyeMainActivity(int position, DialogInterface d, int w) {
        DeviceStorage.deleteDevice(this, this.devices.get(position).code, this.devices.get(position).ip);
        this.devices.remove(position);
        this.adapter.notifyDataSetChanged();
    }

    private void openMonitor(DeviceStorage.Device device) {
        Intent intent = new Intent(this, (Class<?>) MonitorActivity.class);
        intent.putExtra("name", device.name);
        intent.putExtra("ip", device.ip);
        intent.putExtra("code", device.code);
        startActivity(intent);
    }

    class DeviceAdapter extends BaseAdapter {
        DeviceAdapter() {
        }

        @Override // android.widget.Adapter
        public int getCount() {
            return MainActivity.this.devices.size();
        }

        @Override // android.widget.Adapter
        public Object getItem(int pos) {
            return MainActivity.this.devices.get(pos);
        }

        @Override // android.widget.Adapter
        public long getItemId(int pos) {
            return pos;
        }

        @Override // android.widget.Adapter
        public View getView(int position, View convertView, ViewGroup parent) {
            if (convertView == null) {
                convertView = LayoutInflater.from(MainActivity.this).inflate(R.layout.item_device, parent, false);
            }
            DeviceStorage.Device d = (DeviceStorage.Device) MainActivity.this.devices.get(position);
            ((TextView) convertView.findViewById(R.id.tvDeviceName)).setText(d.name);
            ((TextView) convertView.findViewById(R.id.tvDeviceIp)).setText(d.ip + "  #" + d.code);
            TextView dot = (TextView) convertView.findViewById(R.id.tvStatusDot);
            Boolean online = (Boolean) MainActivity.this.statusMap.get(d.ip);
            if (online == null) {
                dot.setTextColor(-12303292);
            } else if (online.booleanValue()) {
                dot.setTextColor(-16718218);
            } else {
                dot.setTextColor(-49859);
            }
            return convertView;
        }
    }
}
