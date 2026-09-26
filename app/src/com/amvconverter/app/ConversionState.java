package com.amvconverter.app;

import android.os.Handler;
import android.os.Looper;

import java.util.ArrayList;
import java.util.List;

/**
 * The conversion state, shared between the service that does the work and whatever screen happens
 * to be showing at the time.
 *
 * <p>Conversions used to run on a thread owned by the Activity, which meant entering split-screen
 * or a floating window (or any Activity recreation) threw the work away. The work now lives in a
 * foreground service and publishes its state here, so a screen can attach, detach and re-attach
 * freely: {@link #addObserver} immediately delivers the current snapshot, which is what makes
 * "come back to the app and see progress still ticking" work.
 *
 * <p>All observer callbacks are delivered on the main thread, so observers need no locking.
 */
final class ConversionState {

    /** An immutable view of the conversion at one moment. */
    static final class Snapshot {
        public boolean running;
        public boolean batch;
        public int percent;
        public int filesDone;
        public int filesTotal;
        public String status = "";
        /** Multi-line detail: per-stage timings for one file, or the batch summary. */
        public String detail = "";
        /** Final result text (where the file went, or what went wrong). */
        public String result = "";
        public boolean finished;
        /** Rebuilt JPEG of the most recent encoded frame, or null. */
        public byte[] previewJpeg;
        public int previewWidth;
        public int previewHeight;
        /** Per-file status lines in batch mode; null when not batch. */
        public String[] rows;
        /** Cache path of a single-mode result, kept so the app can play it back. */
        public String resultFile;
        /** Content uri of the last saved file, for sharing; null when not shareable. */
        public String resultUri;
        /** Suggested file name of the last result. */
        public String resultName;

        Snapshot copy() {
            Snapshot s = new Snapshot();
            s.running = running;
            s.batch = batch;
            s.percent = percent;
            s.filesDone = filesDone;
            s.filesTotal = filesTotal;
            s.status = status;
            s.detail = detail;
            s.result = result;
            s.finished = finished;
            s.previewJpeg = previewJpeg;
            s.previewWidth = previewWidth;
            s.previewHeight = previewHeight;
            s.resultFile = resultFile;
            s.resultUri = resultUri;
            s.resultName = resultName;
            if (rows != null) {
                s.rows = new String[rows.length];
                System.arraycopy(rows, 0, s.rows, 0, rows.length);
            }
            return s;
        }
    }

    interface Observer {
        void onConversionState(Snapshot snapshot);
    }

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final List<Observer> OBSERVERS = new ArrayList<>();
    private static Snapshot current = new Snapshot();

    private ConversionState() {
    }

    /** Current state; safe to call from any thread. */
    static Snapshot current() {
        return current.copy();
    }

    static boolean isRunning() {
        return current.running;
    }

    /** Registers an observer and immediately delivers the current state to it. */
    static void addObserver(final Observer observer) {
        MAIN.post(new Runnable() {
            public void run() {
                if (!OBSERVERS.contains(observer)) OBSERVERS.add(observer);
                observer.onConversionState(current.copy());
            }
        });
    }

    static void removeObserver(final Observer observer) {
        MAIN.post(new Runnable() {
            public void run() {
                OBSERVERS.remove(observer);
            }
        });
    }

    /** Starts a fresh run: clears any previous result and notifies observers. */
    static void begin(boolean batch, int filesTotal) {
        update(new Updater() {
            public void apply(Snapshot s) {
                s.running = true;
                s.finished = false;
                s.batch = batch;
                s.filesTotal = filesTotal;
                s.filesDone = 0;
                s.percent = 0;
                s.status = "准备中…";
                s.detail = "";
                s.result = "";
                s.previewJpeg = null;
                s.previewWidth = 0;
                s.previewHeight = 0;
                s.rows = batch ? new String[filesTotal] : null;
                if (s.rows != null) {
                    java.util.Arrays.fill(s.rows, "等待");
                }
            }
        });
    }

    static void clear() {
        update(new Updater() {
            public void apply(Snapshot s) {
                s.running = false;
                s.finished = false;
                s.filesDone = 0;
                s.filesTotal = 0;
                s.percent = 0;
                s.status = "";
                s.detail = "";
                s.result = "";
                s.previewJpeg = null;
            }
        });
    }

    interface Updater {
        void apply(Snapshot s);
    }

    /** Mutates the state on the main thread and notifies every observer. */
    static void update(final Updater updater) {
        MAIN.post(new Runnable() {
            public void run() {
                updater.apply(current);
                Snapshot snapshot = current.copy();
                for (int i = OBSERVERS.size() - 1; i >= 0; i--) {
                    OBSERVERS.get(i).onConversionState(snapshot);
                }
            }
        });
    }
}
