package com.lynxeye;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.util.AttributeSet;
import android.view.View;

import java.util.Random;

public class HackerVisualizerView extends View {

    private final Paint wavePaint  = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glowPaint  = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint barPaint   = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint barGlow    = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint gridPaint  = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path  wavePath   = new Path();

    private final Random rng = new Random();
    private final int BAR_COUNT = 24;
    private final float[] barHeights    = new float[BAR_COUNT];
    private final float[] barTargets    = new float[BAR_COUNT];
    private final float[] wavePoints    = new float[128];

    private boolean running = false;
    private final Runnable animator = new Runnable() {
        @Override public void run() {
            update();
            invalidate();
            if (running) postDelayed(this, 40); // ~25fps
        }
    };

    public HackerVisualizerView(Context ctx) { super(ctx); init(); }
    public HackerVisualizerView(Context ctx, AttributeSet a) { super(ctx, a); init(); }

    private void init() {
        // Wave
        wavePaint.setColor(0xFF00E676);
        wavePaint.setStrokeWidth(1.5f);
        wavePaint.setStyle(Paint.Style.STROKE);

        glowPaint.setColor(0x3300E676);
        glowPaint.setStrokeWidth(5f);
        glowPaint.setStyle(Paint.Style.STROKE);
        glowPaint.setMaskFilter(new android.graphics.BlurMaskFilter(
                6f, android.graphics.BlurMaskFilter.Blur.NORMAL));

        // Bars
        barPaint.setColor(0xFF00E676);
        barPaint.setStyle(Paint.Style.FILL);

        barGlow.setColor(0x5500E676);
        barGlow.setStyle(Paint.Style.FILL);
        barGlow.setMaskFilter(new android.graphics.BlurMaskFilter(
                8f, android.graphics.BlurMaskFilter.Blur.NORMAL));

        // Grid
        gridPaint.setColor(0x15005500);
        gridPaint.setStrokeWidth(1f);
        gridPaint.setStyle(Paint.Style.STROKE);

        // Init bar targets
        for (int i = 0; i < BAR_COUNT; i++) {
            barTargets[i] = rng.nextFloat() * 0.7f + 0.05f;
            barHeights[i] = barTargets[i];
        }

        // Init wave
        for (int i = 0; i < wavePoints.length; i++) {
            wavePoints[i] = (rng.nextFloat() - 0.5f) * 0.4f;
        }
    }

    private float audioLevel = 0f;

    public void updateLevel(float level) {
        audioLevel = level;
    }

    public void start() {
        if (!running) {
            running = true;
            post(animator);
        }
    }

    public void stop() {
        running = false;
        removeCallbacks(animator);
    }

    private void update() {
        // Animate bars toward targets
        float base = audioLevel > 0.01f ? audioLevel : 0f;
        for (int i = 0; i < BAR_COUNT; i++) {
            barHeights[i] += (barTargets[i] - barHeights[i]) * 0.15f;
            if (rng.nextFloat() < 0.08f) {
                float t = base > 0.01f ? base * (0.4f + rng.nextFloat() * 0.8f) : rng.nextFloat() * 0.15f + 0.02f;
                barTargets[i] = Math.min(1f, t);
            }
        }

        // Animate wave
        for (int i = 0; i < wavePoints.length - 1; i++) {
            wavePoints[i] = wavePoints[i + 1];
        }
        float wAmp = audioLevel > 0.01f ? audioLevel * 1.2f : 0.05f;
        wavePoints[wavePoints.length - 1] = (rng.nextFloat() - 0.5f) * wAmp * 2f;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int w = getWidth();
        int h = getHeight();
        if (w == 0 || h == 0) return;

        canvas.drawColor(0xFF0A0A0A);

        // Draw grid lines
        int gridRows = 4;
        for (int i = 1; i < gridRows; i++) {
            float y = h * i / (float) gridRows;
            canvas.drawLine(0, y, w, y, gridPaint);
        }

        // Split: left half = wave, right half = bars
        float mid = w * 0.5f;
        float waveH = h * 0.45f;

        // ── Wave (left side) ──────────────────────────
        float waveCY = h * 0.28f;
        wavePath.reset();
        float wStep = mid / (wavePoints.length - 1);
        for (int i = 0; i < wavePoints.length; i++) {
            float x = i * wStep;
            float y = waveCY + wavePoints[i] * waveH;
            if (i == 0) wavePath.moveTo(x, y);
            else wavePath.lineTo(x, y);
        }
        canvas.drawPath(wavePath, glowPaint);
        canvas.drawPath(wavePath, wavePaint);

        // Label
        Paint labelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        labelPaint.setColor(0x6600E676);
        labelPaint.setTextSize(18f);
        labelPaint.setTypeface(android.graphics.Typeface.MONOSPACE);
        canvas.drawText("AUDIO", 8f, h - 8f, labelPaint);

        // ── Bars (right side) ─────────────────────────
        float barAreaW = w - mid - 8f;
        float barW = (barAreaW / BAR_COUNT) * 0.7f;
        float gap   = (barAreaW / BAR_COUNT) * 0.3f;
        float barBottom = h - 12f;

        for (int i = 0; i < BAR_COUNT; i++) {
            float x = mid + 4f + i * (barW + gap);
            float bh = barHeights[i] * (h - 20f);
            float top = barBottom - bh;

            // Glow
            canvas.drawRect(x - 2, top - 2, x + barW + 2, barBottom + 2, barGlow);
            // Bar
            canvas.drawRect(x, top, x + barW, barBottom, barPaint);

            // Top cap glow
            Paint capPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            capPaint.setColor(0xFFAAFFCC);
            capPaint.setStyle(Paint.Style.FILL);
            canvas.drawRect(x, top, x + barW, top + 2f, capPaint);
        }

        // Label
        canvas.drawText("FREQ", mid + 4f, h - 8f, labelPaint);

        // Separator line
        Paint sep = new Paint();
        sep.setColor(0x33005500);
        sep.setStrokeWidth(1f);
        canvas.drawLine(mid, 8f, mid, h - 8f, sep);
    }
}
