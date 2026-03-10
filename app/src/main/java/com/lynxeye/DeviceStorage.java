package com.lynxeye;

import android.content.Context;
import com.lynxeye.DeviceStorage;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import org.json.JSONArray;
import org.json.JSONObject;

/* JADX INFO: loaded from: classes3.dex */
public class DeviceStorage {
    private static final String KEY_DEVICES = "devices";
    private static final String PREFS = "lynxeye_prefs";

    public static class Device {
        public String code;
        public String ip;
        public String name;

        public Device(String name, String ip, String code) {
            this.name = name;
            this.ip = ip;
            this.code = code;
        }
    }

    public static List<Device> getDevices(Context ctx) {
        List<Device> list = new ArrayList<>();
        try {
            String json = ctx.getSharedPreferences(PREFS, 0).getString(KEY_DEVICES, "[]");
            JSONArray arr = new JSONArray(json);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject obj = arr.getJSONObject(i);
                list.add(new Device(obj.getString("name"), obj.getString("ip"), obj.getString("code")));
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return list;
    }

    public static void saveDevice(Context ctx, final Device device) {
        try {
            List<Device> devices = getDevices(ctx);
            devices.removeIf(new Predicate() { // from class: com.lynxeye.DeviceStorage$$ExternalSyntheticLambda0
                @Override // java.util.function.Predicate
                public final boolean test(Object obj) {
                    return DeviceStorage.lambda$saveDevice$0(device, (DeviceStorage.Device) obj);
                }
            });
            devices.add(0, device);
            if (devices.size() > 20) {
                devices = devices.subList(0, 20);
            }
            JSONArray arr = new JSONArray();
            for (Device d : devices) {
                JSONObject obj = new JSONObject();
                obj.put("name", d.name);
                obj.put("ip", d.ip);
                obj.put("code", d.code);
                arr.put(obj);
            }
            ctx.getSharedPreferences(PREFS, 0).edit().putString(KEY_DEVICES, arr.toString()).apply();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    static /* synthetic */ boolean lambda$saveDevice$0(Device device, Device d) {
        return d.code.equals(device.code) && d.ip.equals(device.ip);
    }

    public static void deleteDevice(Context ctx, final String code, final String ip) {
        try {
            List<Device> devices = getDevices(ctx);
            devices.removeIf(new Predicate() { // from class: com.lynxeye.DeviceStorage$$ExternalSyntheticLambda1
                @Override // java.util.function.Predicate
                public final boolean test(Object obj) {
                    return DeviceStorage.lambda$deleteDevice$1(code, ip, (DeviceStorage.Device) obj);
                }
            });
            JSONArray arr = new JSONArray();
            for (Device d : devices) {
                JSONObject obj = new JSONObject();
                obj.put("name", d.name);
                obj.put("ip", d.ip);
                obj.put("code", d.code);
                arr.put(obj);
            }
            ctx.getSharedPreferences(PREFS, 0).edit().putString(KEY_DEVICES, arr.toString()).apply();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    static /* synthetic */ boolean lambda$deleteDevice$1(String code, String ip, Device d) {
        return d.code.equals(code) && d.ip.equals(ip);
    }
}
