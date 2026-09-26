package com.amvconverter.app;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Locale;

/**
 * The built-in AMV player.
 *
 * <p>Worth having because no Android component can open an AMV file: without this, the files the
 * app produces could only be checked by copying them onto the media player. It is also the target
 * for "open with" from a file manager, so an .amv tap anywhere lands here.
 */
public final class PlayerActivity extends Activity implements AmvPlayer.Listener {

    public static final String EXTRA_FILE = "file";
    public static final String EXTRA_URI = "uri";
    public static final String EXTRA_TITLE = "title";

    private final Handler ui = new Handler(Looper.getMainLooper());

    private ImageView image;
    private ProgressBar loading;
    private TextView message, title, time, frameText, info;
    private SeekBar seek;
    private Button playButton;

    private AmvPlayer player;
    private boolean seeking;

    public static Intent intentFor(Context context, File file, String title) {
        Intent i = new Intent(context, PlayerActivity.class);
        i.putExtra(EXTRA_FILE, file.getAbsolutePath());
        i.putExtra(EXTRA_TITLE, title);
        return i;
    }

    public static Intent intentForUri(Context context, Uri uri, String title) {
        Intent i = new Intent(context, PlayerActivity.class);
        i.putExtra(EXTRA_URI, uri.toString());
        i.putExtra(EXTRA_TITLE, title);
        return i;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_player);

        image = (ImageView) findViewById(R.id.playerImage);
        loading = (ProgressBar) findViewById(R.id.playerLoading);
        message = (TextView) findViewById(R.id.tvPlayerMessage);
        title = (TextView) findViewById(R.id.tvPlayerTitle);
        time = (TextView) findViewById(R.id.tvPlayerTime);
        frameText = (TextView) findViewById(R.id.tvPlayerFrame);
        info = (TextView) findViewById(R.id.tvPlayerInfo);
        seek = (SeekBar) findViewById(R.id.playerSeek);
        playButton = (Button) findViewById(R.id.btnPlayerPlay);

        findViewById(R.id.btnBack).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { finish(); }
        });
        playButton.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { toggle(); }
        });
        findViewById(R.id.btnPlayerPrev).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { step(-1); }
        });
        findViewById(R.id.btnPlayerNext).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { step(1); }
        });
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                if (fromUser && player != null) {
                    player.seekToFrame(Math.round(progress / 1000f * player.frameCount()));
                }
            }
            public void onStartTrackingTouch(SeekBar bar) { seeking = true; }
            public void onStopTrackingTouch(SeekBar bar) { seeking = false; }
        });

        loading.setVisibility(View.VISIBLE);
        message.setText("正在打开…");
        resolveSource(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (player != null) {
            player.release();
            player = null;
        }
        resolveSource(intent);
    }

    private void resolveSource(final Intent intent) {
        final String path = intent.getStringExtra(EXTRA_FILE);
        final String uriString = intent.getStringExtra(EXTRA_URI);
        Uri uri = intent.getData();
        String label = intent.getStringExtra(EXTRA_TITLE);

        if (path != null) {
            startPlayer(new File(path), label);
            return;
        }
        if (uriString != null) uri = Uri.parse(uriString);
        if (uri == null) {
            fail("没有收到要播放的文件");
            return;
        }
        final Uri source = uri;
        if (label == null) label = source.getLastPathSegment();
        title.setText(label == null ? "AMV 播放" : label);

        // AmvReader needs random access, so a content:// document is staged into the cache first.
        final String finalLabel = label;
        loading.setVisibility(View.VISIBLE);
        message.setText("正在读取文件…");
        new Thread(new Runnable() {
            public void run() {
                try {
                    File staged = stage(source);
                    startPlayer(staged, finalLabel);
                } catch (final Throwable t) {
                    ui.post(new Runnable() {
                        public void run() { fail("无法读取文件: " + t.getMessage()); }
                    });
                }
            }
        }, "amv-stage").start();
    }

    private File stage(Uri uri) throws IOException {
        File dir = new File(getCacheDir(), "open");
        if (!dir.exists() && !dir.mkdirs()) throw new IOException("无法创建缓存目录");
        File out = new File(dir, "incoming.amv");
        InputStream in = getContentResolver().openInputStream(uri);
        if (in == null) throw new IOException("无法打开输入流");
        try {
            FileOutputStream fos = new FileOutputStream(out);
            try {
                byte[] buf = new byte[1 << 16];
                int n;
                while ((n = in.read(buf)) > 0) fos.write(buf, 0, n);
            } finally {
                fos.close();
            }
        } finally {
            try {
                in.close();
            } catch (IOException ignored) {
            }
        }
        return out;
    }

    private void startPlayer(final File file, final String label) {
        ui.post(new Runnable() {
            public void run() {
                if (label != null) title.setText(label);
                try {
                    player = new AmvPlayer(file, PlayerActivity.this);
                    player.prepare();
                    loading.setVisibility(View.GONE);
                    message.setText("");
                    Bitmap first = player.decodeFrame(0);
                    if (first != null) image.setImageBitmap(first);
                    player.play();
                } catch (Throwable t) {
                    fail("不是有效的 AMV 文件：" + t.getMessage());
                }
            }
        });
    }

    private void fail(String text) {
        loading.setVisibility(View.GONE);
        message.setText(text);
        info.setText("");
        playButton.setEnabled(false);
    }

    private void toggle() {
        if (player == null) return;
        if (player.isPlaying()) player.pause();
        else player.play();
    }

    private void step(int delta) {
        if (player == null) return;
        player.pause();
        int target = player.currentFrame() - 1 + delta;
        if (target < 0) target = 0;
        player.seekToFrame(target);
        Bitmap bmp = player.decodeFrame(target);
        if (bmp != null) image.setImageBitmap(bmp);
        updatePosition(target, player.frameCount());
    }

    // ------------------------------------------------------------------ listener

    @Override
    public void onFrame(Bitmap bitmap, int frameIndex, int frameCount) {
        image.setImageBitmap(bitmap);
        if (!seeking) updatePosition(frameIndex, frameCount);
    }

    private void updatePosition(int frameIndex, int frameCount) {
        if (player == null || frameCount <= 0) return;
        long usPerFrame = player.durationUs() / Math.max(1, frameCount);
        long nowUs = (long) frameIndex * usPerFrame;
        time.setText(String.format(Locale.US, "%s / %s", clock(nowUs), clock(player.durationUs())));
        frameText.setText(String.format(Locale.US, "第 %d / %d 帧", Math.min(frameIndex + 1, frameCount), frameCount));
        if (!seeking) {
            seek.setProgress((int) Math.min(1000, 1000L * frameIndex / Math.max(1, frameCount)));
        }
    }

    private static String clock(long us) {
        long sec = Math.max(0, us) / 1000000L;
        return String.format(Locale.US, "%02d:%02d", sec / 60, sec % 60);
    }

    @Override
    public void onInfo(int width, int height, int frameCount, long durationUs, boolean hasAudio) {
        info.setText(String.format(Locale.US, "%dx%d · %d 帧 · %s · %s",
                width, height, frameCount, clock(durationUs), hasAudio ? "含音频" : "无音频"));
        seek.setProgress(0);
    }

    @Override
    public void onStateChanged(boolean playing) {
        playButton.setText(playing ? "暂停" : "播放");
    }

    @Override
    public void onError(String errorMessage) {
        Toast.makeText(this, errorMessage, Toast.LENGTH_LONG).show();
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (player != null) player.pause();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (player != null) {
            player.release();
            player = null;
        }
    }
}
