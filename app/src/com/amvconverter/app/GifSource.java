package com.amvconverter.app;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Movie;
import android.net.Uri;

import com.amvconverter.core.FrameSource;
import com.amvconverter.core.YuvFrame;

import java.io.IOException;
import java.io.InputStream;

/**
 * Turns an animated GIF into AMV frames.
 *
 * <p>android.graphics.Movie predates the modern image stack but still decodes GIF on every
 * supported API level and, crucially, can render "the frame at time t" - which is exactly the
 * random access the converter needs. Frames are emitted on a fixed 25 fps grid of GIF time
 * (repeating a frame when the GIF advances slower, which is harmless) and the converter picks
 * whichever one matches each output frame.
 */
public final class GifSource implements FrameSource {

    private static final long STEP_US = 40000;   // 25 fps on the GIF timeline

    private final Movie movie;
    private final int width;
    private final int height;
    private final long durationUs;
    private final Bitmap bitmap;
    private final Canvas canvas;
    private final int[] argb;

    private long nextUs = 0;

    public GifSource(Context context, Uri uri) throws IOException {
        InputStream in = null;
        try {
            in = context.getContentResolver().openInputStream(uri);
            movie = Movie.decodeStream(in);
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (IOException ignored) {
                }
            }
        }
        if (movie == null) throw new IOException("无法解析 GIF");
        width = movie.width() > 0 ? movie.width() : 1;
        height = movie.height() > 0 ? movie.height() : 1;
        long ms = movie.duration();
        durationUs = (ms > 0 ? ms : 1000L) * 1000L;
        bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        canvas = new Canvas(bitmap);
        argb = new int[width * height];
        movie.setTime(0);
    }

    public long durationUs() { return durationUs; }

    @Override
    public int width() { return width; }

    @Override
    public int height() { return height; }

    @Override
    public boolean isFullRange() { return true; }

    @Override
    public long readFrameFor(YuvFrame dst, long targetUs) throws IOException {
        // A GIF can be sampled at any point on its own timeline, so there is no reason to walk
        // frames one at a time: render exactly what the requested time asks for.
        if (targetUs >= durationUs) return -1;
        long t = Math.max(0, targetUs);
        movie.setTime((int) (t / 1000));
        bitmap.eraseColor(0);          // GIF frames can have transparency
        movie.draw(canvas, 0, 0);
        bitmap.getPixels(argb, 0, width, 0, 0, width, height);
        YuvFrame.argbToYuv420(argb, width, height, dst);
        return t;
    }

    public void release() {
        bitmap.recycle();
    }
}
