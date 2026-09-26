# AMV ("Actions Media Video") — Community / Practical Documentation Survey

Research brief: how people actually create, play and hit limits with `.amv` files for cheap
Chinese MP3/MP4/MP5 players. **This is a research report, not a format spec.** Community /
practical sources in English and Chinese, plus the FFmpeg implementation as the primary
source of truth.

Legend used throughout:

- **[QUOTE]** = verbatim text/dumpsurable from a real, reachable source (URL given).
- **[SRC]** = taken directly from FFmpeg source code (authoritative primary source, not community).
- **[INFER]** = my own inference or arithmetic — not quoted from anyone.
- **[UNVERIFIED]** = I could not confirm this; treat as unknown.

Method notes / tooling limits that shaped coverage:

- Stack Exchange HTML pages return HTTP 403 to this tool; all SE material below was retrieved
  through the public Stack Exchange API (`api.stackexchange.com/2.3/...`), which returns the
  same post bodies. URLs cited are the normal human-facing question URLs.
- `web.archive.org` was unreachable (fetch errors) for the entire session, so the classic
  **amv-codec-tools `AmvDocumentation` wiki page** could not be read. It is cited only where a
  third party quotes or links it. **Its contents are UNVERIFIED here.**
- `lists.ffmpeg.org` and `lists.mplayerhq.hu` are behind an "Anubis" proof-of-work wall →
  ffmpeg-devel / MPlayer-dev-eng 2007 AMV patch threads could not be read (titles only).
  `mailman.videolan.org` and `www.mail-archive.com` were reachable and are used instead.
- `zhihu.com` returns 403; `en.wikipedia.org` / `*.m.wikipedia.org` are blocked at DNS in this
  environment → the Wikipedia AMV article was read indirectly via **DBpedia**.
- A research subagent I dispatched for additional Chinese byte-level sources failed; a second
  (playback/limits) was still running when this report was written. Coverage gaps are flagged
  explicitly rather than guessed.

---

## 0. Headline correction: the fourCC is `AMVF`, not `AMVV`/`AMVA`

**[SRC]** The current FFmpeg RIFF fourCC registry contains exactly **one** AMV video entry.
I decoded the whole file from the GitHub API and searched it:

```
    { AV_CODEC_ID_AMV,          MKTAG('A', 'M', 'V', 'F') },
```

