package com.lynxeye;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;

public class DeviceStorage {

    public static class Device {
        public String name;
        public String ip;
        public String code;

        public Device(String name, String ip, String code) {
            this.name = name;
            this.ip = ip;
            this.code = code;
        }
    }

    private static final String PREFS = "lynxeye_prefs";
    private static final String KEY_DEVICES = "devices";

    public static List<Device> getDevices(Context ctx) {
        List<Device> list = new ArrayList<>();
        try {
            String json = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_DEVICES, "[]");
            JSONArray arr = new JSONArray(json);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject obj = arr.getJSONObject(i);
                list.add(new Device(obj.getString("name"), obj.getString("ip"), obj.getString("code")));
            }
        } catch (Exception e) { e.printStackTrace(); }
        return list;
    }

    public static void saveDevice(Context ctx, Device device) {
        try {
            List<Device> devices = getDevices(ctx);
            // Remove duplicate by code + ip (same device same IP)
            devices.removeIf(d -> d.code.equals(device.code) && d.ip.equals(device.ip));
            devices.add(0, device);
            if (devices.size() > 20) devices = devices.subList(0, 20);
            JSONArray arr = new JSONArray();
            for (Device d : devices) {
                JSONObject obj = new JSONObject();
                obj.put("name", d.name);
                obj.put("ip", d.ip);
                obj.put("code", d.code);
                arr.put(obj);
            }
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_DEVICES, arr.toString()).apply();
        } catch (Exception e) { e.printStackTrace(); }
    }

    public static void deleteDevice(Context ctx, String code, String ip) {
        try {
            List<Device> devices = getDevices(ctx);
            devices.removeIf(d -> d.code.equals(code) && d.ip.equals(ip));
            JSONArray arr = new JSONArray();
            for (Device d : devices) {
                JSONObject obj = new JSONObject();
                obj.put("name", d.name);
                obj.put("ip", d.ip);
                obj.put("code", d.code);
                arr.put(obj);
            }
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_DEVICES, arr.toString()).apply();
        } catch (Exception e) { e.printStackTrace(); }
    }
}
