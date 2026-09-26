package com.amvconverter.app;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.amvconverter.core.AdpcmImaAmvDecoder;
import com.amvconverter.core.AmvJpegEncoder;
import com.amvconverter.core.AmvReader;

import java.io.File;
import java.io.IOException;

/**
 * Plays an AMV file.
 *
 * <p>Android has no AMV support at all, so this does the two things the format needs and nothing
 * else: video frames are rebuilt into ordinary JPEGs - the format is a table-stripped JPEG, so the
 * platform decoder can handle them once the fixed DQT/DHT/SOF/SOS header is put back - and the
 * audio blocks are run through the IMA ADPCM expander. Video is paced against the system clock and
 * audio is streamed through an AudioTrack with its own writer thread, which is more than enough for
 * 10-25 fps preview material.
 */
public final class AmvPlayer {

    private static final String TAG = "AmvPlayer";

    public interface Listener {
        void onFrame(Bitmap bitmap, int frameIndex, int frameCount);

        void onInfo(int width, int height, int frameCount, long durationUs, boolean hasAudio);

        void onStateChanged(boolean playing);

        void onError(String message);
    }

    private final File file;
    private final Listener listener;
    private final Handler ui = new Handler(Looper.getMainLooper());

    private AmvReader reader;
    private AmvReader.Info info;

    private Thread videoThread;
    private Thread audioThread;
    private AudioTrack audioTrack;

    private volatile boolean playing;
    private volatile boolean released;
    private volatile int frameIndex;
    private volatile int seekTarget = -1;
    private volatile int audioSeekTarget = -1;

    private byte[] frameBuf = new byte[1 << 16];
    private byte[] audioBuf = new byte[4096];

    public AmvPlayer(File file, Listener listener) {
        this.file = file;
        this.listener = listener;
    }

    public int frameCount() { return info == null ? 0 : info.videoFrameCount; }

    public int width() { return info == null ? 0 : info.width; }

    public int height() { return info == null ? 0 : info.height; }

    public long durationUs() { return reader == null ? 0 : reader.durationUs(); }

    public boolean isPlaying() { return playing; }

    public int currentFrame() { return frameIndex; }

    public String codecSummary() {
        if (info == null) return "";
        return info.width + "x" + info.height + " · " + info.fpsNum + " 帧/秒 · "
                + info.videoFrameCount + " 帧 · 音频块 " + info.audioBlockBytes + "B";
    }

    public void prepare() throws IOException {
        reader = new AmvReader(file);
        info = reader.info();
        if (info.videoFrameCount <= 0) throw new IOException("AMV 里没有视频帧");
        ui.post(new Runnable() {
            public void run() {
                listener.onInfo(info.width, info.height, info.videoFrameCount,
                        reader.durationUs(), info.audioBlockCount > 0);
            }
        });
    }

    /** Decodes a single frame; used for the still shown before playback starts. */
    public Bitmap decodeFrame(int index) {
        try {
            if (index < 0) index = 0;
            if (index >= info.videoFrameCount) index = info.videoFrameCount - 1;
            int len = reader.readFrame(index, frameBuf);
            if (len <= 4) return null;
            return AmvFrameBitmap.decodeFrame(frameBuf, len, info.width, info.height);
        } catch (Throwable t) {
            Log.w(TAG, "decodeFrame failed", t);
            return null;
        }
    }

    public void play() {
        if (released || reader == null) return;
        if (playing) return;
        playing = true;
        startAudio();
        startVideo();
        ui.post(new Runnable() {
            public void run() { listener.onStateChanged(true); }
        });
    }

    public void pause() {
        playing = false;
        if (audioTrack != null) {
            try {
                audioTrack.pause();
            } catch (Throwable ignored) {
            }
        }
        ui.post(new Runnable() {
            public void run() { listener.onStateChanged(false); }
        });
    }

    public void seekToFrame(int index) {
        if (index < 0) index = 0;
        if (index >= info.videoFrameCount) index = info.videoFrameCount - 1;
        seekTarget = index;
        audioSeekTarget = index;
        frameIndex = index;
        if (audioTrack != null) {
            try {
                audioTrack.pause();
                audioTrack.flush();
            } catch (Throwable ignored) {
            }
        }
    }

