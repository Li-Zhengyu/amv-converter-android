package com.amvconverter.app;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;

import com.amvconverter.core.AmvJpegEncoder;

/**
 * Turns an encoded AMV frame into a bitmap the right way up.
 *
 * <p>An AMV frame is a table-stripped JPEG, and the format stores its pictures <em>bottom-up</em>
 * (an AVI/DIB inheritance). Decoders are expected to flip it back: FFmpeg's AMV decoder sets its
 * {@code flipped} flag for exactly this reason. A plain JPEG decoder such as
 * {@link BitmapFactory} knows nothing about that convention, so anything that renders an AMV
 * frame has to flip it itself - forgetting this shows the whole picture upside-down.
 *
 * <p>Measured on a vertically asymmetric test pattern: rebuilt frame against the source reads
 * 5.6 dB upright versus 45.4 dB flipped, and a vertical-flip-only comparison reaching 45.4 dB is
 * itself proof that no horizontal flip is involved.
 */
final class AmvFrameBitmap {

    private static final Matrix FLIP = new Matrix();
    static {
        FLIP.setScale(1f, -1f);
    }

    private static final BitmapFactory.Options OPTIONS = new BitmapFactory.Options();
    static {
        OPTIONS.inPreferredConfig = Bitmap.Config.ARGB_8888;
    }

    private AmvFrameBitmap() {
    }

    /**
     * Rebuilds an encoded frame into a decodable JPEG (the way a hardware player does) and
     * returns it upright.
     *
     * @param frame the encoded frame, including its SOI/EOI wrappers
     * @return the decoded frame, or null if the frame could not be decoded
     */
    static Bitmap decodeFrame(byte[] frame, int length, int width, int height) {
        if (length <= 4) return null;
        byte[] jpeg = AmvJpegEncoder.wrapAsJpeg(frame, length, width, height);
        return decodeUpright(jpeg, jpeg.length);
    }

    /**
     * Decodes an already-rebuilt JPEG and returns it upright. Use this when the caller holds the
     * rebuilt JPEG (the conversion preview does) rather than the raw frame.
     */
    static Bitmap decodeUpright(byte[] jpeg, int length) {
        Bitmap raw = BitmapFactory.decodeByteArray(jpeg, 0, length, OPTIONS);
        if (raw == null) return null;
        if (raw.getWidth() <= 0 || raw.getHeight() <= 0) return raw;
        try {
            Bitmap upright = Bitmap.createBitmap(raw, 0, 0, raw.getWidth(), raw.getHeight(), FLIP, false);
            if (upright != raw) raw.recycle();
            return upright;
        } catch (Throwable t) {
            return raw;   // flipping is cosmetic; never lose the frame over it
        }
    }
}
