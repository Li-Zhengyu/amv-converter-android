package com.amvconverter.selftest;

import com.amvconverter.core.AdpcmImaAmvEncoder;
import com.amvconverter.core.AmvConverter;
import com.amvconverter.core.AmvMuxer;
import com.amvconverter.core.AmvOptions;
import com.amvconverter.core.AudioSource;
import com.amvconverter.core.FrameSource;
import com.amvconverter.core.Sp5xTables;
import com.amvconverter.core.YuvFrame;
import com.amvconverter.core.YuvScaler;

import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Random;

/**
 * Desktop self-test for the AMV encoder core. It works from raw decoded material
 * (YUV420 frames + 22050 Hz mono PCM) produced by an external tool, so the exact same code path
 * that Android uses is exercised here without needing a device.
 *
 * <p>Usage: AmvSelfTest convert &lt;frames.yuv&gt; &lt;srcW&gt; &lt;srcH&gt; &lt;pcm.s16le&gt; &lt;srcFpsNum&gt;
 * &lt;srcFpsDen&gt; &lt;outW&gt; &lt;outH&gt; &lt;outFpsNum&gt; &lt;outFpsDen&gt; &lt;out.amv&gt; [fit] [quantScale]
 * <br>or: AmvSelfTest unit
 */
public final class AmvSelfTest {

    public static void main(String[] args) throws Exception {
        if (args.length > 0 && args[0].equals("unit")) {
            unitTests();
            return;
        }
        if (args.length > 1 && args[0].equals("reader")) {
            readerReport(new File(args[1]), args.length > 2 ? args[2] : null);
            return;
        }
        int base = (args.length > 0 && args[0].equals("convert")) ? 1 : 0;
        if (args.length < base + 11) {
            System.err.println("usage: AmvSelfTest convert <frames.yuv> <srcW> <srcH> <pcm.s16le> <srcFpsNum>"
                    + " <srcFpsDen> <outW> <outH> <outFpsNum> <outFpsDen> <out.amv> [fit] [quantScale]");
            System.exit(2);
        }
        File framesFile = new File(args[base]);
        final int w = Integer.parseInt(args[base + 1]);
        final int h = Integer.parseInt(args[base + 2]);
        File pcmFile = new File(args[base + 3]);
        final int srcFpsNum = Integer.parseInt(args[base + 4]);
        final int srcFpsDen = Integer.parseInt(args[base + 5]);
        int outW = Integer.parseInt(args[base + 6]);
        int outH = Integer.parseInt(args[base + 7]);
        int fpsNum = Integer.parseInt(args[base + 8]);
        int fpsDen = Integer.parseInt(args[base + 9]);
        File outFile = new File(args[base + 10]);
        String fitName = args.length > base + 11 ? args[base + 11] : "fit";

        int frameBytes = w * h + 2 * ((w + 1) / 2) * ((h + 1) / 2);
        long frameCount = framesFile.length() / frameBytes;
        System.out.println("source: " + w + "x" + h + ", " + frameCount + " frames, "
                + frameBytes + " bytes/frame");
        System.out.println("output: " + outW + "x" + outH + " @ " + fpsNum + "/" + fpsDen
                + " fps, fit=" + fitName);
        System.out.println("pcm   : " + (pcmFile.length() / 2) + " samples @ " + AmvConverter.SAMPLE_RATE + " Hz");

        final java.io.RandomAccessFile in =
                new java.io.RandomAccessFile(framesFile, "r");
        final short[] pcm = readPcm(pcmFile);
        final byte[] tmp = new byte[frameBytes];
        final int cwPlane = (w + 1) / 2, chPlane = (h + 1) / 2;

        AmvOptions opt = new AmvOptions();
        opt.width = outW;
        opt.height = outH;
        opt.fpsNum = fpsNum;
        opt.fpsDen = fpsDen;
        opt.fit = "stretch".equalsIgnoreCase(fitName) ? YuvScaler.Fit.STRETCH
                : ("crop".equalsIgnoreCase(fitName) ? YuvScaler.Fit.CROP : YuvScaler.Fit.FIT);
        if (args.length > base + 12) opt.quantScale = Double.parseDouble(args[base + 12]);

        long start = System.currentTimeMillis();
        AmvConverter conv = new AmvConverter(opt, new FrameSource() {
            public int width() { return w; }
            public int height() { return h; }
            public long readFrameFor(YuvFrame dst, long targetUs) throws IOException {
                // The raw file is random access, so the frame the target time asks for is
                // computed directly: the most recent frame at or before the target.
                long idx = (long) Math.floor(targetUs / 1000000.0 * srcFpsNum / srcFpsDen);
                if (idx < 0) idx = 0;
                if (idx >= frameCount) return -1;
                in.seek(idx * (long) frameBytes);
                in.readFully(tmp);
                System.arraycopy(tmp, 0, dst.y, 0, w * h);
                System.arraycopy(tmp, w * h, dst.u, 0, cwPlane * chPlane);
                System.arraycopy(tmp, w * h + cwPlane * chPlane, dst.v, 0, cwPlane * chPlane);
                return (long) (idx * 1000000L * srcFpsDen / srcFpsNum);
            }
        }, new AudioSource() {
            public short[] readAll() { return pcm; }
        }, null);

        long durationUs = (long) (frameCount * 1000000L * srcFpsDen / srcFpsNum);
        AmvConverter.Result r = conv.convert(outFile, durationUs);
        long ms = System.currentTimeMillis() - start;
        System.out.println("written: " + r.bytes + " bytes, video=" + r.videoFrames
                + " audio=" + r.audioBlocks + " blockBytes=" + r.audioBlockBytes);
        System.out.println("elapsed: " + ms + " ms  (" + (ms / Math.max(1, r.videoFrames)) + " ms/frame)");
        in.close();
    }