- `libavformat/riff.c`: <https://github.com/FFmpeg/FFmpeg/blob/master/libavformat/riff.c>
  (retrieved via <https://api.github.com/repos/FFmpeg/FFmpeg/contents/libavformat/riff.c>)
- There is **no** `AMVV` and **no** `AMVA` in that file. **Treat `AMVV`/`AMVA` as NOT container
  fourCCs.** ⚠️ **CORRECTION (added after a second research pass):** an `AMVV` token *does* occur in
  the wild, but as an **MPC-HC internal-filter identifier, not a fourCC**: a user-supplied
  `mpc-hc64.ini` contains `TRA_AMVV=1` under `[Internal Filters]` alongside `TRA_MJPEG=1` /
  `TRA_H264=1`, and the MPC-HC installer deletes `HKCR\mplayerc64.amv` (so MPC-HC registers `.amv`).
  Sources: <https://github.com/clsid2/mpc-hc/issues/3801> and
  <https://github.com/clsid2/mpc-hc/issues/1568> . `TRA_AMVV` is very probably "AMV **V**ideo" —
  i.e. the identifier names the codec, not a fourCC stored in the file. See §12.
- The only community text I found using `AMVF` is a 2019 Super User report: the user encoded to
  `.avi` and renamed it, then "MediaInfo reports that output.amv is in AVI format, and its video
  portion is in **AMVF** format" — <https://superuser.com/questions/1426454/using-ffmpeg-to-convert-video-solution-to-rejected-amv-container>

**[SRC]** Note the asymmetry: FFmpeg's own **muxer** writes *no* fourCC at all for the video
stream — it fills the `strh`/`strf` bodies with zeros and deliberately writes zero tag sizes:

```c
static void amv_write_vlist(AVFormatContext *s, AVCodecParameters *par)
{
    ...
    tag_str = ff_start_tag(s->pb, "strh");
    ffio_fill(s->pb, 0, AMV_VIDEO_STRH_SIZE);
    ff_end_tag(s->pb, tag_str);
    ...
}
static int64_t amv_start_tag(AVIOContext *pb, const char *tag)
{
    ffio_wfourcc(pb, tag);
    avio_wl32(pb, 0);      /* size deliberately 0 */
    return avio_tell(pb);
}
```

with the file header comment: "The sizes of certain tags are deliberately set to 0 as some
players break when they're set correctly."
Source: <https://github.com/FFmpeg/FFmpeg/commit/a2fea0f4690e4f11ba093f14509643c44a485165> (full `amvenc.c` is in the commit patch).

---

## 1. Verbatim ffmpeg command lines found in the wild

All lines below are copied exactly as written, including typos and stray double spaces.

### 1.1 Stack Exchange (English)

| # | Command line (verbatim) | Outcome reported | Source |
|---|---|---|---|
| 1 | `ffmpeg.exe -i "c:\source160x120.mp4" -c:v amv -c:a adpcm_ima_amv -pix_fmt yuvj420p -vstrict -1 -s 160x120 -ar 22050 -b:a 40400  "c:\destination.amv"` | **FAILED** — "Only mono is supported" | <https://superuser.com/questions/1649146/convert-mpeg4-to-amv-via-ffmpeg> |
| 2 | `ffmpeg.exe -i "c:\source160x120.mp4" -c:v amv -c:a adpcm_ima_amv -pix_fmt yuvj420p -vstrict -1 -s 160x120 -ar 22050 -b:a 40400  -af pan="mono\| c0=FL" "c:\destination.amv"` | **FAILED** — "Invalid audio frame size… Try -block_size 1378" | same |
| 3 | `ffmpeg -i input.mp4 -ac 1 -ar 22050 -r 25 -block_size 882 output.amv` | **accepted answer** (llogan); note: no `-f amv`, no `-c:v`, no `-c:a` at all | <https://superuser.com/questions/1649146/convert-mpeg4-to-amv-via-ffmpeg> (answer 1649175) |
| 4 | `ffmpeg.exe -i "c:\input160x120.mp4" -c:v amv -c:a adpcm_ima_amv -pix_fmt yuvj420p -vstrict -1 -s 160x120  -ac 1 -ar 22050 -r 25 -block_size 882   "c:\output.amv"` | **WORKED** — "checked on the player that it was converted for and it looks like it works includin timing and all!" | same (answer 1649185) |
| 5 | `ffmpeg -r 25 -i input.mp4 -c:v amv -vf scale=160:128 output.amv` | **FAILED** (2019, pre-4.4) | <https://superuser.com/questions/1426454/using-ffmpeg-to-convert-video-solution-to-rejected-amv-container> |
| 6 | `ffmpeg -i C:\Users\26591\Videos\WebADB-Demo.mp4 -s 160x120 -ac 1 -ar 22050 -vstrict -1 -r 25 -block_size 882 C:\Users\26591\Videos\WebADB-Demo.amv` | worked in ~March 2022; **later FAILED** with "AMV files only support 2 streams" | <https://superuser.com/questions/1740001/> |
| 7 | `ffmpeg -i C:\Users\26591\Videos\WebADB-Demo.mp4 -s 160x120 -ac 1 -ar 22050 -vstrict -1 -r 25 -block_size 882 -map 0:0 -c:v amv -c:a adpcm_ima_amv -pix_fmt yuvj420p C:\Users\26591\Videos\WebADB-Demo.amv` | still FAILED identically | same |
| 8 | `ffmpeg -i input.mp4 -ac 1 -ar 22050 -r 10 -block_size 2205 -vf scale=w=208:h=176 output.amv` | "**And it works.**" (but stretched aspect ratio) | <https://superuser.com/questions/1852829/how-do-i-convert-mp4-files-to-amv-with-ffmpeg> |
| 9 | `-c:v amv -c:a adpcm_ima_amv -pix_fmt yuvj420p -vf "scale=148:128:force_original_aspect_ratio=dec reas e,crop=128:in_h:10:0,pad=160:128:32:-1:color=black" -vstrict -1 -r 30 -ac 1 -ar 22050 -block_size 735` | works but "the video quality is poor, blurry"; **question has 0 answers** | <https://video.stackexchange.com/questions/36730/ffmpeg-amv-conversion> |
| 10 | `-c:v amv -c:a adpcm_ima_amv -pix_fmt yuvj420p -vf "scale=296:256:force_original_aspect_ratio:flags=lanczos,crop=256:in_h:20:0,pad=320:256:64:-1:color=black" -vstrict -1 -r 18 -ac 1 -ar 22050 -block_size 1225` | works; asked only how to speed it up | <https://superuser.com/questions/1809283/convert-mp4-to-amv-faster-ffmpeg> |

**[QUOTE] llogan (accepted answer), the single most useful rule set found:**

```
ffmpeg -i input.mp4 -ac 1 -ar 22050 -r 25 -block_size 882 output.amv
```

- "Audio channel layout must be mono (`-ac 1`)."
- "Audio sample rate must be 22050 (`-ar 22050`)."
- "Frame rate must be divisible by the audio sample rate (22050). So frame rate (`-r`) can be 10, 14, 15, 18, 21, 25, 30, etc."
- "`-block_size` depends on frame rate. The console output will tell you what to use. Example message: `Try -block_size 1378`."

Source: <https://superuser.com/questions/1649146/convert-mpeg4-to-amv-via-ffmpeg>

### 1.2 Chinese sources

| # | Command line (verbatim) | Source |
|---|---|---|
| 11 | `ffmpeg -i input.mp4 -ar 22050 -ac 1 -vf "scale=160*128" -r 15 -block_size 1470 output.amv` | <https://blog.csdn.net/2401_86101673/article/details/145552499> (mirrored at <https://2048ai.net/682d8274606a8318e858c9c6.html>) |
| 12 | `ffmpeg -i "$1" -vf "scale=-2:128, crop=160:128" -r 14 -pix_fmt yuvj420p -c:v amv -c:a adpcm_ima_amv -ac 1 -ar 22050 -block_size 1575 "$name"-p.amv` | `video2actions18.sh` in <https://github.com/fdd4s/portable_mp3_player_video_converter_tools> |
| 13 | `ffmpeg -i source.mp4 -vf "scale=-2:240, crop=320:240" -r 14 -pix_fmt yuvj420p -c:v amv -c:a adpcm_ima_amv -ac 1 -ar 22050 -block_size 1575 dest.amv` | same repo README (Actions 2.4-inch 240x320 variant) |

**[QUOTE] The Chinese tutorial at #11 documents its own flags, and mislabels them.** Verbatim:

> `-ar 22050`: 设置音频采样率为 22050 Hz
> `-ac 1`: 设置音频通道数为单声道
> `-s 160*128`: 设置输出视频的分辨率为 160\*128 像素
> `-r 15`: 设置帧率为每秒 15 帧
> `-block_size 1470`: 设置块大小为 1470 字节

Two problems worth flagging: the actual flag in the command is `-vf "scale=160*128"` (FFmpeg's
`scale` filter wants `160:128`, not `160*128` — this is a broken filtergraph), and the
explanation calls it `-s`. **[INFER]** This tutorial's command is very likely non-functional as
written; do not propagate it as a working recipe.

**[QUOTE] 2007-era community fork of ffmpeg (pre-dates upstream AMV support entirely)** — the
`amv-codec-tools` project's Windows/Linux binary, from the VLC mailing list:

```
./amv-ffmpeg-linux-i386-20071030 -i sky.wmv -s 160x88 -ac 1 -ar 22050 -qmin 3 -qmax 3 -padtop 16 -padbottom 16 skypadtop.amv
```

followed immediately by the most valuable practical data point in the whole survey:

> "Note that -r 12 to make the AMV run at 12fps produced a file the player (a cheap S1 MP3 player) wouldn't play."

Source: <https://mailman.videolan.org/pipermail/vlc/2007-December/015249.html>

### 1.3 Summary of which flags actually appear

- `-c:v amv` — used in most recipes; **not strictly required** (see §2).
- `-c:a adpcm_ima_amv` — used in most 2021+ recipes; not required when the source audio already
  matches what the muxer wants.
- `-pix_fmt yuvj420p` — used in nearly every recipe. **[SRC]** FFmpeg emits
  `[swscaler] deprecated pixel format used, make sure you did set range correctly` for it; every
  tutorial that uses it also shows that warning.
- `-vstrict -1` — appears in several recipes. **[UNVERIFIED]** what problem it solves; no source
  explains it and `amvenc.c` contains no `strict_std_compliance` reference. Likely cargo-culted.
- `-f amv` — **appears in none of the community command lines I found.** (See §2.)
- `-block_size N` — near-universal; values seen: 882, 1225, 1470, 1575, 2205, 735, 1378.
- `-r` values seen: 10, 14, 15, 18, 25, 30, 12 (2007). Also `-r 16` in the fdd4s **AVI/MJPEG**
  scripts — note those two scripts (`video2shenju18.sh`, `video2shenju24.sh`) are for a *different*
  format (MJPEG AVI), not AMV; the two *AMV* scripts use `-r 14`.
- `-s 160x120`, `-vf scale=...`, `-ar 22050`, `-ac 1` — universal.

---

## 2. Is `-f amv` required? — **No, on FFmpeg ≥ 4.4**

**[SRC]** The muxer is registered with extension-based detection:

```c
AVOutputFormat ff_amv_muxer = {
    .name           = "amv",
    .long_name      = NULL_IF_CONFIG_SMALL("AMV"),
    .mime_type      = "video/amv",
    .extensions     = "amv",
    ...
    .audio_codec    = AV_CODEC_ID_ADPCM_IMA_AMV,
    .video_codec    = AV_CODEC_ID_AMV,
```

Source: <https://github.com/FFmpeg/FFmpeg/commit/a2fea0f4690e4f11ba093f14509643c44a485165>

So `output.amv` is auto-detected. Empirically confirmed by the accepted Super User answer
(`output.amv`, no `-f amv`, no codec flags) and by the 2022 report.

**Version boundary — this is the crux of most "it doesn't work" reports:**

- Commit adding the AMV muxer: **2020-11-09**, `a2fea0f4690e4f11ba093f14509643c44a485165` —
  <https://github.com/FFmpeg/FFmpeg/commit/a2fea0f4690e4f11ba093f14509643c44a485165>
- **[SRC]** FFmpeg **4.4** changelog lists, in order, "**ADPCM IMA AMV encoder**" and
  "**AMV muxer**" — <https://abi-laboratory.pro/index.php?view=changelog&l=ffmpeg&v=4.4>
  → **FFmpeg 4.4 (April 2021) is the first release with AMV writing support.**
- Before that, the exact failure modes people reported:
  - 2011, ffmpeg 0.9 / amv-codec-tools: `[NULL @ 039a7860] Requested output format 'amv' is not a suitable output format` / `sample.amv: Invalid argument` — <https://trac.ffmpeg.org/ticket/747>
  - 2019: `Unable to find a suitable output format for 'output.amv'` / `output.amv: Invalid argument` — <https://superuser.com/questions/1426454/using-ffmpeg-to-convert-video-solution-to-rejected-amv-container>
- The 2019 workaround the community used, verbatim from that SU question: "if I change the command
  to produce output.avi rather than output.amv, and then rename the finished output.avi to be
  output.amv, I get a file that plays on the computer… An attempt to play output.amv produces
  **"Format error"** on the portable device that requires AMV files, unfortunately."
  A Reddit answer he cites: "FFmpeg supports the amv codec but for some reason not the amv container."

**Tracer ticket:** <https://trac.ffmpeg.org/ticket/747> ("Enable AMV encoding for audio", opened
2011-12-14 by Shimmy, closed 2020-11-09 as fixed). Verbatim from it: "The amv format is made for
**chinese s1mp3 hardware players**, and is now part of the latest version of FFmpeg (0.9 -
Harmony). However, its underlying audio is adpcm_ima_amv, and this is unsupported by FFmpeg… it
contains the line `D A D adpcm_ima_amv ADPCM IMA AMV`, which means Decoding supported, Audio codec,
Direct rendering, but it doesn't contain E which stands for Encoding." The same ticket points at
`amv-codec-tools` (`/trunk/AMVmuxer/ffmpeg/libavcodec/adpcm.c`) and at
"More documentation on the AMV format" = `code.google.com/p/amv-codec-tools/wiki/AmvDocumentation`,
plus "Source code for an AMV decoder written in Perl" = `svn.rot13.org/index.cgi/amv/view/amv.pl`.
**Both of those artefact URLs are almost certainly dead now; I could not retrieve either.**

**[SRC] Official commit message is itself quotable colour:** *"avformat: add amv muxer — AMV is a
hard-coded (and broken) subset of AVI. It's not worth sullying the existing AVI muxer with its
filth. Fixes ticket #747."* (Zane van Iperen, 2020)

**[SRC]** The muxer's own header comment block, which is the best practical spec of the player
constraints that exists anywhere:

```
 * Things to note:
 * - AMV is a hard-coded (and broken) subset of AVI. It's not worth sullying the
 *   existing AVI muxer with its filth.
 * - No separate demuxer as the existing AVI demuxer can handle these.
 * - The sizes of certain tags are deliberately set to 0 as some players break
 *   when they're set correctly. Ditto with some header fields.
 * - There is no index.
 * - Players are **very** sensitive to the frame order and sizes.
 *   - Frames must be strictly interleaved as V-A, any V-V or A-A will
 *     cause crashes.
 *   - Variable video frame sizes seem to be handled fine.
 *   - Variable audio frame sizes cause crashes.
 *   - If audio is shorter than video, it's padded with silence.
 *   - If video is shorter than audio, the most recent frame is repeated.
```

