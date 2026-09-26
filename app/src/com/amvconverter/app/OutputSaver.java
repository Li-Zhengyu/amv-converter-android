package com.amvconverter.app;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Moves the finished AMV out of the cache directory into somewhere the user can find.
 *
 * <p>Storing an {@code .amv} is awkward because no Android collection really expects the format,
 * so instead of betting on one location this tries several in order of usefulness and always ends
 * up with a file somewhere:
 * <ol>
 *   <li>{@code Movies/AMV} through MediaStore (API 29+, no permission needed);</li>
 *   <li>{@code Download/AMV} through the Downloads collection, for devices or mime validation
 *       that reject a video-ish type in the generic Files collection;</li>
 *   <li>the public Movies directory on older releases, which needs WRITE_EXTERNAL_STORAGE;</li>
 *   <li>the app's own external directory, which needs nothing at all.</li>
 * </ol>
 *
 * <p>When an attempt fails the reason is kept and reported, because "save failed" with no cause is
 * impossible to act on. The previous version did exactly that by swallowing the exception, which
 * cost a round trip with the user.
 */
public final class OutputSaver {

    public static final String SUBDIR = "AMV";
    private static final String MIME = "video/x-amv";
    private static final String TAG = "OutputSaver";

    private OutputSaver() {
    }

    /** Where the file ended up, plus anything that went wrong on the way. */
    public static final class Result {
        public Uri uri;          // content uri, or null when saved straight to a file
        public File file;        // set when the destination is a real path
        public String where;     // human readable location
        public String warning;   // null when the first-choice location worked
    }

