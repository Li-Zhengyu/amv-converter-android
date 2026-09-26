# AMV 格式规范（Actions Media Video）— 逆向实测版

本文档由**实测 + FFmpeg 源码交叉验证**得出，不是网络传闻。所有结论都有可复现的验证步骤。
验证环境：FFmpeg `N-126497-g5b614efc7e`（Windows x64 gpl），源码 `FFmpeg-n7.1`。

参考文件：`.tools/lab/ref_v15.amv`（160x120, 15fps, 22050Hz 单声道，154788 字节，45 帧）。

---

## 1. 总体结论

AMV = **一个被硬编码阉割过的 AVI 子集**：
- 容器：RIFF，`form type = "AMV "`（注意带空格），不是 `"AVI "`。
- 视频：每帧 = **只有 SOI/EOI 的裸 JPEG 熵编码数据**，所有 JPEG 头段（DQT/DHT/SOF/SOS）全部被删除。
- 音频：IMA ADPCM，**固定 22050 Hz、单声道**。
- **没有 idx1 索引**，且大量字段被**故意写成 0**（写对反而会让部分播放器崩溃）。

FFmpeg 源码中的原话（`libavformat/amvenc.c` 注释）：
> AMV is a hard-coded (and broken) subset of AVI. It's not worth sullying the existing AVI muxer with its filth.
> The sizes of certain tags are deliberately set to 0 as some players break when they're set correctly.
> Players are **very** sensitive to the frame order and sizes.
> - Frames must be strictly interleaved as V-A, any V-V or A-A will cause crashes.
> - Variable audio frame sizes cause crashes.

---

## 2. 文件结构（逐字节）

```
偏移   内容
0      "RIFF"
4      u32 = 0            ← 故意为 0
8      "AMV "             ← 4 字节，含尾部空格
12     "LIST"  u32=0  "hdrl"          ← LIST 大小故意为 0
24     "amvh"  u32=56
32     56 字节 amvh 负载：
         +0  u32 us_per_frame        (1000000/fps，如 15fps -> 66667)
         +4  ..+31  全 0
         +32 u32 width               ← 分辨率只在这里！
         +36 u32 height
         +40 u32 fps_den             (= fps，如 15)
         +44 u32 fps_num             (= 1)
         +48 u32 0
         +52 u32 时长字段（结束时回填）: byte ss, byte mm, u16 hh
88     "LIST"  u32=0  "strl"
100    "strh"  u32=56   + 56 字节全 0      ← 视频流头，故意全 0
164    "strf"  u32=36   + 36 字节全 0      ← 视频流格式，故意全 0
208    "LIST"  u32=0  "strl"
220    "strh"  u32=48   + 48 字节全 0
276    "strf"  u32=20   + WAVEFORMATEX（"假"的）:
          u16 wFormatTag = 1 (PCM! 谎报)
          u16 channels   = 1
          u32 sample_rate= 22050
          u32 avg_bytes  = 44100
          u16 block_align= 2    (也是假的)
          u16 bits       = 16
          u16 cbSize     = 0
          u16 pad        = 0
        → 实际 304
304    "LIST"  u32=0  "movi"
316    数据区：严格 V-A 交替
          "00dc" u32 size + JPEG 裸数据
          "01wb" u32 size + ADPCM 块
        ★ chunk 之间【没有】偶数对齐补位（奇数字节直接紧接下一个 chunk）
末尾   若 movi 内容长度为奇数则补 1 字节 0
       "AMV_"  "END_"        ← 文件最后 8 字节
```

**关键实测**：视频 chunk 大小可以是奇数（45 帧里 24 帧是奇数），后面**没有** pad 字节。
按标准 AVI 规则补位会导致解析错位。

时长字段实测：3 秒文件 → `ss=3, mm=0, hh=0`。

---

## 3. 视频帧格式

每帧：
```
FF D8                       ← SOI
<熵编码数据>                 ← 直接就是 JPEG scan data，中间全是 FF 00 字节填充
FF D9                       ← EOI
```

