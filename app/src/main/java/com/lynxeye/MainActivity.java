package com.lynxeye;

import android.app.AlertDialog;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ListView;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

public class MainActivity extends AppCompatActivity {

    private List<DeviceStorage.Device> devices;
    private DeviceAdapter adapter;
    private Handler pingHandler = new Handler();
    private Runnable pingRunnable;

    // Cache: ip -> true/false
    private ConcurrentHashMap<String, Boolean> statusMap = new ConcurrentHashMap<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        ListView listView         = findViewById(R.id.listDevices);
        ImageButton btnAdd        = findViewById(R.id.btnAdd);
        ImageButton btnSettings   = findViewById(R.id.btnSettings);
        ImageButton btnRecordings = findViewById(R.id.btnRecordings);

        devices = DeviceStorage.getDevices(this);
        adapter = new DeviceAdapter();
        listView.setAdapter(adapter);

        listView.setOnItemClickListener((parent, view, position, id) ->
                openMonitor(devices.get(position)));

        listView.setOnItemLongClickListener((parent, view, position, id) -> {
            showDeleteDialog(position);
            return true;
        });

        btnAdd.setOnClickListener(v -> showAddDialog());
        btnSettings.setOnClickListener(v -> startActivity(new Intent(this, SettingsActivity.class)));
        btnRecordings.setOnClickListener(v -> startActivity(new Intent(this, RecordingsActivity.class)));
    }

    @Override
    protected void onResume() {
        super.onResume();
        devices.clear();
        devices.addAll(DeviceStorage.getDevices(this));
        adapter.notifyDataSetChanged();
        startPinging();
    }

    @Override
    protected void onPause() {
        super.onPause();
        stopPinging();
    }

    private void startPinging() {
        pingRunnable = new Runnable() {
            @Override public void run() {
                pingAllDevices();
                pingHandler.postDelayed(this, 5000); // ping every 5s
            }
        };
        pingHandler.post(pingRunnable);
    }

    private void stopPinging() {
        if (pingRunnable != null) pingHandler.removeCallbacks(pingRunnable);
    }

    private void pingAllDevices() {
        for (DeviceStorage.Device device : devices) {
            final String ip = device.ip;
            new Thread(() -> {
                boolean online = isOnline(ip);
                statusMap.put(ip, online);
                runOnUiThread(() -> adapter.notifyDataSetChanged());
            }).start();
        }
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
        View dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_add_device, null);
        EditText etName = dialogView.findViewById(R.id.etName);
        EditText etIp   = dialogView.findViewById(R.id.etIp);
        EditText etCode = dialogView.findViewById(R.id.etCode);

        new AlertDialog.Builder(this, R.style.DialogDark)
            .setTitle("ADD DEVICE")
            .setView(dialogView)
            .setPositiveButton("CONNECT", (d, w) -> {
                String name = etName.getText().toString().trim();
                String ip   = etIp.getText().toString().trim();
                String code = etCode.getText().toString().trim().toUpperCase();
                if (!name.isEmpty() && !ip.isEmpty()) {
                    if (name.isEmpty()) name = "Cat Monitor";
                    DeviceStorage.Device device = new DeviceStorage.Device(name, ip, code);
                    DeviceStorage.saveDevice(this, device);
                    openMonitor(device);
                }
            })
            .setNegativeButton("CANCEL", null)
            .show();
    }

    private void showDeleteDialog(int position) {
        new AlertDialog.Builder(this, R.style.DialogDark)
            .setTitle("DELETE DEVICE")
            .setMessage("Remove " + devices.get(position).name + "?")
            .setPositiveButton("DELETE", (d, w) -> {
                DeviceStorage.deleteDevice(this, devices.get(position).code);
                devices.remove(position);
                adapter.notifyDataSetChanged();
            })
            .setNegativeButton("CANCEL", null)
            .show();
    }

    private void openMonitor(DeviceStorage.Device device) {
        Intent intent = new Intent(this, MonitorActivity.class);
        intent.putExtra("name", device.name);
        intent.putExtra("ip", device.ip);
        intent.putExtra("code", device.code);
        startActivity(intent);
    }

    class DeviceAdapter extends BaseAdapter {
        @Override public int getCount() { return devices.size(); }
        @Override public Object getItem(int pos) { return devices.get(pos); }
        @Override public long getItemId(int pos) { return pos; }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            if (convertView == null)
                convertView = LayoutInflater.from(MainActivity.this)
                        .inflate(R.layout.item_device, parent, false);

            DeviceStorage.Device d = devices.get(position);
            ((TextView) convertView.findViewById(R.id.tvDeviceName)).setText(d.name);
            ((TextView) convertView.findViewById(R.id.tvDeviceIp)).setText(d.ip + "  #" + d.code);

            // Status dot
            TextView dot = convertView.findViewById(R.id.tvStatusDot);
            Boolean online = statusMap.get(d.ip);
            if (online == null) {
                dot.setTextColor(0xFF444444); // grey = checking
            } else if (online) {
                dot.setTextColor(0xFF00E676); // green = online
            } else {
                dot.setTextColor(0xFFFF3D3D); // red = offline
            }

            return convertView;
        }
    }
}
