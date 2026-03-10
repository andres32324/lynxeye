package com.lynxeye;

import android.content.Context;

/* JADX INFO: loaded from: classes3.dex */
public class PinManager {
    private static final String KEY_PIN = "pin";
    private static final String PREFS = "lynxeye_prefs";

    public static boolean hasPin(Context ctx) {
        return ctx.getSharedPreferences(PREFS, 0).contains(KEY_PIN);
    }

    public static void savePin(Context ctx, String pin) {
        ctx.getSharedPreferences(PREFS, 0).edit().putString(KEY_PIN, pin).apply();
    }

    public static boolean checkPin(Context ctx, String pin) {
        String saved = ctx.getSharedPreferences(PREFS, 0).getString(KEY_PIN, "");
        return saved.equals(pin);
    }

    public static void clearPin(Context ctx) {
        ctx.getSharedPreferences(PREFS, 0).edit().remove(KEY_PIN).apply();
    }
}