---

## 3. Resolutions and frame rates that actually work

### 3.1 Observed on real hardware (highest-confidence numbers)

**[QUOTE]** A factory `.amv` shipped on a cheap player, dumped by ffmpeg (2021):

```
Input #0, avi, from '..\1.8_Test-2.amv':
  Duration: N/A, start: 0.000000, bitrate: N/A
  Stream #0:0: Video: amv, yuvj420p(pc, bt470bg/unknown/unknown), 160x120, 16 fps, 16 tbr, 16 tbn, 16 tbc
  Stream #0:1: Audio: adpcm_ima_amv ([1][0][0][0] / 0x0001), 22050 Hz, mono, s16, 88 kb/s
```

plus the muxer's own warning while copying it: `scale/rate is 0/0 which is invalid. (This file has
been generated by broken software.)` and `[amv @ ...] Invalid audio packet size (698 != 697)`.

Source: <https://superuser.com/questions/1649146/convert-mpeg4-to-amv-via-ffmpeg>

→ **[INFER]** Real-world sweet spot corroborated by hardware: **160x120 @ 16 fps, audio
22050 Hz mono**. Note that `duration` is `N/A` in ffmpeg's own readback and the AVI `scale/rate`
fields are zero — the file really is malformed by AVI rules.

### 3.2 Reported "supported" ranges and maxima

| Claim (verbatim) | Source | Reliability |
|---|---|---|
| "low frame rate (12-16 fps), low resolution (**up to 208×176**)" ; "these files use a modified AVI container and the Motion JPEG (MJPEG) video codec in combination with a version of the IMA ADPCM audio codec" ; "The AMV format has been reverse-engineered and its support added to ffmpeg, so all ffmpeg-based media players are capable of opening `.amv` files for playback." | <https://www.filetypeadvisor.com/extension/amv> (and its Chinese twin <https://www.filetypeadvisor.com/zh-cn/extension/amv>) | Vendor file-info site; **medium** — but 208×176 independently matches the SU user who used `scale=w=208:h=176` |
| "Standard Resolution: **160×120 or 220×176** (Higher may cause Format Error)" ; "Optimal Frame Rate: **12-16 FPS** (Reduces lag on legacy processors)"; "Audio Codec **IMA ADPCM**" | <https://www.anymp4.com/video-converter/mp4-to-amv.html> | Commercial SEO page, 2026, AI-assisted; **low-medium**. Note 220×176 conflicts with 208×176 |
| "AMV支持的分辨率相对较低，通常为 **120×160、160×120、176×200** 等适合小屏幕播放的尺寸" | <https://blog.csdn.net/stitches_fly/article/details/139372064> | Student blog; **medium** for the value range |
| "推荐的分辨率有 **320\*240、160\*128**；推荐的帧率有 **14、15、25fps**" + "如果视频的原始分辨率或者帧率太高，容易出现转换为amv格式后播放画面卡顿的情况" | <http://m.pcgeshi.com/faq/daorump3mp4.html> (Format Factory official FAQ) | **Vendor-official**; good for "what the tool defaults to" |
| "分辨率极低常见如 **96x96, 128x128, 160x120** 帧率也低**通常12fps或以下** 音频是单声道ADPCM" | <http://www.ldpk.cn/news/4798> | **Low** — 2026 SEO/AI-generated page; do not rely on |
| "固定分辨率（常见为 **208×176、224×176、320×240**）、固定帧率（通常为**12fps或16fps**）… 仅含I帧和P帧的 **MPEG-1** 风格视频编码，并强制使用ADPCM音频编码" ; "替换RIFF头标识为 **"AMV\0"** … 精简FOURCC字段（视频流设为 **"MPG1"**，音频流设为 **"ADPM"**），并将数据块对齐至**2048字节边界**" | <https://wenku.csdn.net/doc/6td256ft8n> (CSDN download-page blurb) | **DO NOT CITE.** See §9 — these byte-level claims directly contradict the verified FFmpeg source |

**Concrete resolutions users actually pushed and reported working:** 160x120, 160x128, 160x88,
208x176, 240x320, 320x256. **[UNVERIFIED]** whether 320x240/320x256 actually plays on any given
cheap player — the users who used them reported only that *encoding* succeeded.

**What happens when you exceed the device's limits** — the reports, verbatim:

- File copied to the player → "**格式错误**" / "文件格式错误" / "Format error" / "Format Not
  Supported". Sources: <https://blog.csdn.net/stitches_fly/article/details/139372064>
  ("放到数码播放器里打开文件会显示文件格式错误！！！"), SU 1426454, AnyMP4 page.
- Stutter / lag: "如果视频的原始分辨率或者帧率太高，容易出现转换为amv格式后播放画面卡顿的情况"
  (<http://m.pcgeshi.com/faq/daorump3mp4.html>).
- Blank / black screen: a changelog entry for AMV格式转换器 lists a fixed bug "修改转换出来文件PC上播放黑屏BUG" — <https://xiazai.zol.com.cn/baike/561645.shtml>
- Mirrored/upside-down video from a wrong screen-size preset: "不要用1.8寸，我试过1.8寸不行的，输出后就镜像颠倒出BUG…所以我建议你们选1.5寸" — <https://2048ai.net/6824650da5baf817cf4bf9a3.html>
- Refuses to play at all: "**-r 12** … produced a file the player (a cheap S1 MP3 player) wouldn't play." — <https://mailman.videolan.org/pipermail/vlc/2007-December/015249.html>
- Buzzing/hissing audio, with the community's fix: "**如果有滋滋声音请用快转转为avi mpeg4格式后再转amv**" — <https://blog.csdn.net/stitches_fly/article/details/139372064>
- Player shows "播放错误" until the card is formatted: "**放入数码播放器（我用的是内部存储，不是存储卡）后一定要进行格式化**，不然会显示播放错误，这是作者问数码播放器商家问来的。" — same CSDN post.

**Screen-size presets are a real, practical taxonomy in the Chinese community.** Tools name their
output presets by screen diagonal, not by pixel dimensions: 「1.5寸AMV转换.exe」 vs 1.8寸 / 2.4寸.
Sources: <https://2048ai.net/6824650da5baf817cf4bf9a3.html> and the fdd4s repo README, which lists
players as "Portable MP3 Player Actions 1.8 inch **128x160**" and "Actions 2.4 inch **240x320**"
(<https://github.com/fdd4s/portable_mp3_player_video_converter_tools>).

---

## 4. Practical limits: size, duration, bitrate

**Verified numbers:**

- Real factory file: **62.00 s → 5031 kB total (664.7 kbits/s), video 4339 kB + audio 675 kB.**
  `<https://superuser.com/questions/1649146/convert-mpeg4-to-amv-via-ffmpeg>` → **[INFER]** ≈ 4.9 MB
  for one minute, ≈ 81 kB/s. That is the source of the "a few MB" folk wisdom: it follows directly
  from 160x120 @ 16 fps MJPEG-ish video.
- Chinese vendor claim: "AMV转换工具转换出来的影音文件**一分钟的容量约为1.8MB**" —
  <https://xiazai.zol.com.cn/baike/561645.shtml>. **[INFER]** ~1.8 MB/min corresponds to a much lower
  video bitrate than the factory sample above; the two are not contradictory, they just reflect
  different quality presets.
- CSDN download-page claim: "AMV文件体积极小（**1分钟视频常不足2MB**）" —
  <https://wenku.csdn.net/doc/6td256ft8n> (same unreliable page as §9).
- ldpk.cn claim: "它的优势是文件极小**一首歌的MV可能只有几MB**" — <http://www.ldpk.cn/news/4798>
  (low reliability).
- **Counter-example worth keeping:** in 2007 the alpha `amv-codec-tools` encoder turned a 6 MB WMV
  into a **20 MB** AMV: "The original was a 6MB WMV, and the AMV was 20MB! The aspect ratio was
  wrong (16:9 to 5:4) and the sound was choppy." —
  <https://mailman.videolan.org/pipermail/vlc/2007-December/015246.html>
  → **[INFER]** "AMV is always small" is a property of sensible encoder settings, not of the format.

**Max file size / max duration — NOT VERIFIED.** I found no source that states a hard size or
duration cap for the hardware. The closest thing found is a vendor FAQ:

> "**How do I fix the Memory Full error on my player?** A: The Memory Full error happens when the
> device's internal storage is full or when the video file size is too large for the player to
> handle. To fix it, delete unused files or move them to another computer to free up space."
> — <https://www.anymp4.com/video-converter/mp4-to-amv.html>

That is consistent with a **storage-capacity** limit rather than a format limit, but it does not
give a number. **[INFER]** the "2 GB / 4 GB FAT32" hypothesis is plausible for these devices but I
found no source confirming it for AMV playback. Do not state a max size/duration as fact.

---

## 5. Which tools people use besides ffmpeg

