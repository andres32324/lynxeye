package com.lynxeye;

import java.lang.reflect.Array;
import kotlin.UByte;

/* JADX INFO: loaded from: classes3.dex */
public class DspEqualizer {
    public static final int BANDS = 10;
    public static final float[] FREQUENCIES = {32.0f, 64.0f, 125.0f, 250.0f, 500.0f, 1000.0f, 2000.0f, 4000.0f, 8000.0f, 16000.0f};
    public static final String[] LABELS = {"32Hz", "64Hz", "125Hz", "250Hz", "500Hz", "1kHz", "2kHz", "4kHz", "8kHz", "16kHz"};
    private final float sampleRate;
    private final float[] gains = new float[10];
    private final float[][] state = (float[][]) Array.newInstance((Class<?>) Float.TYPE, 10, 4);
    private final float[][] coeffs = (float[][]) Array.newInstance((Class<?>) Float.TYPE, 10, 5);

    public DspEqualizer(float sampleRate) {
        this.sampleRate = sampleRate;
        for (int i = 0; i < 10; i++) {
            this.gains[i] = 0.0f;
            computeCoeffs(i);
        }
    }

    public void setGain(int band, float db) {
        if (band < 0 || band >= 10) {
            return;
        }
        this.gains[band] = Math.max(-12.0f, Math.min(12.0f, db));
        computeCoeffs(band);
    }

    public float getGain(int band) {
        return this.gains[band];
    }

    private void computeCoeffs(int band) {
        float f0 = FREQUENCIES[band];
        float dBgain = this.gains[band];
        float A = (float) Math.pow(10.0d, ((double) dBgain) / 40.0d);
        float w0 = (float) ((((double) f0) * 6.283185307179586d) / ((double) this.sampleRate));
        float cosW0 = (float) Math.cos(w0);
        float sinW0 = (float) Math.sin(w0);
        float alpha = sinW0 / (2.0f * 1.41f);
        float b0 = (alpha * A) + 1.0f;
        float b1 = cosW0 * (-2.0f);
        float b2 = 1.0f - (alpha * A);
        float a0 = (alpha / A) + 1.0f;
        float a1 = (-2.0f) * cosW0;
        float a2 = 1.0f - (alpha / A);
        float[] fArr = this.coeffs[band];
        fArr[0] = b0 / a0;
        fArr[1] = b1 / a0;
        fArr[2] = b2 / a0;
        fArr[3] = a1 / a0;
        fArr[4] = a2 / a0;
    }

    public byte[] process(byte[] input) {
        int samples = input.length / 2;
        float[] pcm = new float[samples];
        for (int i = 0; i < samples; i++) {
            pcm[i] = ((short) ((input[(i * 2) + 1] << 8) | (input[i * 2] & UByte.MAX_VALUE))) / 32768.0f;
        }
        for (int b = 0; b < 10; b++) {
            if (Math.abs(this.gains[b]) >= 0.1f) {
                float[] fArr = this.coeffs[b];
                float b0 = fArr[0];
                float b1 = fArr[1];
                float b2 = fArr[2];
                float a1 = fArr[3];
                float a2 = fArr[4];
                float[] fArr2 = this.state[b];
                float x1 = fArr2[0];
                float x2 = fArr2[1];
                float y1 = fArr2[2];
                float y2 = fArr2[3];
                for (int i2 = 0; i2 < samples; i2++) {
                    float x = pcm[i2];
                    float y = ((((b0 * x) + (b1 * x1)) + (b2 * x2)) - (a1 * y1)) - (a2 * y2);
                    x2 = x1;
                    x1 = x;
                    y2 = y1;
                    y1 = y;
                    pcm[i2] = y;
                }
                float[] fArr3 = this.state[b];
                fArr3[0] = x1;
                fArr3[1] = x2;
                fArr3[2] = y1;
                fArr3[3] = y2;
            }
        }
        byte[] output = new byte[input.length];
        for (int i3 = 0; i3 < samples; i3++) {
            float f = pcm[i3];
            if (f > 1.0f) {
                f = 1.0f;
            }
            if (f < -1.0f) {
                f = -1.0f;
            }
            short s = (short) (32767.0f * f);
            output[i3 * 2] = (byte) (s & 255);
            output[(i3 * 2) + 1] = (byte) (s >> 8);
        }
        return output;
    }
}
