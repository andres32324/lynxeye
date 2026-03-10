package com.lynxeye;

import android.content.Context;
import android.content.SharedPreferences;

public class PinManager {
    private static final String PREFS = "lynxeye_prefs";
    private static final String KEY_PIN = "pin";

    public static boolean hasPin(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).contains(KEY_PIN);
    }

    public static void savePin(Context ctx, String pin) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_PIN, pin).apply();
    }

    public static boolean checkPin(Context ctx, String pin) {
        String saved = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_PIN, "");
        return saved.equals(pin);
    }

    public static void clearPin(Context ctx) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY_PIN).apply();
    }
}
