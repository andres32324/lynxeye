package com.lynxeye;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;

public class AudioVisualizerView extends View {

    private final Paint wavePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private float[] points = new float[0];
    private byte[]  lastChunk = new byte[0];

    public AudioVisualizerView(Context ctx) { super(ctx); init(); }
    public AudioVisualizerView(Context ctx, AttributeSet a) { super(ctx, a); init(); }

    private void init() {
        wavePaint.setColor(0xFF00E676);
        wavePaint.setStrokeWidth(2f);
        wavePaint.setStyle(Paint.Style.STROKE);

        glowPaint.setColor(0x4400E676);
        glowPaint.setStrokeWidth(6f);
        glowPaint.setStyle(Paint.Style.STROKE);
        glowPaint.setMaskFilter(new android.graphics.BlurMaskFilter(8f, android.graphics.BlurMaskFilter.Blur.NORMAL));
    }

    public void updateAudio(byte[] chunk) {
        if (chunk == null || chunk.length < 2) return;
        lastChunk = chunk;
        postInvalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        canvas.drawColor(0xFF111111);

        int w = getWidth();
        int h = getHeight();
        if (w == 0 || h == 0 || lastChunk.length < 2) return;

        int samples = lastChunk.length / 2;
        int step = Math.max(1, samples / w);
        int numPoints = samples / step;

        if (points.length != numPoints * 4) points = new float[numPoints * 4];

        float cx = w / 2f;
        float cy = h / 2f;
        float scaleY = cy * 0.85f;

        for (int i = 0; i < numPoints - 1; i++) {
            int idx1 = i * step * 2;
            int idx2 = (i + 1) * step * 2;
            if (idx2 + 1 >= lastChunk.length) break;

            short s1 = (short) ((lastChunk[idx1 + 1] << 8) | (lastChunk[idx1] & 0xFF));
            short s2 = (short) ((lastChunk[idx2 + 1] << 8) | (lastChunk[idx2] & 0xFF));

            float x1 = (float) i / numPoints * w;
            float x2 = (float) (i + 1) / numPoints * w;
            float y1 = cy - (s1 / 32768f) * scaleY;
            float y2 = cy - (s2 / 32768f) * scaleY;

            points[i * 4]     = x1;
            points[i * 4 + 1] = y1;
            points[i * 4 + 2] = x2;
            points[i * 4 + 3] = y2;
        }

        canvas.drawLines(points, glowPaint);
        canvas.drawLines(points, wavePaint);
    }
}