    public static String suggestedName(String sourceName) {
        String base = sourceName == null ? "video" : sourceName;
        int dot = base.lastIndexOf('.');
        if (dot > 0) base = base.substring(0, dot);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < base.length(); i++) {
            char c = base.charAt(i);
            if (Character.isLetterOrDigit(c) || c == '-' || c == '_' || c > 0x7F) sb.append(c);
            else sb.append('_');
        }
        String name = sb.length() == 0 ? "video" : sb.toString();
        if (name.length() > 40) name = name.substring(0, 40);
        return name + ".amv";
    }

    public static boolean needsLegacyPermission() {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.Q;
    }

    public static Result save(Context context, File source, String displayName) throws IOException {
        List<String> problems = new ArrayList<>();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // 1. the natural home for it
            try {
                Result r = viaMediaStore(context, MediaStore.Files.getContentUri(
                        MediaStore.VOLUME_EXTERNAL_PRIMARY),
                        Environment.DIRECTORY_MOVIES + "/" + SUBDIR, source, displayName);
                r.where = "影片/AMV/" + displayName;
                return r;
            } catch (Throwable t) {
                problems.add("影片目录: " + describe(t));
                Log.w(TAG, "Movies/AMV failed", t);
            }
            // 2. Downloads is more permissive about unfamiliar mime types
            try {
                Result r = viaMediaStore(context, MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                        Environment.DIRECTORY_DOWNLOADS + "/" + SUBDIR, source, displayName);
                r.where = "Download/AMV/" + displayName;
                r.warning = "影片目录不可写，已改存到 Download/AMV";
                return r;
            } catch (Throwable t) {
                problems.add("下载目录: " + describe(t));
                Log.w(TAG, "Download/AMV failed", t);
            }
        } else {
            // 3. older releases: plain file in the public Movies directory
            try {
                return legacyFile(context, source, displayName);
            } catch (Throwable t) {
                problems.add("公共影片目录: " + describe(t));
                Log.w(TAG, "public Movies failed", t);
            }
        }

        // 4. always available: the app's own external directory, no permission required
        try {
            File dir = context.getExternalFilesDir(Environment.DIRECTORY_MOVIES);
            if (dir == null) dir = new File(context.getFilesDir(), "Movies");
            File target = new File(new File(dir, SUBDIR), uniqueName(new File(new File(dir, SUBDIR), displayName)));
            File parent = target.getParentFile();
            if (parent != null && !parent.exists() && !parent.mkdirs()) {
                throw new IOException("无法创建目录 " + parent);
            }
            try (OutputStream out = new FileOutputStream(target)) {
                copy(source, out);
            }
            Result r = new Result();
            r.file = target;
            r.where = target.getAbsolutePath();
            r.warning = "系统媒体库拒绝写入（" + String.join("；", problems)
                    + "），已存到应用目录，可用「另存为」导出到任意位置";
            return r;
        } catch (Throwable t) {
            problems.add("应用目录: " + describe(t));
        }

        throw new IOException("所有保存位置都失败 — " + String.join("；", problems));
    }

    private static Result viaMediaStore(Context context, Uri collection, String relativePath,
                                        File source, String displayName) throws IOException {
        ContentResolver cr = context.getContentResolver();
        Uri target = null;
        Throwable last = null;
        // The mime type is a guess in either direction: some releases validate it against what
        // the collection expects, so fall back to a neutral one if the honest one is rejected.
        for (String mime : new String[]{MIME, "application/octet-stream", "video/mp4"}) {
            ContentValues values = new ContentValues();
            values.put(MediaStore.MediaColumns.DISPLAY_NAME, displayName);
            values.put(MediaStore.MediaColumns.MIME_TYPE, mime);
            values.put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath);
            try {
                target = cr.insert(collection, values);
                if (target != null) break;
            } catch (Throwable t) {
                last = t;
            }
        }
        if (target == null) {
            throw new IOException(last == null ? "媒体库 insert 返回空" : describe(last));
        }
        try {
            OutputStream out = cr.openOutputStream(target);
            if (out == null) throw new IOException("openOutputStream 返回空");
            copy(source, out);
        } catch (Throwable t) {
            try {
                cr.delete(target, null, null);
            } catch (Throwable ignored) {
            }
            throw new IOException(describe(t));
        }
        Result r = new Result();
        r.uri = target;
        return r;
    }

    private static Result legacyFile(Context context, File source, String name) throws IOException {
        File dir = new File(Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_MOVIES), SUBDIR);
        if (!dir.exists() && !dir.mkdirs()) throw new IOException("无法创建目录 " + dir);
        File dst = new File(dir, uniqueName(new File(dir, name)));
        try (OutputStream out = new FileOutputStream(dst)) {
            copy(source, out);
        }
        try {
            MediaScannerConnection.scanFile(context, new String[]{dst.getAbsolutePath()},
                    new String[]{MIME}, null);
        } catch (Throwable ignored) {
        }
        Result r = new Result();
        r.file = dst;
        r.uri = Uri.fromFile(dst);
        r.where = "影片/AMV/" + dst.getName();
        return r;
    }

    private static String uniqueName(File candidate) {
        if (!candidate.exists()) return candidate.getName();
        String name = candidate.getName();
        String stem = name.endsWith(".amv") ? name.substring(0, name.length() - 4) : name;
        File dir = candidate.getParentFile();
        for (int i = 1; i < 1000; i++) {
            File f = new File(dir, stem + "_" + i + ".amv");
            if (!f.exists()) return f.getName();
        }
        return stem + "_" + System.currentTimeMillis() + ".amv";
    }

    /** "Save as" through the system file picker; needs no permission on any release. */
    public static void copyToUri(Context context, File source, Uri target) throws IOException {
        OutputStream out = context.getContentResolver().openOutputStream(target, "wt");
        if (out == null) throw new IOException("无法写入所选位置");
        copy(source, out);
    }

    public static void copy(File source, OutputStream out) throws IOException {
        try (InputStream in = new FileInputStream(source)) {
            byte[] buf = new byte[1 << 16];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        } finally {
            try {
                out.flush();
            } catch (Throwable ignored) {
            }
            try {
                out.close();
            } catch (Throwable ignored) {
            }
        }
    }

    private static String describe(Throwable t) {
        String msg = t.getMessage();
        if (msg == null || msg.isEmpty()) msg = t.getClass().getSimpleName();
        return msg;
    }

    public static String describe(File f) {
        long size = f.length();
        if (size < 1024) return size + " B";
        if (size < 1024 * 1024) return String.format("%.0f KB", size / 1024.0);
        return String.format("%.1f MB", size / 1048576.0);
    }
}
