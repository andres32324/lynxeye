package com.lynxeye;

/**
 * 10-band DSP Equalizer using biquad filters
 * Bands: 32Hz, 64Hz, 125Hz, 250Hz, 500Hz, 1kHz, 2kHz, 4kHz, 8kHz, 16kHz
 */
public class DspEqualizer {

    public static final int BANDS = 10;
    public static final float[] FREQUENCIES = {32f, 64f, 125f, 250f, 500f, 1000f, 2000f, 4000f, 8000f, 16000f};
    public static final String[] LABELS = {"32Hz","64Hz","125Hz","250Hz","500Hz","1kHz","2kHz","4kHz","8kHz","16kHz"};

    private final float sampleRate;
    private final float[] gains = new float[BANDS]; // dB gains -12 to +12

    // Biquad filter state per band (x1, x2, y1, y2)
    private final float[][] state = new float[BANDS][4];

    // Biquad coefficients per band (b0, b1, b2, a1, a2)
    private final float[][] coeffs = new float[BANDS][5];

    public DspEqualizer(float sampleRate) {
        this.sampleRate = sampleRate;
        for (int i = 0; i < BANDS; i++) {
            gains[i] = 0f;
            computeCoeffs(i);
        }
    }

    public void setGain(int band, float db) {
        if (band < 0 || band >= BANDS) return;
        gains[band] = Math.max(-12f, Math.min(12f, db));
        computeCoeffs(band);
    }

    public float getGain(int band) {
        return gains[band];
    }

    /** Peaking EQ biquad filter */
    private void computeCoeffs(int band) {
        float f0 = FREQUENCIES[band];
        float dBgain = gains[band];
        float Q = 1.41f; // bandwidth ~1 octave
        float A  = (float) Math.pow(10.0, dBgain / 40.0);
        float w0 = (float) (2.0 * Math.PI * f0 / sampleRate);
        float cosW0 = (float) Math.cos(w0);
        float sinW0 = (float) Math.sin(w0);
        float alpha = sinW0 / (2f * Q);

        float b0 =  1f + alpha * A;
        float b1 = -2f * cosW0;
        float b2 =  1f - alpha * A;
        float a0 =  1f + alpha / A;
        float a1 = -2f * cosW0;
        float a2 =  1f - alpha / A;

        coeffs[band][0] = b0 / a0;
        coeffs[band][1] = b1 / a0;
        coeffs[band][2] = b2 / a0;
        coeffs[band][3] = a1 / a0;
        coeffs[band][4] = a2 / a0;
    }

    /** Process a buffer of PCM 16-bit samples */
    public byte[] process(byte[] input) {
        int samples = input.length / 2;
        float[] pcm = new float[samples];

        // Convert bytes to float
        for (int i = 0; i < samples; i++) {
            short s = (short) ((input[i*2+1] << 8) | (input[i*2] & 0xFF));
            pcm[i] = s / 32768f;
        }

        // Apply each band filter
        for (int b = 0; b < BANDS; b++) {
            if (Math.abs(gains[b]) < 0.1f) continue; // skip if flat
            float b0 = coeffs[b][0], b1 = coeffs[b][1], b2 = coeffs[b][2];
            float a1 = coeffs[b][3], a2 = coeffs[b][4];
            float x1 = state[b][0], x2 = state[b][1];
            float y1 = state[b][2], y2 = state[b][3];

            for (int i = 0; i < samples; i++) {
                float x  = pcm[i];
                float y  = b0*x + b1*x1 + b2*x2 - a1*y1 - a2*y2;
                x2 = x1; x1 = x;
                y2 = y1; y1 = y;
                pcm[i] = y;
            }
            state[b][0] = x1; state[b][1] = x2;
            state[b][2] = y1; state[b][3] = y2;
        }

        // Convert back to bytes with clipping
        byte[] output = new byte[input.length];
        for (int i = 0; i < samples; i++) {
            float f = pcm[i];
            if (f >  1f) f =  1f;
            if (f < -1f) f = -1f;
            short s = (short) (f * 32767f);
            output[i*2]   = (byte) (s & 0xFF);
            output[i*2+1] = (byte) (s >> 8);
        }
        return output;
    }
}
