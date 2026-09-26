package com.amvconverter.app;

import android.util.Log;

import com.amvconverter.core.AdpcmImaAmvEncoder;
import com.amvconverter.core.AmvJpegEncoder;
import com.amvconverter.core.AmvMuxer;
import com.amvconverter.core.Sp5xTables;
import com.amvconverter.core.YuvFrame;
import com.amvconverter.core.YuvScaler;

import java.io.File;
import java.io.RandomAccessFile;

/**
 * Startup sanity check for the encoder core, run on the device.
 *
 * <p>The core is verified against FFmpeg on a desktop, but a device still has its own JVM, its own
 * byte order assumptions and its own file system, and a silent encoder failure is the worst
 * possible outcome for the user. This encodes a few synthetic frames into a real AMV file, checks
 * the bytes that a hardware player depends on, and logs the result. It costs a few milliseconds,
 * touches no UI, and turns "it doesn't work" into a precise log line.
 */
final class CoreSelfTest {

    private static final String TAG = "AmvSelfTest";

    private CoreSelfTest() {
    }

    static boolean run(File cacheDir) {
        File out = null;
        try {
            YuvFrame src = new YuvFrame(64, 48);
            // A gradient with a bright bar makes the flip and the pad rows detectable.
            for (int y = 0; y < src.height; y++) {
                for (int x = 0; x < src.width; x++) {
                    int v = 24 + y * 3;
                    if (y > 34 && y < 40) v = 240;
                    src.y[y * src.width + x] = (byte) v;
                }
            }
            java.util.Arrays.fill(src.u, (byte) 128);
            java.util.Arrays.fill(src.v, (byte) 128);

            YuvFrame target = new YuvFrame(160, 120);
            YuvScaler scaler = new YuvScaler();
            scaler.setExpandVideoRange(false);
            scaler.scale(src, target, YuvScaler.Fit.STRETCH);

            AmvJpegEncoder encoder = new AmvJpegEncoder(160, 120);
            int frameLen = encoder.encode(target);
            byte[] frame = encoder.frameBuffer();
            if (frame[0] != (byte) 0xFF || frame[1] != (byte) 0xD8
                    || frame[frameLen - 2] != (byte) 0xFF || frame[frameLen - 1] != (byte) 0xD9) {
                Log.e(TAG, "FAIL: frame is not wrapped in SOI/EOI");
                return false;
            }
            // A decodable JPEG can be rebuilt from the frame, exactly like a player does.
            byte[] jpeg = AmvJpegEncoder.wrapAsJpeg(frame, frameLen, 160, 120);
            if (jpeg.length <= frameLen) {
                Log.e(TAG, "FAIL: reconstructed JPEG is not larger than the frame");
                return false;
            }

            int samples = com.amvconverter.core.AmvConverter.samplesPerFrame(15.0);
            int blockBytes = AdpcmImaAmvEncoder.blockSize(samples);
            byte[] audio = new byte[blockBytes];
            short[] pcm = new short[samples];
            for (int i = 0; i < samples; i++) {
                pcm[i] = (short) (9000 * Math.sin(2 * Math.PI * 440 * i / 22050.0));
            }
            AdpcmImaAmvEncoder adpcm = new AdpcmImaAmvEncoder();
            if (adpcm.encodeBlock(pcm, 0, samples, audio, 0) != blockBytes) {
                Log.e(TAG, "FAIL: unexpected audio block size");
                return false;
            }

            out = new File(cacheDir, "selftest.amv");
            AmvMuxer muxer = new AmvMuxer(out, 160, 120, 15, 1, blockBytes);
            for (int i = 0; i < 3; i++) {
                muxer.writeVideo(frame, frameLen);
                muxer.writeAudio(audio, blockBytes);
            }
            muxer.finish(200000L);
            muxer.close();

            RandomAccessFile raf = new RandomAccessFile(out, "r");
            byte[] head = new byte[32];
            raf.readFully(head);
            raf.seek(out.length() - 8);
            byte[] tail = new byte[8];
            raf.readFully(tail);
            raf.close();

            boolean ok = tag(head, 0, "RIFF") && tag(head, 8, "AMV ")
                    && tag(head, 12, "LIST") && tag(head, 20, "hdrl")
                    && tag(head, 24, "amvh") && readLe32(head, 32) == 66667
                    && tag(tail, 0, "AMV_") && tag(tail, 4, "END_");
            if (!ok) {
                Log.e(TAG, "FAIL: container header/trailer mismatch");
                return false;
            }
            Log.i(TAG, "self test PASS: frame=" + frameLen + "B jpeg=" + jpeg.length
                    + "B audio=" + blockBytes + "B file=" + out.length() + "B quant0="
                    + Sp5xTables.QUANT_Y_ZIGZAG[0]);
            return true;
        } catch (Throwable t) {
            Log.e(TAG, "FAIL: " + t, t);
            return false;
        } finally {
            if (out != null) out.delete();
        }
    }

    private static boolean tag(byte[] b, int off, String s) {
        for (int i = 0; i < 4; i++) {
            if ((char) (b[off + i] & 0xFF) != s.charAt(i)) return false;
        }
        return true;
    }

    private static int readLe32(byte[] b, int off) {
        return (b[off] & 0xFF) | ((b[off + 1] & 0xFF) << 8)
                | ((b[off + 2] & 0xFF) << 16) | ((b[off + 3] & 0xFF) << 24);
    }
}
