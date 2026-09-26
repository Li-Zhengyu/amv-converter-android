package com.amvconverter.app;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.graphics.BitmapFactory;
import android.graphics.Movie;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.net.Uri;
import android.provider.OpenableColumns;

import java.io.InputStream;

/**
 * What kind of thing did the user pick, and how big/long is it?
 *
 * <p>Probing is intentionally forgiving: the mime type reported by the document provider is only
 * a hint, so anything ambiguous is tried as a media container first and as an image second.
 */
public final class InputInfo {

    public enum Kind { VIDEO, GIF, IMAGE, AUDIO }

    public Kind kind = Kind.VIDEO;
    public String displayName = "未命名";
    public long sizeBytes;
    public long durationUs;
    public int width;
    public int height;
    public String mime = "";
    public boolean hasAudio;
    public String trackSummary = "";

    public String prettySize() {
        if (sizeBytes <= 0) return "";
        if (sizeBytes < 1024) return sizeBytes + " B";
        if (sizeBytes < 1024 * 1024) return String.format("%.0f KB", sizeBytes / 1024.0);
        return String.format("%.1f MB", sizeBytes / 1048576.0);
    }

    public static String prettyDuration(long us) {
        if (us <= 0) return "--:--";
        long sec = us / 1000000L;
        return String.format("%02d:%02d", sec / 60, sec % 60);
    }

    public static InputInfo probe(Context context, Uri uri) {
        InputInfo info = new InputInfo();
        ContentResolver cr = context.getContentResolver();
        try {
            info.mime = cr.getType(uri) == null ? "" : cr.getType(uri);
        } catch (Throwable ignored) {
        }
        Cursor c = null;
        try {
            c = cr.query(uri, new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE},
                    null, null, null);
            if (c != null && c.moveToFirst()) {
                int nameIdx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                int sizeIdx = c.getColumnIndex(OpenableColumns.SIZE);
                if (nameIdx >= 0 && !c.isNull(nameIdx)) info.displayName = c.getString(nameIdx);
                if (sizeIdx >= 0 && !c.isNull(sizeIdx)) info.sizeBytes = c.getLong(sizeIdx);
            }
        } catch (Throwable ignored) {
        } finally {
            if (c != null) c.close();
        }

        String mime = info.mime;
        if (mime.startsWith("image/gif")) {
            probeGif(context, uri, info);
            return info;
        }
        if (mime.startsWith("audio/")) {
            if (probeContainer(context, uri, info)) {
                info.kind = InputInfo.Kind.AUDIO;
                return info;
            }
        } else if (mime.startsWith("image/")) {
            if (probeImage(context, uri, info)) {
                info.kind = InputInfo.Kind.IMAGE;
                return info;
            }
        }
        // Video, or an unknown type: let the container prober decide.
        if (probeContainer(context, uri, info)) {
            info.kind = info.width > 0 ? Kind.VIDEO : Kind.AUDIO;
            return info;
        }
        if (probeGif(context, uri, info)) return info;
        if (probeImage(context, uri, info)) {
            info.kind = Kind.IMAGE;
            return info;
        }
        info.kind = Kind.VIDEO;
        info.trackSummary = "无法识别该文件";
        return info;
    }

    private static boolean probeContainer(Context context, Uri uri, InputInfo info) {
        MediaExtractor ex = new MediaExtractor();
        try {
            ex.setDataSource(context, uri, null);
            int videoTrack = -1, audioTrack = -1;
            String videoMime = null, audioMime = null;
            long duration = 0;
            for (int i = 0; i < ex.getTrackCount(); i++) {
                MediaFormat f = ex.getTrackFormat(i);
                String m = f.getString(MediaFormat.KEY_MIME);
                if (m == null) continue;
                if (m.startsWith("video/") && videoTrack < 0) {
                    videoTrack = i;
                    videoMime = m;
                    if (f.containsKey(MediaFormat.KEY_WIDTH)) info.width = f.getInteger(MediaFormat.KEY_WIDTH);
                    if (f.containsKey(MediaFormat.KEY_HEIGHT)) info.height = f.getInteger(MediaFormat.KEY_HEIGHT);
                    if (f.containsKey(MediaFormat.KEY_DURATION)) {
                        duration = Math.max(duration, f.getLong(MediaFormat.KEY_DURATION));
                    }
                } else if (m.startsWith("audio/") && audioTrack < 0) {
                    audioTrack = i;
                    audioMime = m;
                    if (f.containsKey(MediaFormat.KEY_DURATION)) {
                        duration = Math.max(duration, f.getLong(MediaFormat.KEY_DURATION));
                    }
                }
            }
            info.hasAudio = audioTrack >= 0;
            info.durationUs = duration;
            StringBuilder sb = new StringBuilder();
            if (videoMime != null) sb.append(videoMime);
            if (audioMime != null) {
                if (sb.length() > 0) sb.append(" + ");
                sb.append(audioMime);
            }
            info.trackSummary = sb.toString();
            return videoTrack >= 0 || audioTrack >= 0;
        } catch (Throwable t) {
            return false;
        } finally {
            try {
                ex.release();
            } catch (Throwable ignored) {
            }
        }
    }

    private static boolean probeGif(Context context, Uri uri, InputInfo info) {
        InputStream in = null;
        try {
            in = context.getContentResolver().openInputStream(uri);
            Movie movie = Movie.decodeStream(in);
            if (movie == null) return false;
            info.kind = Kind.GIF;
            info.width = movie.width();
            info.height = movie.height();
            long ms = movie.duration();
            info.durationUs = (ms > 0 ? ms : 1000L) * 1000L;
            info.trackSummary = ms > 0 ? "动图 GIF" : "静态 GIF";
            return true;
        } catch (Throwable t) {
            return false;
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private static boolean probeImage(Context context, Uri uri, InputInfo info) {
        InputStream in = null;
        try {
            in = context.getContentResolver().openInputStream(uri);
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeStream(in, null, o);
            if (o.outWidth <= 0 || o.outHeight <= 0) return false;
            info.width = o.outWidth;
            info.height = o.outHeight;
            info.kind = Kind.IMAGE;
            info.trackSummary = o.outMimeType == null ? "图片" : o.outMimeType;
            if (info.durationUs <= 0) info.durationUs = 30_000_000L;   // default still duration
            return true;
        } catch (Throwable t) {
            return false;
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }
}
