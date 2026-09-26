package com.amvconverter.app;

import android.content.Context;
import android.media.AudioFormat;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.net.Uri;
import android.util.Log;

import com.amvconverter.core.AmvConverter;
import com.amvconverter.core.AudioSource;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.ShortBuffer;

/**
 * Decodes the audio track to 22050 Hz mono - the only rate and channel count AMV supports.
 *
 * <p>The whole track is decoded up front because AMV needs exactly one audio block per video
 * frame; having the samples ready makes that slicing trivial and keeps the main loop simple.
 * Resampling is a streaming linear interpolation with one sample of history, so no intermediate
 * buffer at the source rate is ever allocated. At 22050 Hz mono a five minute track is about
 * 13 MB.
 */
public final class MediaAudioSource implements AudioSource {

    private static final String TAG = "MediaAudioSource";
    private static final long TIMEOUT_US = 10000;
    /** Safety cap so a corrupt or endless file cannot exhaust memory (30 minutes). */
    private static final int MAX_SAMPLES = AmvConverter.SAMPLE_RATE * 60 * 30;

    private final MediaExtractor extractor = new MediaExtractor();
    private MediaCodec codec;
    private final int sourceRate;
    private final int channels;
    private final boolean audioPresent;

    private short[] pcm = new short[0];

    public MediaAudioSource(Context context, Uri uri, long startUs) {
        int track = -1;
        MediaFormat format = null;
        try {
            extractor.setDataSource(context, uri, null);
            for (int i = 0; i < extractor.getTrackCount(); i++) {
                MediaFormat f = extractor.getTrackFormat(i);
                String mime = f.getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("audio/")) {
                    track = i;
                    format = f;
                    break;
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "no audio track", t);
        }
        if (track < 0 || format == null) {
            audioPresent = false;
            sourceRate = AmvConverter.SAMPLE_RATE;
            channels = 1;
            try {
                extractor.release();
            } catch (Throwable ignored) {
            }
            return;
        }
        extractor.selectTrack(track);
        int rate = format.containsKey(MediaFormat.KEY_SAMPLE_RATE)
                ? format.getInteger(MediaFormat.KEY_SAMPLE_RATE) : AmvConverter.SAMPLE_RATE;
        int ch = format.containsKey(MediaFormat.KEY_CHANNEL_COUNT)
                ? format.getInteger(MediaFormat.KEY_CHANNEL_COUNT) : 1;
        this.sourceRate = rate > 0 ? rate : AmvConverter.SAMPLE_RATE;
        this.channels = ch > 0 ? ch : 1;
        boolean ok = true;
        try {
            String mime = format.getString(MediaFormat.KEY_MIME);
            codec = MediaCodec.createDecoderByType(mime);
            codec.configure(format, null, null, 0);
            codec.start();
        } catch (Throwable t) {
            Log.w(TAG, "audio decoder init failed, treating as silent", t);
            if (codec != null) {
                try {
                    codec.release();
                } catch (Throwable ignored) {
                }
                codec = null;
            }
            ok = false;
        }
        this.audioPresent = ok;
        if (startUs > 0) {
            try {
                extractor.seekTo(startUs, MediaExtractor.SEEK_TO_CLOSEST_SYNC);
                if (codec != null) codec.flush();
            } catch (Throwable ignored) {
            }
        }
    }

    public boolean hasAudio() { return audioPresent && codec != null; }

