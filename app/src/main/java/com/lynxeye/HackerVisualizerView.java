package com.lynxeye;

import android.content.Context;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Typeface;
import android.util.AttributeSet;
import android.view.View;
import java.util.Random;

/* JADX INFO: loaded from: classes3.dex */
public class HackerVisualizerView extends View {
    private final int BAR_COUNT;
    private final Runnable animator;
    private final Paint barGlow;
    private final float[] barHeights;
    private final Paint barPaint;
    private final float[] barTargets;
    private final Paint glowPaint;
    private final Paint gridPaint;
    private final Random rng;
    private boolean running;
    private final Paint wavePaint;
    private final Path wavePath;
    private final float[] wavePoints;

    public HackerVisualizerView(Context ctx) {
        super(ctx);
        this.wavePaint = new Paint(1);
        this.glowPaint = new Paint(1);
        this.barPaint = new Paint(1);
        this.barGlow = new Paint(1);
        this.gridPaint = new Paint(1);
        this.wavePath = new Path();
        this.rng = new Random();
        this.BAR_COUNT = 24;
        this.barHeights = new float[24];
        this.barTargets = new float[24];
        this.wavePoints = new float[128];
        this.running = false;
        this.animator = new Runnable() { // from class: com.lynxeye.HackerVisualizerView.1
            @Override // java.lang.Runnable
            public void run() {
                HackerVisualizerView.this.update();
                HackerVisualizerView.this.invalidate();
                if (HackerVisualizerView.this.running) {
                    HackerVisualizerView.this.postDelayed(this, 40L);
                }
            }
        };
        init();
    }

    public HackerVisualizerView(Context ctx, AttributeSet a) {
        super(ctx, a);
        this.wavePaint = new Paint(1);
        this.glowPaint = new Paint(1);
        this.barPaint = new Paint(1);
        this.barGlow = new Paint(1);
        this.gridPaint = new Paint(1);
        this.wavePath = new Path();
        this.rng = new Random();
        this.BAR_COUNT = 24;
        this.barHeights = new float[24];
        this.barTargets = new float[24];
        this.wavePoints = new float[128];
        this.running = false;
        this.animator = new Runnable() { // from class: com.lynxeye.HackerVisualizerView.1
            @Override // java.lang.Runnable
            public void run() {
                HackerVisualizerView.this.update();
                HackerVisualizerView.this.invalidate();
                if (HackerVisualizerView.this.running) {
                    HackerVisualizerView.this.postDelayed(this, 40L);
                }
            }
        };
        init();
    }

    private void init() {
        this.wavePaint.setColor(-16718218);
        this.wavePaint.setStrokeWidth(1.5f);
        this.wavePaint.setStyle(Paint.Style.STROKE);
        this.glowPaint.setColor(855697014);
        this.glowPaint.setStrokeWidth(5.0f);
        this.glowPaint.setStyle(Paint.Style.STROKE);
        this.glowPaint.setMaskFilter(new BlurMaskFilter(6.0f, BlurMaskFilter.Blur.NORMAL));
        this.barPaint.setColor(-16718218);
        this.barPaint.setStyle(Paint.Style.FILL);
        this.barGlow.setColor(1426122358);
        this.barGlow.setStyle(Paint.Style.FILL);
        this.barGlow.setMaskFilter(new BlurMaskFilter(8.0f, BlurMaskFilter.Blur.NORMAL));
        this.gridPaint.setColor(352343296);
        this.gridPaint.setStrokeWidth(1.0f);
        this.gridPaint.setStyle(Paint.Style.STROKE);
        for (int i = 0; i < 24; i++) {
            this.barTargets[i] = (this.rng.nextFloat() * 0.7f) + 0.05f;
            this.barHeights[i] = this.barTargets[i];
        }
        int i2 = 0;
        while (true) {
            float[] fArr = this.wavePoints;
            if (i2 < fArr.length) {
                fArr[i2] = (this.rng.nextFloat() - 0.5f) * 0.4f;
                i2++;
            } else {
                return;
            }
        }
    }

    public void start() {
        if (!this.running) {
            this.running = true;
            post(this.animator);
        }
    }

    public void stop() {
        this.running = false;
        removeCallbacks(this.animator);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void update() {
        for (int i = 0; i < 24; i++) {
            float[] fArr = this.barHeights;
            float f = fArr[i];
            fArr[i] = f + ((this.barTargets[i] - f) * 0.15f);
            if (this.rng.nextFloat() < 0.08f) {
                this.barTargets[i] = (this.rng.nextFloat() * 0.85f) + 0.05f;
            }
        }
        int i2 = 0;
        while (true) {
            float[] fArr2 = this.wavePoints;
            if (i2 < fArr2.length - 1) {
                fArr2[i2] = fArr2[i2 + 1];
                i2++;
            } else {
                int i3 = fArr2.length;
                fArr2[i3 - 1] = (this.rng.nextFloat() - 0.5f) * 0.6f;
                return;
            }
        }
    }

    @Override // android.view.View
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int w = getWidth();
        int h = getHeight();
        if (w == 0 || h == 0) {
            return;
        }
        canvas.drawColor(-16119286);
        for (int i = 1; i < 4; i++) {
            float y = (h * i) / 4;
            canvas.drawLine(0.0f, y, w, y, this.gridPaint);
        }
        float mid = w * 0.5f;
        float waveH = h * 0.45f;
        float waveCY = h * 0.28f;
        this.wavePath.reset();
        float wStep = mid / (this.wavePoints.length - 1);
        int i2 = 0;
        while (true) {
            float[] fArr = this.wavePoints;
            if (i2 >= fArr.length) {
                break;
            }
            float x = i2 * wStep;
            float y2 = (fArr[i2] * waveH) + waveCY;
            if (i2 == 0) {
                this.wavePath.moveTo(x, y2);
            } else {
                this.wavePath.lineTo(x, y2);
            }
            i2++;
        }
        canvas.drawPath(this.wavePath, this.glowPaint);
        canvas.drawPath(this.wavePath, this.wavePaint);
        Paint labelPaint = new Paint(1);
        labelPaint.setColor(1711335030);
        labelPaint.setTextSize(18.0f);
        labelPaint.setTypeface(Typeface.MONOSPACE);
        float f = 8.0f;
        canvas.drawText("AUDIO", 8.0f, h - 8.0f, labelPaint);
        float barAreaW = (w - mid) - 8.0f;
        float barW = (barAreaW / 24.0f) * 0.7f;
        float gap = (barAreaW / 24.0f) * 0.3f;
        float barBottom = h - 12.0f;
        int i3 = 0;
        while (i3 < 24) {
            float x2 = 4.0f + mid + (i3 * (barW + gap));
            float bh = this.barHeights[i3] * (h - 20.0f);
            float top = barBottom - bh;
            canvas.drawRect(x2 - 2.0f, top - 2.0f, x2 + barW + 2.0f, barBottom + 2.0f, this.barGlow);
            canvas.drawRect(x2, top, x2 + barW, barBottom, this.barPaint);
            Paint capPaint = new Paint(1);
            capPaint.setColor(-5570612);
            capPaint.setStyle(Paint.Style.FILL);
            canvas.drawRect(x2, top, x2 + barW, top + 2.0f, capPaint);
            i3++;
            f = f;
            labelPaint = labelPaint;
        }
        float f2 = f;
        canvas.drawText("FREQ", 4.0f + mid, h - f2, labelPaint);
        Paint sep = new Paint();
        sep.setColor(855659776);
        sep.setStrokeWidth(1.0f);
        canvas.drawLine(mid, 8.0f, mid, h - f2, sep);
    }
}
