package com.amvconverter.core;

/**
 * Conversion settings.
 *
 * <p>Note that AMV quality is not adjustable: the decoder owns the quantisation table.
 * {@link #quantScale} only coarsens the encoder beyond that fixed point, which shrinks the
 * file at the cost of picture quality. Only resolution and frame rate give a real quality/size
 * trade-off.
 */
public final class AmvOptions {

    public int width = 160;
    public int height = 120;

    /** Frame rate as a fraction, e.g. 15/1. */
    public int fpsNum = 15;
    public int fpsDen = 1;

    public YuvScaler.Fit fit = YuvScaler.Fit.FIT;

    /** 1.0 = the format's own quality point. */
    public double quantScale = 1.0;

    /** Trim range in microseconds; {@code endUs < 0} means "to the end". */
    public long startUs = 0;
    public long endUs = -1;

    public double fps() { return (double) fpsNum / fpsDen; }

    public String describe() {
        return width + "x" + height + " " + fpsNum + "/" + fpsDen + "fps " + fit;
    }
}
