package com.amvconverter.app;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.VideoView;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The converter screen: pick one file or many, look at it, choose a few settings, convert.
 *
 * <p>The conversion itself runs in {@link ConvertService}, not here. That is deliberate: an
 * Activity-owned thread dies with the Activity, so entering split-screen or a floating window used
 * to throw away a long-running conversion. This screen starts the job and then merely observes
 * {@link ConversionState}, which means it can be destroyed and recreated freely and still show
 * live progress when it comes back.
 */
public final class MainActivity extends Activity implements ConversionState.Observer {

    private static final int REQ_PICK = 1001;
    private static final int REQ_OPEN_AMV = 1002;
    private static final int REQ_SAVE_AS = 1003;
    private static final int REQ_LEGACY_PERM = 1004;
    private static final String PREFS = "amv";
    private static final String PREF_MULTI_HINT = "multi_hint_shown";

    private final Handler ui = new Handler(Looper.getMainLooper());

    private TextView tvFileName, tvFileInfo, tvRange, tvEstimate, tvStatus, tvResult, tvTiming,
            tvPreviewHint, tvMultiHint;
    private VideoView videoView;
    private ImageView imagePreview, outPreview;
    private FrameLayout previewBox;
    private LinearLayout resultActions, progressCard, batchList, batchCard, trimRow;
    private Button btnPick, btnPlay, btnSetStart, btnSetEnd, btnConvert, btnSaveAs, btnShare,
            btnPlayResult, btnOpenPlayer, btnClearBatch;
    private LinearLayout rowSize, rowFps, rowFit, rowQuality;
    private ProgressBar progress;

    /** Single-file state. */
    private InputInfo info;
    private Uri sourceUri;

    /** Batch state: parallel lists, index-aligned. */
    private final List<Uri> batchUris = new ArrayList<>();
    private final List<String> batchNames = new ArrayList<>();
    private final List<TextView> batchStatus = new ArrayList<>();
    private boolean batchMode;

    private File outputFile;
    private Uri outputSaverUri;
    private String outputName;
    private long batchTotalUs;
    private boolean destroyed;

    private long trimStartUs = -1;
    private long trimEndUs = -1;
    private boolean trimming;

    private int chipSize = Presets.DEFAULT_SIZE;
    private int chipFps = Presets.DEFAULT_FPS;
    private int chipFit = Presets.DEFAULT_FIT;
    private int chipQuality = Presets.DEFAULT_QUALITY;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        bindViews();
        setupChips();
        wireActions();
        updateEstimate();
        updateRangeText();
        ConversionState.addObserver(this);

        new Thread(new Runnable() {
            public void run() { CoreSelfTest.run(getCacheDir()); }
        }, "amv-selftest").start();

        handleIncoming(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIncoming(intent);
    }

    private void handleIncoming(Intent intent) {
        if (intent == null) return;
        Uri uri = intent.getData();
        if (uri == null) return;
        String mime = intent.getType();
        String name = uri.getLastPathSegment();
        boolean looksLikeAmv = (name != null && name.toLowerCase(Locale.US).endsWith(".amv"))
                || "video/x-amv".equals(mime);
        if (looksLikeAmv) {
            startActivity(PlayerActivity.intentForUri(this, uri, name));
        } else {
            List<Uri> single = new ArrayList<>();
            single.add(uri);
            setSources(single);
        }
    }

