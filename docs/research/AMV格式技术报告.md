# AMV (Actions Media Video) 格式技术报告

> 调查方式：FFmpeg 源码逐行核对 + **本地真实生成 .amv 文件并做字节级/像素级实验**（本机 ffmpeg N-126497 / git 源码 FFmpeg-n7.1）+ 中英文社区资料检索。
> 证据等级标注：**[A]** = 我亲自验证（源码或可复现实验）；**[B]** = 多个独立来源一致（含他源源码/真实设备文件）；**[C]** = 社区说法/未验证。

---

## 0. 结论摘要（最重要的 8 条）

1. **容器不是标准 AVI**：它是 `RIFF` 表单，表单类型是 **`"AMV "`（含尾随空格）**，不是 `"AVI "`。头部用私有块 **`amvh`（56 字节）取代标准 `avih`**。[A]
2. **不存在 `AMVV` / `AMVA` fourCC**。FFmpeg 整个源码树中 `AMVV`/`AMVA` **零次出现**；RIFF 注册表里只有 `{ AV_CODEC_ID_AMV, MKTAG('A','M','V','F') }`（即 `AMVF`）。FFmpeg 生成的 .amv 里，视频 `strh`/`strf` 是 **56/36 字节全零**，音频 `strh` 是 **48 字节全零**。[A]
3. **视频帧不是可看的 JPEG**：每帧 = `FF D8` + 裸熵编码数据 + `FF D9`，**没有 DQT(FFDB)/DHT(FFC4)/SOF0(FFC0)/SOS(FFDA)/APP0(FFE0)**。我实测把帧原样导出为 .jpg 后，ffmpeg 报 `No JPEG data found in image` 并退出码 69。**"AMV 帧是有效 JPEG，可直接抽取查看" = 已被证伪。**[A]
4. **帧是上下颠倒（bottom-up）存储的**。我用 sp5x 固定表自行重建 JPEG 头后与 ffmpeg 解码结果逐字节比对：**vflip 后 19200/19200(Y) 与 4800/4800(U) 完全一致，mad=0.00** —— 位精确证明。[A]
5. 视频是 **表被剥离的基线 JPEG**，量化表/Huffman 表/几何/扫描参数全部"硬编码在播放器里"，文件里没有。FFmpeg 用的是 **SP5X 表**（= JPEG Annex K 表 ×0.8），Huffman 用 **标准 Annex K 默认表**。[A]
6. 音频确实是 **IMA/DVI ADPCM，且只有 22050 Hz、只有单声道**（编码器 `adpcm_ima_amv` 只支持 22050/mono）。块 = **8 字节块头 + ⌈每帧样本数/2⌉ 字节**，块头是 `int16 predictor, uint8 step_index, uint8 reserved, uint32 frame_size`。[A]
7. **经典社区命令 `-r 16` 在当代 ffmpeg 上必然失败**（22050 无法被 1378 整除）；`-s 160x120` 还必须加 `-strict -1`（120 不是 16 的倍数）。我实测两者都会报错。[A]
8. `.amv`（RIFF）**与 `.mtv` 是完全不同的格式**：`.mtv` 魔数是 ASCII `AMV`、512 字节头、音频是 **MP3**、视频是 RGB565 原始像素（FFmpeg `libavformat/mtv.c`）。"MTV 转换器"输出的是 .mtv，不要和 .amv 混淆。[A]

---

## 1. 容器层：到底是不是 AVI？fourCC 是什么？

### 1.1 顶层结构（我生成的真实文件 `tC.amv`，105232 字节，14fps/160x120/22050Hz）

```
[0]     fourCC      = "RIFF"
[4]     RIFF size   = 0            ← 故意写 0（不是文件长度-8）
[8]     form type   = "AMV "       hex = 41 4d 56 20   ← 尾随空格！
@12     "LIST" size_field=0 subtype="hdrl"     ← LIST 长度也是 0
@24     "amvh" size=56             ← 私有主头，取代标准 avih
@92     "LIST" size=0 subtype="strl"
@100    "strh" size=56  数据 56 字节全 00      ← 视频流头，全零
@164    "strf" size=36  数据 36 字节全 00      ← 视频 BITMAPINFOHEADER，全零
@212    "LIST" size=0 subtype="strl"
@220    "strh" size=48  数据 48 字节全 00      ← 音频流头，全零
@276    "strf" size=20  数据 20 字节（伪 WAVEFORMATEX，见 §4）
@312    "movi"                                 ← 固定偏移！0x138
@316    第一个 "00dc"                          ← 0x13C
...
末尾    "AMV_" "END_"（8 字节 ASCII 尾标，无尾部填充）
```

- **没有 `idx1` 索引块**（实测搜索 `idx1` 无命中）；`amvenc.c` 注释直言 `There is no index.`。[A]
- **没有标准 `avih`**；FFmpeg 的解复用器正是靠 `amvh` 的存在判定"这是 AMV"：`libavformat/avidec.c` 中 `case MKTAG('a','m','v','h'): amv_file_format = 1;` 然后 fallthrough 到 `avih` 分支，并**强制**把第一个流的 `fccType` 当成 `vids`、第二个当成 `auds`，**完全跳过 strf 内容**（`avio_skip(pb, size)`），音频直接钉死为 `AV_CODEC_ID_ADPCM_IMA_AMV`。[A]
- 头部前导长度是**恒定的 0x138/0x13C**：我的实测值与 2008 年 `amv-codec-tools` 的 `compare_amv.c`（`fseek(fp, 0x138)` 期待 `movi`）**完全一致**，可交叉验证。[A][B]

### 1.2 `amvh` 56 字节布局（我从真实文件读出 + 源码交叉验证）

| 偏移 | 类型 | 我的实测值 | 含义 |
|---|---|---|---|
| +0 | uint32 LE | 71429 | 每帧微秒数（`us_per_frame`，71429 → 14 fps） |
| +4..+31 | 28 字节 | 全 0 | 保留（OxideAV 称之为"7 dword 哨兵"） |
| +32 | uint32 LE | 160 | 宽 |
| +36 | uint32 LE | 120 | 高 |
| +40 | uint32 LE | 14 | 帧率分母（`time_base.den`）；2008 中文头文件把该槽命名为 `dwSpeed`（帧/秒） |
| +44 | uint32 LE | 1 | 帧率分子；2008 中文头文件注明 `reserve0 // 值都是1，用途不明`（即设备上恒为 1） |
| +48 | uint32 LE | 0 | 0；2008 中文头文件注明 `reserve1 // 值都是0` |
| +52 | 3 字节 | `02 00 00 00` | 时长，**打包为 ss, mm, uint16 hh**（不是线性秒数！）2008 中文头文件同为 `BYTE dwTimeSec; BYTE dwTimeMin; WORD dwTimeHour`，其解码器据此算 `totalframe = (hh*3600+mm*60+ss) * dwSpeed` |

来源：`libavformat/amvenc.c`（`amv_write_header`/`amv_write_trailer`）+ 我的实测 dump。[A]

### 1.3 fourCC：`AMVV`/`AMVA` 是**传说**

- FFmpeg 全树 grep `AMVV|AMVA` → **0 命中**（`amvh`、`AMV_`、`AMVF` 有命中）。[A]
- `libavformat/riff.c` 中唯一的 AMV 注册项：`{ AV_CODEC_ID_AMV, MKTAG('A','M','V','F') }` → **`AMVF`**。[A]
- 实测我生成文件的 `strh`/`strf` 全零；ffprobe 报视频 `codec_tag_string=[0][0][0][0]`（即 0x00000000），音频 `0x0001`。因此**没有任何 `AMVV`/`AMVA` 出现在文件里**。[A]
- 2008 年 `amv-codec-tools` 自带的反向工程头文件 `C-AMVDecoder/amvlib/AMVHeader.h`（中文注释）把 strh 体直接声明为 `BYTE reserved[56]/[36]/[48]`，**同样没有 AMVV/AMVA**；AVI 的 `fccType`/`fccHandler`/`rcFrame` 等字段名在该文件里**只存在于被注释掉**的清单中。[B]
- 2008 年那棵树里 `codec_bmp_tags[]` **根本没有 AMV 条目**（fccHandler 解析为 0），`codec_wav_tags[]` 里 ADPCM_IMA_AMV 映射的是**数字 `0x01`**，不是 `AMVA`。`AMVF` 是 2008 年之后才加进 FFmpeg 的，且从未是 `AMVV`。[B]
- 唯一会填 fccType 的写入器（2008 年那个）写的是 `"vids"`/`"auds"`（视频 fccHandler = codec_tag，音频硬编码 1）——**仍然没有 AMVV/AMVA**。[B]
- **`AMVA` 三个字母在任何被取证的源码/文件里都没有出现过。** 结论：`AMVV`/`AMVA` 属于以讹传讹；唯一有依据的 fourCC 是 `AMVF`（AVI 命名空间，且 AMV 复用器自己都不写）。