**解码端契约**（`libavcodec/sp5xdec.c`，AMV 与 SP5X 共用 `sp5x_decode_frame`）：

解码器拿到 AMV 包后，**丢掉前 2 字节和后 2 字节**，只取中间的裸熵数据，然后**自己拼一个完整 JPEG**：

```c
/* SOI */
recoded[j++]=0xFF; recoded[j++]=0xD8;
memcpy(... sp5x_data_dqt ...);        /* 固定量化表 */
memcpy(... sp5x_qscale_five_quant_table[0/1] ...);  /* 固定 Q60 量化值 */
memcpy(... sp5x_data_dht ...);        /* 固定 Huffman 表 */
memcpy(... sp5x_data_sof ...);        /* 分辨率来自容器（coded_width/height） */
memcpy(... sp5x_data_sos ...);
/* AMV: 原样拷贝裸熵数据（不做 FF 转义处理） */
recoded[j++] = 0xFF; recoded[j++] = 0xD9;
```

### 3.1 因此编码端必须匹配的固定表

| 项目 | 值 | 来源 |
|---|---|---|
| 量化表 0 (Y) | `sp5x_qscale_five_quant_table[0]`，注释 "index 5, Q60" | `sp5x.h:138` |
| 量化表 1 (Cb/Cr) | `sp5x_qscale_five_quant_table[1]` | `sp5x.h:142` |
| Huffman | **标准 Annex K**（DC/AC 亮度+色度，4 张表，162+12 项） | `sp5x.h:77` |
| 采样 | Y: 1x2x2 (4:2:0)，Cb: 1x1x1，Cr: 1x1x1 | `sp5x.h` SOF |
| 分量 ID | 1=Y, 2=Cb, 3=Cr | `sp5x.h` SOF/SOS |
| 扫描 | Ss=0, Se=0x3F, Ah/Al=0；Y→表0/0，Cb,Cr→表1/1 | `sp5x.h:40` |

**重要推论：AMV 的量化表是写死在解码器里的，所以画质不可调。**
编码端唯一能改的是"用更粗的步长量化"（解码端仍按 Q60 反量化），这会降低画质并减小体积 —— 作为高级选项提供。

### 3.2 图像必须垂直翻转存储

- 编码端 `mjpegenc.c:612`：`//picture should be flipped upside-down` 然后逐行反向。
- 解码端 `mjpegdec.c:190`：`if (avctx->codec->id == AV_CODEC_ID_AMV) s->flipped = 1;`

即 **AMV 帧是"倒着存"的**（沿用 AVI/DIB 自下而上的传统），解码端翻回来。
若不做翻转，在真实播放器上画面会上下颠倒。

### 3.3 熵编码规则

- 帧内 DC 预测器初值为 0，每帧重置（每帧是独立 JPEG 扫描）。
- **无重启标记**（没有 DRI 段，不能出现 RSTn）。
- 输出字节中 `0xFF` 必须后跟 `0x00` 转义（实测：第一帧 60 个 FF，全部 `FF 00`）。
- 末尾不足 8 bit 用 **1** 填充。
- 分辨率不是 16 倍数时，按**边缘复制**补齐到 MCU 边界（160x120 → 160x128 编码，解码端裁掉多余行）。

### 3.4 补齐行必须放在存储图的**末尾**（实测踩坑）

这是整个实现里最容易错、且只靠肉眼看很难发现的一条。设显示高度 h=120、补齐到 128：

- 帧是倒着存的：存储行 `y` 对应显示行 `h-1-y`
- 解码端解出 128 行后**只保留前 120 行**（存储顺序）再翻转

因此正确做法是：

```
存储行 0..119  = 显示行 119..0        （翻转，填充区在外）
存储行 120..127 = 复制显示行 0         （补在末尾 → 解码时被裁掉）
```

若反过来把补齐放在开头（存储行 0..7 = 复制），解码端裁掉末尾 8 行后，
整个画面会**下移 8 行**（对 120 行画面来说偏移 6.7%，在 160x120 的小画面上很容易被忽略）。

