package com.amvconverter.core;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;

/**
 * Reads AMV files: the container side of the built-in player.
 *
 * <p>The layout is the deliberately odd one described in {@code docs/AMV-format-spec.md}: form
 * type {@code "AMV "}, a private {@code amvh} header carrying the resolution and frame rate, every
 * RIFF/LIST size written as zero (so the file must be walked, not sized), chunks that are not
 * padded to an even boundary, and the ASCII trailer {@code AMV_END_}.
 *
 * <p>Because the sizes are useless, the {@code movi} list is scanned once on open and the offsets
 * of the video and audio chunks are kept in two compact arrays. That single pass buys O(1) random
 * access to any frame, which is what makes a seek bar possible.
 */
public final class AmvReader implements Closeable {

    private static final int MAX_CHUNKS = 2_000_000;

    public static final class Info {
        public int width;
        public int height;
        public int fpsNum = 15;
        public int fpsDen = 1;
        public long usPerFrame = 66667;
        /** Duration as stored in the header (0 when the file does not carry one). */
        public long headerDurationSeconds;
        public int videoFrameCount;
        public int audioBlockCount;
        public long fileSize;
        /** Bytes per audio block, or 0 when the blocks vary. */
        public int audioBlockBytes;
    }

    private final RandomAccessFile file;
    private final Info info = new Info();
    private long[] videoOffsets = new long[0];
    private int[] videoSizes = new int[0];
    private long[] audioOffsets = new long[0];
    private int[] audioSizes = new int[0];

    public AmvReader(File f) throws IOException {
        file = new RandomAccessFile(f, "r");
        info.fileSize = file.length();
        try {
            readHeader();
            buildIndex();
        } catch (IOException e) {
            file.close();
            throw e;
        }
    }

    public Info info() { return info; }

    public int frameCount() { return info.videoFrameCount; }

    public int audioBlockCount() { return info.audioBlockCount; }

    public long durationUs() {
        long fromHeader = info.headerDurationSeconds * 1_000_000L;
        long fromFrames = (long) info.videoFrameCount * info.usPerFrame;
        return fromFrames > 0 ? fromFrames : fromHeader;
    }

    // ------------------------------------------------------------------ header

    private void readHeader() throws IOException {
        if (file.length() < 320) throw new IOException("文件太小，不是 AMV");
        byte[] head = new byte[320];
        file.seek(0);
        file.readFully(head);
        if (!tag(head, 0, "RIFF")) throw new IOException("不是 RIFF 文件");
        if (!tag(head, 8, "AMV ")) throw new IOException("不是 AMV 文件（form type 不是 \"AMV \"）");
        if (!tag(head, 24, "amvh")) throw new IOException("缺少 amvh 头");

        info.usPerFrame = le32(head, 32);
        info.width = le32(head, 64);
        info.height = le32(head, 68);
        info.fpsNum = le32(head, 72);
        info.fpsDen = le32(head, 76);
        if (info.usPerFrame <= 0) info.usPerFrame = 66667;
        if (info.fpsNum <= 0 || info.fpsDen <= 0 || info.fpsNum > 100000) {
            info.fpsNum = (int) Math.round(1000000.0 / info.usPerFrame);
            info.fpsDen = 1;
        }
        int ss = head[84] & 0xFF, mm = head[85] & 0xFF;
        int hh = (head[86] & 0xFF) | ((head[87] & 0xFF) << 8);
        info.headerDurationSeconds = ss + 60L * mm + 3600L * hh;
        if (info.width <= 0 || info.height <= 0 || info.width > 4096 || info.height > 4096) {
            throw new IOException("AMV 分辨率异常: " + info.width + "x" + info.height);
        }
    }

    // ------------------------------------------------------------------ index

