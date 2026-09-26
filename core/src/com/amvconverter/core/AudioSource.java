package com.amvconverter.core;

import java.io.IOException;

/**
 * Supplies the audio track already decoded, downmixed to mono and resampled to 22050 Hz,
 * which is the only rate AMV supports.
 */
public interface AudioSource {
    /** @return the whole track as 22050 Hz mono 16-bit samples, or an empty array if silent. */
    short[] readAll() throws IOException;
}