| Tool | What the sources say (verbatim where quoted) | Still obtainable? | Source |
|---|---|---|---|
| **AMVplayer** | "用AMVplayer（只支持部分AVI）转化为AMV，拖进去就好了。" | Unknown | <https://www.cnblogs.com/gwj1314/p/10200054.html> |
| **「1.5寸AMV转换.exe」** | Chinese step-by-step: MP4/FLV → 魔影工厂/格式工厂 → AVI → `1.5寸AMV转换.exe` → MP4随身听. "不要用1.8寸，我试过1.8寸不行的…" | Distributed via Baidu Pan links in the post | <https://2048ai.net/6824650da5baf817cf4bf9a3.html> |
| **AMV格式转换器** | ZOL software-download listing, updated **2023-06-28**, 1.35 MB: "将所有的视频轻松转换到**AMV和MTV格式**"; "AMV格式转换器与AMV转换精灵、格式工厂一样都是很实用的转换工具"; "一分钟的容量约为1.8MB"; "你可以通过察看自己的MP3/MP4产品说明书来知道自己的机器是否支持AMV视频格式" | **Listed as downloadable** on ZOL (2023 build) | <https://xiazai.zol.com.cn/baike/561645.shtml> |
| **AMV转换精灵** | "AMV转换精灵V2.3专业版" exists on CSDN wenku; ZOL has an AMV精灵 page (updated 2022-03-14, 1.5 MB, freeware, 简体中文) | **Listed** on ZOL; v3.00 绿色版 floating on CSDN download | <https://xiazai.zol.com.cn/detail/11/108312.shtml> , <https://wenku.csdn.net/doc/7mf7g09f49> |
| **格式工厂 (Format Factory)** | Officially supports AMV output with editable resolution/bitrate/framerate. Official FAQ: "在目标格式处选择amv格式" … "amv格式并不会被手机识别为一个视频文件，因此无法保存到相册"; output goes to `内部存储-formatFile-vedio`. A third-party Chinese guide: "格式工厂国产免费软件支持格式非常全面包括不常见的AMV。它的预设里往往就有"MP4播放器"或"AMV"这样的设备预设一键转换比较方便。但需要注意有时它的默认参数可能不够优化需要手动微调。" | **Yes, actively maintained** (Android/iOS/PC; 2026) | <http://m.pcgeshi.com/faq/daorump3mp4.html> , <http://www.ldpk.cn/news/4798> |
| **快转视频转换器** | Used in the main CSDN walkthrough; UI screenshots show an AMV button. Warning text visible in its UI: "如果有滋滋声音请用快转转为avi mpeg4格式后再转amv" | Distributed via CSDN download attachment | <https://blog.csdn.net/stitches_fly/article/details/139372064> |
| **魔影工厂** | "魔影工厂或格式工厂（视频格式转换工具）" — used to make the AVI intermediate | Baidu Pan link in post | <https://2048ai.net/6824650da5baf817cf4bf9a3.html> |
| **超级解霸 + Total Video Converter 5** | "用超级解霸，，Total Video Converter 5" (as "a solution" for MP4→AVI) | **Dead/abandoned** (both are 2000s-era commercial products) | <https://www.cnblogs.com/gwj1314/p/10200054.html> |
| **amv-codec-tools** (Google Code) | The original 2007 hack — "a severely hacked-up version of ffmpeg"; project called itself "alpha". Referenced by FFmpeg trac #747 as the source of the `adpcm_ima_amv` encoder, and by the FFmpeg trac ticket as `AMVmuxer/ffmpeg/libavcodec/adpcm.c` | **Dead** — `code.google.com/p/amv-codec-tools/` no longer resolves as a live project | <https://trac.ffmpeg.org/ticket/747> , <https://mailman.videolan.org/pipermail/vlc/2007-December/015244.html> |
| **bamvc** (GUI wrapper for amv-codec-tools) | "here's a quick hacked-up GUI version of the amv-codec-tools encoder, for Windows and Linux: http://www.bytessence.com/bamvc.html" | **[UNVERIFIED]** — I did not check whether this URL still resolves | <https://mailman.videolan.org/pipermail/vlc/2007-December/015250.html> |
| **AMV DirectShow filter (`amv.ax`)** | A CSDN download package (the AI-written blurb in §9) lists `amv.ax` (116 KB) among 182 files, i.e. a DirectShow decoder filter for AMV exists so Windows players can decode it. Also `AMVFairy\AMV播放.exe` appears as a separate CSDN download. | Packages are on CSDN download (points-gated) | <https://wenku.csdn.net/doc/6td256ft8n> , <https://download.csdn.net/download/u012496571/6421285> |
| **AnyMP4 Video Converter Ultimate** | Commercial; has an AMV output preset and advises locking frame rate: "manually lock the frame rate to 15 FPS to ensure your anime cuts stay perfectly aligned with the music's beat" | **Yes**, current (2026 marketing) | <https://www.anymp4.com/video-converter/mp4-to-amv.html> |
| **HandBrake** | Recommended by a Chinese guide for the *AVI/Xvid* route, explicitly noting "虽然预设里可能没有直接的"AMV"选项" (no direct AMV preset) | Yes | <http://www.ldpk.cn/news/4798> |

**Tools explicitly named by the user that I could NOT find AMV support for:** MTV Video Converter,
视频转换大师, MP4/RM转换专家. **[UNVERIFIED]** — I saw 视频转换大师 listed on ZOL/QQ download
pages as a general converter but found no AMV-specific claim. Do not assert these support AMV.

Note the recurring **two-step AVI intermediate** pattern in Chinese guides (MP4 → AVI → AMV), with
a stated reason: AMV converters are picky about their AVI input ("只支持部分AVI", "不支持转换MP4 FLV").

---

## 6. What actually plays `.amv`

| Player | Evidence found | Status |
|---|---|---|
| **ffmpeg / ffprobe** | Proven with a real device file (full stream dump quoted in §3.1), including `Invalid audio packet size (698 != 697)` while stream-copying. | **VERIFIED works** (decode + demux) — <https://superuser.com/questions/1649146/convert-mpeg4-to-amv-via-ffmpeg> |
| **VLC** | **No verbatim report of VLC playing or failing to play an `.amv` was found.** Best VLC-side source is the Dec 2007 thread: OP asks "Does VLC do AMVs? Does anything else do AMVs?"; a VideoLAN dev replies "IIRC, this is just a patch to ffmpeg, so should be pretty possible to integrate the support to ffmpeg and then tell VLC to use fourcc's of the needed kind by means of ffmpeg plugin." — i.e. *at that time* VLC did **not** support it. Today: a vendor file-info site states "The AMV format has been reverse-engineered and its support added to ffmpeg, so **all ffmpeg-based media players are capable of opening `.amv` files** for playback" (<https://www.filetypeadvisor.com/extension/amv>), while a commercial page hedges: "Q: Can VLC play AMV files? A: VLC can play some AMV files, but **support is not guaranteed**. VLC sometimes fails to decode the file correctly because AMV uses proprietary codecs designed for specific devices." (<https://www.anymp4.com/video-converter/mp4-to-amv.html>) | **SUPERSEDED — see §12.3.** This table row was written before the second research pass. Final verdict: documented **failure history** (2007 developer "you can't with VLC"; a **reproduced 2011 crash** in VLC 1.1.9 — VideoLAN issue #4870, closed as `works for me`; an unresolved 2020 user failure; a 2023 developer generalisation), but **no test of VLC 3.0.x exists**. Report as "documented failure history; current-version behaviour untested". |
| **VLC "video but no audio" specifically** | **NOT FOUND.** No such report exists in anything I reached. The Dec 2007 thread's "sound was choppy" refers to the *encoder's* output on the device, not to VLC playback: "The aspect ratio was wrong (16:9 to 5:4) and the sound was choppy." | **[UNVERIFIED]** |
| **MPlayer** | Only the 2007 patch-thread title "[PATCH] AMV files playback support" — <https://lists.mplayerhq.hu/archives/list/mplayer-dev-eng@mplayerhq.hu/message/GDMQDYDQ6LPPLOWMZQZK2Z7DL7C4RNHU/> . **Body unreadable (Anubis wall).** | **[UNVERIFIED]** |
| **MPC-HC** | Listed as an AMV-capable program by <https://www.filetypeadvisor.com/extension/amv> (populated automatically by the site's own database, "rating1" popularity). | **Weak/unverified** — no user report |
| **KMPlayer / GOM Player** | Same file-info listing. | **Weak/unverified** |
| **MPV** | Nothing found. Only the claim that "主流播放器（VLC、PotPlayer、MPV）亦需手动加载第三方解码器" (mainstream players need a third-party decoder loaded manually) from the unreliable CSDN page. | **[UNVERIFIED]**, and contradicted by FFmpeg's native AMV decoder |
| **Windows Media Player** | "在计算机上，VLC 和 Windows Media Player 等是打开 AMV 文件的常用播放器" — <https://www.jianlu365.com/info/1372.html> (vendor help page selling a converter — **low reliability**). Also the existence of `amv.ax`, a DirectShow filter, implies WMP *can* be made to play it with a filter installed. | **[UNVERIFIED]** |
| **Cheap hardware players** | Positive: the factory file plays on the device it shipped on (§3.1). Negative: the `-r 12` report in §3.1/§3.2. | **VERIFIED both ways** |

**[INFER]** The technically sound expectation, which I could not confirm by direct report: any
libavcodec-based player (VLC, mpv, MPC-HC + LAV, MPlayer) should demux AMV with the **AVI demuxer**
and decode with the **AMV video decoder** (a stripped-table MJPEG variant), so video should work;
audio is where I'd expect trouble, because the container tags the audio stream as format tag
`0x0001` (PCM) while it is really IMA ADPCM — the muxer writes a *deliberately incorrect*
WAVEFORMATEX, and the demuxer must special-case it. That is exactly the kind of mismatch that
produces "video plays, no audio" in players that don't special-case it. **This is my inference, not
a quoted report.**

---

## 7. Byte-level / hex-dump descriptions

### 7.1 **NOT FOUND: no Chinese blog with a hex dump / WinHex screenshot / 文件头 analysis**

I searched repeatedly (Chinese and English) for: `AMV 文件头 结构 分析`, `AMV格式 详解`,
`AMV 十六进制`, `AMVV AMVA`, `amvh`, `AMV_END_`, `AMV 文件 结构`, `炬力 AMV 格式`,
`Actions AMV 格式`, `AMV 封装 格式 解析`, `amv 格式 逆向`, `"amv" "00dc" "01wb" 文件 结构`.
**I found no Chinese source containing an actual hex dump, a 文件头 field table, WinHex/UltraEdit
screenshots described in text, or sightings of `amvh` / `AMV_END_` / `00dc` / `01wb` / `AMVF`.**
The Chinese material that exists is all GUI-tutorial level (click here, choose this preset).

The closest things found, in decreasing order of usefulness:

### 7.2 The best byte-level description available — a modern reverse-engineering project (English)

**[QUOTE]** <https://github.com/OxideAV/oxideav-amv> (README; a pure-Rust AMV container +
video/IMA-ADPCM decoder, "the non-standard AVI variant used by inexpensive portable media players
(S1 / Actions / ALi-chip devices)"):

- "AMV pairs a custom intra-only Motion-JPEG-like video stream (fixed, hardcoded quant / Huffman
  tables) with an IMA-ADPCM-style mono audio stream wrapped in a `RIFF` form whose type is `AMV `
  (trailing space)."