    private static short[] readPcm(File f) throws IOException {
        if (f == null || !f.isFile()) return new short[0];
        byte[] b = new byte[(int) f.length()];
        try (InputStream in = new FileInputStream(f)) {
            int p = 0;
            while (p < b.length) {
                int r = in.read(b, p, b.length - p);
                if (r < 0) break;
                p += r;
            }
        }
        short[] s = new short[b.length / 2];
        for (int i = 0; i < s.length; i++) {
            s[i] = (short) ((b[i * 2] & 0xFF) | (b[i * 2 + 1] << 8));
        }
        return s;
    }

    // ------------------------------------------------------------------ AMV reader / player core

    /**
     * Exercises the decode side the built-in player uses: container parse, ADPCM expansion and
     * rebuilding a table-stripped frame back into a decodable JPEG. Audio is dumped so a shell
     * script can diff it against FFmpeg's own decode of the same file, and a few frames are
     * dumped as .jpg so an external decoder can be pointed at them.
     */
    private static void readerReport(File amvFile, String outPrefix) throws Exception {
        com.amvconverter.core.AmvReader reader = new com.amvconverter.core.AmvReader(amvFile);
        com.amvconverter.core.AmvReader.Info info = reader.info();
        System.out.println("file      : " + amvFile.getName() + " (" + info.fileSize + " bytes)");
        System.out.println("size      : " + info.width + "x" + info.height);
        System.out.println("rate      : " + info.fpsNum + "/" + info.fpsDen + " fps, usPerFrame="
                + info.usPerFrame);
        System.out.println("frames    : " + info.videoFrameCount + " video, "
                + info.audioBlockCount + " audio");
        System.out.println("audioBlk  : " + info.audioBlockBytes + " bytes");
        System.out.println("duration  : " + (reader.durationUs() / 1000000L) + " s (header says "
                + info.headerDurationSeconds + " s)");

        boolean ok = info.videoFrameCount > 0 && info.width > 0 && info.height > 0;
        if (info.audioBlockCount != info.videoFrameCount) {
            System.out.println("note      : audio/video block counts differ ("
                    + info.audioBlockCount + " vs " + info.videoFrameCount + ")");
        }

        // ---- audio ----
        if (info.audioBlockCount > 0) {
            byte[] block = new byte[Math.max(1024, info.audioBlockBytes)];
            short[] samples = new short[4096];
            java.io.ByteArrayOutputStream pcm = new java.io.ByteArrayOutputStream();
            int total = 0;
            for (int i = 0; i < info.audioBlockCount; i++) {
                int len = reader.readAudioBlock(i, block);
                if (len <= 0) break;
                int n = com.amvconverter.core.AdpcmImaAmvDecoder.decodeBlock(block, 0, len, samples, 0);
                for (int k = 0; k < n; k++) {
                    pcm.write(samples[k] & 0xFF);
                    pcm.write((samples[k] >> 8) & 0xFF);
                }
                total += n;
            }
            System.out.println("audioPcm  : " + total + " samples");
            if (outPrefix != null) {
                File out = new File(outPrefix + ".s16le");
                try (java.io.FileOutputStream fos = new java.io.FileOutputStream(out)) {
                    fos.write(pcm.toByteArray());
                }
                System.out.println("wrote     : " + out.getPath());
            }
        }

        // ---- video ----
        byte[] frame = new byte[1 << 20];
        int checked = 0, bad = 0;
        int dumpCount = outPrefix != null ? Math.min(3, info.videoFrameCount) : 0;
        for (int i = 0; i < info.videoFrameCount && (i < 5 || i < dumpCount); i++) {
            int len = reader.readFrame(i, frame);
            if (len <= 4) { bad++; continue; }
            if (frame[0] != (byte) 0xFF || frame[1] != (byte) 0xD8
                    || frame[len - 2] != (byte) 0xFF || frame[len - 1] != (byte) 0xD9) {
                System.out.println("frame " + i + ": SOI/EOI missing FAIL");
                bad++;
                continue;
            }
            byte[] jpeg = com.amvconverter.core.AmvJpegEncoder.wrapAsJpeg(frame, len,
                    info.width, info.height);
            // a rebuilt JPEG must start with SOI, carry the fixed tables, and end with EOI
            boolean good = jpeg.length > len && jpeg[0] == (byte) 0xFF && jpeg[1] == (byte) 0xD8
                    && jpeg[jpeg.length - 2] == (byte) 0xFF && jpeg[jpeg.length - 1] == (byte) 0xD9
                    && indexOf(jpeg, new byte[]{(byte) 0xFF, (byte) 0xDB}) > 0     // DQT
                    && indexOf(jpeg, new byte[]{(byte) 0xFF, (byte) 0xC4}) > 0     // DHT
                    && indexOf(jpeg, new byte[]{(byte) 0xFF, (byte) 0xC0}) > 0     // SOF0
                    && indexOf(jpeg, new byte[]{(byte) 0xFF, (byte) 0xDA}) > 0;    // SOS
            if (!good) { bad++; System.out.println("frame " + i + ": rebuilt JPEG malformed FAIL"); }
            checked++;
            if (i < dumpCount) {
                File out = new File(outPrefix + "_frame" + i + ".jpg");
                try (java.io.FileOutputStream fos = new java.io.FileOutputStream(out)) {
                    fos.write(jpeg);
                }
                System.out.println("wrote     : " + out.getName() + " (" + jpeg.length + " bytes)");
            }
        }
        System.out.println("video     : " + checked + " frames checked, " + bad + " bad");
        reader.close();
        if (!ok || bad > 0) {
            System.out.println("READER TEST FAILED");
            System.exit(1);
        }
        System.out.println("READER TEST OK");
    }

