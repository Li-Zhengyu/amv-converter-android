package com.amvconverter.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import com.amvconverter.core.AmvConverter;
import com.amvconverter.core.AmvJpegEncoder;
import com.amvconverter.core.AmvOptions;
import com.amvconverter.core.AudioSource;
import com.amvconverter.core.FrameSource;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Runs conversions in a foreground service, so the work survives the screen going away.
 *
 * <p>This exists because conversions used to run on an Activity-owned thread: switching to
 * split-screen or a floating window - or any Activity recreation - destroyed the Activity and
 * threw the conversion away, including work that had been running for minutes. A foreground
 * service keeps the process alive with a visible notification, and {@link ConversionState}
 * lets a screen attach and re-attach to whatever is running.
 *
 * <p>One job is one file (single mode) or many (batch); the settings are passed in the Intent.
 */
public final class ConvertService extends Service {

    private static final String TAG = "ConvertService";
    private static final String CHANNEL_ID = "amv-convert";
    private static final int NOTIFICATION_ID = 1001;
    private static final int PREVIEW_EVERY = 6;
    private static final int PROGRESS_EVERY = 3;

    public static final String ACTION_START = "com.amvconverter.app.action.START";
    public static final String ACTION_CANCEL = "com.amvconverter.app.action.CANCEL";

    private static final String EXTRA_URIS = "uris";
    private static final String EXTRA_WIDTH = "width";
    private static final String EXTRA_HEIGHT = "height";
    private static final String EXTRA_FPS_NUM = "fpsNum";
    private static final String EXTRA_FPS_DEN = "fpsDen";
    private static final String EXTRA_FIT = "fit";
    private static final String EXTRA_QUANT = "quant";
    private static final String EXTRA_START_US = "startUs";
    private static final String EXTRA_END_US = "endUs";

    private volatile boolean cancelled;
    private Thread worker;

    /** Builds the intent that starts one conversion job. */
    public static Intent jobIntent(Context context, List<Uri> uris, Presets.Size size,
                                   Presets.Fps fps, Presets.Fit fit, Presets.Quality quality,
                                   long startUs, long endUs) {
        Intent i = new Intent(context, ConvertService.class);
        i.setAction(ACTION_START);
        String[] strings = new String[uris.size()];
        for (int k = 0; k < uris.size(); k++) strings[k] = uris.get(k).toString();
        i.putExtra(EXTRA_URIS, strings);
        i.putExtra(EXTRA_WIDTH, size.width);
        i.putExtra(EXTRA_HEIGHT, size.height);
        i.putExtra(EXTRA_FPS_NUM, fps.num);
        i.putExtra(EXTRA_FPS_DEN, fps.den);
        i.putExtra(EXTRA_FIT, fit.mode.ordinal());
        i.putExtra(EXTRA_QUANT, quality.scale);
        i.putExtra(EXTRA_START_US, startUs);
        i.putExtra(EXTRA_END_US, endUs);
        return i;
    }