- "It reuses the AVI 1.0 RIFF vocabulary (`LIST`, `hdrl`, `strl`, `strh`, `strf`, `movi`, the
  `<nn>dc` / `<nn>wb` chunk-tag convention, `WAVEFORMATEX`) while discarding most of its semantics:
  all RIFF / LIST sizes are zeroed, leaf chunks are not padded to an even byte boundary,
  stream-header bodies are blank, there is no `idx1` index, and the stream is bounded by an
  **`AMV_END_` ASCII trailer** instead of a RIFF chunk length."
- Demuxer "Parses the **`amvh`** main header (resolution, fps, packed duration) and the audio
  `WAVEFORMATEX`, walks the `movi` payload, and emits one packet per `00dc` (video) / `01wb`
  (audio) leaf chunk, terminating cleanly at the `AMV_END_` trailer."
- Video frames: "the on-disk payload is `FF D8` + bare entropy-coded data + `FF D9`" — the device
  encoder "strips the JPEG marker segments (`DQT`, `SOF0`, `DHT`, `SOS`) from every `00dc` video
  frame … and the player splices fixed bytes back in before decode". The inserted segments are
  "the JPEG Annex K example tables verbatim and unscaled — quant K.1 (luma) / K.2 (chroma) in
  zig-zag order, Huffman K.3/K.4 (luma+chroma DC+AC), baseline SOF0 at the `amvh` resolution with
  4:2:0 sampling, and one full-spectral interleaved scan."
- Audio blocks: "the 8-byte per-block header carrying an `int16` predictor seed, one block per
  video frame, low-nibble-first packing"; "the step index is reset to 0 at block start (no state
  carries across blocks)"; "the codec is **standard IMA/DVI ADPCM** — the 89-entry step-size table
  and the 8-entry index-adjust table `{-1,-1,-1,-1,2,4,6,8}` are the canonical IMA tables, used
  unmodified".
- `amvh` internals: "`amvh` stores integer fps + `1e6/fps` µs/frame — flooring 12.5 to 12 would
  desync the timing"; strict-mode sentinels include "the §2 `amvh` reserved 7-dword span
  (`+0x04..+0x1C`) and the §3 all-zero stream-header bodies".
- Two real device profiles used as fixtures: "**`comedian.amv` (128 × 96 @ 12 fps, 1116 pairs)** and
  **`noel-son-lumiere.amv` (96 × 64 @ 16 fps, 2928 pairs)**", both redistributable samples.