    private void bindViews() {
        tvFileName = (TextView) findViewById(R.id.tvFileName);
        tvFileInfo = (TextView) findViewById(R.id.tvFileInfo);
        tvRange = (TextView) findViewById(R.id.tvRange);
        tvEstimate = (TextView) findViewById(R.id.tvEstimate);
        tvStatus = (TextView) findViewById(R.id.tvStatus);
        tvResult = (TextView) findViewById(R.id.tvResult);
        tvTiming = (TextView) findViewById(R.id.tvTiming);
        tvPreviewHint = (TextView) findViewById(R.id.tvPreviewHint);
        tvMultiHint = (TextView) findViewById(R.id.tvMultiHint);
        videoView = (VideoView) findViewById(R.id.videoView);
        imagePreview = (ImageView) findViewById(R.id.imagePreview);
        outPreview = (ImageView) findViewById(R.id.outPreview);
        previewBox = (FrameLayout) findViewById(R.id.previewBox);
        resultActions = (LinearLayout) findViewById(R.id.resultActions);
        progressCard = (LinearLayout) findViewById(R.id.progressCard);
        batchList = (LinearLayout) findViewById(R.id.batchList);
        batchCard = (LinearLayout) findViewById(R.id.batchCard);
        trimRow = (LinearLayout) findViewById(R.id.trimRow);
        btnPick = (Button) findViewById(R.id.btnPick);
        btnPlay = (Button) findViewById(R.id.btnPlay);
        btnSetStart = (Button) findViewById(R.id.btnSetStart);
        btnSetEnd = (Button) findViewById(R.id.btnSetEnd);
        btnConvert = (Button) findViewById(R.id.btnConvert);
        btnSaveAs = (Button) findViewById(R.id.btnSaveAs);
        btnShare = (Button) findViewById(R.id.btnShare);
        btnPlayResult = (Button) findViewById(R.id.btnPlayResult);
        btnOpenPlayer = (Button) findViewById(R.id.btnOpenPlayer);
        btnClearBatch = (Button) findViewById(R.id.btnClearBatch);
        rowSize = (LinearLayout) findViewById(R.id.rowSize);
        rowFps = (LinearLayout) findViewById(R.id.rowFps);
        rowFit = (LinearLayout) findViewById(R.id.rowFit);
        rowQuality = (LinearLayout) findViewById(R.id.rowQuality);
        progress = (ProgressBar) findViewById(R.id.progress);
    }

    private void setupChips() {
        ChipRow.build(rowSize, Presets.SIZES, chipSize, new ChipRow.OnSelect() {
            public void onSelect(int index) { chipSize = index; updateEstimate(); }
        });
        ChipRow.build(rowFps, Presets.FPS, chipFps, new ChipRow.OnSelect() {
            public void onSelect(int index) { chipFps = index; updateEstimate(); }
        });
        ChipRow.build(rowFit, Presets.FITS, chipFit, new ChipRow.OnSelect() {
            public void onSelect(int index) { chipFit = index; updateEstimate(); }
        });
        ChipRow.build(rowQuality, Presets.QUALITIES, chipQuality, new ChipRow.OnSelect() {
            public void onSelect(int index) { chipQuality = index; updateEstimate(); }
        });
    }

