package com.lynxeye;

import android.content.Context;
import android.content.SharedPreferences;

public class AppSettings {
    private static final String PREFS = "lynxeye_settings";

    // Existing
    public static boolean isMixAudio(Context ctx) { return get(ctx).getBoolean("mix_audio", false); }
    public static void setMixAudio(Context ctx, boolean v) { set(ctx).putBoolean("mix_audio", v).apply(); }

    public static boolean isOpusCodec(Context ctx) { return get(ctx).getBoolean("opus_codec", false); }
    public static void setOpusCodec(Context ctx, boolean v) { set(ctx).putBoolean("opus_codec", v).apply(); }

    public static boolean isNoiseSuppression(Context ctx) { return get(ctx).getBoolean("noise_suppression", false); }
    public static void setNoiseSuppression(Context ctx, boolean v) { set(ctx).putBoolean("noise_suppression", v).apply(); }

    // Video on/off
    public static boolean isVideoEnabled(Context ctx) { return get(ctx).getBoolean("video_enabled", true); }
    public static void setVideoEnabled(Context ctx, boolean v) { set(ctx).putBoolean("video_enabled", v).apply(); }

    // Audio mode: 0=normal, 1=high_quality, 2=low_bandwidth
    public static int getAudioMode(Context ctx) { return get(ctx).getInt("audio_mode", 0); }
    public static void setAudioMode(Context ctx, int v) { set(ctx).putInt("audio_mode", v).apply(); }

    // Sample rate
    public static int getSampleRate(Context ctx) { return get(ctx).getInt("sample_rate", 44100); }
    public static void setSampleRate(Context ctx, int v) { set(ctx).putInt("sample_rate", v).apply(); }

    private static SharedPreferences get(Context ctx) { return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE); }
    private static SharedPreferences.Editor set(Context ctx) { return get(ctx).edit(); }
}
