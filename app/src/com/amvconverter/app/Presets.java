package com.amvconverter.app;

import com.amvconverter.core.AmvConverter;
import com.amvconverter.core.YuvScaler;

/**
 * The choices offered in the UI.
 *
 * <p>Two facts about AMV drive these lists:
 * <ul>
 *   <li>Quality is not adjustable - the player owns the quantisation table - so file size is
 *       controlled by resolution, frame rate and (as an advanced trade) a coarser encoder
 *       quantiser.</li>
 *   <li>Heights that are not multiples of 16 have caused real players trouble, so the classic
 *       160x120 is offered alongside genuinely safer 16-multiple sizes such as 128x96.</li>
 * </ul>
 */
public final class Presets {

    public static final class Size {
        public final int width, height;
        public final String label;
        Size(int w, int h, String label) { this.width = w; this.height = h; this.label = label; }
        @Override public String toString() { return label; }
    }

    public static final class Fps {
        public final int num, den;
        public final String label;
        Fps(int num, int den, String label) { this.num = num; this.den = den; this.label = label; }
        @Override public String toString() { return label; }
    }

    public static final class Fit {
        public final YuvScaler.Fit mode;
        public final String label;
        Fit(YuvScaler.Fit mode, String label) { this.mode = mode; this.label = label; }
        @Override public String toString() { return label; }
    }

    public static final class Quality {
        public final double scale;
        public final String label;
        Quality(double scale, String label) { this.scale = scale; this.label = label; }
        @Override public String toString() { return label; }
    }

    public static final Size[] SIZES = {
            new Size(160, 120, "160x120"),
            new Size(128, 96, "128x96"),
            new Size(96, 64, "96x64"),
            new Size(160, 128, "160x128"),
            new Size(176, 144, "176x144"),
            new Size(192, 144, "192x144"),
            new Size(320, 240, "320x240"),
    };

    /**
     * The configuration that is known to work on real hardware, kept as one tap rather than a
     * combination the user has to rediscover: 160x128 (a multiple of 16, so no decoder anywhere
     * objects), 10 fps and 2205 samples per audio block - exactly the recipe that was verified
     * against an FFmpeg command line whose output plays on the user's player.
     */
    public static final int VERIFIED_SIZE = 3;   // 160x128
    public static final int VERIFIED_FPS = 0;    // 10 fps

    public static final Fps[] FPS = {
            new Fps(10, 1, "10"),
            new Fps(12, 1, "12"),
            new Fps(14, 1, "14"),
            new Fps(15, 1, "15"),
            new Fps(18, 1, "18"),
            new Fps(20, 1, "20"),
            new Fps(25, 1, "25"),
    };

    public static final Fit[] FITS = {
            new Fit(YuvScaler.Fit.STRETCH, "拉伸"),
            new Fit(YuvScaler.Fit.FIT, "适应"),
            new Fit(YuvScaler.Fit.CROP, "裁剪"),
    };

    public static final Quality[] QUALITIES = {
            new Quality(1.0, "标准"),
            new Quality(1.5, "省空间"),
            new Quality(2.2, "最小"),
    };

    /** Defaults: the verified, known-good combination. */
    public static final int DEFAULT_SIZE = VERIFIED_SIZE;
    public static final int DEFAULT_FPS = VERIFIED_FPS;
    public static final int DEFAULT_FIT = 0;
    public static final int DEFAULT_QUALITY = 0;

    public static boolean isVerifiedCombo(int sizeIndex, int fpsIndex) {
        return sizeIndex == VERIFIED_SIZE && fpsIndex == VERIFIED_FPS;
    }

    /** Rough bytes per frame for the size estimate, measured at 160x120 with the standard table. */
    public static int bytesPerFrameEstimate(Size size) {
        double pixels = (double) size.width * size.height;
        double at160x120 = 2800.0;
        return (int) Math.max(600, at160x120 * (pixels / (160.0 * 120.0)));
    }

    /**
     * Estimated output size. Audio is a fixed 4 bits per sample per frame, video scales with the
     * pixel count, and a coarser quantiser shrinks the video part (roughly with its square root,
     * because most of the bits are in the non-zero coefficients).
     */
    public static long estimateBytes(Size size, Fps fps, double quantScale, long durationUs) {
        double fpsValue = (double) fps.num / fps.den;
        long frames = Math.max(1, Math.round(durationUs / 1000000.0 * fpsValue));
        double video = bytesPerFrameEstimate(size) / Math.sqrt(Math.max(1.0, quantScale));
        int audioBlock = AmvConverter.audioBlockBytes(fpsValue);
        return frames * ((long) video + audioBlock);
    }
}