    private void wireActions() {
        btnPick.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { pickFiles(); }
        });
        btnOpenPlayer.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { pickOne(REQ_OPEN_AMV); }
        });
        btnPlay.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { togglePlay(); }
        });
        btnSetStart.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { setTrim(true); }
        });
        btnSetEnd.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { setTrim(false); }
        });
        tvRange.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { clearTrim(); }
        });
        tvMultiHint.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { showMultiSelectHelp(); }
        });
        btnConvert.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { onConvertClicked(); }
        });
        btnSaveAs.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { saveAs(); }
        });
        btnShare.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { share(); }
        });
        btnPlayResult.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { playResult(); }
        });
        btnClearBatch.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { clearSources(); }
        });
        previewBox.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                if (!batchMode && info != null && info.kind == InputInfo.Kind.VIDEO) togglePlay();
            }
        });
    }

    // ------------------------------------------------------------------ picking

    private void pickFiles() {
        showMultiSelectHelpOnce();
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        i.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{
                "video/*", "audio/*", "image/*", "application/octet-stream"
        });
        try {
            startActivityForResult(i, REQ_PICK);
        } catch (Throwable t) {
            toast("无法打开文件选择器");
        }
    }

    /**
     * The system picker only enters multi-select on a long press, which nobody discovers on their
     * own, so batch conversion is explained once up front (and a permanent hint sits under the
     * button afterwards).
     */
    private void showMultiSelectHelpOnce() {
        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        if (prefs.getBoolean(PREF_MULTI_HINT, false)) return;
        prefs.edit().putBoolean(PREF_MULTI_HINT, true).apply();
        showMultiSelectHelp();
    }

    private void showMultiSelectHelp() {
        new AlertDialog.Builder(this)
                .setTitle("批量转换怎么用")
                .setMessage("在接下来的文件选择窗口里：\n\n"
                        + "• 想转一个文件：直接点它\n"
                        + "• 想批量转换：**长按**任意一个文件进入多选，再点其它文件，最后点「打开」\n\n"
                        + "（不同手机的文件选择器界面略有差别，但多选基本都是长按触发。）\n\n"
                        + "批量模式下参数统一应用，每个文件整段转换，不裁剪。")
                .setPositiveButton("知道了", null)
                .show();
    }

    private void pickOne(int request) {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        try {
            startActivityForResult(i, request);
        } catch (Throwable t) {
            toast("无法打开文件选择器");
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null) return;

        if (requestCode == REQ_PICK) {
            List<Uri> uris = new ArrayList<>();
            ClipData clip = data.getClipData();
            if (clip != null) {
                for (int i = 0; i < clip.getItemCount(); i++) {
                    Uri u = clip.getItemAt(i).getUri();
                    if (u != null) uris.add(u);
                }
            } else if (data.getData() != null) {
                uris.add(data.getData());
            }
            if (uris.isEmpty()) return;
            for (Uri u : uris) {
                try {
                    getContentResolver().takePersistableUriPermission(u,
                            Intent.FLAG_GRANT_READ_URI_PERMISSION);
                } catch (Throwable ignored) {
                }
            }
            if (uris.size() > 1) toast("已选 " + uris.size() + " 个文件，进入批量模式");
            setSources(uris);
            return;
        }

        if (data.getData() == null) return;
        Uri uri = data.getData();
        if (requestCode == REQ_OPEN_AMV) {
            startActivity(PlayerActivity.intentForUri(this, uri, uri.getLastPathSegment()));
        } else if (requestCode == REQ_SAVE_AS) {
            try {
                OutputSaver.copyToUri(this, outputFile, uri);
                toast("已另存到所选位置");
            } catch (Throwable t) {
                toast("另存失败: " + t.getMessage());
            }
        }
    }

    /** One file -> single mode with preview and trimming; several -> batch mode with a list. */
    private void setSources(List<Uri> uris) {
        if (uris.isEmpty()) return;
        batchMode = uris.size() > 1;
        batchUris.clear();
        batchUris.addAll(uris);
        batchNames.clear();
        batchStatus.clear();
        batchList.removeAllViews();
        tvResult.setText("");
        resultActions.setVisibility(View.GONE);
        trimStartUs = -1;
        trimEndUs = -1;
        trimming = false;

        if (batchMode) {
            info = null;
            sourceUri = null;
            stopPlayback();
            batchCard.setVisibility(View.VISIBLE);
            previewBox.setVisibility(View.GONE);
            trimRow.setVisibility(View.GONE);
            tvMultiHint.setVisibility(View.GONE);
            batchList.setVisibility(View.VISIBLE);
            tvFileName.setText("批量转换：" + uris.size() + " 个文件");
            tvFileInfo.setText("正在读取文件信息…");
            tvRange.setText("批量模式：每个文件整段转换，参数统一");
            buildBatchRows(uris);
            probeBatch();
        } else {
            batchCard.setVisibility(View.GONE);
            batchList.setVisibility(View.GONE);
            previewBox.setVisibility(View.VISIBLE);
            trimRow.setVisibility(View.VISIBLE);
            tvMultiHint.setVisibility(View.VISIBLE);
            loadSingle(uris.get(0));
        }
        updateEstimate();
    }

    private void clearSources() {
        batchMode = false;
        batchUris.clear();
        batchNames.clear();
        batchStatus.clear();
        batchList.removeAllViews();
        batchCard.setVisibility(View.GONE);
        previewBox.setVisibility(View.VISIBLE);
        trimRow.setVisibility(View.VISIBLE);
        tvMultiHint.setVisibility(View.VISIBLE);
        tvFileName.setText("未选择文件");
        tvFileInfo.setText("视频 / GIF / 图片 / 音乐");
        tvRange.setText("片段：请先选择文件");
        tvPreviewHint.setVisibility(View.VISIBLE);
        info = null;
        sourceUri = null;
        imagePreview.setVisibility(View.GONE);
        videoView.setVisibility(View.GONE);
        btnPlay.setEnabled(false);
        btnSetStart.setEnabled(false);
        btnSetEnd.setEnabled(false);
        updateEstimate();
    }

    private void buildBatchRows(List<Uri> uris) {
        batchList.removeAllViews();
        for (int i = 0; i < uris.size(); i++) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setPadding(0, dp(6), 0, dp(6));

            TextView name = new TextView(this);
            name.setText((i + 1) + ". " + shortName(uris.get(i).getLastPathSegment()));
            name.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            name.setTextColor(Color.parseColor("#101828"));
            name.setSingleLine(true);
            name.setEllipsize(TextUtils.TruncateAt.MIDDLE);
            LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            name.setLayoutParams(nlp);

            TextView status = new TextView(this);
            status.setText("等待");
            status.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            status.setTextColor(Color.parseColor("#667085"));
            status.setGravity(Gravity.END);
            status.setMinWidth(dp(84));

            row.addView(name);
            row.addView(status);
            batchList.addView(row);
            batchStatus.add(status);
        }
    }

    private static String shortName(String raw) {
        if (raw == null) return "文件";
        int slash = raw.lastIndexOf('/');
        return slash >= 0 ? raw.substring(slash + 1) : raw;
    }

    /** Probing several files can take a moment, so it happens off the UI thread. */
    private void probeBatch() {
        final List<Uri> uris = new ArrayList<>(batchUris);
        new Thread(new Runnable() {
            public void run() {
                long totalUs = 0;
                int withPicture = 0;
                final List<String> labels = new ArrayList<>();
                final List<Long> durations = new ArrayList<>();
                for (Uri u : uris) {
                    InputInfo ii = InputInfo.probe(MainActivity.this, u);
                    labels.add(shortName(ii.displayName));
                    durations.add(Math.max(0L, ii.durationUs));
                    totalUs += Math.max(0, ii.durationUs);
                    if (ii.kind == InputInfo.Kind.VIDEO || ii.kind == InputInfo.Kind.GIF) withPicture++;
                }
                final String summary = withPicture + " 个含画面 · 合计 " + InputInfo.prettyDuration(totalUs);
                final long totalForEstimate = totalUs;
                ui.post(new Runnable() {
                    public void run() {
                        if (destroyed) return;
                        batchNames.clear();
                        batchNames.addAll(labels);
                        batchTotalUs = totalForEstimate;
                        for (int i = 0; i < batchStatus.size() && i < durations.size(); i++) {
                            batchStatus.get(i).setText(InputInfo.prettyDuration(durations.get(i)));
                        }
                        tvFileInfo.setText(summary);
                        updateEstimate();
                    }
                });
            }
        }, "amv-probe").start();
    }

    private void loadSingle(Uri uri) {
        sourceUri = uri;
        info = InputInfo.probe(this, uri);
        updateRangeText();
        tvFileName.setText(info.displayName);
        StringBuilder sb = new StringBuilder();
        sb.append(InputInfo.prettyDuration(info.durationUs));
        if (info.width > 0 && info.height > 0) sb.append(" · ").append(info.width).append('x').append(info.height);
        if (info.sizeBytes > 0) sb.append(" · ").append(info.prettySize());
        if (info.hasAudio) sb.append(" · 含音频");
        tvFileInfo.setText(sb.toString());
        tvPreviewHint.setVisibility(View.GONE);
        btnSetStart.setEnabled(true);
        btnSetEnd.setEnabled(true);
        showPreview();
        updateEstimate();
    }

    private void showPreview() {
        if (info == null) return;
        if (info.kind == InputInfo.Kind.VIDEO) {
            imagePreview.setVisibility(View.GONE);
            videoView.setVisibility(View.VISIBLE);
            try {
                videoView.setVideoURI(sourceUri);
                videoView.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
                    public void onPrepared(MediaPlayer mp) {
                        mp.setLooping(true);
                        videoView.seekTo(1);
                    }
                });
                videoView.setOnErrorListener(new MediaPlayer.OnErrorListener() {
                    public boolean onError(MediaPlayer mp, int what, int extra) {
                        showStillThumbnail();
                        return true;
                    }
                });
            } catch (Throwable t) {
                showStillThumbnail();
            }
            btnPlay.setEnabled(true);
            btnPlay.setText("播放");
        } else {
            stopPlayback();
            videoView.setVisibility(View.GONE);
            imagePreview.setVisibility(View.VISIBLE);
            btnPlay.setEnabled(false);
            btnPlay.setText("预览");
            showStillThumbnail();
        }
    }

    private void showStillThumbnail() {
        try {
            if (info != null && info.kind == InputInfo.Kind.GIF) {
                android.graphics.Movie m = android.graphics.Movie.decodeStream(
                        getContentResolver().openInputStream(sourceUri));
                if (m != null) {
                    Bitmap bmp = Bitmap.createBitmap(Math.max(1, m.width()), Math.max(1, m.height()),
                            Bitmap.Config.ARGB_8888);
                    m.setTime(0);
                    m.draw(new android.graphics.Canvas(bmp), 0, 0);
                    imagePreview.setImageBitmap(bmp);
                    return;
                }
            }
            java.io.InputStream in = getContentResolver().openInputStream(sourceUri);
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inSampleSize = 2;
            Bitmap bmp = BitmapFactory.decodeStream(in, null, o);
            if (in != null) in.close();
            imagePreview.setImageBitmap(bmp);
        } catch (Throwable t) {
            imagePreview.setImageDrawable(null);
        }
    }

    private void togglePlay() {
        if (info == null || info.kind != InputInfo.Kind.VIDEO) return;
        if (videoView.isPlaying()) {
            videoView.pause();
            btnPlay.setText("播放");
        } else {
            videoView.start();
            btnPlay.setText("暂停");
        }
    }

    private void stopPlayback() {
        try {
            if (videoView.isPlaying()) videoView.pause();
        } catch (Throwable ignored) {
        }
    }

    // ------------------------------------------------------------------ trimming

    private void setTrim(boolean start) {
        if (info == null || batchMode) return;
        if (info.kind != InputInfo.Kind.VIDEO) {
            toast("当前输入不支持裁剪");
            return;
        }
        long pos = videoView.getCurrentPosition() * 1000L;
        if (start) {
            trimStartUs = pos;
            if (trimEndUs > 0 && trimEndUs <= trimStartUs) trimEndUs = -1;
        } else {
            trimEndUs = pos;
            if (trimStartUs < 0) trimStartUs = 0;
        }
        trimming = trimStartUs > 0 || (trimEndUs > 0 && info.durationUs > 0
                && trimEndUs < info.durationUs - 200000L);
        updateRangeText();
        updateEstimate();
    }

    private void clearTrim() {
        if (batchMode) {
            toast("批量模式不支持裁剪");
            return;
        }
        if (info == null) return;
        trimStartUs = -1;
        trimEndUs = -1;
        trimming = false;
        updateRangeText();
        updateEstimate();
        toast("已恢复整段转换");
    }

    private void updateRangeText() {
        if (batchMode) {
            tvRange.setText("批量模式：每个文件整段转换，参数统一");
            return;
        }
        if (info == null) {
            tvRange.setText("片段：请先选择文件");
            return;
        }
        if (!trimming) {
            tvRange.setText("片段：全片（" + InputInfo.prettyDuration(info.durationUs) + "）· 点此可清除");
        } else {
            long s = trimStartUs < 0 ? 0 : trimStartUs;
            long e = trimEndUs < 0 ? info.durationUs : trimEndUs;
            tvRange.setText("片段：" + InputInfo.prettyDuration(s) + " → "
                    + InputInfo.prettyDuration(e) + " · 点此清除");
        }
    }

    private void updateEstimate() {
        Presets.Size size = Presets.SIZES[chipSize];
        Presets.Fps fps = Presets.FPS[chipFps];
        Presets.Quality q = Presets.QUALITIES[chipQuality];
        String note = Presets.isVerifiedCombo(chipSize, chipFps) ? " · 已实测可播放" : "";

        if (batchMode) {
            if (batchNames.isEmpty() || batchTotalUs <= 0) {
                tvEstimate.setText(batchNames.isEmpty() ? "读取文件信息中…"
                        : batchNames.size() + " 个文件 · 参数统一应用" + note);
                return;
            }
            long bytes = Presets.estimateBytes(size, fps, q.scale, batchTotalUs);
            long frames = Math.max(1, Math.round(batchTotalUs / 1000000.0 * fps.num / fps.den));
            tvEstimate.setText(String.format(Locale.US,
                    "%d 个文件 · 预计共 %.1f MB · %d 帧%s",
                    batchNames.size(), bytes / 1048576.0, frames, note));
            return;
        }
        if (info == null) {
            tvEstimate.setText("选择文件后显示预计体积");
            return;
        }
        long spanStart = !batchMode && trimming && trimStartUs > 0 ? trimStartUs : 0;
        long spanEnd = info.durationUs;
        if (!batchMode && trimming && trimEndUs > 0) {
            spanEnd = Math.min(spanEnd == 0 ? trimEndUs : spanEnd, trimEndUs);
        }
        long span = spanEnd - spanStart;
        if (span <= 0) span = info.durationUs;
        if (span <= 0) {
            tvEstimate.setText("无法确定时长");
            return;
        }
        long bytes = Presets.estimateBytes(size, fps, q.scale, span);
        long frames = Math.max(1, Math.round(span / 1000000.0 * fps.num / fps.den));
        tvEstimate.setText(String.format(Locale.US,
                "预计 %.1f MB · %d 帧 · 音频 22050Hz 单声道%s", bytes / 1048576.0, frames, note));
    }

    // ------------------------------------------------------------------ converting

    private void onConvertClicked() {
        ConversionState.Snapshot snapshot = ConversionState.current();
        if (snapshot.running) {
            ConvertService.cancel(this);
            btnConvert.setText("正在停止…");
            return;
        }
        List<Uri> uris;
        if (batchMode) {
            if (batchUris.isEmpty()) {
                toast("请先选择文件");
                return;
            }
            uris = new ArrayList<>(batchUris);
        } else {
            if (info == null || sourceUri == null) {
                toast("请先选择文件");
                return;
            }
            if (effectiveSpanEnd() <= effectiveSpanStart()) {
                toast("无法确定时长");
                return;
            }
            uris = new ArrayList<>();
            uris.add(sourceUri);
        }
        if (OutputSaver.needsLegacyPermission()
                && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQ_LEGACY_PERM);
            return;
        }
        startConversion(uris);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_LEGACY_PERM) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                if (batchMode) startConversion(new ArrayList<>(batchUris));
                else {
                    List<Uri> one = new ArrayList<>();
                    one.add(sourceUri);
                    startConversion(one);
                }
            } else {
                toast("没有存储权限，可用「另存为」保存到任意位置");
            }
        }
    }

    private long effectiveSpanStart() {
        return !batchMode && trimming && trimStartUs > 0 ? trimStartUs : 0;
    }

    private long effectiveSpanEnd() {
        if (info == null) return 0;
        long spanEnd = info.durationUs;
        if (!batchMode && trimming && trimEndUs > 0) {
            spanEnd = Math.min(spanEnd == 0 ? trimEndUs : spanEnd, trimEndUs);
        }
        return spanEnd;
    }

    private void startConversion(List<Uri> uris) {
        stopPlayback();
        resultActions.setVisibility(View.GONE);
        progressCard.setVisibility(View.VISIBLE);
        progress.setProgress(0);
        tvStatus.setText("准备中…");
        tvTiming.setText("输出画面实时预览");
        tvResult.setText("");
        btnConvert.setText("停止");
        outputFile = null;
        outputSaverUri = null;
        requestNotificationPermissionIfNeeded();
        ConvertService.start(this, ConvertService.jobIntent(this, uris,
                Presets.SIZES[chipSize], Presets.FPS[chipFps], Presets.FITS[chipFit],
                Presets.QUALITIES[chipQuality], effectiveSpanStart(), effectiveSpanEnd()));
    }

    /** Only affects whether the progress notification is visible; the job runs either way. */
    private void requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < 33) return;
        try {
            if (checkSelfPermission("android.permission.POST_NOTIFICATIONS")
                    != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 1005);
            }
        } catch (Throwable ignored) {
        }
    }

    // ------------------------------------------------------------------ service state

    @Override
    public void onConversionState(final ConversionState.Snapshot s) {
        if (destroyed) return;
        boolean running = s.running;
        boolean finished = s.finished;

        if (running || finished) {
            progressCard.setVisibility(View.VISIBLE);
        }
        btnConvert.setText(running ? "停止" : (batchMode ? "批量转换" : "开始转换"));
        btnPick.setEnabled(!running);
        btnOpenPlayer.setEnabled(!running);

        if (s.status != null && s.status.length() > 0) tvStatus.setText(s.status);
        progress.setProgress(s.percent);
        if (s.detail != null && s.detail.length() > 0 && s.detail.indexOf("row:") != 0) {
            tvTiming.setText(s.detail);
        }
        if (s.previewJpeg != null) {
            try {
                // AMV 画面倒着存，解码端负责翻回来；见 AmvFrameBitmap。
                Bitmap bmp = AmvFrameBitmap.decodeUpright(s.previewJpeg, s.previewJpeg.length);
                if (bmp != null) outPreview.setImageBitmap(bmp);
            } catch (Throwable ignored) {
            }
        }
        if (s.rows != null && batchStatus.size() == s.rows.length) {
            for (int i = 0; i < s.rows.length; i++) {
                String text = s.rows[i];
                if (text == null) continue;
                TextView tv = batchStatus.get(i);
                if (!text.equals(tv.getText().toString())) {
                    tv.setText(text);
                    tv.setTextColor(Color.parseColor(colorFor(text)));
                }
            }
        }
        if (finished) {
            tvResult.setText(s.result == null ? "" : s.result);
            resultActions.setVisibility(View.VISIBLE);
            btnPlayResult.setVisibility(batchMode ? View.GONE : View.VISIBLE);
            if (s.resultFile != null) outputFile = new File(s.resultFile);
            outputSaverUri = s.resultUri == null ? null : Uri.parse(s.resultUri);
            outputName = s.resultName;
            if (!s.running) progress.setProgress(100);
        }
    }

    private static String colorFor(String rowText) {
        if (rowText.startsWith("完成")) return "#067647";
        if (rowText.startsWith("失败") || rowText.startsWith("跳过")) return "#B42318";
        if (rowText.equals("已取消")) return "#667085";
        if (rowText.endsWith("%")) return "#2B6CF6";
        return "#667085";
    }

    // ------------------------------------------------------------------ result

    private void playResult() {
        if (outputFile == null || !outputFile.exists()) {
            toast("没有可播放的文件（批量模式请用「另存为」导出后再打开）");
            return;
        }
        startActivity(PlayerActivity.intentFor(this, outputFile, "转换结果预览"));
    }

    private void saveAs() {
        if (outputFile == null || !outputFile.exists()) {
            toast("没有可单独保存的文件（批量结果已分别保存）");
            return;
        }
        Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("application/octet-stream");
        i.putExtra(Intent.EXTRA_TITLE, outputName != null ? outputName
                : OutputSaver.suggestedName(info == null ? "video" : info.displayName));
        try {
            startActivityForResult(i, REQ_SAVE_AS);
        } catch (Throwable t) {
            toast("无法打开保存对话框");
        }
    }

    private void share() {
        if (outputFile == null || !outputFile.exists()) {
            toast("没有可分享的文件");
            return;
        }
        try {
            Uri uri = outputSaverUri;
            if (uri == null) {
                uri = OutputSaver.save(this, outputFile,
                        outputName != null ? outputName
                                : OutputSaver.suggestedName(info == null ? "video" : info.displayName)).uri;
                outputSaverUri = uri;
            }
            Intent i = new Intent(Intent.ACTION_SEND);
            i.setType("application/octet-stream");
            i.putExtra(Intent.EXTRA_STREAM, uri);
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(i, "分享 AMV 文件"));
        } catch (Throwable t) {
            toast("分享失败: " + t.getMessage());
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        destroyed = true;
        ConversionState.removeObserver(this);
        stopPlayback();
        // Deliberately NOT cancelling the conversion: it belongs to the service and must survive
        // this Activity going away (split screen, floating window, recreation, backgrounding).
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void toast(String s) {
        if (!TextUtils.isEmpty(s)) Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }
}
