package com.amvconverter.app;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;

import com.amvconverter.core.FrameSource;
import com.amvconverter.core.YuvFrame;

/**
 * A single still frame, repeated for the whole output.
 *
 * <p>Used for still images and - with a generated title card - for audio-only inputs, which makes
 * "MP3 to AMV" work without any extra machinery: the converter already repeats the last frame
 * when the source runs out, and the audio supplies the duration.
 */
public final class StillSource implements FrameSource {

    private final YuvFrame frame;

    public StillSource(Bitmap bitmap) {
        int w = bitmap.getWidth();
        int h = bitmap.getHeight();
        int[] argb = new int[w * h];
        bitmap.getPixels(argb, 0, w, 0, 0, w, h);
        frame = new YuvFrame(w, h);
        YuvFrame.argbToYuv420(argb, w, h, frame);
    }

    /** Builds a dark title card bitmap, used when the input has audio but no picture. */
    public static Bitmap titleCardBitmap(String title, String subtitle, int width, int height) {
        Bitmap bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        c.drawColor(Color.rgb(12, 16, 24));

        Paint bar = new Paint(Paint.ANTI_ALIAS_FLAG);
        bar.setColor(Color.rgb(30, 120, 220));
        bar.setAlpha(60);
        c.drawRect(0, height * 0.35f, width, height * 0.65f, bar);

        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(Color.WHITE);
        p.setTextAlign(Paint.Align.CENTER);
        float titleSize = Math.max(10f, height / 12f);
        p.setTextSize(titleSize);
        c.drawText(ellipsize(title, 22), width / 2f, height * 0.48f, p);

        p.setColor(Color.rgb(150, 190, 230));
        p.setTextSize(Math.max(8f, height / 22f));
        c.drawText(subtitle, width / 2f, height * 0.58f, p);

        return bmp;
    }

    /** Builds a dark title card, used when the input has audio but no picture. */
    public static StillSource titleCard(String title, String subtitle, int width, int height) {
        return new StillSource(titleCardBitmap(title, subtitle, width, height));
    }

    private static String ellipsize(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }

    @Override
    public int width() { return frame.width; }

    @Override
    public int height() { return frame.height; }

    @Override
    public boolean isFullRange() { return true; }

    @Override
    public long readFrameFor(YuvFrame dst, long targetUs) {
        System.arraycopy(frame.y, 0, dst.y, 0, dst.y.length);
        System.arraycopy(frame.u, 0, dst.u, 0, dst.u.length);
        System.arraycopy(frame.v, 0, dst.v, 0, dst.v.length);
        return 0;
    }
}
