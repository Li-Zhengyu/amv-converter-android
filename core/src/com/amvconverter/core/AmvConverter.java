package com.amvconverter.core;

import java.io.File;
import java.io.IOException;

/**
 * Drives a conversion: pulls source frames, scales them to the AMV frame size, encodes video and
 * audio, and interleaves them strictly 1:1 into an AMV file.
 *
 * <p>Frame timing follows the source timestamps: each output frame uses the most recent source
 * frame at or before its presentation time, so variable frame rate sources behave sensibly and a
 * short source simply repeats its last frame (which is what an AMV player expects).
 */
public final class AmvConverter {

    /** Audio sample rate is fixed by the format. */
    public static final int SAMPLE_RATE = 22050;

    public interface Listener {
        /** Called once per encoded frame; {@code bytes} is the file size so far. */
        void onFrame(int index, int totalFrames, byte[] amvFrame, int frameLength, long bytes);

        /** @return true to abort the conversion. */
        boolean isCancelled();
    }

    public static final class Result {
        public long videoFrames;
        public long audioBlocks;
        public long bytes;
        public long durationUs;
        public int audioBlockBytes;
    }

    private final AmvOptions options;
    private final FrameSource video;
    private final AudioSource audio;
    private final Listener listener;

    public AmvConverter(AmvOptions options, FrameSource video, AudioSource audio, Listener listener) {
        this.options = options;
        this.video = video;
        this.audio = audio;
        this.listener = listener;
    }

    /** Samples of audio that go with one video frame. */
    public static int samplesPerFrame(double fps) {
        return (int) Math.round(SAMPLE_RATE / fps);
    }

    public static int audioBlockBytes(double fps) {
        return AdpcmImaAmvEncoder.blockSize(samplesPerFrame(fps));
    }

    /**
     * Estimated output size in bytes, for showing the user up front.
     *
     * @param bytesPerFrame a typical encoded frame size, roughly 2000-3000 bytes for 160x120
     */
    public static long estimateBytes(double fps, long durationUs, int bytesPerFrame) {
        long frames = Math.max(1, Math.round(durationUs / 1000000.0 * fps));
        return frames * (bytesPerFrame + audioBlockBytes(fps));
    }

    public Result convert(File output, long durationUs) throws IOException {
        if (durationUs <= 0) throw new IOException("unknown duration");
        long startUs = Math.max(0, options.startUs);
        long endUs = options.endUs > 0 ? Math.min(options.endUs, durationUs) : durationUs;
        if (endUs <= startUs) throw new IOException("empty trim range");
        long spanUs = endUs - startUs;

        double fps = options.fps();
        int samplesPerFrame = samplesPerFrame(fps);
        int blockBytes = audioBlockBytes(fps);
        int totalFrames = (int) Math.max(1, Math.round(spanUs / 1000000.0 * fps));

        AmvJpegEncoder encoder = new AmvJpegEncoder(options.width, options.height);
        encoder.setQuantScale(options.quantScale);
        YuvScaler scaler = new YuvScaler();
        // Exactly one range conversion: sources that hand us video-range YUV (the usual case for
        // a video decoder) get expanded here; RGB-derived sources are full range already.
        scaler.setExpandVideoRange(!video.isFullRange());

        YuvFrame target = new YuvFrame(options.width, options.height);
        int srcW = video.width(), srcH = video.height();
        if (srcW <= 0 || srcH <= 0) throw new IOException("source has no picture");

        short[] pcm = audio != null ? audio.readAll() : new short[0];
        if (pcm == null) pcm = new short[0];

        AmvMuxer muxer = new AmvMuxer(output, options.width, options.height,
                options.fpsNum, options.fpsDen, blockBytes);
        byte[] audioBuf = new byte[blockBytes];
        short[] blockPcm = new short[samplesPerFrame];
        AdpcmImaAmvEncoder adpcm = new AdpcmImaAmvEncoder();
        Result result = new Result();
        result.audioBlockBytes = blockBytes;

        // One buffer is enough: the source writes the requested frame straight into it. (An
        // earlier version ping-ponged two buffers, which silently shifted the whole video one
        // frame late and left the first frame blank.)
        YuvFrame frame = new YuvFrame(srcW, srcH);
        boolean haveFrame = false;
        boolean exhausted = false;
        long usPerFrame = Math.round(1000000.0 * options.fpsDen / options.fpsNum);
        long audioStartSample = Math.round(startUs * SAMPLE_RATE / 1000000.0);
        scaler.prepare(srcW, srcH, options.width, options.height, options.fit);

        try {
            for (int i = 0; i < totalFrames; i++) {
                if (listener != null && listener.isCancelled()) throw new CancelledException();
                long targetUs = startUs + i * usPerFrame;

                if (!exhausted) {
                    long pts = video.readFrameFor(frame, targetUs);
                    if (pts < 0) exhausted = true;
                    else haveFrame = true;
                }
                if (!haveFrame) throw new IOException("source produced no frames");

                scaler.scale(frame, target);
                int len = encoder.encode(target);
                muxer.writeVideo(encoder.frameBuffer(), len);

                fillAudioBlock(pcm, audioStartSample + (long) i * samplesPerFrame, samplesPerFrame, blockPcm);
                int ab = adpcm.encodeBlock(blockPcm, 0, samplesPerFrame, audioBuf, 0);
                muxer.writeAudio(audioBuf, ab);

                if (listener != null) {
                    listener.onFrame(i, totalFrames, encoder.frameBuffer(), len, muxer.getBytesWritten());
                }
            }
            result.videoFrames = muxer.getVideoFrameCount();
            result.audioBlocks = muxer.getAudioBlockCount();
            result.durationUs = (long) (totalFrames * usPerFrame);
            muxer.finish(result.durationUs);
            result.bytes = muxer.getBytesWritten();
        } finally {
            muxer.close();
        }
        return result;
    }

    /** Slices the requested frame's worth of audio, padding with silence past the end. */
    private static void fillAudioBlock(short[] pcm, long start, int samplesPerFrame, short[] out) {
        for (int k = 0; k < samplesPerFrame; k++) {
            long idx = start + k;
            out[k] = (idx >= 0 && idx < pcm.length) ? pcm[(int) idx] : 0;
        }
    }

    /** Thrown when the listener asks to stop; the partial file is left for the caller to delete. */
    public static final class CancelledException extends IOException {
        public CancelledException() { super("cancelled"); }
    }
}