    private void startVideo() {
        if (videoThread != null && videoThread.isAlive()) return;
        videoThread = new Thread(new Runnable() {
            public void run() { videoLoop(); }
        }, "amv-video");
        videoThread.start();
    }

    private void videoLoop() {
        long usPerFrame = info.usPerFrame;
        long baseFrame = -1;
        long baseNanos = 0;
        while (!released && playing) {
            int target = seekTarget;
            if (target >= 0) {
                seekTarget = -1;
                frameIndex = target;
                baseFrame = -1;
            }
            int i = frameIndex;
            if (i >= info.videoFrameCount) {
                playing = false;
                ui.post(new Runnable() {
                    public void run() {
                        listener.onStateChanged(false);
                    }
                });
                break;
            }
            if (baseFrame < 0) {
                baseFrame = i;
                baseNanos = System.nanoTime();
            }
            // Absolute deadlines rather than "sleep the remainder of the slot", so a frame that
            // runs long does not push every following frame late as well.
            long deadline = baseNanos + (i - baseFrame) * usPerFrame * 1000L;
            long now = System.nanoTime();
            if (now < deadline) {
                long waitNanos = deadline - now;
                try {
                    Thread.sleep(waitNanos / 1000000L, (int) (waitNanos % 1000000L));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                continue;
            }
            Bitmap bmp = decodeFrame(i);
            if (bmp != null) {
                final Bitmap frame = bmp;
                final int idx = i;
                ui.post(new Runnable() {
                    public void run() {
                        if (!released) listener.onFrame(frame, idx, info.videoFrameCount);
                    }
                });
            }
            frameIndex = i + 1;
        }
    }

    private void startAudio() {
        if (info.audioBlockCount <= 0) return;
        if (audioThread != null && audioThread.isAlive()) {
            if (audioTrack != null) {
                try {
                    audioTrack.play();
                } catch (Throwable ignored) {
                }
            }
            return;
        }
        audioThread = new Thread(new Runnable() {
            public void run() { audioLoop(); }
        }, "amv-audio");
        audioThread.start();
    }

    private void audioLoop() {
        int minBuf = AudioTrack.getMinBufferSize(22050, AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT);
        int bufSize = Math.max(minBuf * 2, 8192);
        try {
            audioTrack = new AudioTrack(AudioManager.STREAM_MUSIC, 22050,
                    AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT,
                    bufSize, AudioTrack.MODE_STREAM);
        } catch (Throwable t) {
            Log.w(TAG, "AudioTrack unavailable, playing silently", t);
            return;
        }
        short[] samples = new short[8192];
        int index = 0;
        boolean started = false;
        try {
            audioTrack.play();
            while (!released && playing) {
                int target = audioSeekTarget;
                if (target >= 0) {
                    audioSeekTarget = -1;
                    index = target;
                    try {
                        audioTrack.pause();
                        audioTrack.flush();
                        audioTrack.play();
                    } catch (Throwable ignored) {
                    }
                }
                if (index >= info.audioBlockCount) {
                    // Audio ran out before the video: keep the track alive but silent.
                    try {
                        Thread.sleep(50);
                    } catch (InterruptedException e) {
                        return;
                    }
                    continue;
                }
                int len = reader.readAudioBlock(index, audioBuf);
                if (len <= 0) {
                    index++;
                    continue;
                }
                int n = AdpcmImaAmvDecoder.decodeBlock(audioBuf, 0, len, samples, 0);
                if (n > 0) {
                    audioTrack.write(samples, 0, n);   // blocking write paces this thread
                }
                index++;
                started = true;
            }
        } catch (Throwable t) {
            Log.w(TAG, "audio playback stopped: " + t);
        } finally {
            if (started) {
                try {
                    audioTrack.stop();
                } catch (Throwable ignored) {
                }
            }
            try {
                audioTrack.release();
            } catch (Throwable ignored) {
            }
            audioTrack = null;
        }
    }

    public void release() {
        released = true;
        playing = false;
        if (audioTrack != null) {
            try {
                audioTrack.stop();
            } catch (Throwable ignored) {
            }
            try {
                audioTrack.release();
            } catch (Throwable ignored) {
            }
            audioTrack = null;
        }
        if (reader != null) {
            try {
                reader.close();
            } catch (Throwable ignored) {
            }
            reader = null;
        }
    }
}
