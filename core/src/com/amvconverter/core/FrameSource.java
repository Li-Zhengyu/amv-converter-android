package com.amvconverter.core;

import java.io.IOException;

/**
 * Supplies the source picture, in display order (top row first) and full range.
 *
 * <p>The interface is pull-based <em>by target time</em> rather than "give me the next frame",
 * because that is what lets a decoder skip work: for a 30 fps source converted to 15 fps, half
 * the frames are never used, and a source that knows the target time can drop them without ever
 * copying their pixels out of the codec. Implementations decode with MediaCodec / GIF decoding on
 * Android, or read raw files in the desktop self-test.
 */
public interface FrameSource {
    int width();

    int height();

    /**
     * Makes available the frame that should be shown at {@code targetUs} - the most recent frame
     * whose presentation time is at or before it - and copies it into {@code dst}.
     *
     * @return that frame's presentation timestamp in microseconds, or -1 when the source is
     *         exhausted. {@code dst} must be left untouched when -1 is returned, so the caller
     *         can keep displaying the previous frame.
     */
    long readFrameFor(YuvFrame dst, long targetUs) throws IOException;

    /**
     * Whether the frames this source produces are already in full range.
     *
     * <p>Video decoders normally emit video range (Y 16..235), while AMV frames are JPEG scans
     * and therefore full range. Returning false here makes the converter expand the range exactly
     * once. Sources that build YUV from RGB (GIF, stills) are full range already.
     */
    default boolean isFullRange() {
        return false;
    }
}