### 1.3b `AMVV` 传说的来历（已查明：有作者、有日期、有撤回）

`AMVV` **不是**从文件里观察到的，而是 2007 年 FFmpeg AMV 补丁作者 **Vladimir Voroshilov** 临时自造的 MPlayer 内部调度标记：

> "AMV files contains ugly headers: **strh sections are filled by zero**, strf sections contains movie duration, image size and frame duration for video and waveformatex structure for audio respectively. **Index is absent.**
> P.S. I also use new fourcc (**AMVV**) to allow further interaction with mplayer."
> —— 2007-09-24，https://ffmpeg.org/pipermail/ffmpeg-devel/2007-September/039519.html

他随后解释为何不用 `MJPG`：`"When i set codec_tag to MJPG, mjpeg decoder is loaded instead of amvv (in MPlayer of course). codec_tag==AMVV plus apropriate section in codecs.conf works fine."`（即纯粹为了 MPlayer 的 `codecs.conf` 分流）Aurelien Jacobs 回复 `"IIRC, this is not needed."`

**两天后他自己删掉了它**（2007-09-26）：
> "**Fake fourcc code removed.** I've also changed CODEC_ID_ADPCM_IMA_WS to CODEC_ID_ADPCM_IMA_AMV ..."
> —— https://ffmpeg.org/pipermail/ffmpeg-devel/2007-September/039624.html

同期评审还反对过命名（`"Why do you call it "amvv"? Just "amv" would be better."`，https://ffmpeg.org/pipermail/ffmpeg-devel/2007-September/039651.html ）。

**因此：`AMVV` 是 2007-09-24 为 MPlayer 分流临时发明、2007-09-26 提交前删除的私有 codec_tag，从未被任何写入器写进过任何文件。** 而 `AMVA` 在所有被取证的源码/表/文档/样本中**零命中**，属凭空捏造。这段引用同时也是"真实设备文件里 strh 全为零"的**原作者证词**。[B]
- 社区里唯一可见的 fourCC 是 `AMVF`：superuser 用户报告"输出 .amv 是 AVI 格式，其视频部分是 AMVF 格式"（https://superuser.com/questions/1426454/using-ffmpeg-to-convert-video-solution-to-rejected-amv-container ）。[C]
- **结论：不要写 `AMVV`/`AMVA`。** 播放器不依赖 strh/strf 内容（FFmpeg 解复用器直接跳过并强制类型）。若一定要填，`AMVF` 是唯一有依据的值。

### 1.4 真实 .amv 能被哪些播放器打开？

- **ffmpeg/ffprobe：能，且我本地实测通过**（`ffprobe` 识别 `format_name=avi`，视频 `amv/yuvj420p/160x120`，音频 `adpcm_ima_amv/22050/mono`；解码 `-f null -` 退出码 0，首帧还原为正确图像）。[A]
- 真实播放器导出的工厂 .amv 也被 ffmpeg 正常识别：superuser 用户 remux 后得到 `Input #0, avi, from '..\1.8_Test-2.amv'` / `Video: amv, yuvj420p, 160x120, 16 fps` / `Audio: adpcm_ima_amv ... 22050 Hz, mono`，并有 `scale/rate is 0/0 which is invalid. (This file has been generated by broken software.)` 警告（https://superuser.com/questions/1649146/convert-mpeg4-to-amv-via-ffmpeg ）。[B]
- **VLC：找不到任何"VLC 确实播放过 .amv"的实证记录**。唯一直接相关的是 2007-12 vlc-list 邮件列表讨论（https://mailman.videolan.org/pipermail/vlc/2007-December/015243.html 起），开发者回复大意是"这只是 ffmpeg 的补丁，把支持并进 ffmpeg 再让 VLC 用相应 fourcc 即可"——即当时 VLC **没有**支持。现代 VLC 是否支持：**未验证**，请勿断言。[C]
- **MPC-HC / MPV / WMP / MPlayer：未验证**。MPlayer 有 2007 年 "AMV files playback support" 补丁标题但正文站点被 Anubis 拦截；MPC-HC/MPV 因使用 libavcodec 而"应当"能播，属推断。[C]
- 廉价硬件播放器：2007 年有人报告 `-r 12` 生成的文件在低端 S1 MP3 播放器上**不播**（https://mailman.videolan.org/pipermail/vlc/2007-December/015249.html ）；2021 年有用户报告按 `-r 25 -block_size 882` 生成的 .amv **在目标播放器上播放正常（含时间轴）**（https://superuser.com/a/1649185 ）。[B]

### 1.5 别名澄清：`.mtv` ≠ `.amv`

FFmpeg `libavformat/mtv.c`：魔数是前 3 字节 ASCII `AMV`，固定 512 字节头，音频标识字符串固定为 `MP3`，视频是 RGB565/555 原始帧，采样率常量 44100。这是**另一套格式**（.mtv，常见于"MTV 转换器"输出），与 RIFF 容器无关。[A]

---

## 2. 视频编解码：帧到底是什么？

### 2.1 帧 = 表被剥离的 JPEG（关键结论）

对真实文件的第一个 `00dc`（3061 字节）做标记普查：

```
first 4 bytes: ff d8 f3 1a      last 4 bytes: f6 e7 ff d9
count FFD8 SOI  = 1        count FFD9 EOI  = 1
count FFDB DQT  = 0        count FFC4 DHT  = 0
count FFC0 SOF0 = 0        count FFDA SOS  = 0
count FFE0 APP0 = 0        count FFEE APP14 = 0        count FFDD DRI = 0
frames 中 0xFF 之后出现的字节种类：只有 00 / d8 / d9（00 = JPEG 字节填充）
```

**源码铁证**（`libavcodec/mjpegenc_common.c`，`ff_mjpeg_encode_picture_header`）：

```c
    put_marker(pb, SOI);

    // hack for AMV mjpeg format
    if (avctx->codec_id == AV_CODEC_ID_AMV)
        return;
```

即 AMV 编码器**只写 SOI 就 return**，DQT/DHT/SOF0/SOS/COM/APP0 全部不写。[A]
（对应解码侧：AMV 的**视频解码器**其实是 `libavcodec/sp5xdec.c` 里的 `ff_amv_decoder`，它在内存中把 `sp5x_data_dqt`/`sp5x_data_dht`/`sp5x_data_sof`/`sp5x_data_sos` **拼回**到帧数据前，再交给 MJPEG 解码器；对 AMV 它把 `buf[2 .. size-2]` 原样复制，不做重新填充。）[A]

### 2.2 "AMV 帧可以直接抽取查看" —— **证伪**

我把第一个 `00dc` 载荷原样写成 `rawframe.jpg`（3061 字节，以 FFD8 开头、FFD9 结尾），交给 ffmpeg 解码：

```
[mjpeg] No JPEG data found in image
[vist#0:0/mjpeg] Error submitting packet to decoder: Invalid data found when processing input
ffprobe: Could not find codec parameters ... unspecified size      exit code 69
```

**任何标准 JPEG 解码器都会拒绝**（缺 SOF0 → 不知道尺寸；缺 DQT/DHT → 无法反量化/熵解码；缺 SOS → 没有扫描头）。[A]

> 该传闻为何流行：(a) 帧确实以 `FF D8` 开始、`FF D9` 结束，用 `dd` 抠出来"看起来像" JPEG；(b) ffmpeg <4.4 时代人们的变通做法是"编成 .avi 再把扩展名改成 .amv"（https://superuser.com/questions/1426454/ ），**那种文件里的帧是完整 JPEG**（我实测 `-c:v mjpeg` 的帧含 APP0"JFIF"+DQT+DHT+SOF0+SOS 各 1 个）。所以"AMV 帧是完整 JPEG"对**改名文件**成立，对**真正的 AMV** 不成立。[A]

兼容性补充实验：我手工构造了一个 `00dc` 载荷为**完整 JPEG**（含 APP0/DQT/DHT/SOF0/SOS）的 .amv，FFmpeg 的 AMV 解码器**居然能解**（文件自带的 SOF 生效，输出变成 `yuvj444p`，并有 `overread 8` 警告）。说明解码路径等价于"先塞一套默认头、再解析整段"，**对两种写法都容忍**，但设备原生/FFmpeg 产出的是剥离式。[A]

### 2.3 固定量化表与 Huffman 表

