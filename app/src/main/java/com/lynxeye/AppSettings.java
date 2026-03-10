package com.lynxeye;

import android.content.Context;
import android.content.SharedPreferences;

/* JADX INFO: loaded from: classes3.dex */
public class AppSettings {
    private static final String PREFS = "lynxeye_settings";

    public static boolean isMixAudio(Context ctx) {
        return get(ctx).getBoolean("mix_audio", false);
    }

    public static void setMixAudio(Context ctx, boolean v) {
        set(ctx).putBoolean("mix_audio", v).apply();
    }

    public static boolean isOpusCodec(Context ctx) {
        return get(ctx).getBoolean("opus_codec", false);
    }

    public static void setOpusCodec(Context ctx, boolean v) {
        set(ctx).putBoolean("opus_codec", v).apply();
    }

    public static boolean isNoiseSuppression(Context ctx) {
        return get(ctx).getBoolean("noise_suppression", false);
    }

    public static void setNoiseSuppression(Context ctx, boolean v) {
        set(ctx).putBoolean("noise_suppression", v).apply();
    }

    public static boolean isVideoEnabled(Context ctx) {
        return get(ctx).getBoolean("video_enabled", true);
    }

    public static void setVideoEnabled(Context ctx, boolean v) {
        set(ctx).putBoolean("video_enabled", v).apply();
    }

    public static int getAudioMode(Context ctx) {
        return get(ctx).getInt("audio_mode", 0);
    }

    public static void setAudioMode(Context ctx, int v) {
        set(ctx).putInt("audio_mode", v).apply();
    }

    public static int getSampleRate(Context ctx) {
        return get(ctx).getInt("sample_rate", 44100);
    }

    public static void setSampleRate(Context ctx, int v) {
        set(ctx).putInt("sample_rate", v).apply();
    }

    public static boolean isVisualizerEnabled(Context ctx) {
        return get(ctx).getBoolean("visualizer", true);
    }

    public static void setVisualizerEnabled(Context ctx, boolean v) {
        set(ctx).putBoolean("visualizer", v).apply();
    }

    public static void setEqGain(Context ctx, String ip, int band, float db) {
        set(ctx).putFloat("eq_" + ip.replace(".", "_") + "_" + band, db).apply();
    }

    public static float getEqGain(Context ctx, String ip, int band) {
        return get(ctx).getFloat("eq_" + ip.replace(".", "_") + "_" + band, 0.0f);
    }

    private static SharedPreferences get(Context ctx) {
        return ctx.getSharedPreferences(PREFS, 0);
    }

    private static SharedPreferences.Editor set(Context ctx) {
        return get(ctx).edit();
    }
}
