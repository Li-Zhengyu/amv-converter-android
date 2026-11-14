package com.amvconverter.app;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.animation.DecelerateInterpolator;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

/**
 * A small always-on-top card that shows conversion progress and can lock the app in the
 * background.
 *
 * <p>The conversion itself already lives in {@link ConvertService}, so it is not lost when this
 * screen goes away. What this window adds is what a foreground service alone cannot give:
 * <ul>
 *   <li>progress that stays visible while the user is in another app, and</li>
 *   <li>an explicit "lock" that holds a partial wake lock and keeps a foreground service alive,
 *       so a long conversion is not suspended when the screen turns off or the task is swiped
 *       away from recents.</li>
 * </ul>
 *
 * <p>The window is only added when the user has granted the overlay permission; the service also
 * stops itself if that permission is revoked later. The user's choice is persisted, so the window
 * comes back after a process restart instead of silently disappearing.
 */
public final class FloatingWindowService extends Service implements ConversionState.Observer {

    private static final String CHANNEL_ID = "amv-overlay";
    private static final int NOTIFICATION_ID = 1002;
    private static final String PREFS = "amv";
    static final String PREF_ENABLED = "overlay_enabled";
    private static final String PREF_LOCKED = "overlay_locked";
    private static final String PREF_X = "overlay_x";
    private static final String PREF_Y = "overlay_y";

    public static final String ACTION_SHOW = "com.amvconverter.app.action.OVERLAY_SHOW";
    public static final String ACTION_HIDE = "com.amvconverter.app.action.OVERLAY_HIDE";

    /** Whether a window is currently attached; read by the activity to label its toggle. */
    private static volatile boolean showing;

    private WindowManager windowManager;
    private View root;
    private WindowManager.LayoutParams params;
    private TextView tvStatus;
    private ProgressBar progress;
    private Button btnLock;
    private Button btnStop;

    private SharedPreferences prefs;
    private PowerManager.WakeLock wakeLock;
    private boolean locked;
    private ValueAnimator snapAnimator;

    static boolean isEnabled(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean(PREF_ENABLED, false);
    }

    static boolean isShowing() {
        return showing;
    }

    /** True when the app may draw over other apps (always true before API 23). */
    static boolean canDrawOverlays(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true;
        return Settings.canDrawOverlays(context);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        if (!canDrawOverlays(this)) {
            // The activity is supposed to check this before starting us; if the permission was
            // revoked in the meantime, leave quietly instead of crashing in addView.
            stopSelf();
            return;
        }
        startForegroundCompat(buildNotification());
        locked = prefs.getBoolean(PREF_LOCKED, false);
        attachWindow();
        ConversionState.addObserver(this);
        updateLockUi();
        if (locked) acquireWakeLock();
        showing = true;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        if (ACTION_HIDE.equals(action)) {
            prefs.edit().putBoolean(PREF_ENABLED, false).apply();
            stopSelf();
            return START_NOT_STICKY;
        }
        // START_STICKY: if the system reclaims the process, the locked window should come back
        // rather than vanish without the user asking.
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        showing = false;
        cancelSnap();
        ConversionState.removeObserver(this);
        releaseWakeLock();
        if (root != null && windowManager != null) {
            try {
                windowManager.removeView(root);
            } catch (Throwable ignored) {
            }
            root = null;
        }
        stopForegroundCompat();
        super.onDestroy();
    }

    // ------------------------------------------------------------------ window