    public static void start(Context context, Intent job) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(job);
        } else {
            context.startService(job);
        }
    }

    public static void cancel(Context context) {
        Intent i = new Intent(context, ConvertService.class);
        i.setAction(ACTION_CANCEL);
        try {
            context.startService(i);
        } catch (Throwable ignored) {
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        if (ACTION_CANCEL.equals(action)) {
            cancelled = true;
            return START_NOT_STICKY;
        }
        if (!ACTION_START.equals(action) || intent == null) {
            return START_NOT_STICKY;
        }
        if (worker != null && worker.isAlive()) {
            return START_NOT_STICKY;   // one job at a time
        }
        startForeground(NOTIFICATION_ID, buildNotification("准备中…", 0, true));
        final Intent job = intent;
        cancelled = false;
        worker = new Thread(new Runnable() {
            public void run() {
                try {
                    runJob(job);
                } catch (Throwable t) {
                    Log.e(TAG, "conversion job failed", t);
                    final String msg = t.getMessage() == null ? t.toString() : t.getMessage();
                    ConversionState.update(new ConversionState.Updater() {
                        public void apply(ConversionState.Snapshot s) {
                            s.running = false;
                            s.finished = true;
                            s.status = "转换失败";
                            s.result = msg;
                        }
                    });
                } finally {
                    worker = null;
                    stopForegroundCompat();
                    stopSelf();
                }
            }
        }, "amv-convert-job");
        worker.start();
        return START_NOT_STICKY;
    }

    // ------------------------------------------------------------------ the job

    private void runJob(Intent job) {
        String[] uriStrings = job.getStringArrayExtra(EXTRA_URIS);
        if (uriStrings == null || uriStrings.length == 0) return;
        final int width = job.getIntExtra(EXTRA_WIDTH, 160);
        final int height = job.getIntExtra(EXTRA_HEIGHT, 128);
        final int fpsNum = job.getIntExtra(EXTRA_FPS_NUM, 10);
        final int fpsDen = job.getIntExtra(EXTRA_FPS_DEN, 1);
        final int fitOrdinal = job.getIntExtra(EXTRA_FIT, 0);
        final double quant = job.getDoubleExtra(EXTRA_QUANT, 1.0);
        final long startUs = job.getLongExtra(EXTRA_START_US, 0);
        final long endUs = job.getLongExtra(EXTRA_END_US, -1);
        final boolean batch = uriStrings.length > 1;

        List<Uri> uris = new ArrayList<>();
        for (String s : uriStrings) uris.add(Uri.parse(s));

        ConversionState.begin(batch, uris.size());

        int done = 0, failed = 0;
        long totalBytes = 0;
        StringBuilder problems = new StringBuilder();
        String firstWhere = null;
        File lastFile = null;
        Uri lastUri = null;
        String lastName = null;

        for (int i = 0; i < uris.size() && !cancelled; i++) {
            final int index = i;
            final Uri uri = uris.get(i);
            InputInfo info = InputInfo.probe(this, uri);
            long spanStart = batch ? 0 : startUs;
            long spanEnd = batch ? info.durationUs
                    : (endUs > 0 ? Math.min(endUs, info.durationUs > 0 ? info.durationUs : endUs) : info.durationUs);
            if (spanEnd <= spanStart) {
                failed++;
                problems.append(info.displayName).append(": 无法确定时长\n");
                continue;
            }

            final String fileName = info.displayName;
            final int fileIndex = index;
            final File out = new File(getCacheDir(), batch ? "batch_" + i + ".amv" : "output.amv");
            if (out.exists()) out.delete();

            setRow(fileIndex, "转换中…");
            Timing timing = new Timing();
            try {
                OutputSaver.Result saved = convertOne(uri, info, out, spanStart, spanEnd,
                        width, height, fpsNum, fpsDen, fitOrdinal, quant, timing,
                        new FileProgress() {
                            public void onProgress(int percent, byte[] previewJpeg) {
                                publishProgress(batch, fileIndex, uris.size(), percent, previewJpeg,
                                        width, height);
                            }
                        });
                done++;
                totalBytes += out.length();
                if (firstWhere == null) firstWhere = saved.where;
                if (!batch) {
                    lastFile = out;
                    lastUri = saved.uri;
                    lastName = OutputSaver.suggestedName(info.displayName);
                }
                setRow(fileIndex, "完成 " + OutputSaver.describe(out));
            } catch (AmvConverter.CancelledException ce) {
                setRow(fileIndex, "已取消");
                break;
            } catch (Throwable t) {
                failed++;
                String msg = t.getMessage() == null ? t.toString() : t.getMessage();
                problems.append(fileName).append(": ").append(msg).append('\n');
                setRow(fileIndex, "失败");
                Log.w(TAG, "file failed: " + fileName, t);
            } finally {
                if (batch) out.delete();   // already saved into the gallery by convertOne
            }
        }

        final int doneFinal = done, failedFinal = failed;
        final long bytesFinal = totalBytes;
        final String problemsFinal = problems.toString().trim();
        final String whereFinal = firstWhere;
        final boolean wasCancelled = cancelled;
        final boolean batchFinal = batch;
        final String resultFilePath = lastFile == null ? null : lastFile.getAbsolutePath();
        final String resultUriString = lastUri == null ? null : lastUri.toString();
        final String resultNameFinal = lastName;

        ConversionState.update(new ConversionState.Updater() {
            public void apply(ConversionState.Snapshot s) {
                s.running = false;
                s.finished = true;
                s.percent = 100;
                s.resultFile = resultFilePath;
                s.resultUri = resultUriString;
                s.resultName = resultNameFinal;
                if (batchFinal) {
                    s.status = String.format(Locale.US, "批量结束：成功 %d · 失败 %d · 共 %.1f MB",
                            doneFinal, failedFinal, bytesFinal / 1048576.0);
                    s.detail = wasCancelled ? "已中途停止" : "全部处理完毕";
                } else {
                    s.status = wasCancelled ? "已停止" : "转换完成";
                }
                StringBuilder sb = new StringBuilder();
                if (whereFinal != null) sb.append("已保存到 ").append(whereFinal);
                else if (wasCancelled) sb.append("已取消，未生成文件");
                else sb.append("没有成功保存的文件");
                if (problemsFinal.length() > 0) sb.append('\n').append("失败明细：\n").append(problemsFinal);
                s.result = sb.toString();
            }
        });
        notifyFinished(batchFinal, doneFinal, failedFinal, wasCancelled);
    }

    private interface FileProgress {
        void onProgress(int percent, byte[] previewJpeg);
    }

    private void setRow(final int index, final String text) {
        ConversionState.update(new ConversionState.Updater() {
            public void apply(ConversionState.Snapshot s) {
                if (s.rows == null || index < 0 || index >= s.rows.length) return;
                s.rows[index] = text;
            }
        });
    }

    private void publishProgress(final boolean batch, final int fileIndex, final int fileCount,
                                 final int filePercent, final byte[] previewJpeg,
                                 final int width, final int height) {
        final int overall = batch
                ? (int) ((fileIndex + filePercent / 100.0) / Math.max(1, fileCount) * 100)
                : filePercent;
        final String status = batch
                ? String.format(Locale.US, "批量转换中 %d%% · 第 %d/%d 个 · 本文件 %d%%",
                overall, fileIndex + 1, fileCount, filePercent)
                : String.format(Locale.US, "转换中 %d%%", filePercent);
        ConversionState.update(new ConversionState.Updater() {
            public void apply(ConversionState.Snapshot s) {
                s.percent = overall;
                s.filesDone = fileIndex;
                s.status = status;
                if (s.rows != null && fileIndex < s.rows.length && batch) {
                    s.rows[fileIndex] = filePercent + "%";
                }
                if (previewJpeg != null) {
                    s.previewJpeg = previewJpeg;
                    s.previewWidth = width;
                    s.previewHeight = height;
                }
            }
        });
        updateNotification(status, overall, true);
    }

    // ------------------------------------------------------------------ conversion

    private OutputSaver.Result convertOne(Uri uri, InputInfo srcInfo, File out,
                                          long startUs, long endUs,
                                          int width, int height, int fpsNum, int fpsDen,
                                          int fitOrdinal, double quant, final Timing timing,
                                          final FileProgress progressCallback) throws IOException {
        FrameSource videoSource = null;
        AudioSource audioSource = null;
        try {
            switch (srcInfo.kind) {
                case VIDEO:
                    videoSource = new MediaVideoSource(this, uri, startUs);
                    audioSource = new MediaAudioSource(this, uri, startUs);
                    break;
                case GIF:
                    videoSource = new GifSource(this, uri);
                    break;
                case AUDIO:
                    videoSource = new StillSource(StillSource.titleCardBitmap(
                            srcInfo.displayName, "音频转 AMV", width, height));
                    audioSource = new MediaAudioSource(this, uri, 0);
                    break;
                default:
                    Bitmap bmp = decodeStill(uri);
                    if (bmp == null) throw new IOException("无法读取图片");
                    videoSource = new StillSource(bmp);
                    audioSource = new MediaAudioSource(this, uri, 0);
                    break;
            }

            final AmvOptions options = new AmvOptions();
            options.width = width;
            options.height = height;
            options.fpsNum = fpsNum;
            options.fpsDen = fpsDen;
            options.fit = Presets.FITS[Math.max(0, Math.min(Presets.FITS.length - 1, fitOrdinal))].mode;
            options.quantScale = quant;
            options.startUs = startUs;
            options.endUs = endUs;

            AmvConverter.Listener listener = new AmvConverter.Listener() {
                public void onFrame(int index, int totalFrames, byte[] frame, int frameLength, long bytes) {
                    if (index % PROGRESS_EVERY != 0) return;
                    int pct = totalFrames <= 0 ? 0 : (int) (100L * index / totalFrames);
                    byte[] preview = null;
                    if (index % PREVIEW_EVERY == 0) {
                        preview = AmvJpegEncoder.wrapAsJpeg(frame, frameLength, width, height);
                    }
                    if (progressCallback != null) progressCallback.onProgress(pct, preview);
                }

                public boolean isCancelled() {
                    return cancelled;
                }
            };

            AmvConverter converter = new AmvConverter(options, videoSource, audioSource, listener);
            timing.restart();
            AmvConverter.Result result = converter.convert(out, endUs - startUs);
            timing.frames = result.videoFrames;
            if (videoSource instanceof MediaVideoSource) {
                MediaVideoSource vs = (MediaVideoSource) videoSource;
                timing.decodeNanos = vs.decodeNanos;
                timing.copyNanos = vs.copyNanos;
                timing.copied = vs.framesCopied;
                timing.decoded = vs.framesDecoded;
                timing.codec = vs.codecName();
            }
            final String timingText = timing.describe();
            ConversionState.update(new ConversionState.Updater() {
                public void apply(ConversionState.Snapshot s) {
                    s.detail = timingText;
                }
            });
            return OutputSaver.save(this, out, OutputSaver.suggestedName(srcInfo.displayName));
        } finally {
            if (videoSource instanceof MediaVideoSource) ((MediaVideoSource) videoSource).release();
            if (videoSource instanceof GifSource) ((GifSource) videoSource).release();
            if (audioSource instanceof MediaAudioSource) ((MediaAudioSource) audioSource).release();
        }
    }

    private Bitmap decodeStill(Uri uri) {
        try {
            java.io.InputStream in = getContentResolver().openInputStream(uri);
            Bitmap bmp = BitmapFactory.decodeStream(in);
            if (in != null) in.close();
            if (bmp != null) return bmp;
        } catch (Throwable ignored) {
        }
        return null;
    }

    /** Per-file timing, reported so a slow device can be diagnosed. */
    static final class Timing {
        long startMs = System.currentTimeMillis();
        long frames;
        String codec = "";
        long decodeNanos, copyNanos;
        int copied, decoded;

        void restart() {
            startMs = System.currentTimeMillis();
        }

        String describe() {
            double seconds = (System.currentTimeMillis() - startMs) / 1000.0;
            StringBuilder sb = new StringBuilder();
            sb.append(String.format(Locale.US, "总 %.1f 秒 · %.1f 帧/秒",
                    seconds, frames / Math.max(0.001, seconds)));
            if (codec.length() > 0) {
                sb.append(String.format(Locale.US,
                        "\n解码 %.1fs · 取帧拷贝 %.1fs（只拷了 %d/%d 帧）· 缩放编码等 %.1fs",
                        decodeNanos / 1e9, copyNanos / 1e9, copied, decoded,
                        Math.max(0, seconds - decodeNanos / 1e9 - copyNanos / 1e9)));
                sb.append("\n解码器 ").append(codec);
            }
            return sb.toString();
        }
    }

    // ------------------------------------------------------------------ notification

    private void updateNotification(String text, int percent, boolean ongoing) {
        if (!ongoing && percent >= 100) return;
        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (manager == null) return;
        try {
            manager.notify(NOTIFICATION_ID, buildNotification(text, percent, ongoing));
        } catch (Throwable ignored) {
        }
    }

    private void notifyFinished(boolean batch, int done, int failed, boolean cancelled) {
        String text = cancelled ? "已停止"
                : (batch ? String.format(Locale.US, "批量完成：成功 %d · 失败 %d", done, failed)
                : "转换完成");
        try {
            NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (manager != null) manager.notify(NOTIFICATION_ID, buildNotification(text, 100, false));
        } catch (Throwable ignored) {
        }
    }

    private Notification buildNotification(String text, int percent, boolean ongoing) {
        ensureChannel();
        Intent open = new Intent(this, MainActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) flags |= PendingIntent.FLAG_IMMUTABLE;
        PendingIntent content = PendingIntent.getActivity(this, 0, open, flags);

        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            b = new Notification.Builder(this, CHANNEL_ID);
        } else {
            b = new Notification.Builder(this);
        }
        b.setContentTitle("AMV 转换器")
                .setContentText(text)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentIntent(content)
                .setOngoing(ongoing)
                .setOnlyAlertOnce(true);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            b.setProgress(100, Math.max(0, Math.min(100, percent)), percent <= 0);
        }
        if (ongoing) {
            Intent cancelIntent = new Intent(this, ConvertService.class);
            cancelIntent.setAction(ACTION_CANCEL);
            PendingIntent cancelPending = PendingIntent.getService(this, 1, cancelIntent, flags);
            b.addAction(0, "停止", cancelPending);
        }
        return b.build();
    }

    private void ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (manager == null) return;
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return;
        NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "转换任务",
                NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("显示 AMV 转换进度");
        channel.setShowBadge(false);
        manager.createNotificationChannel(channel);
    }

    private void stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_DETACH);
        } else {
            stopForeground(false);
        }
    }
}
