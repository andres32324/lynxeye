package com.lynxeye;

import android.content.Context;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;
import kotlin.UByte;

/* JADX INFO: loaded from: classes3.dex */
public class AudioVisualizerView extends View {
    private final Paint glowPaint;
    private byte[] lastChunk;
    private float[] points;
    private final Paint wavePaint;

    public AudioVisualizerView(Context ctx) {
        super(ctx);
        this.wavePaint = new Paint(1);
        this.glowPaint = new Paint(1);
        this.points = new float[0];
        this.lastChunk = new byte[0];
        init();
    }

    public AudioVisualizerView(Context ctx, AttributeSet a) {
        super(ctx, a);
        this.wavePaint = new Paint(1);
        this.glowPaint = new Paint(1);
        this.points = new float[0];
        this.lastChunk = new byte[0];
        init();
    }

    private void init() {
        this.wavePaint.setColor(-16718218);
        this.wavePaint.setStrokeWidth(2.0f);
        this.wavePaint.setStyle(Paint.Style.STROKE);
        this.glowPaint.setColor(1140909686);
        this.glowPaint.setStrokeWidth(6.0f);
        this.glowPaint.setStyle(Paint.Style.STROKE);
        this.glowPaint.setMaskFilter(new BlurMaskFilter(8.0f, BlurMaskFilter.Blur.NORMAL));
    }

    public void updateAudio(byte[] chunk) {
        if (chunk == null || chunk.length < 2) {
            return;
        }
        this.lastChunk = chunk;
        postInvalidate();
    }

    @Override // android.view.View
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        canvas.drawColor(-15658735);
        int w = getWidth();
        int h = getHeight();
        if (w == 0 || h == 0) {
            return;
        }
        byte[] bArr = this.lastChunk;
        int i = 2;
        if (bArr.length < 2) {
            return;
        }
        int samples = bArr.length / 2;
        int step = Math.max(1, samples / w);
        int numPoints = samples / step;
        if (this.points.length != numPoints * 4) {
            this.points = new float[numPoints * 4];
        }
        float f = w / 2.0f;
        float cy = h / 2.0f;
        float scaleY = 0.85f * cy;
        int i2 = 0;
        while (i2 < numPoints - 1) {
            int idx1 = i2 * step * i;
            int idx2 = (i2 + 1) * step * i;
            int i3 = idx2 + 1;
            byte[] bArr2 = this.lastChunk;
            if (i3 >= bArr2.length) {
                break;
            }
            short s1 = (short) ((bArr2[idx1 + 1] << 8) | (bArr2[idx1] & UByte.MAX_VALUE));
            short s2 = (short) ((bArr2[idx2] & UByte.MAX_VALUE) | (bArr2[idx2 + 1] << 8));
            int h2 = h;
            float x1 = (i2 / numPoints) * w;
            int samples2 = samples;
            float x2 = ((i2 + 1) / numPoints) * w;
            float y1 = cy - ((s1 / 32768.0f) * scaleY);
            int w2 = w;
            float y2 = cy - ((s2 / 32768.0f) * scaleY);
            int step2 = step;
            float[] fArr = this.points;
            fArr[i2 * 4] = x1;
            fArr[(i2 * 4) + 1] = y1;
            fArr[(i2 * 4) + 2] = x2;
            fArr[(i2 * 4) + 3] = y2;
            i2++;
            i = 2;
            h = h2;
            samples = samples2;
            step = step2;
            w = w2;
        }
        canvas.drawLines(this.points, this.glowPaint);
        canvas.drawLines(this.points, this.wavePaint);
    }
}
