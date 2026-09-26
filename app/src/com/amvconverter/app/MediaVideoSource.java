package com.amvconverter.app;

import android.content.Context;
import android.graphics.ImageFormat;
import android.graphics.Rect;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.Image;
import android.net.Uri;
import android.util.Log;

import com.amvconverter.core.FrameSource;
import com.amvconverter.core.YuvFrame;

import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * Decodes a video track with MediaCodec and hands frames to the encoder as YUV 4:2:0.
 *
 * <p>Decoding straight to YUV (never through a Bitmap) is what makes this fast, but the decisive
 * optimisation is elseless obvious: <b>frames that the output never uses are never copied</b>.
 * A 30 fps source converted to 15 fps throws away half its frames, and for 1080p each wasted copy
 * is several megabytes. The decoder therefore holds one decoded buffer back and only copies the
 * frame that the caller actually asked for.
 *
 * <p>A codec is chosen explicitly, preferring hardware decoders that can output a YUV colour
 * format into a ByteBuffer; the chosen name and the time spent decoding versus copying are
 * reported so a slow device can be diagnosed instead of guessed at.
 */
public final class MediaVideoSource implements FrameSource {

    private static final String TAG = "MediaVideoSource";
    private static final long DEQUEUE_TIMEOUT_US = 10000;
    private static final int MAX_IDLE_ROUNDS = 200;

    private final MediaExtractor extractor = new MediaExtractor();
    private MediaCodec codec;
    private final int width;
    private final int height;
    private final long durationUs;
    private final boolean expandRange;
    private final String codecName;

    private final MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();
    private boolean inputDone;
    private boolean outputDone;
    private long lastPts = -1;
    private int idleRounds;

    /** A decoded buffer that has been dequeued but whose pixels have not been copied out yet. */
    private int heldIndex = -1;
    private long heldPts;
    private boolean heldValid;

    /** The candidate frame currently being compared against the held one. */
    private int nextIndex = -1;
    private long nextPts;

    private byte[] scratch;

    // ---- instrumentation (reported at the end of a conversion) ----
    public long decodeNanos;
    public long copyNanos;
    public int framesDecoded;
    public int framesCopied;

    public MediaVideoSource(Context context, Uri uri, long startUs) throws IOException {
        extractor.setDataSource(context, uri, null);
        int track = -1;
        MediaFormat format = null;
        for (int i = 0; i < extractor.getTrackCount(); i++) {
            MediaFormat f = extractor.getTrackFormat(i);
            String mime = f.getString(MediaFormat.KEY_MIME);
            if (mime != null && mime.startsWith("video/")) {
                track = i;
                format = f;
                break;
            }
        }
        if (track < 0 || format == null) {
            extractor.release();
            throw new IOException("没有找到视频轨道");
        }
        extractor.selectTrack(track);

        this.width = format.getInteger(MediaFormat.KEY_WIDTH);
        this.height = format.getInteger(MediaFormat.KEY_HEIGHT);
        long dur = format.containsKey(MediaFormat.KEY_DURATION)
                ? format.getLong(MediaFormat.KEY_DURATION) : 0L;
        this.durationUs = dur > 0 ? dur : 0L;

        String mime = format.getString(MediaFormat.KEY_MIME);
        String chosen = chooseDecoder(mime);
        if (chosen == null) {
            extractor.release();
            throw new IOException("设备没有可用的 " + mime + " 解码器");
        }
        this.codecName = chosen;
        try {
            codec = MediaCodec.createByCodecName(chosen);
            codec.configure(format, null, null, 0);
            codec.start();
        } catch (Exception e) {
            release();
            throw new IOException("视频解码器初始化失败: " + e.getMessage());
        }
        Log.i(TAG, "decoder=" + chosen + " size=" + width + "x" + height);

        if (startUs > 0) {
            extractor.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC);
            codec.flush();
        }