    private void buildIndex() throws IOException {
        long moviAt = findMovi();
        if (moviAt < 0) throw new IOException("找不到 movi 数据区");

        long[] vOff = new long[1024];
        int[] vSize = new int[1024];
        long[] aOff = new long[1024];
        int[] aSize = new int[1024];
        int vn = 0, an = 0;

        long pos = moviAt + 12;   // "LIST"(4) + size(4) + "movi"(4)
        long len = file.length();
        byte[] tagBuf = new byte[8];
        int scanned = 0;
        while (pos + 8 <= len && scanned < MAX_CHUNKS) {
            file.seek(pos);
            file.readFully(tagBuf);
            String id = new String(tagBuf, 0, 4, StandardCharsets.ISO_8859_1);
            int size = le32(tagBuf, 4);
            if (size < 0 || pos + 8 + size > len) break;
            if (id.equals("00dc")) {
                if (vn == vOff.length) {
                    vOff = grow(vOff); vSize = grow(vSize);
                }
                vOff[vn] = pos + 8;
                vSize[vn] = size;
                vn++;
            } else if (id.equals("01wb")) {
                if (an == aOff.length) {
                    aOff = grow(aOff); aSize = grow(aSize);
                }
                aOff[an] = pos + 8;
                aSize[an] = size;
                an++;
                if (info.audioBlockBytes == 0) info.audioBlockBytes = size;
                else if (info.audioBlockBytes != size) info.audioBlockBytes = -1;
            } else if (vn == 0 && an == 0) {
                pos += 8 + size;      // still walking the header region
                continue;
            } else {
                break;                // trailer reached
            }
            pos += 8 + size;          // NOTE: AMV chunks are NOT padded to an even boundary
            scanned++;
        }

        if (vn == 0) throw new IOException("AMV 里没有视频帧");
        if (info.audioBlockBytes < 0) info.audioBlockBytes = 0;
        videoOffsets = java.util.Arrays.copyOf(vOff, vn);
        videoSizes = java.util.Arrays.copyOf(vSize, vn);
        audioOffsets = java.util.Arrays.copyOf(aOff, an);
        audioSizes = java.util.Arrays.copyOf(aSize, an);
        info.videoFrameCount = vn;
        info.audioBlockCount = an;
    }

    private static long[] grow(long[] a) {
        long[] b = new long[a.length * 2];
        System.arraycopy(a, 0, b, 0, a.length);
        return b;
    }

    private static int[] grow(int[] a) {
        int[] b = new int[a.length * 2];
        System.arraycopy(a, 0, b, 0, a.length);
        return b;
    }

    private long findMovi() throws IOException {
        long pos = 12;
        long len = file.length();
        byte[] buf = new byte[16];
        long limit = Math.min(len, 16384);
        while (pos + 12 <= limit) {
            file.seek(pos);
            file.readFully(buf, 0, 12);
            String id = new String(buf, 0, 4, StandardCharsets.ISO_8859_1);
            if (id.equals("LIST")) {
                String type = new String(buf, 8, 4, StandardCharsets.ISO_8859_1);
                if (type.equals("movi")) return pos;
                pos += 12;
                continue;
            }
            if (id.equals("amvh")) {
                pos += 8 + le32(buf, 4);
                continue;
            }
            int size = le32(buf, 4);
            if (size < 0 || size > len) return -1;
            pos += 8 + size;
        }
        return -1;
    }

    // ------------------------------------------------------------------ access

    /** Reads video frame {@code index} into {@code out}; returns its length, or 0 on failure. */
    public int readFrame(int index, byte[] out) throws IOException {
        if (index < 0 || index >= videoOffsets.length) return 0;
        int size = videoSizes[index];
        if (size > out.length) return 0;
        file.seek(videoOffsets[index]);
        file.readFully(out, 0, size);
        return size;
    }

    /** Reads audio block {@code index} into {@code out}; returns its length, or 0 on failure. */
    public int readAudioBlock(int index, byte[] out) throws IOException {
        if (index < 0 || index >= audioOffsets.length) return 0;
        int size = audioSizes[index];
        if (size > out.length) return 0;
        file.seek(audioOffsets[index]);
        file.readFully(out, 0, size);
        return size;
    }

    @Override
    public void close() throws IOException {
        file.close();
    }

    private static boolean tag(byte[] b, int off, String s) {
        for (int i = 0; i < 4; i++) {
            if ((char) (b[off + i] & 0xFF) != s.charAt(i)) return false;
        }
        return true;
    }

    private static int le32(byte[] b, int off) {
        return (b[off] & 0xFF) | ((b[off + 1] & 0xFF) << 8)
                | ((b[off + 2] & 0xFF) << 16) | ((b[off + 3] & 0xFF) << 24);
    }
}