- **Huffman：标准 JPEG Annex K 默认表，不写在文件里。** `libavcodec/mjpegenc.c`：`if (s->codec_id == AV_CODEC_ID_AMV || use_slices) m->huffman = HUFFMAN_TABLE_DEFAULT;` —— AMV 永远用默认表（不会用逐帧最优表），这正是播放器能硬编码的原因。[A]
- **量化表：FFmpeg 用 SP5X 表，并且在编解码两侧都强制写死。**
  - 解码侧：`sp5xdec.c` 把 `sp5x_data_dqt` 的第 5 字节起和第 70 字节起**覆写**为 `sp5x_qscale_five_quant_table[0]`（亮度）与 `[1]`（色度）。[A]
  - 编码侧：`libavcodec/mpegvideo_enc.c` 第 3781-3799 行对 `AV_CODEC_ID_AMV` **直接覆写 quant 矩阵**：
    ```c
    s->intra_matrix[j]        = sp5x_qscale_five_quant_table[0][i];
    s->chroma_intra_matrix[j] = sp5x_qscale_five_quant_table[1][i];
    s->y_dc_scale_table = y;   // 32 个 13
    s->c_dc_scale_table = c;   // 32 个 14
    s->intra_matrix[0] = 13;  s->chroma_intra_matrix[0] = 14;
    s->qscale = 8;
    ```
  - 我实测的表值（十进制）：亮度 `13,9,10,11,10,8,13,11,10,11,14,14,13,15,19,32,...,79`；色度 `14,14,14,19,17,19,38,21,21,38,79,53,45,53,79,...79`。
  - **我另外发现一个数学关系**：这两张表恰好等于 **JPEG 标准 Annex K.1/K.2 表（按 zig-zag 顺序）× 0.8**（= libjpeg quality 60 的缩放系数）。这解释了命名里的 "qscale_five"，也说明 SP5X 表本身就是"标准表缩放到 Q60"。[A]
  - 编码器还把 DC 预测初值设为 `128*8/13`（亮度）/`128*8/14`（色度），与上述表首值自洽（`mpegvideo_enc.c:2894`）。[A]
- **副作用（重要）**：`-q:v` / `-qscale` 对 AMV **完全无效**。我分别用 `-q:v 2/5/10/20/31` 编码，5 个文件与默认值文件 **MD5 完全相同**（`DBCCBCFE3CF3FD15314CAEF055F65BCA`，均 105232 字节）；同样参数对 `-c:v mjpeg` 则从 42244 字节变到 18478 字节。**画质无法通过 -q:v 调节，只能调分辨率/帧率**。[A]

### 2.4 帧是上下颠倒存储的（bottom-up）

- 编码器（`libavcodec/mjpegenc.c` `amv_encode_picture`）：
  ```c
  //picture should be flipped upside-down
  pic->data[i] += pic->linesize[i] * (vsample * s->c.height / V_MAX - 1);
  pic->linesize[i] *= -1;
  ```
- 解码器（`libavcodec/mjpegdec.c:2751`）：`if (s->flipped && !s->rgb) { frame->data[i] += (h-1)*linesize[i]; frame->linesize[i] *= -1; }`，而 `s->flipped` 在 `AV_CODEC_ID_AMV` 时置 1。[A]
- **我的位精确实验**：用 `sp5x.h` 里的原始表自行重建 JPEG 头，套在同一个 `00dc` 载荷上，分别解码"我重建的 JPEG"与"原 .amv 首帧"为原生 `yuvj420p`，再逐字节比较：
  ```
  Y 平面: identity 相等 10774/19200, mad=25.10 ；vflip 后 相等 19200/19200, mad=0.00
  U 平面: identity 相等  2124/4800 , mad=38.95 ；vflip 后 相等  4800/4800 , mad=0.00
  ```
  行映射抽样也显示 `b 行 r ≈ a 行 119-r`。**结论：文件中存储的扫描数据在"正着解"时得到的是上下镜像图**；任何自己写 AMV 编码器的人必须把图像上下翻转后再做 DCT。[A]

### 2.5 与 OxideAV 的冲突（一项已裁决，一项仍存疑）

