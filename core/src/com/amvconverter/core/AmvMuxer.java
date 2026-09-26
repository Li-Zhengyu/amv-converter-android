package com.amvconverter.core;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;

/**
 * AMV container writer: a deliberately crippled AVI subset.
 *
 * <p>Everything here is dictated by what cheap players accept, not by the RIFF spec:
 * <ul>
 *   <li>form type is {@code "AMV "} (with the trailing space), not {@code "AVI "};</li>
 *   <li>the RIFF size and every LIST size is written as 0 - some players break if they are correct;</li>
 *   <li>the only metadata is the private {@code amvh} chunk; the stream headers are 56/36/48 zero bytes;</li>
 *   <li>no {@code idx1} index;</li>
 *   <li>leaf chunks are NOT padded to an even boundary (odd-sized frames are followed immediately
 *       by the next chunk);</li>
 *   <li>video and audio chunks strictly alternate 1:1;</li>
 *   <li>the file ends with the ASCII markers {@code AMV_} + {@code END_}.</li>
 * </ul>
 */
public final class AmvMuxer implements Closeable {

    private static final int DURATION_OFFSET = 32 + 52;
    private static final int SAMPLE_RATE = 22050;

    private final RandomAccessFile out;
    private final boolean ownsFile;
    private final int fpsNum;
    private final int fpsDen;
    private final int usPerFrame;
    private final int expectedAudioBytes;

    private long moviStart;
    private long framesVideo;
    private long framesAudio;
    private int lastStream = -1;      // 0 = video, 1 = audio, -1 = none
    private boolean finished;

    /**
     * @param fpsNum frames per second numerator (e.g. 15 for 15 fps)
     * @param fpsDen frames per second denominator (usually 1)
     * @param audioBytesPerBlock audio block size that every block must use
     */
    public AmvMuxer(File file, int width, int height, int fpsNum, int fpsDen,
                    int audioBytesPerBlock) throws IOException {
        if (width <= 0 || height <= 0) throw new IllegalArgumentException("bad size");
        if (fpsNum <= 0 || fpsDen <= 0) throw new IllegalArgumentException("bad fps");
        this.fpsNum = fpsNum;
        this.fpsDen = fpsDen;
        this.usPerFrame = (int) Math.round(1000000.0 * fpsDen / fpsNum);
        if (usPerFrame < 15873) throw new IllegalArgumentException("AMV refuses more than ~63 fps");
        this.expectedAudioBytes = audioBytesPerBlock;
        this.out = new RandomAccessFile(file, "rw");
        this.ownsFile = true;
        out.setLength(0);
        writeHeader(width, height);
    }

    private void writeHeader(int width, int height) throws IOException {
        // RIFF ---- AMV
        writeFourcc("RIFF");
        writeLe32(0);                 // deliberately 0
        writeFourcc("AMV ");

        // LIST hdrl
        writeFourcc("LIST");
        writeLe32(0);                 // deliberately 0
        writeFourcc("hdrl");

        // amvh
        writeFourcc("amvh");
        writeLe32(56);
        writeLe32(usPerFrame);        // +0
        for (int i = 0; i < 7; i++) writeLe32(0);   // +4 .. +31
        writeLe32(width);             // +32
        writeLe32(height);            // +36
        writeLe32(fpsNum);            // +40
        writeLe32(fpsDen);            // +44
        writeLe32(0);                 // +48
        writeLe32(0);                 // +52 duration, patched in finish()

        // video stream: strh 56 zeros, strf 36 zeros
        writeFourcc("LIST");
        writeLe32(0);
        writeFourcc("strl");
        writeFourcc("strh");
        writeLe32(56);
        writeZeros(56);
        writeFourcc("strf");
        writeLe32(36);
        writeZeros(36);

        // audio stream: strh 48 zeros, strf = deliberately "wrong" PCM WAVEFORMATEX
        writeFourcc("LIST");
        writeLe32(0);
        writeFourcc("strl");
        writeFourcc("strh");
        writeLe32(48);
        writeZeros(48);
        writeFourcc("strf");
        writeLe32(20);
        writeLe16(1);                 // wFormatTag = PCM (a lie, kept for compatibility)
        writeLe16(1);                 // channels
        writeLe32(SAMPLE_RATE);
        writeLe32(SAMPLE_RATE * 2);   // avg bytes per second
        writeLe16(2);                 // block align (also a lie)
        writeLe16(16);                // bits per sample
        writeLe16(0);                 // cbSize
        writeLe16(0);                 // pad to 20 bytes

        // LIST movi
        writeFourcc("LIST");
        writeLe32(0);
        writeFourcc("movi");
        moviStart = out.getFilePointer();
    }

    public long getVideoFrameCount() { return framesVideo; }
    public long getAudioBlockCount() { return framesAudio; }
    public long getBytesWritten() throws IOException { return out.getFilePointer(); }

    public void writeVideo(byte[] data, int len) throws IOException {
        if (finished) throw new IllegalStateException("already finished");
        if (lastStream == 0) throw new IllegalStateException("AMV requires strict V-A interleaving (got two video chunks)");
        writeFourcc("00dc");
        writeLe32(len);
        out.write(data, 0, len);
        framesVideo++;
        lastStream = 0;
    }

    public void writeAudio(byte[] data, int len) throws IOException {
        if (finished) throw new IllegalStateException("already finished");
        if (lastStream != 0) throw new IllegalStateException("AMV requires strict V-A interleaving (audio before video)");
        if (len != expectedAudioBytes) {
            // Players are documented to crash on variable audio block sizes.
            throw new IllegalStateException("audio block is " + len + " bytes, expected " + expectedAudioBytes);
        }
        writeFourcc("01wb");
        writeLe32(len);
        out.write(data, 0, len);
        framesAudio++;
        lastStream = 1;
    }

    /**
     * Writes the trailer and patches the duration field in {@code amvh}.
     *
     * @param durationUs total duration in microseconds
     */
    public void finish(long durationUs) throws IOException {
        if (finished) return;
        finished = true;
        // pad the movi list to an even boundary, then the RIFF (never both)
        if ((out.getFilePointer() & 1) != 0) out.write(0);
        writeFourcc("AMV_");
        writeFourcc("END_");

        long seconds = durationUs / 1000000L;
        int ss = (int) (seconds % 60);
        int mm = (int) ((seconds / 60) % 60);
        int hh = (int) (seconds / 3600);
        long pos = out.getFilePointer();
        out.seek(DURATION_OFFSET);
        out.write(ss & 0xFF);
        out.write(mm & 0xFF);
        writeLe16(hh & 0xFFFF);
        out.seek(pos);
    }

    private void writeFourcc(String tag) throws IOException {
        for (int i = 0; i < 4; i++) out.write(tag.charAt(i));
    }

    private void writeLe16(int v) throws IOException {
        out.write(v & 0xFF);
        out.write((v >> 8) & 0xFF);
    }

    private void writeLe32(int v) throws IOException {
        out.write(v & 0xFF);
        out.write((v >> 8) & 0xFF);
        out.write((v >> 16) & 0xFF);
        out.write((v >> 24) & 0xFF);
    }

    private void writeZeros(int n) throws IOException {
        for (int i = 0; i < n; i++) out.write(0);
    }

    @Override
    public void close() throws IOException {
        if (!finished) {
            try {
                finish(0);
            } catch (IOException ignored) {
                // best effort
            }
        }
        if (ownsFile) out.close();
    }
}