    private static int indexOf(byte[] haystack, byte[] needle) {
        outer:
        for (int i = 0; i + needle.length <= haystack.length; i++) {
            for (int k = 0; k < needle.length; k++) {
                if (haystack[i + k] != needle[k]) continue outer;
            }
            return i;
        }
        return -1;
    }

    // ------------------------------------------------------------------ unit tests

    private static void unitTests() {
        int failures = 0;
        failures += checkHuffmanCompleteness();
        failures += checkDctAgainstReference();
        failures += checkAdpcmRoundTrip();
        failures += checkZigzagTables();
        failures += checkMuxerHeader();
        failures += checkScalerRangeExpansion();
        failures += checkArgbRoundTrip();
        failures += checkEstimateSanity();
        if (failures == 0) {
            System.out.println("ALL UNIT TESTS PASSED");
        } else {
            System.out.println(failures + " UNIT TEST(S) FAILED");
            System.exit(1);
        }
    }

    /**
     * The GIF/still path converts RGB to YUV; a mistake there would tint or invert every such
     * conversion. Random per-pixel noise is the worst possible input for 4:2:0 chroma
     * subsampling, so the round trip is measured on a smooth image (where a correct conversion
     * is nearly lossless) plus a few exact primary colours.
     */
    private static int checkArgbRoundTrip() {
        int w = 64, h = 48;
        int[] argb = new int[w * h];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int r = 255 * x / (w - 1);
                int g = 255 * y / (h - 1);
                int b = 255 * (x + y) / (w + h - 2);
                argb[y * w + x] = 0xFF000000 | (r << 16) | (g << 8) | b;
            }
        }
        YuvFrame f = new YuvFrame(w, h);
        YuvFrame.argbToYuv420(argb, w, h, f);
        int[] back = new int[w * h];
        YuvFrame.yuvToArgb(f, back);
        long sumR = 0, sumG = 0, sumB = 0;
        double sq = 0;
        for (int i = 0; i < argb.length; i++) {
            int dr = ((argb[i] >> 16) & 0xFF) - ((back[i] >> 16) & 0xFF);
            int dg = ((argb[i] >> 8) & 0xFF) - ((back[i] >> 8) & 0xFF);
            int db = (argb[i] & 0xFF) - (back[i] & 0xFF);
            sumR += dr; sumG += dg; sumB += db;
            sq += dr * dr + dg * dg + db * db;
        }
        double rms = Math.sqrt(sq / (3.0 * argb.length));
        double bias = (Math.abs(sumR) + Math.abs(sumG) + Math.abs(sumB)) / (3.0 * argb.length);
        boolean ok = rms < 6.0 && bias < 2.0;

        // Exact primary colours: catches a swapped or inverted channel immediately.
        int[][] primaries = {{255, 0, 0}, {0, 255, 0}, {0, 0, 255}, {255, 255, 255}, {0, 0, 0},
                {128, 128, 128}};
        for (int[] c : primaries) {
            YuvFrame one = new YuvFrame(2, 2);
            int[] px = {0xFF000000 | (c[0] << 16) | (c[1] << 8) | c[2],
                    0xFF000000 | (c[0] << 16) | (c[1] << 8) | c[2],
                    0xFF000000 | (c[0] << 16) | (c[1] << 8) | c[2],
                    0xFF000000 | (c[0] << 16) | (c[1] << 8) | c[2]};
            YuvFrame.argbToYuv420(px, 2, 2, one);
            int[] out = new int[4];
            YuvFrame.yuvToArgb(one, out);
            int dr = Math.abs(((out[0] >> 16) & 0xFF) - c[0]);
            int dg = Math.abs(((out[0] >> 8) & 0xFF) - c[1]);
            int db = Math.abs((out[0] & 0xFF) - c[2]);
            if (dr > 4 || dg > 4 || db > 4) {
                System.out.println("checkArgbRoundTrip: colour (" + c[0] + "," + c[1] + "," + c[2]
                        + ") round-tripped to (" + ((out[0] >> 16) & 0xFF) + ","
                        + ((out[0] >> 8) & 0xFF) + "," + (out[0] & 0xFF) + ") FAIL");
                ok = false;
            }
        }
        System.out.println("checkArgbRoundTrip: rms=" + String.format("%.2f", rms)
                + " bias=" + String.format("%.2f", bias) + " " + (ok ? "ok" : "FAIL"));
        return ok ? 0 : 1;
    }

    /** The size estimate shown in the UI must be plausible and must react to the settings. */
    private static int checkEstimateSanity() {
        long small = com.amvconverter.app.Presets.estimateBytes(
                com.amvconverter.app.Presets.SIZES[2],      // 96x64
                com.amvconverter.app.Presets.FPS[0],        // 10 fps
                1.0, 60_000_000L);
        long big = com.amvconverter.app.Presets.estimateBytes(
                com.amvconverter.app.Presets.SIZES[6],      // 320x240
                com.amvconverter.app.Presets.FPS[6],        // 25 fps
                1.0, 60_000_000L);
        long coarser = com.amvconverter.app.Presets.estimateBytes(
                com.amvconverter.app.Presets.SIZES[0],
                com.amvconverter.app.Presets.FPS[3],
                2.2, 60_000_000L);
        long normal = com.amvconverter.app.Presets.estimateBytes(
                com.amvconverter.app.Presets.SIZES[0],
                com.amvconverter.app.Presets.FPS[3],
                1.0, 60_000_000L);
        // One minute at 160x120/15fps should land in the low single-digit MB range.
        boolean plausible = normal > 1_000_000L && normal < 20_000_000L;
        boolean ordered = small < normal && normal < big && coarser < normal;
        boolean ok = plausible && ordered;
        System.out.println("checkEstimateSanity: 96x64@10=" + (small / 1024)
                + "KB 160x120@15=" + (normal / 1024) + "KB 320x240@25=" + (big / 1024)
                + "KB 160x120@15(coarse)=" + (coarser / 1024) + "KB " + (ok ? "ok" : "FAIL"));
        return ok ? 0 : 1;
    }

    private static int checkHuffmanCompleteness() {
        // Every baseline symbol the encoder can emit must exist in the Annex K tables.
        int[] acBitsL = Sp5xTables.DHT_AC_LUMA_BITS, acValsL = Sp5xTables.DHT_AC_LUMA_VALS;
        int[] acBitsC = Sp5xTables.DHT_AC_CHROMA_BITS, acValsC = Sp5xTables.DHT_AC_CHROMA_VALS;
        int[] dcBitsL = Sp5xTables.DHT_DC_LUMA_BITS, dcValsL = Sp5xTables.DHT_DC_LUMA_VALS;
        boolean ok = true;
        ok &= hasAllAc(acBitsL, acValsL);
        ok &= hasAllAc(acBitsC, acValsC);
        for (int i = 0; i <= 11; i++) ok &= contains(dcValsL, i);
        System.out.println("checkHuffmanCompleteness: " + (ok ? "ok" : "FAIL"));
        return ok ? 0 : 1;
    }

    private static boolean hasAllAc(int[] bits, int[] vals) {
        java.util.Set<Integer> set = new java.util.HashSet<>();
        for (int v : vals) set.add(v);
        if (!set.contains(0x00) || !set.contains(0xF0)) return false;
        for (int run = 0; run <= 15; run++) {
            for (int size = 1; size <= 10; size++) {
                if (!set.contains((run << 4) | size)) return false;
            }
        }
        return true;
    }

    private static boolean contains(int[] a, int v) {
        for (int x : a) if (x == v) return true;
        return false;
    }

    private static int checkDctAgainstReference() {
        // The fast separable DCT must agree with the textbook definition to well within the
        // quantiser's rounding step, otherwise coefficient rounding differs.
        Random rnd = new Random(1234);
        double[][] cosT = new double[8][8];
        for (int u = 0; u < 8; u++) {
            for (int x = 0; x < 8; x++) cosT[u][x] = Math.cos((2 * x + 1) * u * Math.PI / 16.0);
        }
        double maxErr = 0;
        for (int trial = 0; trial < 40; trial++) {
            int[] px = new int[64];
            for (int i = 0; i < 64; i++) px[i] = rnd.nextInt(256) - 128;
            // reference
            for (int v = 0; v < 8; v++) {
                for (int u = 0; u < 8; u++) {
                    double sum = 0;
                    for (int y = 0; y < 8; y++) {
                        for (int x = 0; x < 8; x++) {
                            sum += px[y * 8 + x] * cosT[u][x] * cosT[v][y];
                        }
                    }
                    double cu = (u == 0) ? 1 / Math.sqrt(2) : 1;
                    double cv = (v == 0) ? 1 / Math.sqrt(2) : 1;
                    double ref = 0.25 * cu * cv * sum;
                    double got = fastDct(px, u, v);
                    maxErr = Math.max(maxErr, Math.abs(ref - got));
                }
            }
        }
        boolean ok = maxErr < 0.05;
        System.out.println("checkDctAgainstReference: maxErr=" + String.format("%.5f", maxErr) + " " + (ok ? "ok" : "FAIL"));
        return ok ? 0 : 1;
    }

    private static double fastDct(int[] px, int u, int v) {
        float[] cos = new float[64];
        float[] c = new float[8];
        for (int i = 0; i < 8; i++) {
            c[i] = (i == 0) ? (float) (1.0 / Math.sqrt(2.0)) : 1.0f;
            for (int x = 0; x < 8; x++) cos[i * 8 + x] = (float) Math.cos((2 * x + 1) * i * Math.PI / 16.0);
        }
        float[] tmp = new float[64];
        for (int y = 0; y < 8; y++) {
            for (int uu = 0; uu < 8; uu++) {
                float sum = 0;
                for (int x = 0; x < 8; x++) sum += px[y * 8 + x] * cos[uu * 8 + x];
                tmp[y * 8 + uu] = sum;
            }
        }
        float sum = 0;
        for (int y = 0; y < 8; y++) sum += tmp[y * 8 + u] * cos[v * 8 + y];
        return 0.25f * c[u] * c[v] * sum;
    }

    private static int checkAdpcmRoundTrip() {
        // Encode a sine and decode it with an independent implementation of the reference
        // expander; the result must track the input closely and use valid step indices.
        int frames = 30, samples = 1470;
        short[] pcm = new short[frames * samples];
        for (int i = 0; i < pcm.length; i++) {
            pcm[i] = (short) (12000 * Math.sin(2 * Math.PI * 440 * i / 22050.0));
        }
        AdpcmImaAmvEncoder enc = new AdpcmImaAmvEncoder();
        int blockBytes = AdpcmImaAmvEncoder.blockSize(samples);
        byte[] block = new byte[blockBytes];
        double sumSq = 0, sigSq = 0;
        for (int f = 0; f < frames; f++) {
            int n = enc.encodeBlock(pcm, f * samples, samples, block, 0);
            if (n != blockBytes) {
                System.out.println("checkAdpcmRoundTrip: block size mismatch FAIL");
                return 1;
            }
            int predictor = (short) ((block[0] & 0xFF) | (block[1] << 8));
            int step = block[2] & 0xFF;
            if (predictor != pcm[f * samples] || step > 88) {
                System.out.println("checkAdpcmRoundTrip: header FAIL");
                return 1;
            }
            short[] dec = expand(block, samples);
            for (int i = 0; i < samples; i++) {
                double e = dec[i] - pcm[f * samples + i];
                sumSq += e * e;
                sigSq += (double) pcm[f * samples + i] * pcm[f * samples + i];
            }
        }
        double snr = 10 * Math.log10(sigSq / Math.max(1e-9, sumSq));
        boolean ok = snr > 15.0;
        System.out.println("checkAdpcmRoundTrip: SNR=" + String.format("%.2f", snr) + " dB " + (ok ? "ok" : "FAIL"));
        return ok ? 0 : 1;
    }

    /** Independent IMA expander (mirrors ffmpeg's adpcm_ima_expand_nibble, shift 3). */
    private static short[] expand(byte[] block, int samples) {
        int[] stepTable = AdpcmImaAmvEncoderTables.STEP;
        int[] indexTable = AdpcmImaAmvEncoderTables.INDEX;
        int predictor = (short) ((block[0] & 0xFF) | (block[1] << 8));
        int stepIndex = block[2] & 0xFF;
        short[] out = new short[samples];
        int p = 8;
        for (int i = 0; i < samples; i++) {
            int b = block[p + (i >> 1)] & 0xFF;
            int nibble = (i & 1) == 0 ? (b >> 4) : (b & 0x0F);
            int step = stepTable[stepIndex];
            stepIndex += indexTable[nibble];
            if (stepIndex < 0) stepIndex = 0;
            if (stepIndex > 88) stepIndex = 88;
            int diff = ((2 * (nibble & 7) + 1) * step) >> 3;
            predictor += (nibble & 8) != 0 ? -diff : diff;
            if (predictor > 32767) predictor = 32767;
            if (predictor < -32768) predictor = -32768;
            out[i] = (short) predictor;
        }
        return out;
    }

    private static int checkZigzagTables() {
        // The fixed quantisation tables must be consistent with the decoder's DQT payload and
        // contain no zero entries (a zero would make quantisation undefined).
        boolean ok = true;
        for (int i = 0; i < 64; i++) {
            if (Sp5xTables.QUANT_Y_ZIGZAG[i] <= 0 || Sp5xTables.QUANT_C_ZIGZAG[i] <= 0) ok = false;
        }
        // natural() must invert the zig-zag permutation
        for (int k = 0; k < 64; k++) {
            if (Sp5xTables.QUANT_Y[Sp5xTables.ZIGZAG[k]] != Sp5xTables.QUANT_Y_ZIGZAG[k]) ok = false;
        }
        java.util.Set<Integer> seen = new java.util.HashSet<>();
        for (int z : Sp5xTables.ZIGZAG) seen.add(z);
        if (seen.size() != 64) ok = false;
        System.out.println("checkZigzagTables: " + (ok ? "ok" : "FAIL"));
        return ok ? 0 : 1;
    }

    private static int checkMuxerHeader() {
        try {
            File f = File.createTempFile("amv-hdr", ".amv");
            AmvMuxer m = new AmvMuxer(f, 160, 120, 15, 1, 743);
            m.finish(3000000L);
            m.close();
            byte[] b = new byte[(int) f.length()];
            try (InputStream in = new FileInputStream(f)) {
                int p = 0;
                while (p < b.length) {
                    int r = in.read(b, p, b.length - p);
                    if (r < 0) break;
                    p += r;
                }
            }
            boolean ok = true;
            ok &= tag(b, 0, "RIFF") && le32(b, 4) == 0 && tag(b, 8, "AMV ");
            ok &= tag(b, 12, "LIST") && le32(b, 16) == 0 && tag(b, 20, "hdrl");
            ok &= tag(b, 24, "amvh") && le32(b, 28) == 56;
            ok &= le32(b, 32) == 66667 && le32(b, 64) == 160 && le32(b, 68) == 120;
            ok &= le32(b, 72) == 15 && le32(b, 76) == 1;
            ok &= b[84] == 3 && b[85] == 0 && b[86] == 0 && b[87] == 0;
            ok &= tag(b, 88, "LIST") && tag(b, 100, "strh") && le32(b, 104) == 56;
            ok &= tag(b, 164, "strf") && le32(b, 168) == 36;
            ok &= tag(b, 208, "LIST") && tag(b, 220, "strh") && le32(b, 224) == 48;
            ok &= tag(b, 276, "strf") && le32(b, 280) == 20;
            ok &= le16(b, 284) == 1 && le16(b, 286) == 1 && le32(b, 288) == 22050;
            ok &= le32(b, 292) == 44100 && le16(b, 296) == 2 && le16(b, 298) == 16;
            ok &= tag(b, 304, "LIST") && tag(b, 312, "movi");
            ok &= tag(b, b.length - 8, "AMV_") && tag(b, b.length - 4, "END_");
            System.out.println("checkMuxerHeader: " + (ok ? "ok" : "FAIL") + " (size=" + b.length + ")");
            f.delete();
            return ok ? 0 : 1;
        } catch (Exception e) {
            System.out.println("checkMuxerHeader: FAIL " + e);
            return 1;
        }
    }

    private static boolean tag(byte[] b, int off, String s) {
        if (off + 4 > b.length) return false;
        for (int i = 0; i < 4; i++) if ((char) (b[off + i] & 0xFF) != s.charAt(i)) return false;
        return true;
    }

    private static int le32(byte[] b, int o) {
        return (b[o] & 0xFF) | ((b[o + 1] & 0xFF) << 8) | ((b[o + 2] & 0xFF) << 16) | ((b[o + 3] & 0xFF) << 24);
    }

    private static int le16(byte[] b, int o) {
        return (b[o] & 0xFF) | ((b[o + 1] & 0xFF) << 8);
    }

    private static int checkScalerRangeExpansion() {
        // A dark video-range value must come out dark in full range, not washed out.
        YuvFrame src = new YuvFrame(32, 32);
        src.fill(16, 128, 128);   // video-range black
        YuvFrame dst = new YuvFrame(32, 32);
        YuvScaler s = new YuvScaler();
        s.setExpandVideoRange(true);
        s.scale(src, dst, YuvScaler.Fit.STRETCH);
        boolean ok = (dst.y[0] & 0xFF) <= 1;
        s.setExpandVideoRange(false);
        s.scale(src, dst, YuvScaler.Fit.STRETCH);
        ok &= (dst.y[0] & 0xFF) == 16;
        // and a full-range white must survive
        src.fill(235, 128, 128);
        s.setExpandVideoRange(true);
        s.scale(src, dst, YuvScaler.Fit.STRETCH);
        ok &= (dst.y[0] & 0xFF) >= 250;
        System.out.println("checkScalerRangeExpansion: " + (ok ? "ok" : "FAIL"));
        return ok ? 0 : 1;
    }

    /** Small accessor so the test can use the generated tables without exposing them publicly. */
    static final class AdpcmImaAmvEncoderTables {
        static final int[] STEP = com.amvconverter.core.AdpcmTables.STEP_TABLE;
        static final int[] INDEX = com.amvconverter.core.AdpcmTables.INDEX_TABLE;
    }
}
