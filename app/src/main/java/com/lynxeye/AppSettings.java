package com.lynxeye;

import android.content.Context;
import android.content.SharedPreferences;

public class AppSettings {
    private static final String PREFS = "lynxeye_prefs";

    public static boolean isMixAudio(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("mix_audio", false);
    }
    public static void setMixAudio(Context ctx, boolean v) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("mix_audio", v).apply();
    }

    public static boolean isOpusCodec(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("opus_codec", false);
    }
    public static void setOpusCodec(Context ctx, boolean v) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("opus_codec", v).apply();
    }

    public static boolean isNoiseSuppression(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("noise_suppression", false);
    }
    public static void setNoiseSuppression(Context ctx, boolean v) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("noise_suppression", v).apply();
    }
}