    private void attachWindow() {
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        if (windowManager == null) {
            stopSelf();
            return;
        }
        root = LayoutInflater.from(this).inflate(R.layout.floating_window, null);
        tvStatus = (TextView) root.findViewById(R.id.tvOverlayStatus);
        progress = (ProgressBar) root.findViewById(R.id.overlayProgress);
        btnLock = (Button) root.findViewById(R.id.btnOverlayLock);
        btnStop = (Button) root.findViewById(R.id.btnOverlayStop);

        int type = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;
        params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.START;
        params.x = prefs.getInt(PREF_X, -1);
        params.y = prefs.getInt(PREF_Y, dp(88));

        root.findViewById(R.id.btnOverlayClose).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                prefs.edit().putBoolean(PREF_ENABLED, false).apply();
                stopSelf();
            }
        });
        btnLock.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                setLocked(!locked, true);
            }
        });
        btnStop.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                ConvertService.cancel(FloatingWindowService.this);
                btnStop.setEnabled(false);
                btnStop.setText("正在停止…");
            }
        });
        // The whole card drags: child buttons are clickable, so they consume their own taps
        // instead of forwarding them here.
        wireDrag(root);

        try {
            windowManager.addView(root, params);
        } catch (Throwable t) {
            // Most often: the permission was revoked between the check and this call.
            Toast.makeText(this, "浮窗添加失败，请检查「显示在其他应用上层」权限", Toast.LENGTH_LONG).show();
            stopSelf();
            return;
        }

        // The first layout pass is when the real window size is known, so place it then.
        root.post(new Runnable() {
            public void run() {
                if (root == null) return;
                if (params.x < 0) {
                    DisplayMetrics dm = getResources().getDisplayMetrics();
                    params.x = Math.max(0, dm.widthPixels - root.getWidth());
                }
                clamp();
                try {
                    windowManager.updateViewLayout(root, params);
                } catch (Throwable ignored) {
                }
            }
        });
    }

    private void wireDrag(final View target) {
        target.setOnTouchListener(new View.OnTouchListener() {
            private float downRawX, downRawY;
            private int startX, startY;
            private boolean dragging;

            public boolean onTouch(View v, MotionEvent event) {
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        cancelSnap();
                        downRawX = event.getRawX();
                        downRawY = event.getRawY();
                        startX = params.x;
                        startY = params.y;
                        dragging = false;
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        int dx = (int) (event.getRawX() - downRawX);
                        int dy = (int) (event.getRawY() - downRawY);
                        if (!dragging && (Math.abs(dx) > dp(6) || Math.abs(dy) > dp(6))) {
                            dragging = true;
                        }
                        if (dragging) {
                            params.x = startX + dx;
                            params.y = startY + dy;
                            clamp();
                            try {
                                windowManager.updateViewLayout(root, params);
                            } catch (Throwable ignored) {
                            }
                        }
                        return true;
                    case MotionEvent.ACTION_UP:
                        if (dragging) snapToEdge();
                        return true;
                    case MotionEvent.ACTION_CANCEL:
                        if (dragging) persistPosition();
                        return true;
                    default:
                        return false;
                }
            }
        });
    }

    /** After a drag, glide to whichever screen edge the card is closer to. */
    private void snapToEdge() {
        if (root == null || windowManager == null) return;
        DisplayMetrics dm = getResources().getDisplayMetrics();
        int width = root.getWidth();
        int maxX = Math.max(0, dm.widthPixels - width);
        final int targetX = (params.x + width / 2) < dm.widthPixels / 2 ? 0 : maxX;
        if (targetX == params.x) {
            persistPosition();
            return;
        }
        cancelSnap();
        snapAnimator = ValueAnimator.ofInt(params.x, targetX);
        snapAnimator.setDuration(200);
        snapAnimator.setInterpolator(new DecelerateInterpolator());
        snapAnimator.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            public void onAnimationUpdate(ValueAnimator animation) {
                if (root == null) return;
                params.x = (Integer) animation.getAnimatedValue();
                try {
                    windowManager.updateViewLayout(root, params);
                } catch (Throwable ignored) {
                }
            }
        });
        snapAnimator.addListener(new AnimatorListenerAdapter() {
            public void onAnimationEnd(Animator animation) {
                persistPosition();
            }
        });
        snapAnimator.start();
    }

    private void cancelSnap() {
        if (snapAnimator != null) {
            snapAnimator.cancel();
            snapAnimator = null;
        }
    }

    private void persistPosition() {
        if (prefs == null || params == null) return;
        prefs.edit().putInt(PREF_X, params.x).putInt(PREF_Y, params.y).apply();
    }

    /** Keeps the card fully on screen after a drag or a rotation. */
    private void clamp() {
        DisplayMetrics dm = getResources().getDisplayMetrics();
        int width = root == null ? dp(168) : root.getWidth();
        int height = root == null ? dp(106) : root.getHeight();
        params.x = Math.max(0, Math.min(params.x, dm.widthPixels - width));
        params.y = Math.max(0, Math.min(params.y, dm.heightPixels - height));
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    // ------------------------------------------------------------------ lock

    private void setLocked(boolean value, boolean announce) {
        locked = value;
        prefs.edit().putBoolean(PREF_LOCKED, value).apply();
        if (value) acquireWakeLock();
        else releaseWakeLock();
        updateLockUi();
        updateNotification();
        if (announce) {
            Toast.makeText(this, value
                    ? "已锁定后台：屏幕熄灭后转换也会继续"
                    : "已取消锁定，系统可以按常规策略回收后台", Toast.LENGTH_SHORT).show();
        }
    }

    private void acquireWakeLock() {
        try {
            if (wakeLock == null) {
                PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
                if (pm == null) return;
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "amv:overlay-lock");
                wakeLock.setReferenceCounted(false);
            }
            if (!wakeLock.isHeld()) wakeLock.acquire();
        } catch (Throwable ignored) {
        }
    }

    private void releaseWakeLock() {
        try {
            if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        } catch (Throwable ignored) {
        }
    }

    private void updateLockUi() {
        if (btnLock == null) return;
        btnLock.setText(locked ? "已锁定" : "锁定");
        btnLock.setTextColor(getResources().getColor(locked ? R.color.accent_on : R.color.text_primary));
        btnLock.setBackgroundResource(locked
                ? R.drawable.bg_button_primary : R.drawable.bg_button_secondary);
        btnLock.setPadding(dp(8), 0, dp(8), 0);
    }

    // ------------------------------------------------------------------ state

    @Override
    public void onConversionState(ConversionState.Snapshot s) {
        if (root == null) return;
        boolean running = s.running;
        if (running) {
            tvStatus.setText(s.status != null && s.status.length() > 0
                    ? s.status : "转换中 " + s.percent + "%");
            progress.setVisibility(View.VISIBLE);
            progress.setProgress(s.percent);
            btnStop.setText("停止");
            btnStop.setEnabled(true);
            btnStop.setAlpha(1f);
        } else if (s.finished) {
            tvStatus.setText(s.status != null && s.status.length() > 0 ? s.status : "转换结束");
            progress.setVisibility(View.VISIBLE);
            progress.setProgress(100);
            btnStop.setText("停止");
            btnStop.setEnabled(false);
            btnStop.setAlpha(0.45f);
        } else {
            tvStatus.setText("待命 · 没有进行中的转换");
            progress.setVisibility(View.GONE);
            btnStop.setText("停止");
            btnStop.setEnabled(false);
            btnStop.setAlpha(0.45f);
        }
    }

    // ------------------------------------------------------------------ notification

    private Notification buildNotification() {
        ensureChannel();
        Intent open = new Intent(this, MainActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) flags |= PendingIntent.FLAG_IMMUTABLE;
        PendingIntent content = PendingIntent.getActivity(this, 0, open, flags);

        Intent hide = new Intent(this, FloatingWindowService.class);
        hide.setAction(ACTION_HIDE);
        PendingIntent hidePending = PendingIntent.getService(this, 2, hide, flags);

        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            b = new Notification.Builder(this, CHANNEL_ID);
        } else {
            b = new Notification.Builder(this);
        }
        b.setContentTitle("AMV 浮窗")
                .setContentText(locked ? "后台已锁定，转换会继续" : "浮窗显示在其他应用上层")
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentIntent(content)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .addAction(0, "关闭浮窗", hidePending);
        return b.build();
    }

    private void updateNotification() {
        try {
            NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (manager != null) manager.notify(NOTIFICATION_ID, buildNotification());
        } catch (Throwable ignored) {
        }
    }

    private void ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (manager == null) return;
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return;
        NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "后台浮窗",
                NotificationManager.IMPORTANCE_MIN);
        channel.setDescription("浮窗显示时保持后台运行");
        channel.setShowBadge(false);
        manager.createNotificationChannel(channel);
    }

    private void startForegroundCompat(Notification notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    private void stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE);
        } else {
            stopForeground(true);
        }
    }
}