- A device-writer quirk worth noting: "§4b closes the observation that the noel-profile device
  writes that header **truncated by one second** (3:02 against a derived 2928 × 16 = 3:03 — "should
  not be used as an authoritative duration")".
- Audio decode sanity: "decodes all 1116 blocks of `comedian.amv` to **2 050 650 mono samples =
  exactly 93.0 s at 22 050 Hz**".

**[UNVERIFIED]** The README links a full trace document at
`https://github.com/OxideAV/oxideav-workspace/blob/master/docs/container/amv/amv-container-trace.md`.
**That path returns 404 via the GitHub API and I could not retrieve it.** Everything above is from
the `oxideav-amv` README only. Also note its provenance claim: "No external multimedia-library
source code or any third-party AMV demuxer was consulted" (i.e. it is independent of FFmpeg).

### 7.3 Byte-level facts from FFmpeg source (independently confirms the above)

**[SRC]** From `libavformat/amvenc.c` in commit `a2fea0f4690e4f11ba093f14509643c44a485165`
(<https://github.com/FFmpeg/FFmpeg/commit/a2fea0f4690e4f11ba093f14509643c44a485165>):

- Header: `RIFF` + fourCC **`"AMV "`** (with trailing space; `ffio_wfourcc(pb, "AMV ")`), then
  `LIST`/`hdrl`, then chunk **`amvh`** with `avio_wl32(pb, 56)` (a **56-byte** header) containing:
  `+0` µs-per-frame, `+32` width, `+36` height, `+40` time_base.den, `+44` time_base.num, `+48` 0,
  `+52` duration (patched at trailer time). Bytes `+4..+31` are left zero.
- Duration packing at trailer: `avio_w8(ss); avio_w8(mm); avio_wl16(hh);` → seconds, minutes,
  hours as three fields.
- Stream header sizes: `AMV_VIDEO_STRH_SIZE 56`, `AMV_VIDEO_STRF_SIZE 36`,
  `AMV_AUDIO_STRH_SIZE 48`, `AMV_AUDIO_STRF_SIZE 20 /* sizeof(WAVEFORMATEX) + 2 */` — all bodies
  written as **zeros** (video) or a bogus WAVEFORMATEX (audio).
- Audio `strf` is explicitly labelled `/* Bodge an (incorrect) WAVEFORMATEX (+2 pad bytes) */`
  with `wFormatTag = 1` (PCM), `sample_rate*channels*2` as avg-bytes/sec, `block_align = 2`,
  `bits = 16`.
- Data chunks: literal `"00dc"` for video, `"01wb"` for audio, each followed by a 32-bit size.
- Trailer: `ffio_wfourcc(s->pb, "AMV_"); ffio_wfourcc(s->pb, "END_");` → the ASCII string
  **`AMV_END_`**.
- Frame-rate cap: `if (amv->us_per_frame < 15873) { av_log(..., "Refusing to mux >63fps video"); }`

**[INFER]** So both of the tokens you asked about — `amvh` and `AMV_END_` — are **real and
confirmed**, from FFmpeg's writer. `AMVV`/`AMVA` are **not** confirmed. `00dc`/`01wb` are
confirmed. The `<nn>dc`/`<nn>wb` framing means the *actual* on-disk tag can vary by writer, which
is consistent with the OxideAV note that it walks "one packet per `00dc` / `01wb` leaf chunk".

### 7.4 A DIRECT WARNING: a CSDN page contains plausible-sounding but wrong byte-level "analysis"

<https://wenku.csdn.net/doc/6td256ft8n> ("AMV视频格式转换器…", CSDN 文库, updated 2025-07-22)
reads like an authoritative format analysis and contains these specific claims:

> "它采用固定分辨率（常见为208×176、224×176、320×240）、固定帧率（通常为12fps或16fps）、单色度采样
> （YUV 4:2:0简化版）、无B帧、仅含I帧和P帧的**MPEG-1风格视频编码**，并强制使用ADPCM音频编码…"
> "最后还需篡改AVI容器结构：替换RIFF头标识为**"AMV\0"**，删除所有非必要ODML索引块，精简FOURCC字段
> （视频流设为**"MPG1"**，音频流设为**"ADPM"**），并将数据块对齐至**2048字节边界**…"

**These contradict the verified primary sources:** AMV video is a **table-stripped baseline
MJPEG** variant, not MPEG-1 (FFmpeg's `AV_CODEC_ID_AMV` is handled by the MJPEG decoder family;
OxideAV confirms intra-only JPEG); the RIFF form type is `"AMV "` with a **trailing space**, not
`"AMV\0"`; the verified fourCC is **`AMVF`**, not `MPG1`/`ADPM`; chunk tags are `00dc`/`01wb`.
The page also asserts "现代操作系统…主流播放器（VLC、PotPlayer、MPV）亦需手动加载第三方解码器" which
is at odds with FFmpeg's native AMV decoder. **[INFER] This page is very likely LLM-generated
content-marketing text and should NOT be cited as a format reference.** It is useful only as an
example of the misinformation circulating in Chinese SEO content.

---

## 8. Error-message catalogue (all verbatim, with sources)

### Reported by users

| Message | Context | Source |
|---|---|---|
| `Only mono is supported Error initializing output stream 0:1 -- Error while opening encoder for output stream #0:1 - maybe incorrect parameters such as bit_rate, rate, width or height` | stereo input with `-c:a adpcm_ima_amv` | <https://superuser.com/questions/1649146/convert-mpeg4-to-amv-via-ffmpeg> |
| `[amv @ 000002914a436c00] Invalid audio frame size. Got 1024, wanted 1378` | frame rate / block size mismatch | same |
| `[amv @ 000002914a436c00] Invalid audio block align. Got 520, wanted 697` | same | same |
| `[amv @ 000002914a436c00] Try -block_size 1378` | the actionable hint | same |
| `Could not write header for output file #0 (incorrect codec parameters ?): Invalid argument` / `Error initializing output stream 0:0 --` / `Conversion failed!` | same | same |
| `[amv @ 0000021460f90800] Invalid audio packet size (698 != 697)` + `Last message repeated 136 times` | **remuxing a real factory .amv** — the file's own audio block size is off by one | same |
| `[swscaler @ …] deprecated pixel format used, make sure you did set range correctly` | `-pix_fmt yuvj420p` | same |
| `[Parsed_pan_0 @ …] Pure channel mapping detected: 0` | `-af pan="mono\| c0=FL"` workaround | same |
| `scale/rate is 0/0 which is invalid. (This file has been generated by broken software.)` | reading a real factory .amv | same |
| `[amv @ 0000028e3abded40] AMV files only support 2 streams` | mp4 that ffmpeg mapped to >2 streams; **same command that previously worked started failing in 2022** | <https://superuser.com/questions/1740001/> |
| `Codec AVOption block_size (set the block size) specified for output file #0 (…) has not been used for any stream. The most likely reason is either wrong type (e.g. a video option with no video streams) or that it is a private option of some encoder which was not actually used for any stream.` | same, when audio wasn't mapped | same |
| `Unable to find a suitable output format for 'output.amv'` / `output.amv: Invalid argument` | ffmpeg < 4.4 | <https://superuser.com/questions/1426454/using-ffmpeg-to-convert-video-solution-to-rejected-amv-container> |
| `[NULL @ 039a7860] Requested output format 'amv' is not a suitable output format` / `sample.amv: Invalid argument` | ffmpeg 0.9 / 2011 | <https://trac.ffmpeg.org/ticket/747> |
| `Format error` (printed by the **hardware player**) | `.avi` renamed to `.amv` | <https://superuser.com/questions/1426454/...> |
| `格式错误` / `文件格式错误` (device UI) | MP4 dropped on an AMV-only player | <https://blog.csdn.net/stitches_fly/article/details/139372064> |

### Present in the AMV muxer source, but I found **no community post** quoting them

**[SRC]** All from `libavformat/amvenc.c`, commit
<https://github.com/FFmpeg/FFmpeg/commit/a2fea0f4690e4f11ba093f14509643c44a485165>:

- `"Audio sample rate not a multiple of the frame size.\nPlease change video frame rate. Suggested rates: 10,14,15,18,21,25,30"`
  → **I could not find a single tutorial/forum post reporting this exact error.** Do not attribute
  it to a user.
- `"Refusing to mux >63fps video"` (guard: `amv->us_per_frame < 15873`)
- `"Cannot remux streams with a different time base"`
- `"Stream not seekable, unable to write output file"`
- `"First AMV stream must be amv"` / `"Second AMV stream must be adpcm_ima_amv"`

### Errors you asked about that I could **not** find anywhere

- **`"Heights which are not a multiple of 16"` — ⚠️ CORRECTION: THIS STRING DOES EXIST.** My first
  pass checked only `amvenc.c` and wrongly concluded the message didn't exist. A second research
  pass found it verbatim in the **mjpeg video encoder**, from `ffmpeg-devel`, 2015-08:
  > `"Heights which are not a multiple of 16 might fail with some decoders, "`
  > `"use vstrict=-1 / -strict -1 to use %d anyway.\n"`
  > `"If you have a device that plays AMV videos, please test if videos with such heights work with it and report your findings to ffmpeg-devel@ffmpeg.org"`
  Sources: <https://www.mail-archive.com/ffmpeg-devel@ffmpeg.org/msg17914.html> and
  <https://www.mail-archive.com/ffmpeg-devel@ffmpeg.org/msg17905.html> . It is an **mjpegenc**
  warning (subsequently removed, 2015-08), **not** an `amvenc.c` message — which is why my
  `amvenc.c`-only check missed it. And the matching real-world bug is FFmpeg trac **#4770
  "Non-modulo 16 height of AMV file"** (<https://trac.ffmpeg.org/ticket/4770>), where FFmpeg N-74378
  **refused to decode** a genuine device file with `[amv @ ...] non mod 16 height AMV` /
  `is not implemented`; fixed 2015-12-01 in `fa9af304f0a167f187a26cc9a0a0ba6a4db08cbe`.
  → So "heights that aren't a multiple of 16" is a **real, documented AMV failure mode on both the
  encode and decode side.** The `-vstrict -1` flag that appears in nearly every community command
  line is very likely a relic of exactly this warning. **[INFER]** that finally explains `-vstrict -1`.
- **`-r 16` recipes now failing.** I found **no** post reporting modern ffmpeg breaking a 16 fps
  recipe. Note `-r 16` is a *real* AMV value — the factory device file was `16 fps` (§3.1) and the
  FFmpeg suggested-rate list (10,14,15,18,21,25,30) does not include 16, but 16 is representable in
  `amvh` and FFmpeg's `amv_init` only rejects >63 fps. In the fdd4s repo, `-r 16` is used by the
  **AVI/MJPEG** scripts, not the AMV ones.

---

## 9. Reliability ranking of the sources used (read this before citing)

**Tier A — primary/authoritative**
- FFmpeg source + commit + trac ticket: `amvenc.c`, `riff.c`, <https://github.com/FFmpeg/FFmpeg/commit/a2fea0f4690e4f11ba093f14509643c44a485165>, <https://trac.ffmpeg.org/ticket/747>, <https://abi-laboratory.pro/index.php?view=changelog&l=ffmpeg&v=4.4>
- <https://superuser.com/questions/1649146/convert-mpeg4-to-amv-via-ffmpeg> — the single densest source of real command lines, real errors and a real device-file dump
- <https://mailman.videolan.org/pipermail/vlc/2007-December/015243.html> … `015250.html` — first-hand 2007 VLC-list thread
- <https://github.com/OxideAV/oxideav-amv> — modern independent byte-level reverse engineering (but I could not read its referenced trace doc)

**Tier B — useful, needs care**
- Other Super User / Video Stack Exchange questions (real problems, often unanswered)
- <https://github.com/fdd4s/portable_mp3_player_video_converter_tools> — working scripts + a device table (Actions 1.8"/2.4", Shenju AVI, Spreadtrum, feature phones)
- <https://www.filetypeadvisor.com/extension/amv> — vendor auto-generated but substantive (208×176, 12–16 fps, MJPEG+IMA ADPCM, ffmpeg-based players)
- <http://m.pcgeshi.com/faq/daorump3mp4.html> — Format Factory's own FAQ (tool defaults, stutter warning)
- <https://blog.csdn.net/stitches_fly/article/details/139372064> — genuine hobbyist account with real failure symptoms
- <https://xiazai.zol.com.cn/baike/561645.shtml> — ZOL download listing (tool existence, 1.8 MB/min, changelog)
- <https://www.cnblogs.com/gwj1314/p/10200054.html> — short but concrete tool chain
- <https://2048ai.net/6824650da5baf817cf4bf9a3.html> and <https://2048ai.net/682d8274606a8318e858c9c6.html> — CSDN devpress mirrors (mirror URLs; the canonical CSDN URLs 404/521 from here)

**Tier C — commercial SEO / AI-generated, cite only with an explicit caveat**
- <https://www.anymp4.com/video-converter/mp4-to-amv.html>
- <https://www.jianlu365.com/info/1372.html>
- <http://www.ldpk.cn/news/4798>
- <https://wenku.csdn.net/doc/6td256ft8n> — **actively contradicts verified sources; see §7.4**

**Related but conflated (watch out):**
- DBpedia's infobox for the Wikipedia "AMV video format" article lists the filename extensions as
  "**.amv, .mtv**" and `extendedFrom` "**AVI and Motion JPEG**", with the article linked to
  `Actions Semiconductor`, `S1 MP3 player`, `ADPCM`, `Motion JPEG`, `Reverse engineering` and
  `FFmpeg`: <https://dbpedia.org/page/AMV_video_format> . So Wikipedia treats `.mtv` as the *same*
  format as `.amv` — which conflicts with the view that `.mtv` is a distinct MP3-audio container.
  **This is a genuine literature inconsistency, not something I resolved.**
- The Zhihu/CSDN/Bilibili/blogosphere also uses "AMV" for *anime music video* throughout, and
  commercial pages (AnyMP4, jianlu365, vidmore, wondershare) routinely blend the two meanings in
  one article. Any claim sourced from those pages must be checked for which sense of "AMV" is meant.
- `.amv` also names an unrelated format: KiriKiri visual-novel engine video
  (<https://github.com/xmoezzz/amv_decoder>, <https://docs.rs/amv_decoder/>). **Not the same
  format** — do not mix these sources in.

---

## 10. Open gaps (explicitly unresolved)

1. **No Chinese source with a hex dump / 文件头 field table / `amvh` / `AMV_END_` sighting was found**
   despite repeated targeted searching. If this matters, the remaining avenues are Baidu-indexed
   数码/MP3-player BBS (imp3, 数码之家, 耳机大家坛) and image-based posts (WeChat/Bilibili), which
   this tooling reaches poorly.
2. **amv-codec-tools' `AmvDocumentation` wiki could not be retrieved** (web.archive.org unreachable;
   Google Code dead). It is repeatedly cited as *the* AMV documentation — worth another attempt from
   a network that can reach the Wayback Machine.
3. **No max file size / max duration** for the hardware players is established anywhere.
4. **Desktop playback is only partly resolved.** After the second pass (§12.3) **VLC** is no longer a
   gap — there is documented failure history (2007 developer "you can't", a reproduced 2011 crash
   closed as "works for me", an unresolved 2020 user failure, a 2023 developer generalisation), but
   **no test of VLC 3.0.x exists**. Still genuinely open: **mpv, Kodi, MPlayer, GOM, Aiseesoft, AVS,
   Android players**, and **no first-hand playback test of any kind for MPC-HC** (only its
   `TRA_AMVV` config flag, §12.2). **Still zero "video but no audio" reports for any desktop player.**
5. **The OxideAV trace document** (`amv-container-trace.md`) is the most promising byte-level
   artefact and is 404 at the advertised path; the second pass did not resolve it either. The same
   author maintains the `oxideav-amv` crate.
6. **`-vstrict -1` — now provisionally explained (see §12.1).** My original "possibly pure cargo
   cult" note was superseded: the 2015 mjpegenc warning explicitly told users to add
   `-vstrict -1` for heights that are not a multiple of 16. **[INFER]** it is a relic of that
   warning, not cargo cult. Not confirmed by any source that says so outright.
7. **`.mtv` vs `.amv` relationship** (Wikipedia/DBpedia vs community) is unresolved — see §9.
8. The historically cited `AmvDocumentation` wiki could not be reached via the **Google Code
   Archive** either: `https://code.google.com/archive/p/amv-codec-tools/` and
   `https://code.google.com/archive/p/amv-codec-tools/wikis/AmvDocumentation.wiki` both failed to
   fetch in this environment.

---

## 11. Addendum: a real-device vendor spec sheet (partial, mojibake)

A 2006 Korean vendor product page for a 1.5-inch AMV-capable MP3/MP4 player (Rexcos K7) contains a
"video playback" spec row whose ASCII tokens are unambiguous:

> `AMV ������ ���� ���� ( 128x96�ȼ� ������ ����)`

Source: <http://www.rexcos.co.kr/Products/mp4/rexk7/rexk72gs.htm>

**[INFER]** The page is EUC-KR and my fetch tool returned it decoded as Latin-1, so the Korean text
is mojibake and **I cannot quote it verbatim**. The readable ASCII in that row is `AMV` and
`128x96`. The same page has a second paragraph containing `AMV` and `129x96`. **[INFER]** the row
almost certainly reads "AMV 동영상 재생 가능 (128x96 해상도 크기까지)" — "AMV video playback
supported (up to 128x96 resolution)" — which would make **128x96 the stated ceiling for a real
1.5-inch device**, matching OxideAV's `comedian.amv` fixture (128 × 96 @ 12 fps) exactly.

Two other values on the same page that I deliberately do **not** rely on: a frame-rate row whose
ASCII reads `24-30 ������/��` (looks like "24-30 프레임/초" = 24–30 fps — implausible for AMV and
probably a sloppy vendor spec), and a bitrate row reading `240-1Mbps`.

---

## 12. Second research pass — CORRECTIONS and additional evidence

A second, independent research pass was run specifically on players and player limits. It **revised
two of my earlier conclusions** (both corrected in place above: §0 and §8) and added the following.
Treat this section as the higher-quality material on VLC and on the 16-pixel-height question.

### 12.1 ⚠️ CORRECTION 1 — the `"multiple of 16"` message is REAL
See §8 (corrected). It is an **mjpegenc** warning from `ffmpeg-devel`, 2015-08, plus FFmpeg trac
**#4770 "Non-modulo 16 height of AMV file"** where a genuine device file was **refused**:
`[amv @ ...] non mod 16 height AMV` / `is not implemented`. My first pass wrongly called this
string nonexistent because I only inspected `amvenc.c`.
- <https://www.mail-archive.com/ffmpeg-devel@ffmpeg.org/msg17914.html>
- <https://www.mail-archive.com/ffmpeg-devel@ffmpeg.org/msg17905.html>
- <https://trac.ffmpeg.org/ticket/4770> (fixed 2015-12-01, `fa9af304f0a167f187a26cc9a0a0ba6a4db08cbe`)

**[INFER]** This almost certainly explains the otherwise-unexplained `-vstrict -1` that appears in
nearly every community command line — the warning explicitly told users to add it.

### 12.2 ⚠️ CORRECTION 2 — an `AMVV` token DOES exist in the wild (but not as a fourCC)
See §0 (corrected). MPC-HC registers `.amv` (installer deletes `HKCR\mplayerc64.amv`) and exposes
`TRA_AMVV=1` under `[Internal Filters]` next to `TRA_MJPEG=1`/`TRA_H264=1`.
- <https://github.com/clsid2/mpc-hc/issues/3801> (user-pasted `mpc-hc64.ini`, also has
  `other=divx amv mxf dv dav`)
- <https://github.com/clsid2/mpc-hc/issues/1568>
→ **`TRA_AMVV` is a player-side filter identifier ("AMV Video"), not an on-disk fourCC.** The
verified on-disk fourCC is still `AMVF` (§0). **No one has yet reported actually playing an `.amv`
in MPC-HC** — the evidence is configuration only.

### 12.3 VLC — now ANSWERED (was UNVERIFIED in §6)

| Date | Evidence (verbatim) | Authority | URL |
|---|---|---|---|
| 2006-05-28 | User seeks a PC player for `.amv`; **0 replies** — the bundled vendor program was the only known route | user question | <https://forum.videolan.org/viewtopic.php?f=14&t=21230> |
| 2007-02-16 | **dionoea (VLC developer)**, on converting to AMV: "Well, no, you can't with VLC." | HIGH (dev) | <https://forum.videolan.org/viewtopic.php?f=2&t=32405> |
| 2011-06-02 | "drij": "This format is used by cheap Chinese \"MP4\" players. This specific file was preloaded on my device. **It crashes VLC 1.1.9, but works fine in FFplay/FFMPEG SVN-r26292**, both on Windows 7." Reply from VLC_help: "Thanks. I can duplicate this. Trac ticket opened" | HIGH | <https://forum.videolan.org/viewtopic.php?f=14&t=90919> |
| 2011-06-03 → 2011-08-12 | VideoLAN issue **#4870 "Crash with AMV file"**, tested with "VLC 1.1.9 and VLC 1.2.0-git-20110603-0003 under Win32"; backtrace in `ff_mjpeg_decode_sof()` / `ff_mjpeg_decode_frame()`. **Closed by Rémi Denis-Courmont (Courmisch) with labels `Component::Decoders` + `Status::works for me`.** | HIGH (official tracker) | <https://code.videolan.org/videolan/vlc/-/work_items/4870> |
| 2020-05-25 | "stevenjklein": "I've been using VLC for at least a decade, and I've never found a video format it can't handle. Until now. … the only video format they support is called AMV. … **if VLC supports AMV, I can't find it. Am I missing something, or is the AMV format unsupported?**" — **0 replies** | user report | <https://forum.videolan.org/viewtopic.php?f=2&t=153664> |
| 2023-02-17 | **Rémi Denis-Courmont, Developer**, answering a request that listed `amv` among ~400 extensions: "Almost all codecs in your list are already supported, just saying…" | MEDIUM (dev, but not `amv`-specific) | <https://forum.videolan.org/viewtopic.php?f=7&t=161162> |

**Revised verdict on VLC:** there is a **documented, reproduced crash** in VLC 1.1.9 (2011), an
explicit developer "you can't" (2007), an unresolved 2020 user failure, and only a general
developer implication of support (2023). **Nobody has ever posted a test of VLC 3.0.x on an `.amv`
file.** Report as: *documented failure history; current-version status untested.* The crash is
notable because it happens inside the **MJPEG** decode path — the same path AMV video uses.

### 12.4 FFmpeg — refined version history (corrects/narrows §2)

FFmpeg's own Changelog, <https://raw.githubusercontent.com/FFmpeg/FFmpeg/n4.4/Changelog>:
- under **"version 0.5:"** → `- AMV audio and video decoder`
- under the 4.4 section → `- ADPCM IMA AMV encoder` and `- AMV muxer`

→ **AMV *decoding* has been in FFmpeg since 0.5; AMV *writing* only from 4.4.** My §2 statement
about 4.4 stands, but "ffmpeg can't do AMV" was only ever true of *encoding*.

### 12.5 Real `.amv` files with exact published data (extends §3/§4)

| File | Provenance | Size | Video | Audio |
|---|---|---|---|---|
| `daomeixiong.amv` | uploaded to FFmpeg trac #4770 (2015); still listed at <https://samples.ffmpeg.org/ffmpeg-bugs/trac/ticket4770/> ("2015-08-06 18:52 **3.6M**") | **3.6 MB** | `amv yuvj420p **160x120 @ 12 fps**` | `adpcm_ima_amv 22050 Hz mono` (header claims 352 kb/s — a bogus AVI field) |
| `1.8_Test-2.amv` | preloaded on a cheap player (superuser 1649146, 2021) | **5031 kB** (video 4339 + audio 675), 992 frames = **62.00 s** | `amv yuvj420p **160x120 @ 16 fps**` | `adpcm_ima_amv 22050 Hz mono s16, 88 kb/s` |
| `France MM.amv` | **preloaded on drij's cheap Chinese "MP4" player** (2011) | not published | unknown | unknown — only "crashes VLC 1.1.9, works fine in FFplay/FFMPEG SVN-r26292" |

**[INFER] AVI header checksum-of-quality tells:** in both dumps the device files report
`Duration: N/A, start: 0.000000, bitrate: N/A` plus `scale/rate is 0/0 which is invalid. (This file
has been generated by broken software.)` — i.e. **device-written AMVs are genuinely malformed by
AVI rules**, which is the mechanical reason player support is unpredictable.

### 12.6 THE CHINESE SIZE + RESOLUTION SOURCE I WAS LOOKING FOR

The earlier Chinese sweep came up empty; the second pass found two:

**[QUOTE]** <https://www.wenjianbaike.com/amv.html> (updated 2023-09-26):
> "AMV文件以**94x64到160x120像素**的低分辨率保存，**一分钟的视频容量大约在1.6MB到1.8MB左右**，非常适合在小屏幕低容量的MP3或MP4播放器上播放。"
> （"AMV files are stored at low resolution from 94x64 to 160x120 pixels; one minute of video is about 1.6MB to 1.8MB, very well suited to small-screen, low-capacity MP3 or MP4 players."）

**[QUOTE]** <https://fileinfo.cn/extension/amv> (updated 2020-02-08):
> "通常以低分辨率保存（**从94x64到160x120**），以适应媒体播放器的屏幕"

→ **This is the Chinese-language resolution floor I had listed as a gap: 94×64.** It matches the
OxideAV fixture `noel-son-lumiere.amv` (**96 × 64**) and the 1.5-inch device spec (128×96), giving a
consistent picture: **floor ≈ 94×64/96×64, common ≈ 128×96 and 160×120, ceiling ≈ 160×120–208×176.**

**[QUOTE]** <https://convert.guru/amv-converter> :
> "It suffers from extremely low resolutions (**frequently capped at 128x96 or 160x120 pixels**) and a very poor compression ratio, generating bulky files for terrible visual quality. Modern web browsers, smartphones, smart TVs, and default operating system players natively reject this format because of its **non-standard, modified header structure**."
> "…portable MP4 and MP3 players (frequently Chinese clones of the iPod Nano)… The format encodes video using a **variant of Motion JPEG (MJPEG)** and audio with **IMA ADPCM**."
(Caution: the same page's "How to convert" text says "In the File menu, look for Save As… or
Export…" — pure autogenerated boilerplate, and VLC has no such menu. Reliability LOW-MEDIUM.)

### 12.7 The "1.6–1.8 MB/min" folk figure is ~2.5–3× optimistic (arithmetic)

**[INFER]** adpcm_ima_amv is 4-bit IMA ADPCM at 22050 Hz mono → 22 050 × 0.5 B/s = 11 025 B/s =
**88.2 kbit/s = 661 kB per minute, not compressible**. That matches the real file's 675 kB of audio
for 62 s and FFmpeg's own "88 kb/s".
- The `1.8_Test-2.amv` sample measures **5031 kB / 62 s = 4.87 MB/min** total.
- So "1 minute ≈ 1.6–1.8 MB" (ZOL and wenjianbaike both say this) is **2.5–3× smaller than a
  measured device-native file**. Flag as a discrepancy; do **not** pick a winner. Both numbers are
  defensible as *encoder defaults at different quality*, but only the measured file is evidence.

**[INFER] Also worth knowing: `-block_size` in the community commands is the audio *frame* size, not
the block align.** From `amvenc.c`: `aframe_size = sample_rate / fps` and
`ablock_align = 8 + (aframe_size / 2)`. At 25 fps: `aframe_size` = 882 → `ablock_align` = 449. Users
pass `-block_size 882` (the frame size), which is why the values seen in the wild are 735 (30 fps),
882 (25 fps), 1225 (18 fps), 1378/1470/1575/2205 — **all ≈ 22 050 / fps**.

### 12.8 Windows Media Player and the Chinese player claims

- **WMP — indirect evidence of NO native support.** A Chinese file-encyclopedia page
  <https://www.wenjianbaike.com/amv.html> advises converting first: "可以使用多媒体处理工具FFmpeg（跨平台）转换AMV视频格式为另一种视频格式，然后可以在Windows Media Player等播放器上播放" ("use FFmpeg to convert the AMV to another format, then it can be played in Windows Media Player etc."). Plus 2009 VLC-forum user: "it didn't play right with wmp" (<https://forum.videolan.org/viewtopic.php?f=14&t=69425>).
- **PotPlayer / KMPlayer / 暴风影音 — claimed, never tested.**
  [QUOTE] "播放AMV视频的最简单方法直接使用电脑的视频播放器打开，一些万能的视频播放器（例如KMPlayer、PotPlayer、暴风影音）都支持播放AMV视频。" — <https://www.wenjianbaike.com/amv.html>
  [QUOTE, vendor ad] "AMV是早期MP4播放器常用的小众视频格式，Windows系统默认无法识别。使用软领Pot播放器可直接播放，无需额外安装解码包。" — <https://www.wyouhua.com/case/show/4127.html> (2026-08-04). Same page's failure symptoms: "AMV播放卡顿…表现为卡顿**或音画不同步**" and "电脑上播放花屏怎么办？…花屏多半是解码过程中出现了错位". **LOW reliability** — it is an ad, and it misidentifies AMV as "anime music video".
- **GOM Player, Aiseesoft, AVS: nothing found.** **Android (VLC/MX Player): unverified aggregator list only** (<https://fileinfo.cn/extension/amv>). Earlier Chinese pages also name "AMV Player" / the software bundled with the device (<https://anyconv.com/amv-converter/>, <https://convert.guru/amv-converter>).

### 12.9 Independent confirmation of the negative results

The second pass **independently failed** to find: any maximum file size, any 2 GB/FAT cap, any
"file is too large" report, any maximum duration, and **any "video but no audio" report for any
desktop player**. It also flagged that the device files carry `Duration: N/A`, so no duration limit
is even representable in the metadata. Observed failure modes instead: **VLC crash (2011)**,
**on-device "Format error"**, **stutter / A-V desync**, **decode refusal (non-mod-16, 2015)**,
**glitching/corrupt picture**, and **choppy audio from the 2007 alpha encoder**.

Note also that the second pass **corrected a wording error in llogan's accepted answer**: he writes
"Frame rate must be divisible by the audio sample rate (22050)" — the wording is inverted (22050 is
not divisible by the frame rate). The real constraint, per `amvenc.c`, is
`sample_rate % aframe_size == 0`, i.e. **sample_rate/fps must be an integer**.