**实测定位方法**：拿垂直渐变源转 AMV，解码后与理想缩放结果做剖面比对，
错误实现会呈现 `mine(y) == ideal(y+8)` 的逐行吻合 —— 一眼就能确认偏移量。
修正后剖面与理想值**逐像素差 ≤1**。

同类问题还能用"翻转自检"发现：与正确方向比 PSNR 45.0 dB，与翻转方向比仅 6.2 dB；
但只要测试图案上下近似对称（如 testsrc2），这个判据会失效（17.4 vs 16.1 dB），
所以**必须用垂直强不对称的图案**（渐变最合适）。

---

## 4. 音频格式

块大小（block_align）与视频帧强绑定：

```
samples_per_frame = 22050 / fps          ← 必须整除（ffmpeg 强制要求）
block_align       = 8 + ceil(samples_per_frame / 2)
```
15fps → samples=1470 → block_align = 8+735 = **743**（实测完全一致）。
25fps → samples=882  → block_align = 8+441 = 449。

每个音频块（实测 743 字节）：
```
+0  i16  predictor      ← 本块第一个采样值（每块重设为该块首采样！）
+2  u8   step_index     ← 跨块延续
+3  u8   reserved = 0
+4  u32  frame_size     ← 本块采样数（1470）
+8  ...  4bit nibbles，每字节高 nibble 在前
```

**实测验证**：45 个块的 predictor = 503, 3522, -3538, -4, 3558, ...，
与解码后 PCM 在 `k*1470` 位置的采样值吻合（差 ≤ 82，属正常 ADPCM 误差）。
确认语义为「每块 predictor 重置为该块首采样，step_index 延续」。

IMA ADPCM 量化（`adpcmenc.c:231`）：
```c
delta  = sample - prev_sample;
nibble = min(7, |delta|*4 / step_table[step_index]) + (delta<0 ? 8 : 0);
prev_sample += (step_table[step_index] * yamaha_difflookup[nibble]) / 8;
prev_sample  = clip16(prev_sample);
step_index   = clip(step_index + index_table[nibble], 0, 88);
```
对应解码 `diff = ((2*(n&7)+1)*step) >> 3`，二者数学等价。

**音频不足时用静音块补齐**：predictor=0, step_index=0, reserved=0, frame_size=frame_size，nibble 全 0。

---

## 5. 生成命令（FFmpeg 官方写法，供对照验证）

```bash
# 15fps 必须配 -block_size 1470，否则复用器报 "Invalid audio frame size"
ffmpeg -i in.mp4 -vf scale=160:120 -r 15 -strict -1 \
       -c:v amv -c:a adpcm_ima_amv -ar 22050 -ac 1 -block_size 1470 out.amv
```

踩坑记录：
1. `-strict -1` 必需，否则 120 高度被拒（`Heights which are not a multiple of 16 ... use vstrict=-1`）。
2. `-block_size <22050/fps>` 必需，否则报 `Invalid audio frame size. Got 1024, wanted 1470`。
3. `-r 16` 等**不整除 22050** 的帧率会被拒：`Audio sample rate not a multiple of the frame size. Suggested rates: 10,14,15,18,21,25,30`。

本工具不依赖 FFmpeg，自行实现上述全部逻辑，因此可以：
- 支持任意帧率（用小数累加器分配每块采样数，保证音视频严格同步）；
- 支持 120/144 等非 16 倍数高度（边缘复制，无需 strict 开关）。

---

## 6. 分辨率的可用帧率（22050 的约数）

| fps | samples/frame | block_align | 适用 |
|---|---|---|---|
| 10 | 2205 | 1111 | 最小体积 |
| 14 | 1575 | 796 | |
| 15 | 1470 | 743 | **默认** |
| 18 | 1225 | 621 | |
| 21 | 1050 | 533 | |
| 25 | 882 | 449 | 最流畅 |

其它帧率（12/16/20/24/30）本工具用小数累加器支持，块内采样数会在相邻帧间 ±1，
块字节数经取整后通常仍相同。