[Rust 实现 OxideAV/oxideav-amv 的 README](https://github.com/OxideAV/oxideav-amv)（对真实设备文件 `comedian.amv` 128×96@12fps、`noel-son-lumiere.amv` 96×64@16fps 做逆向）声称：插入的应当是 **"JPEG Annex K example tables verbatim and unscaled"（未缩放的标准表）**，且音频是 **low-nibble-first** 打包。[B]

而我验证 FFmpeg 用的是 **Annex K × 0.8（SP5X 表）**、音频是 **high-nibble-first**（`adpcm.c`：先 `v >> 4` 再 `v & 0xf`）。量化表差异**恰好是 0.8 倍**，且 FFmpeg 的编码器与解码器互相自洽（所以 ffmpeg 自产文件能完美回放）——**量化表这一点仍无真实设备样本可裁决**。

**但 nibble 顺序已经裁决（见 §11.2）**：2008 年 `amv-codec-tools` 的 C 解码器（针对真实设备文件写的）`C-AMVDecoder/amvlib/AdpcmIma.c` 里的注释原文是 `//压缩算法是按前四个字节->右的顺序存储的吧，先高4bit后低4bit.`，代码亦为先 `src[4*i]>>4` 再 `src[4*i]&0x0F` —— **高位先**。即 **3 个独立来源中 2 个（FFmpeg + 2008 设备解码器）为高位先**，OxideAV 的 low-first 说法是孤例（其自述"未参考任何第三方 AMV 实现"，很可能在该细节上判错）。**建议按高位先实现。**

### 2.6 2008 年实现独立复现了"只写 SOI"与"上下翻转"

`amv-codec-tools` 2008 年的 `AMVmuxer/ffmpeg/libavcodec/mjpegenc.c` 里：

```c
    put_marker(&s->pb, SOI);
    // hack for AMV mjpeg format
    if(s->avctx->codec_id == CODEC_ID_AMV) return;
```

与 2020 年 FFmpeg 的写法**逐字同构**；其 `amv_encode_picture()` 里同样有 `//picture should be flipped upside-down` 与 `pic->linesize[i] *= -1`。这是"帧被剥离表 + bottom-up 存储"的**第二个独立实现级证据**。[B]

---

## 3. 典型视频参数

| 项目 | 值 | 依据 |
|---|---|---|
| 像素格式 | **只接受 `yuvj420p`**（full-range JPEG YUV） | `ff_amv_encoder` 的 `CODECFMTS(AV_PIX_FMT_YUVJ420P)`；`-h encoder=amv` 输出 `Supported pixel formats: yuvj420p` [A] |
| 色度采样 | **4:2:0**（SOF 里 Y=2×2, Cb=Cr=1×1） | `sp5x_data_sof` 字节；`amv_encode_picture` 注释 `/* AMV is 420-only */` [A] |
| 帧率 | 上限：**>63fps 拒绝**（`us_per_frame < 15873` 报 `Refusing to mux >63fps video`）。实际可用帧率受 22050 整除约束（见下表） | `amvenc.c` + 我的实测 [A] |
| 分辨率 | 编码器只约束 JPEG 上限 65500×65500；**高度必须是 16 的倍数**，否则报错（见 §7） | `mjpegenc.c` [A] |
| 码率 | 无独立码率概念，由分辨率/帧率/内容决定；64kbps~700kbps 量级（real 设备文件 62 秒 = 5031 kB，约 664.7 kbit/s） | superuser 1649146 [B] |
| 常见分辨率 | 160×120、128×96、96×96（我实测均可用）；社区另有 176×144、208×176、160×128、320×240、296×256 等 | [A][C] |

**可用帧率表（22050 Hz 下的精确解，我用 ffmpeg 的算法复算并实测验证）**：
`aframe_size = round(22050 × round(1e6/fps) / 1e6)`，要求 `22050 % aframe_size == 0`：

| fps | aframe_size（=每帧样本数= `-block_size`） | block_align |
|---|---|---|
| 10 | 2205 | 1111 |
| **14** | **1575** | **796** |
| 15 | 1470 | 743 |
| 18 | 1225 | 621 |
| 21 | 1050 | 533 |
| **25** | **882** | **449** |
| 30 | 735 | 376 |
| 35 | 630 | 323 |
| 42 | 525 | 271 |
| 45 | 490 | 253 |
| 49 | 450 | 233 |
| 50 | 441 | 229 |
| 63 | 350 | 183 |

**被拒绝的帧率**：4, 8, 11, **12**, 13, **16**, 17, 19, 20, 22, 23, 24, 26…62（因为 `aframe_size × fps ≠ 22050`）。
注意 **12 和 16 都不行** —— 尽管真实设备文件里确实存在 160×120@16fps（说明设备编码器容忍 ~0.01% 的音频漂移，FFmpeg 的复用器不容忍）。[A][B]

---

## 4. 音频：ADPCM IMA ("AMVA")

### 4.1 编码器硬约束（实测 `-h encoder=adpcm_ima_amv`）

```
Supported sample rates: 22050          ← 只有这一个！
Supported sample formats: s16
Supported channel layouts: mono
ADPCM encoder AVOptions: -block_size <int> (from 32 to 8192) (default 1024)
```
源码 `adpcmenc.c`：采样率不是 22050 → `"Sample rate must be 22050"`；声道数 ≠1 → `"Only mono is supported"`；`frame_size = block_size`；`block_align = 8 + FFALIGN(frame_size,2)/2`。[A]

### 4.2 每块字节布局（4-bit IMA/DVI ADPCM）

我实测真实文件第一个 `01wb`（796 字节）的头部 16 字节：
```
00 00 00 00 27 06 00 00 07 77 77 77 20 00 08 08
└─ predictor ─┘ │  │  └ frame_size ┘
   int16 LE = 0  │  └ reserved = 0
                 └ step_index = 0                (0x0627 = 1575 ✓ = 14fps 的每帧样本数)
```
源码（`adpcm.c` 解码侧注释原文）：
```
 * Header format:
 *   int16_t  predictor;
 *   uint8_t  step_index;
 *   uint8_t  reserved;
 *   uint32_t frame_size;
 *
 * Some implementations have step_index as 16-bits, but others
 * only use the lower 8 and store garbage in the upper 8.
```
- 偏移 0：`int16 LE` 初始预测值（= 该块第一个样本）
- 偏移 2：`uint8` 初始 step index（0..88）。**注意**：OxideAV 观察到 +3 字节在某些文件里是 0x00、某些文件里恒为 0xAA，并据此认为 +2 是单字节 step index、+3 是"设备常量"；FFmpeg 则把 +2 读为 uint8 step 并跳过后续 5 字节。两种读法对 +0/+4 的字段一致。[A][B]
- 偏移 4：`uint32 LE` **该块的样本数**（不是字节数！）——FFmpeg 编码器写 `bytestream_put_le32(&dst, avctx->frame_size)`，其静音填充包写 `AV_WL32(apad->data + 4, aframe_size)`。[A]
- 偏移 8 起：packed nibbles。**每个字节高位半字节先解**（FFmpeg：`v>>4` 然后 `v&0xf`，自适应步长索引 3）。块内 step index 在块首重置（每块自包含）。[A]
- 若样本数为**奇数**，最后一个样本只占一个字节的高半字节（编码器 `if (avctx->frame_size & 1)` 分支），因此 `block_align = 8 + ⌈frame_size/2⌉`。[A]
- **一块音频对应一帧视频**，1:1 严格交替。[A]

### 4.3 `strf` 里的 WAVEFORMATEX —— 故意写错的

FFmpeg 源码注释：`/* Bodge an (incorrect) WAVEFORMATEX (+2 pad bytes) */`。实测 20 字节：

| 字段 | 值 | 说明 |
|---|---|---|
| wFormatTag | **1** | = WAVE_FORMAT_PCM，**错的**（真值应为 0x11 IMA ADPCM 之类；FFmpeg 故意写错，因为某些播放器看到"正确值"反而崩） |
| nChannels | 1 | 单声道 |
| nSamplesPerSec | 22050 | ✓ |
| nAvgBytesPerSec | **44100** | = 22050×1×2（按 16bit PCM 算的，错的） |
| nBlockAlign | **2** | 错的（真实块对齐见 §4.2） |
| wBitsPerSample | **16** | 错的（实际 4 bit） |
| cbSize | 0 | |
| 末尾 2 字节 | 0 | `sizeof(WAVEFORMATEX)+2` 的填充 |

因此 `ffprobe` 会报告 `bits_per_sample=4`（来自解码器）但同时 `bit_rate=352800`（来自那个错误的 nAvgBytesPerSec），并且 `codec_tag=0x0001`。**写 AMV 时不要"修正"这些字段**：FFmpeg 的做法是刻意如此，且解复用器根本不读 strf（见 §1.1）。[A]

### 4.4 有些 .amv 用 MP3 吗？

- **RIFF `.amv` 里用 MP3：没找到任何证据**。FFmpeg 的 AMV 复用器**硬性要求** `AV_CODEC_ID_ADPCM_IMA_AMV`（`av_assert1`），解复用器在判定 `amv_file_format` 后也把音频直接钉成 `ADPCM_IMA_AMV`。[A]
- **用 MP3 的是 `.mtv`**（魔数 `AMV`、512 字节头、`MTV_AUDIO_SAMPLING_RATE 44100`、音频标识必须是 `MP3`）：`libavformat/mtv.c`。[A]
- 中文工具页把 AMV 与 MTV 并列为同一转换器的两种输出（https://xiazai.zol.com.cn/baike/561645.shtml ），这大概是"AMV 用 MP3"误解的来源。[C]

---

## 5. ffmpeg 命令：能用的与不能用的

### 5.1 我**实测成功**的最小可用命令（本地 ffmpeg N-126497）

```bash
# 128x96 @ 25fps（96 是 16 的倍数，不需要 -strict）
ffmpeg -i in.mp4 -vf scale=128:96 -r 25 -ac 1 -ar 22050 \
       -c:v amv -c:a adpcm_ima_amv -block_size 882 out.amv

# 160x120 @ 14fps（120 不是 16 的倍数 → 必须 -strict -1）
ffmpeg -i in.mp4 -vf scale=160:120 -r 14 -ac 1 -ar 22050 \
       -c:v amv -c:a adpcm_ima_amv -block_size 1575 -strict -1 out.amv
```
实测结果：128×96@25 → 93150 字节/2 秒；160×120@14 → 105232 字节/2 秒。**`-f amv` 不需要**（`.amv` 扩展名足以选中复用器，日志中显示 `out#0/amv`）。[A]

### 5.2 社区版本（含逐字引用）

- 被采纳的答案（最短形式，**不需要 `-c:v amv`、不需要 `-f amv`**）：
  `ffmpeg -i input.mp4 -ac 1 -ar 22050 -r 25 -block_size 882 output.amv`
  （https://superuser.com/questions/1649146/convert-mpeg4-to-amv-via-ffmpeg ）[B]
- 用户报告"在播放器上验证通过"的版本：
  `ffmpeg.exe -i "c:\input160x120.mp4" -c:v amv -c:a adpcm_ima_amv -pix_fmt yuvj420p -vstrict -1 -s 160x120 -ac 1 -ar 22050 -r 25 -block_size 882 "c:\output.amv"`
  （https://superuser.com/a/1649185 ）[B]
- 208×176（4:3 全屏拉伸）：
  `ffmpeg -i input.mp4 -ac 1 -ar 22050 -r 10 -block_size 2205 -vf scale=w=208:h=176 output.amv`（"And it works."，https://superuser.com/questions/1852829/ ）[B]
- 中文教程（注意 `scale=160*128` 是错的写法）：
  `ffmpeg -i input.mp4 -ar 22050 -ac 1 -vf "scale=160*128" -r 15 -block_size 1470 output.amv`
  （https://blog.csdn.net/2401_86101673/article/details/145552499 ）[C]
- 开源脚本（真实玩家驱动的参数）：`-r 14 ... -block_size 1575`（https://github.com/fdd4s/portable_mp3_player_video_converter_tools ）[B]
- 2007 年 amv-codec-tools 自带 Makefile：`ffmpeg -i $*.avi -f amv -r 16 -s 160x120 -ac 1 -ar 22050 -y $*.amv` —— **注意这里的 `-r 16` 用的是它自带的旧版补丁 ffmpeg，当年能用；用现代 ffmpeg 会失败**。[B]

### 5.3 `-r 16` 在现代 ffmpeg 上必然失败（我实测）

```
$ ffmpeg ... -r 16 -ar 22050 -c:a adpcm_ima_amv ...
[amv @ ...] Invalid audio frame size. Got 1024, wanted 1378
[amv @ ...] Invalid audio block align. Got 520, wanted 697
[amv @ ...] Try -block_size 1378
$ ffmpeg ... -r 16 ... -block_size 1378 ...
[amv @ ...] Audio sample rate not a multiple of the frame size.
Please change video frame rate. Suggested rates: 10,14,15,18,21,25,30
[out#0/amv] Could not write header (incorrect codec parameters ?): Invalid argument
exit code = -22
```
原因：`aframe_size = round(22050 × (1e6/16) / 1e6) = 1378`，而 `16 × 1378 = 22048 ≠ 22050`。同理 12fps 也不可用。**所以网上流传的"`-r 16`"配方在 FFmpeg ≥4.4 上是不能照抄的**（4.4 是第一个带 AMV 复用器/编码器的版本）。[A]

---

## 6. 实际容量/上限

- 真实设备工厂文件的实测：**160×120 @16fps，音频 22050Hz mono 88kb/s，62 秒 → 5031 kB（约 664.7 kbit/s）**（https://superuser.com/questions/1649146/ ）。[B]
- 格式工厂官方 FAQ 的推荐值：`推荐的分辨率有320*240、160*128；推荐的帧率有14、15、25fps`，并说明"如果视频的原始分辨率或者帧率太高，容易出现转换为amv格式后播放画面卡顿"（http://m.pcgeshi.com/faq/daorump3mp4.html ）。[C]
- 典型体积：中文工具页称 `一分钟的容量约为1.8MB`（https://xiazai.zol.com.cn/baike/561645.shtml ）。[C]
- **为什么通常只有几 MB**：①分辨率 ≤320×240、帧率 ≤25fps；②每帧是**强量化**的单帧 JPEG（表被固定为 Q60 左右且无法调节，见 §2.3）；③音频是 4bit/样本的 ADPCM（约 11 kB/s）；④廉价播放器存储/解码能力有限，实践上人们会主动把片子切短。
- **单文件大小上限、时长上限、分辨率上限：没有任何可靠公开数据**。这是典型的"取决于具体播放器固件"的问题，**不要编造数字**。有反例：2007 年有人把 6MB WMV 转成 20MB 的 AMV 也能生成（https://mailman.videolan.org/pipermail/vlc/2007-December/015246.html ）。[C]
- **设备侧分辨率上限的实证线索（按支持度排序）**：**128×96**（某 2006 年韩系 1.5 吋 AMV 播放器规格页写 `AMV … 128x96…`，http://www.rexcos.co.kr/Products/mp4/rexk7/rexk72gs.htm ——该页 EUC-KR 编码被工具按 Latin-1 解码，韩文无法逐字引用，只有 `AMV` 与 `128x96` 可确认；并与 OxideAV 的真实设备样本 `comedian.amv` = 128×96@12fps 吻合）→ **160×120@16fps**（真实播放器导出的工厂文件）→ **208×176**（filetypeadvisor 称 "up to 208×176"，且与用户实测命令吻合）→ 240×320/320×256 仅有"能编码出来"的报告，**没有播放验证**。

**分辨率区间的干净表述**（三个方向交叉验证）：**下限 ≈ 94×64 / 96×64**（中文来源 https://www.wenjianbaike.com/amv.html 与 https://fileinfo.cn/extension/amv 均称"以 94×64 到 160×120 的低分辨率保存"；且与 OxideAV 真实语料 `noel-son-lumiere.amv` = 96×64 吻合）→ **常见 128×96 与 160×120**（韩国 1.5 吋设备规格页 + 两份真实设备文件均为 160×120）→ **上限约 160×120 ~ 208×176**。

**音频体积是不可压缩的固定值（算术）**：4bit IMA ADPCM @22050 单声道 = **11025 B/s = 88.2 kbit/s = 661 kB/分钟**（与实测 62 秒≈675 kB 音频、FFmpeg 自报 "88 kb/s" 一致）。
⚠️ **注意与中文流传数字的冲突**：实测设备文件为 5031 kB / 62 s = **4.87 MB/分钟**，而 ZOL、wenjianbaike 等中文来源普遍声称"一分钟约 1.6–1.8MB"——**相差 2.5~3 倍**。两者请并列标注，不要挑一个当结论。

**第三个真实设备样本**：`daomeixiong.amv`（FFmpeg trac #4770 的样本，https://samples.ffmpeg.org/ffmpeg-bugs/trac/ticket4770/ ，3.6MB）——ffprobe：`amv yuvj420p 160x120, 12 fps` / `adpcm_ima_amv 22050 Hz mono`（头部声称 352 kb/s，是那个伪造的 AVI 字段）。因此现在有**两份相互独立的真实设备文件，都是 160×120**（12fps 与 16fps）。[B]

---

## 7. 程序化生成 AMV 的兼容性陷阱（按重要性排序）

1. **不要写索引 `idx1`**：真 AMV 没有索引（源码注释 `There is no index.`），我实测也没有。加索引反而偏离格式。[A]
2. **RIFF/LIST 的长度字段要写 0**：源码注释 `The sizes of certain tags are deliberately set to 0 as some players break when they're set correctly.` `amv_start_tag()` 特意写 0 且**事后不回填**。我实测：RIFF size=0、所有 LIST size=0，而 `strh`/`strf`/`amvh` 的长度是**正确**的。[A]
3. **叶子块不做 2 字节对齐**：实测 56 个包里有 **26 个**的结束偏移是奇数。2008 年的 C 复用器注释同样写着 `//Data in AMV files are not aligned by 2 bytes`。**不要加填充字节**。[A][B]
4. **严格 V‑A 交替，1:1**：源码注释 `Frames must be strictly interleaved as V-A, any V-V or A-A will cause crashes.`；我的实测序列即 `00dc,01wb,00dc,01wb,...`，且视频帧数 = 音频块数（28/28）。另外：`Variable audio frame sizes cause crashes.`（音频每块大小必须恒定）；视频每块大小**可以**变化（实测 2788~3063）。音频比视频短则补静音；视频比音频短则重复最后一帧。[A]
5. **高度必须是 16 的倍数**（否则报错，需 `-strict -1`）：
   ```
   [amv @ ...] Heights which are not a multiple of 16 might fail with some decoders,
   use vstrict=-1 / -strict -1 to use 120 anyway.
   ```
   实测：`160x120` 不加 `-strict -1` → `Conversion failed!`（退出码异常，且留下 4376 字节的残file）；加了就成功。**注意这条来自视频编码器 `mjpegenc.c`（不是复用器），所以 grep `amvenc.c` 找不到。**`128x96`、`176x144`（96/144 都是 16 的倍数）无需 `-strict`。[A]
6. **帧率必须让 `22050 % aframe_size == 0`**（见 §3 表）；`-block_size` 必须等于该帧率对应的每帧样本数，否则报 `Try -block_size N`。[A]
7. **音频只能 22050 Hz + 单声道**：44100 直接报 `Sample rate must be 22050`；双声道报 `Only mono is supported`。[A]
8. **视频帧必须上下翻转后再编码**（bottom-up），否则在播放器上图像上下颠倒。[A]
9. **视频帧必须剥离 JPEG 表**（只留 `FF D8` + 熵数据 + `FF D9`）：文件里不写 DQT/DHT/SOF0/SOS。不过实测 ffmpeg 的 AMV 解码器对"完整 JPEG"也容忍。[A]
10. **必须用文件末尾的 `AMV_` `END_` 8 字节尾标收尾**；没有它就不是"设备合规"的 AMV（OxideAV 的严格模式把它当终止哨兵）。FFmpeg 自己的解码只靠 `movi` 块的块遍历，所以对 ffmpeg 不是硬需求。[A][B]
11. **没有 RTSP/流式支持**：`amvenc.c` 要求输出可 seek（`Stream not seekable, unable to write output file`），因为时长要在收尾时回填到 `amvh+52`。[A]
12. **"首帧必须是关键帧"这个问题不存在**：AMV 强制 `intra_only = 1`，每帧都是独立 intra JPEG。[A]
13. **只能有 2 条流**（`AMV files only support 2 streams`），且第 1 条必须是 amv 视频、第 2 条必须是 adpcm_ima_amv 音频。用户实测含有额外流的 mp4 会直接失败（https://superuser.com/questions/1740001/ ）。[A][B]
14. **时长字段是 `ss, mm, uint16 hh` 三字节打包**，不是线性秒数；OxideAV 还发现真实设备在写该字段时**会少写 1 秒**（3:02 vs 推导的 3:03），所以别把它当权威时长。[A][B]
15. 关于"是否需要 `movi` 填充"：**不需要，而且不应该**（见第 3 条）。FFmpeg 生成的 `movi` 长度字段是 0，包紧挨着排。

---

## 8. 其它 AMV 写入器/解码器实现（字节布局对照）

| 实现 | 性质 | 关键字节事实 |
|---|---|---|
| **FFmpeg**（4.4+，2020，Zane van Iperen） | 复用器 `libavformat/amvenc.c` + 编码器 `amv`/`adpcm_ima_amv` | 见本文全部实测；strh/strf 全零；`AMV_END_`；无填充；严格 V-A |
| **amv-codec-tools**（2007-2008，Google Code 遗物，现镜像于 https://github.com/tomvanbraeckel/amv-codec-tools ） | 自带补丁版 ffmpeg（`AMVmuxer/ffmpeg/libavformat/amvenc.c`）+ C 解码器（`C-AMVDecoder/amvlib/AMVDec.c`、`AMVHeader.h`） | 同样 `LIST/hdrl` → `amvh`(56) → `LIST/strl`→`strh`/`strf` → `LIST/movi`；帧标签 `<nn>dc`/`<nn>wb`；尾部 `AMV_END_`（注释 `// Added by Tom from AMV compatibility`）；注释 `//Data in AMV files are not aligned by 2 bytes`；`compare_amv.c` 硬编码 `movi` 在 0x138；其头文件把各 strh/strf 体声明为保留零字节，**没有 AMVV/AMVA** [B] |
| **OxideAV/oxideav-amv**（纯 Rust，含 demuxer+muxer+编解码） | 反向工程真实设备文件 | 与上述完全一致：RIFF 类型 `AMV `、长度全零、无填充、无 idx1、`AMV_END_` 尾标、`amvh` 存分辨率/fps/打包时长、视频是"表剥离 JPEG"、音频 8 字节块头 + nibble、1:1 配对 [B] |
| FFmpeg `sp5x`（Sunplus SP5X，2003） | **不是 AMV**，但 AMV 复用了它的固定表和"重建 JPEG 头"思路 | `sp5xdec.c` 里的 `sp5x_data_sof/sos/dqt/dht` + `sp5x_qscale_five_quant_table` [A] |

**相邻但不同的实现（易被误引）**：`atj-avi-encoder`（2025，https://github.com/tytydraco/atj-avi-encoder ）是给 ATJ/Actions 芯片播放器写视频的**原生编码器**，但它写的是 **ATJ 专用 AVI**（`atj_avi_mux.py`、`atj_avi_hdrl.bin`、128×128 MJPEG + IMA ADPCM @25fps、mono 22050Hz、**512 字节音频块**、**带 idx1 索引**）——**不是 .amv 容器**，只有码流档位重合。其 README 说明原厂 "AMV Converter" 与 `AVI_EncDLL.dll` 只被当作参照、未随项目发布，这也解释了为何厂商工具与 "MTV Video Converter" 等**找不到源码**。[B]

**写入器数量结论**：已知的 .amv **写入器只有三个**（2008 年 amv-codec-tools 的补丁 ffmpeg、FFmpeg 4.4+、OxideAV Rust），三者在容器层**逐字节一致**。**VLC 没有 amv 复用器**（其 `modules/mux/` 只有 asf/avi/mpjpeg/ogg/wav/mp4/mpeg 等，经目录列表确认）；MPlayer/GStreamer/Kodi/mpv/Qt 亦未发现 .amv 写入能力；Gitee 上唯一的 AMV 命中是 FFmpeg 镜像，**没有独立的中国 AMV 写入器**；PyPI/npm/RubyGems/crates.io/Go/Java/C# 也未发现写 AMV 容器的包（属"未找到证据"，非"证明不存在"）。[B]

---

## 9. 我做的可复现实验（工作区留档）

工作目录：`docs/research/`

| 文件 | 用途 |
|---|---|
| `analyze.js` | AMV 字节结构解析器（RIFF 块遍历 / amvh 字段 / strh-strf / 包序列 / 帧标记普查 / 尾标 / fourCC 检索） |
| `frame.js` | 单帧 JPEG 标记普查 + 段解析（DQT/DHT/SOF/SOS/APPn） |
| `flip_test.js` | 用 `sp5x.h` 原始表重建 JPEG 头，并做上下翻转比对 |
| `diag.js` / `diag2.js` / `compare.js` / `mad.js` | 像素级/行级比对工具 |
| `fps_table.js` | 复算 FFmpeg 的 `aframe_size`/`block_align` 与可用帧率表 |
| `build_fulljpeg_amv.js` | 构造"载荷为完整 JPEG"的 AMV，测试解码器容忍度 |
| `tC.amv`(14fps/160x120)、`tD.amv`(25fps/128x96)、`tG.amv`(10fps)、`q*.amv`(MD5 相同) | 我生成的真实样本 |
| `synth.jpg`/`synth2.yuv`/`amv2.yuv`/`src.yuv` | 位精确翻转验证 |
| `test_fulljpeg.amv` | 完整 JPEG 载荷容忍性实验 |

复现要点：本机 ffmpeg 位于 `.tools/ffmpeg\ffmpeg-master-latest-win64-gpl\bin\`，FFmpeg 源码位于 `.tools/src\FFmpeg-n7.1\`。

---

## 10. 引用

**一手源码（master / n7.1，我已逐行核对）**
- AMV 复用器：https://raw.githubusercontent.com/FFmpeg/FFmpeg/master/libavformat/amvenc.c （Doxygen 镜像：https://patches.ffmpeg.org/doxygen/trunk/amvenc_8c_source.html ）
- AMV 视频编码器：https://raw.githubusercontent.com/FFmpeg/FFmpeg/master/libavcodec/mjpegenc.c
- **只写 SOI 的 AMV hack**：https://raw.githubusercontent.com/FFmpeg/FFmpeg/master/libavcodec/mjpegenc_common.c
- AMV 视频解码器（重建头 + 翻转）：https://raw.githubusercontent.com/FFmpeg/FFmpeg/master/libavcodec/sp5xdec.c
- 固定表定义：https://raw.githubusercontent.com/FFmpeg/FFmpeg/master/libavcodec/sp5x.h
- 量化表强制 / DC 初值：https://raw.githubusercontent.com/FFmpeg/FFmpeg/master/libavcodec/mpegvideo_enc.c
- ADPCM 解码块布局：https://raw.githubusercontent.com/FFmpeg/FFmpeg/master/libavcodec/adpcm.c
- ADPCM 编码块布局：https://raw.githubusercontent.com/FFmpeg/FFmpeg/master/libavcodec/adpcmenc.c
- `amvh` 识别与 strf 跳过：https://raw.githubusercontent.com/FFmpeg/FFmpeg/master/libavformat/avidec.c
- `AMVF` fourCC 注册：https://raw.githubusercontent.com/FFmpeg/FFmpeg/master/libavformat/riff.c
- `.mtv` 格式（另一套）：https://raw.githubusercontent.com/FFmpeg/FFmpeg/master/libavformat/mtv.c
- 官方文档（唯一的官方描述，一句话）：https://ffmpeg.org/ffmpeg-formats.html#amv （"AMV (Actions Media Video) format muxer."）
- 引入 AMV 复用/编码的补丁系列：https://lists.ffmpeg.org/pipermail/ffmpeg-devel/2020-November/271827.html

**第三方实现**
- OxideAV 纯 Rust AMV 容器+编解码：https://github.com/OxideAV/oxideav-amv
- amv-codec-tools（2007/2008）：https://github.com/tomvanbraeckel/amv-codec-tools

**社区（参数、报错、播放器）**
- https://superuser.com/questions/1649146/convert-mpeg4-to-amv-via-ffmpeg （真实设备文件 ffprobe 输出、成功参数）
- https://superuser.com/a/1649185 （在播放器上验证通过）
- https://superuser.com/questions/1852829/ （208×176）
- https://superuser.com/questions/1809283/ （296×256→320×256）
- https://superuser.com/questions/1426454/ （ffmpeg<4.4 报 `Unable to find a suitable output format for 'output.amv'`）
- https://superuser.com/questions/1740001/ （`AMV files only support 2 streams`）
- https://trac.ffmpeg.org/ticket/747 （2011 年同样报不支持 amv 输出）
- https://mailman.videolan.org/pipermail/vlc/2007-December/015243.html （VLC 2007 年不支持；`-r 12` 在 S1 播放器上不播）
- http://m.pcgeshi.com/faq/daorump3mp4.html （格式工厂推荐分辨率/帧率）
- https://xiazai.zol.com.cn/baike/561645.shtml （AMV/MTV 并列、1 分钟约 1.8MB）
- https://blog.csdn.net/stitches_fly/article/details/139372064 （中文实践：分辨率低、需格式化存储卡）
- https://github.com/fdd4s/portable_mp3_player_video_converter_tools （真实玩家脚本，`-r 14 -block_size 1575`）

---

## 10b. 补充：社区证据（他源，已标注可靠度）与文献陷阱

### 分辨率"天花板"的候选说法
- `"With its low compression, low frame rate (12-16 fps), low resolution (up to 208×176), and poor video quality, the AMV format specifically targets low-end devices… such files use a modified AVI container and the Motion JPEG (MJPEG) video codec in combination with a version of the IMA ADPCM audio codec."` —— https://www.filetypeadvisor.com/extension/amv （Tier B，商业文件查看器厂商的自动生成数据库，**不是**规范）。**"208×176 上限"**与某用户实测 `scale=w=208:h=176` "And it works."（https://superuser.com/questions/1852829/ ）方向一致，是目前支持度最好的"上限"说法，但仍非硬规范。
- 冲突说法（须并列标注）：AnyMP4 称 "Standard Resolution: **160×120 or 220×176** (Higher may cause Format Error)"（https://www.anymp4.com/video-converter/mp4-to-amv.html ，Tier C）；格式工厂默认 320×240 / 160×128（http://m.pcgeshi.com/faq/daorump3mp4.html ）。
- **最大文件大小/时长：仍然没有数字**。唯一提到症状的说法是"**Memory Full** 错误——设备内部存储满了，或**视频文件对播放器来说太大**"（同上 AnyMP4，Tier C），指向存储容量而非格式限制。中文实践也提到放太大/太高的文件设备显示"格式错误"（https://blog.csdn.net/stitches_fly/article/details/139372064 ）。

### 桌面播放器
- 可引用的两句（均为商业/二手来源，Tier C）：`"VLC can play some AMV files, but support is not guaranteed… AMV uses proprietary codecs designed for specific devices."`（AnyMP4）；`"The AMV format has been reverse-engineered and its support added to ffmpeg, so all ffmpeg-based media players are capable of opening .amv files for playback."`（filetypeadvisor，Tier B）。
- **仍然没有任何一手报告**证明 VLC / mpv / MPC-HC / WMP / MPlayer 实际播放过 .amv，也**没有任何"有画面没声音"的报告**。请按"未验证"处理。
- Windows 上存在 DirectShow 路线：CSDN 资源包内含 `amv.ax`（116KB）滤镜，另有 `AMVFairy\AMV播放.exe`（https://download.csdn.net/download/u012496571/6421285 ，未验证）——说明装了第三方滤镜后 WMP 有可能播，但未经证实。

### 文献陷阱（务必不要引用）
1. **https://wenku.csdn.net/doc/6td256ft8n**（CSDN 文库"AMV视频格式转换器"，2025 年更新）看似权威，实则与全部一手证据冲突：它称 AMV 是"仅含 I 帧和 P 帧的 **MPEG-1 风格视频编码**"、要求把 RIFF 标识替换为 **"AMV\0"**、视频 fourCC 设 **"MPG1"**、音频设 **"ADPM"**、数据块对齐到 **2048 字节边界**。**全部为假**：真实表单类型是带尾随空格的 `"AMV "`，视频是表剥离的**基线 MJPEG**，唯一有据的 fourCC 是 `AMVF`，帧标签是 `00dc`/`01wb`，且**不做任何对齐填充**。疑似 AI 生成的营销内容。
2. **中文世界没有任何字节级资料**：系统检索"AMV 文件头 结构 分析 / AMV格式 详解 / AMV 十六进制 / AMVV AMVA / amvh / AMV_END_ / 炬力 AMV 格式 / AMV 封装 格式 解析 / 逆向"等关键词，**没有找到任何十六进制转储、文件头字段表或 `amvh`/`AMV_END_`/`00dc`/`01wb`/`AMVF` 的目击记录**——中文资料全部停留在 GUI 教程层面。字节级知识只在英文（FFmpeg 源码、OxideAV）里。（这是"空结果"，不要靠中文来源补格式细节。）
3. **同名混淆**：`.amv` 还是**无关的 KiriKiri 视觉小说视频格式**的扩展名（https://github.com/xmoezzz/amv_decoder 、https://docs.rs/amv_decoder/ ）；同时商业站点常把 "AMV = anime music video" 与容器格式写在同一篇文章里。三者不要混。
4. **`.mtv` 的文献矛盾**：Wikipedia/DBpedia 的 "AMV video format" 信息框把扩展名写成 **`.amv, .mtv`**，`extendedFrom` = "AVI and Motion JPEG"（https://dbpedia.org/page/AMV_video_format ），即把二者当成同一格式；而 FFmpeg 的 `libavformat/mtv.c` 明确是**另一套独立容器**（魔数 `AMV`、512 字节头、MP3 音频、RGB565）。此矛盾未解决，报告时请并列。

### 工具可获得性（2025-2026）
- **AMV格式转换器**：ZOL 页面 2023-06-28 更新、1.35MB，仍可下载（https://xiazai.zol.com.cn/baike/561645.shtml ）；**AMV转换精灵**：ZOL 2022-03-14 更新（https://xiazai.zol.com.cn/detail/11/108312.shtml ）；**格式工厂**：仍在维护，内置 AMV 目标（http://m.pcgeshi.com/faq/daorump3mp4.html ）。
- 已死/停止维护：amv-codec-tools（Google Code 已关）、AMVplayer、超级解霸+Total Video Converter 路线。
- **找不到** "MTV Video Converter / 视频转换大师 / MP4/RM转换专家" 支持 AMV 的任何证据，不要断言。

### 未能取得的资料（gap）
- amv-codec-tools 当年的 **AmvDocumentation wiki**（历史上最常被引用的"AMV 文档"）随 Google Code 下线，且本次会话无法访问 web.archive.org，**未能取得**。若能访问 Wayback Machine，值得再试（线索来自 https://trac.ffmpeg.org/ticket/747 ）。
- 中文 MP3 播放器论坛（imp3、数码之家等）以图片贴为主，本次工具难以覆盖。

---

## 11. 未决 / 存疑清单（请勿在引用时当成事实）

1. **设备真实量化表**：FFmpeg 用 Annex K×0.8（SP5X），OxideAV 称真实设备用未缩放的 Annex K。二者相差 1.25 倍，我无法裁决（无真实设备样本）。
2. **ADPCM nibble 顺序 —— 已基本裁决为"高位先"**：FFmpeg（`adpcm.c`）与现代 2008 年设备解码器（`AdpcmIma.c`，含中文注释"先高4bit后低4bit"）一致为**高 nibble 先**；OxideAV README 的 "low-nibble-first" 是孤例。**注意一个坑**：2008 年那个项目的 **编码器** 与之相反（把第一个样本放进低 nibble），即它的编码器与解码器互相矛盾（该工程 README 自嘲 "fix those bugs !"），所以不要照抄那个编码器。
3. **块头 +2/+3 字段语义**：FFmpeg 视为 uint8 step index + 后 5 字节跳过；OxideAV 视为单字节 step index + "设备常量"（comedian=0x00 / noel=0xAA），且**解码时忽略该 step index、每块重置为 0**（他们报告沿用传输值会让削波率涨 27 倍）；而 2008 年 C 解码器**使用**传输的 step index（`audio.status[0].step_index = fbuff->audiobuff[2]`）。FFmpeg 与 2008 解码器一致 → 倾向于"应当使用传输值"。+3 字节为何有两种取值（尤其恒为 0xAA）仍未解释。
4. **VLC 对 .amv 的支持状态 —— 已查明为"有失败史，现行版本未测"**：2007-02-16 VLC 开发者 dionoea 在官方论坛直言 `"Well, no, you can't with VLC."`（https://forum.videolan.org/viewtopic.php?f=2&t=32405 ）；2011-06-02 有用户报告 `"It crashes VLC 1.1.9, but works fine in FFplay/FFMPEG SVN-r26292"`（https://forum.videolan.org/viewtopic.php?f=14&t=90919 ），随后建立官方工单 **VideoLAN #4870 "Crash with AMV file"**，崩溃栈在 `ff_mjpeg_decode_sof()`/`ff_mjpeg_decode_frame()`（正是 AMV 视频所用的 MJPEG 路径），该工单被 Rémi Denis-Courmont 以 `Component::Decoders` + `Status::works for me` **关闭**（https://code.videolan.org/videolan/vlc/-/work_items/4870 ）；2020-05-25 有长期用户发帖 `"if VLC supports AMV, I can't find it."` 且**零回复**（https://forum.videolan.org/viewtopic.php?f=2&t=153664 ）；2023-02-17 Rémi 在回复中泛泛表示列表里的格式（含 amv）"已支持"（https://forum.videolan.org/viewtopic.php?f=7&t=161162 ）。**结论：VLC 侧是"有明确失败/崩溃史 + 开发者 2007 年否认 + 2020 年未解决的用户报告"对上"2023 年泛泛的已支持暗示"，但从来没有人贴过 VLC 3.0.x 播 .amv 的实测。请写"有失败史、现行版本未验证"。** 另外**仍然零"有画面无声音"报告**。[B]
   - 注：MPC-HC 侧有配置层证据——用户贴出的 `mpc-hc64.ini` 里含 `TRA_AMVV=1`（与 `TRA_MJPEG=1`、`TRA_H264=1` 并列），且安装程序会写入/删除 `HKCR\mplayerc64.amv`（https://github.com/clsid2/mpc-hc/issues/3801 ）。**`TRA_AMVV` 是播放器内部滤镜标识，不是文件里的 fourCC**（见 §1.3/§1.3b）；且**没有人贴过 MPC-HC 实际播放 .amv 的测试**。
5. **单文件大小/时长/分辨率上限**：无公开可靠数据。
6. **`AMVV`/`AMVA`**：所有一手证据都是**否定**的（详见 §1.3b —— `AMVV` 是 2007-09-24 为 MPlayer 分流临时发明、两天后提交前被作者自己删除的私有 codec_tag，从未写入任何文件；`AMVA` 零命中）。唯一有依据的 fourCC 是 `AMVF`。
7. **关于 `Heights which are not a multiple of 16` 与 `Audio sample rate not a multiple of the frame size` 这两条 ffmpeg 报错字符串**：协助检索的另一个调查线程在 ffmpeg 源码与社区中"找不到"它们，并建议当作不存在——**那是错的**。这两条都由我本人**直接实测复现**（见 §3、§5.3 的原始 stderr），源码位置分别是：
   - `Heights which are not a multiple of 16 …` → `libavcodec/mjpegenc.c` 的 `amv_encode_picture()`（**在视频编码器里，不在 `amvenc.c` 复用器里**，所以只 grep 复用器会漏掉）；
   - `Audio sample rate not a multiple of the frame size…` → `libavformat/amvenc.c` 的 `amv_init()`。
   二者是 `A` 级证据。**注意后者的"Suggested rates: 10,14,15,18,21,25,30"并不包含 12 与 16**，而这正是当代 ffmpeg 拒绝经典 `-r 16` 配方的原因。
   （补充：另一条独立调查线也在本地 ffmpeg 上跑出**完全相同**的 12fps 拒绝信息，属双重独立复现。）
8. **视频 `strf` 是否全零——原作者说法与现代实现不一致（低影响，但请如实标注）**：Voroshilov 2007 年的描述称 `strf sections contains movie duration, image size and frame duration for video`（即视频 strf 里**有**宽高与帧时长）；但三个写入器（2008/2020/2025）写的都是 **36 字节全零**，OxideAV 称之为 "all-zero stream-header bodies"，中文设备结构体声明为 `reserved[36]`，两份真实设备语料（`comedian.amv`、`noel-son-lumiere.amv`）亦为零，FFmpeg 的解复用器更是**整个跳过视频 strf**。最可能的解释是他把 `amvh` 的字段描述混进了 strf。**实现时按"全零"处理**，同时记录该矛盾。
9. **仍未取得的资料**：MultimediaWiki 的 AMV 页、Google Code `AmvDocumentation` wiki（原版格式文档）、2007 年补丁的 diff 附件、Perl 版 `amv.pl`、oxideav 的 trace 文档与 `parse.rs`、MPlayer/VLC/GStreamer/Kodi/mpv 读路径细节。**中文（CSDN/贴吧/论坛）字节级资料仍完全空白**（检索全部零命中 hex dump / 文件头字段表）。

---

## 12. 二次修订与补遗（在初次交付后新增，均为对已交付内容的修正/加固）

### 12.1 "高度非 16 倍数"是一条**双向**的、有真实 bug 记录的失败模式（并解释了 `-vstrict -1`）
- 编码侧：该警告字符串确实存在（我本人实测复现，见 §7.5），源码在 `libavcodec/mjpegenc.c` 的 `amv_encode_picture()`；该字符串 2015-08 引入（ffmpeg-devel 讨论：https://www.mail-archive.com/ffmpeg-devel@ffmpeg.org/msg17914.html 、https://www.mail-archive.com/ffmpeg-devel@ffmpeg.org/msg17905.html ），并且它**明确邀请用户把结果回报给 ffmpeg-devel**。
- **解码侧更严重**：FFmpeg trac **#4770 "Non-modulo 16 height of AMV file"**（https://trac.ffmpeg.org/ticket/4770 ）记录了 2015 年 FFmpeg **拒绝解码一个真实设备文件**：`[amv @ ...] non mod 16 height AMV` / `is not implemented`，2015-12-01 由 `fa9af304f0a167f187a26cc9a0a0ba6a4db08cbe` 修复。
- **因此**："高度必须 16 的倍数"不是 FFmpeg 的任性约束，而是**设备侧真实存在的坑**；同时这也**解释了社区命令里那个看似莫名其妙的 `-vstrict -1` / `-strict -1`** —— 就是这条警告教用户加的。**不要再把它称作 cargo cult。**
- 实务建议：**优先选高度为 16 倍数的分辨率**（96、128、144、176、64、160×128 等）；`160×120`、`94×64`（64 可以，94 不行）、`96×64`（可以）需注意：**120 与 94 都不是 16 的倍数**，用 `-strict -1` 编出来后**在部分设备上可能播不了**——这是"能编码"≠"能播"的典型。

### 12.2 `AMVV` 的第二种"野生"出现形态（务必区分）
除 §1.3b 的 2007 年私有 codec_tag 之外，`AMVV` 在野外的另一处出现是 **MPC-HC 的配置文件**：用户贴出的 `mpc-hc64.ini` 中 `[Internal Filters]` 下有 `TRA_AMVV=1`（与 `TRA_MJPEG=1`、`TRA_H264=1` 并列），且 MPC-HC 安装程序会登记/删除 `HKCR\mplayerc64.amv`（https://github.com/clsid2/mpc-hc/issues/3801 ）。**`TRA_AMVV` 是播放器内部滤镜开关的名字，不是写在文件里的 fourCC。** 正确表述应是：*"`AMVV` 会作为 MPC-HC 的内部滤镜标识出现；而容器里的 fourCC 是 `AMVF`。"* 仍然：**没有任何 `AMVA`，也没有任何以 fourCC 形式存在的 `AMVV`。**

### 12.3 FFmpeg 版本史（把"ffmpeg 不支持 AMV"这句话说准）
FFmpeg 官方 Changelog（https://raw.githubusercontent.com/FFmpeg/FFmpeg/n4.4/Changelog ）：**version 0.5** 起就有 `AMV audio and video decoder`；**4.4** 才加入 `ADPCM IMA AMV encoder` 与 `AMV muxer`。
→ **AMV 的"读"从 0.5 就有，"写"才是 4.4 才有。** 旧帖里"ffmpeg 不支持 AMV"只对**编码**成立（例如 trac #747 的用户只能把 .avi 改名成 .amv）。[B]

### 12.4 `-block_size` 的语义与一个常见错误表述
- `-block_size` 是**音频每帧样本数**（`aframe_size`），**不是** `nBlockAlign`。`amvenc.c`：`aframe_size = sample_rate / fps`，`ablock_align = 8 + FFALIGN(aframe_size,2)/2`。25fps 时 frame size 882 → block align 449。社区里出现过的所有值（735、882、1225、1378、1470、1575、2205）都 ≈ **22050/fps**。[A]
- ⚠️ **不要照抄 superuser 上被采纳答案那句话**：它写成 `"Frame rate must be divisible by the audio sample rate (22050)"`——**这是反的**（22050 不可能被帧率整除）。真正的约束按源码是 **`sample_rate % aframe_size == 0`，即 `22050 / fps` 必须是整数**（等价于 fps ∈ {10,14,15,18,21,25,30,...}）。引用"规则"，不要引用那句话。[A][B]

### 12.5 失败模式清单（**没有任何"最大文件大小/时长"数据**，请勿编造）
双重检索仍然**没有**：最大文件大小、2GB/FAT 上限、"文件太大"报告、最大时长、以及任何桌面播放器"有画面无声音"的报告。真实设备文件在 ffprobe 里就是 `Duration: N/A`（时长字段不可用），所以"时长上限"在该格式里甚至无法表达。**实际观察到的失败模式**是：VLC 崩溃（2011）、设备端显示"格式错误"、卡顿/音画不同步、**解码拒绝（非 16 倍数高度，2015）**、画面花屏、以及 2007 年那个 alpha 编码器的音频断续。[B]
仍未证实支持的播放器：MPlayer（2007 补丁标题存在但正文被 Anubis 拦截，**不可断言**）、mpv、Kodi、GOM；PotPlayer/KMPlayer/暴风影音只有厂商/聚合页的**声称**且无测试证据（其中部分页面还把 AMV 误认成 anime music video）。[C]