    @Override
    public short[] readAll() throws IOException {
        if (!hasAudio() || pcm.length > 0) return pcm;

        short[] acc = new short[Math.max(1 << 16, AmvConverter.SAMPLE_RATE * 8)];
        int accLen = 0;

        final double step = (double) sourceRate / AmvConverter.SAMPLE_RATE;
        double pos = 0.0;            // position of the next output sample, in source samples
        long srcIndex = -1;          // index of the most recent source sample handed to us
        int prev = 0, cur = 0;
        boolean havePrev = false;

        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        boolean inputDone = false, outputDone = false, outputIsFloat = false;
        short[] frame = new short[8192];
        int idleRounds = 0;

        while (!outputDone && accLen < MAX_SAMPLES) {
            if (!inputDone) {
                int inIndex = codec.dequeueInputBuffer(TIMEOUT_US);
                if (inIndex >= 0) {
                    ByteBuffer buf = codec.getInputBuffer(inIndex);
                    int size = buf == null ? -1 : extractor.readSampleData(buf, 0);
                    if (size < 0) {
                        codec.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                        inputDone = true;
                    } else {
                        codec.queueInputBuffer(inIndex, 0, size, extractor.getSampleTime(), 0);
                        extractor.advance();
                    }
                }
            }

            int outIndex = codec.dequeueOutputBuffer(info, TIMEOUT_US);
            if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                try {
                    MediaFormat of = codec.getOutputFormat();
                    if (of.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                        outputIsFloat = of.getInteger(MediaFormat.KEY_PCM_ENCODING)
                                == AudioFormat.ENCODING_PCM_FLOAT;
                    }
                } catch (Throwable ignored) {
                }
                continue;
            }
            if (outIndex < 0) {
                // Do not spin forever on a decoder that never reports end-of-stream.
                if (inputDone && ++idleRounds > 200) break;
                if (!inputDone) idleRounds = 0;
                continue;
            }
            idleRounds = 0;

            if (info.size > 0) {
                int samples;
                if (outputIsFloat) {
                    samples = decodeFloat(codec.getOutputBuffer(outIndex), info, frame);
                } else {
                    samples = decodeShort(codec.getOutputBuffer(outIndex), info, frame);
                }
                if (samples > frame.length) samples = frame.length;
                int frames2 = channels > 0 ? samples / channels : 0;
                for (int s = 0; s < frames2; s++) {
                    int mixed;
                    if (channels == 1) {
                        mixed = frame[s];
                    } else {
                        int sum = 0, base = s * channels;
                        for (int c = 0; c < channels && base + c < samples; c++) sum += frame[base + c];
                        mixed = sum / channels;
                    }
                    srcIndex++;
                    if (!havePrev) {
                        prev = mixed;
                        havePrev = true;
                    }
                    cur = mixed;
                    // Emit every output sample whose position falls between prev and cur.
                    while (pos <= srcIndex) {
                        double frac = pos - (srcIndex - 1);
                        if (frac < 0) frac = 0;
                        if (frac > 1) frac = 1;
                        int value = (int) Math.round(prev + (cur - prev) * frac);
                        if (accLen >= acc.length) {
                            int bigger = (int) Math.min((long) MAX_SAMPLES, (long) acc.length * 2);
                            if (bigger <= accLen) break;
                            short[] na = new short[bigger];
                            System.arraycopy(acc, 0, na, 0, accLen);
                            acc = na;
                        }
                        acc[accLen++] = (short) value;
                        pos += step;
                    }
                    prev = cur;
                    if (accLen >= MAX_SAMPLES) break;
                }
            }
            if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) outputDone = true;
            codec.releaseOutputBuffer(outIndex, false);
        }

        pcm = new short[accLen];
        System.arraycopy(acc, 0, pcm, 0, accLen);
        return pcm;
    }

    private static int decodeShort(ByteBuffer out, MediaCodec.BufferInfo info, short[] dst) {
        out.position(info.offset);
        out.limit(info.offset + info.size);
        out.order(ByteOrder.LITTLE_ENDIAN);
        ShortBuffer sb = out.asShortBuffer();
        int n = Math.min(dst.length, sb.remaining());
        for (int i = 0; i < n; i++) dst[i] = sb.get(i);
        return n;
    }

    private static int decodeFloat(ByteBuffer out, MediaCodec.BufferInfo info, short[] dst) {
        out.position(info.offset);
        out.limit(info.offset + info.size);
        out.order(ByteOrder.nativeOrder());
        FloatBuffer fb = out.asFloatBuffer();
        int n = Math.min(dst.length, fb.remaining());
        for (int i = 0; i < n; i++) {
            float f = fb.get(i);
            if (f > 1f) f = 1f; else if (f < -1f) f = -1f;
            dst[i] = (short) (f * 32767f);
        }
        return n;
    }

    public void release() {
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