        // Range: MediaCodec reports the colour range on some devices; default to video range,
        // which is what almost all H.264/HEVC content uses. Getting this wrong is the difference
        // between a correct picture and a washed-out one.
        boolean limited = true;
        try {
            if (format.containsKey(MediaFormat.KEY_COLOR_RANGE)) {
                int range = format.getInteger(MediaFormat.KEY_COLOR_RANGE);
                limited = range != MediaFormat.COLOR_RANGE_FULL;
            }
        } catch (Throwable ignored) {
        }
        this.expandRange = limited;
    }

    public String codecName() { return codecName; }

    @Override
    public boolean isFullRange() { return !expandRange; }

    @Override
    public int width() { return width; }

    @Override
    public int height() { return height; }

    public long durationUs() { return durationUs; }

    private static String chooseDecoder(String mime) {
        // Prefer an accelerator that can write YUV into a ByteBuffer; fall back to software.
        MediaCodecList list = new MediaCodecList(MediaCodecList.REGULAR_CODECS);
        String softwareFallback = null;
        for (MediaCodecInfo info : list.getCodecInfos()) {
            if (info.isEncoder()) continue;
            boolean supports = false;
            for (String type : info.getSupportedTypes()) {
                if (type.equalsIgnoreCase(mime)) { supports = true; break; }
            }
            if (!supports) continue;
            boolean yuvOk = false;
            try {
                MediaCodecInfo.CodecCapabilities caps = info.getCapabilitiesForType(mime);
                for (int fmt : caps.colorFormats) {
                    if (fmt == ImageFormat.YUV_420_888
                            || fmt == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible
                            || fmt == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar
                            || fmt == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar) {
                        yuvOk = true;
                        break;
                    }
                }
            } catch (Throwable ignored) {
            }
            String name = info.getName();
            boolean software = name.startsWith("OMX.google.") || name.startsWith("c2.android.");
            if (yuvOk) {
                if (!software) return name;
                if (softwareFallback == null) softwareFallback = name;
            } else if (software && softwareFallback == null) {
                softwareFallback = name;
            }
        }
        return softwareFallback;
    }

    @Override
    public long readFrameFor(YuvFrame dst, long targetUs) throws IOException {
        if (!heldValid) {
            if (!dequeueIntoNext()) return -1;
            promoteNextToHeld();
        }
        while (true) {
            if (!dequeueIntoNext()) {
                // No more input: what we are holding is the last frame there is.
                long p = copyHeldInto(dst);
                releaseHeld();
                return p;
            }
            if (nextPts <= targetUs) {
                // The newer frame is at least as good a match: drop the older one *without*
                // copying it. This is where a 30 fps source feeding a 15 fps output saves half
                // its work.
                releaseHeld();
                promoteNextToHeld();
            } else {
                long p = copyHeldInto(dst);
                releaseHeld();
                promoteNextToHeld();     // keep the newer frame for the following call
                return p;
            }
        }
    }

    private void promoteNextToHeld() {
        heldIndex = nextIndex;
        heldPts = nextPts;
        heldValid = true;
        nextIndex = -1;
    }

    /**
     * Dequeues the next decoded frame into the candidate slot (without copying pixels).
     *
     * @return false when the stream is finished
     */
    private boolean dequeueIntoNext() {
        long t0 = System.nanoTime();
        try {
            while (true) {
                if (outputDone) return false;
                feedInput();
                int outIndex;
                try {
                    outIndex = codec.dequeueOutputBuffer(bufferInfo, DEQUEUE_TIMEOUT_US);
                } catch (IllegalStateException e) {
                    outputDone = true;
                    return false;
                }
                if (outIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                    if (inputDone && ++idleRounds > MAX_IDLE_ROUNDS) {
                        outputDone = true;
                        return false;
                    }
                    if (!inputDone) idleRounds = 0;
                    continue;
                }
                if (outIndex < 0) continue;   // INFO_OUTPUT_FORMAT_CHANGED and friends
                idleRounds = 0;

                long pts = bufferInfo.presentationTimeUs;
                boolean eos = (bufferInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                boolean usable = bufferInfo.size > 0;
                if (eos) outputDone = true;
                if (!usable) {
                    codec.releaseOutputBuffer(outIndex, false);
                    if (outputDone) return false;
                    continue;
                }
                if (pts <= lastPts) pts = lastPts + 1;   // guard against non-monotonic pts
                lastPts = pts;
                nextIndex = outIndex;
                nextPts = pts;
                framesDecoded++;
                return true;
            }
        } finally {
            decodeNanos += System.nanoTime() - t0;
        }
    }

    private long copyHeldInto(YuvFrame dst) {
        long t0 = System.nanoTime();
        try {
            Image image = null;
            try {
                image = codec.getOutputImage(heldIndex);
            } catch (Throwable t) {
                Log.w(TAG, "getOutputImage failed", t);
            }
            if (image != null) {
                copyImage(image, dst);
                framesCopied++;
            }
            return heldPts;
        } finally {
            copyNanos += System.nanoTime() - t0;
        }
    }

    private void releaseHeld() {
        if (heldValid) {
            try {
                codec.releaseOutputBuffer(heldIndex, false);
            } catch (Throwable ignored) {
            }
            heldValid = false;
            heldIndex = -1;
        }
    }

    private void feedInput() {
        if (inputDone) return;
        int inIndex;
        try {
            inIndex = codec.dequeueInputBuffer(DEQUEUE_TIMEOUT_US);
        } catch (IllegalStateException e) {
            inputDone = true;
            return;
        }
        if (inIndex < 0) return;
        ByteBuffer buffer = codec.getInputBuffer(inIndex);
        if (buffer == null) return;
        int size;
        try {
            size = extractor.readSampleData(buffer, 0);
        } catch (Throwable t) {
            size = -1;
        }
        if (size < 0) {
            codec.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
            inputDone = true;
        } else {
            codec.queueInputBuffer(inIndex, 0, size, extractor.getSampleTime(), 0);
            extractor.advance();
        }
    }

    /**
     * Copies the cropped decoded frame into the destination.
     *
     * <p>Both paths read whole rows with a single bulk {@code get} and then work on a local array:
     * the semi-planar (NV12/NV21) case used to do one {@code ByteBuffer.get} per chroma sample,
     * which for 1080p meant well over a million calls per frame.
     *
     * <p>The range conversion is deliberately NOT done here: the source just declares whether it
     * is full range via {@link #isFullRange()} and the converter's scaler performs it exactly
     * once, so there is no way to double-expand.
     */
    private void copyImage(Image image, YuvFrame dst) {
        Rect crop = image.getCropRect();
        Image.Plane[] planes = image.getPlanes();
        int cw = (crop.width() + 1) / 2;
        int ch = (crop.height() + 1) / 2;

        copyPlane(planes[0], crop.left, crop.top, crop.width(), crop.height(),
                dst.y, dst.width, dst.height);
        copyPlane(planes[1], crop.left / 2, crop.top / 2, cw, ch,
                dst.u, dst.chromaWidth(), dst.chromaHeight());
        copyPlane(planes[2], crop.left / 2, crop.top / 2, cw, ch,
                dst.v, dst.chromaWidth(), dst.chromaHeight());
    }

    private void copyPlane(Image.Plane plane, int x0, int y0, int pw, int ph,
                           byte[] out, int outStride, int outHeight) {
        ByteBuffer buffer = plane.getBuffer();
        int rowStride = plane.getRowStride();
        int pixelStride = plane.getPixelStride();
        int copyW = Math.min(pw, outStride);
        int copyH = Math.min(ph, outHeight);
        int capacity = buffer.capacity();
        ByteBuffer dup = buffer.duplicate();

        if (pixelStride == 1) {
            for (int y = 0; y < copyH; y++) {
                int pos = (y0 + y) * rowStride + x0;
                if (pos + copyW > capacity) break;
                dup.position(pos);
                dup.get(out, y * outStride, copyW);
            }
        } else {
            int rowBytes = copyW * pixelStride;
            if (scratch == null || scratch.length < rowBytes) scratch = new byte[rowBytes];
            for (int y = 0; y < copyH; y++) {
                int pos = (y0 + y) * rowStride + x0 * pixelStride;
                if (pos + rowBytes > capacity) break;
                dup.position(pos);
                dup.get(scratch, 0, rowBytes);
                int d = y * outStride;
                for (int x = 0, s = 0; x < copyW; x++, s += pixelStride) {
                    out[d + x] = scratch[s];
                }
            }
        }
    }

    public void release() {
        heldValid = false;
        if (codec != null) {
            try {
                codec.stop();
            } catch (Throwable ignored) {
            }
            try {
                codec.release();
            } catch (Throwable ignored) {
            }
            codec = null;
        }
        try {
            extractor.release();
        } catch (Throwable ignored) {
        }
    }
}
