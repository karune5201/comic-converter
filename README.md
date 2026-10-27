# 漫画转换器（ComicConv）

把漫画压缩包和电子书转成 **PDF / EPUB / CBZ / MOBI / 图片文件夹** 的安卓应用，重点是不糟蹋画质。

- **真无损**：JPEG 按原始字节直接嵌入，PNG 搬运原始 IDAT 流，不解码、不重新压缩
- **格式覆盖全**：ZIP / CBZ / RAR / CBR / 7z / TAR / EPUB / PDF / MOBI / AZW3 / 文件夹
- **Material Design 3** 界面，跟着系统深浅色走；手机单列、平板双栏
- **支持 Android 5.0 起**（minSdk 21），不挑架构
- 批量转换、进度通知、随时取消

安装包见 [Releases](../../releases)。

## 用法

装上后直接点「扫描」就行，两个目录都是默认好的：

| | 路径 |
|---|---|
| 漫画目录（输入） | `/storage/emulated/0/漫画` |
| 输出目录 | `/storage/emulated/0/Comicconv` |

- 目录不存在会自动建出来（缺「所有文件访问」权限时建不了，界面会提示去开）。
- 首次使用需要给「所有文件访问」权限，否则读不到漫画文件夹。
- 两个目录都可以在界面上改成别的位置，改过之后会记住，下次直接沿用。

## 无损是怎么做到的

| 输入格式 | 处理方式 | 无损级别 |
|---|---|---|
| JPEG | 原始字节写进 PDF 的 `/DCTDecode` 流；EPUB/CBZ 里原样存放 | **字节级** |
| PNG | 原始 IDAT zlib 流 + `/Predictor 15`；调色板图走 `/Indexed` | **字节级** |
| JPEG 渐进式 | 解码后重新编码 | 像素级 |
| BMP / GIF / WebP 等 | 解码成原始样本 + `FlateDecode` | 像素级 |
| 带透明通道 | 拆出 `/SMask` | 像素级 |

**关于 PDF 里的 PNG**：PDF 的容器格式装不下 PNG 那层壳，所以是把 IDAT 压缩流原封不动搬进去再套上 PDF 的对象结构。像素数据一个 bit 都没动，但整个文件不是字节一致的 —— 这是格式本身的限制，不是转换过程有损。

**输出到 EPUB / CBZ / MOBI / 图片文件夹时是字节级完全一致**，可以拿原文件做 `cmp` 逐字节比对。

## 平板布局

界面按 MD3 断点自适应，判据是「宽度 ≥ 600dp **且** 高度 ≥ 480dp」：

| 场景 | 布局 |
|---|---|
| 手机竖屏 | 单列滚动 + 底部操作区 |
| 手机横屏 | 仍是单列（高度不够，塞双栏会挤） |
| 平板竖屏 / 横屏 | 左栏设置 + 右栏书目和日志 |

旋转、拖拽分屏时不会重建 Activity，输入内容、勾选状态、日志和进度都会迁移过去。

## 构建

**不需要 Gradle，也不需要 AndroidX**。全程 `javac -> d8 -> aapt -> zipalign -> apksigner`。

### 准备

1. **JDK 11+**
2. **Android SDK**：`android.jar`（编译用）、`d8.jar`、`aapt`、`zipalign`、`apksigner`
3. **framework-res.apk**：`aapt1` 打包资源时要用它，直接拿 `android.jar` 会解析错资源表
4. **四个依赖 jar**，版本很关键（见下）

依赖必须放在 `libs/` 下：

```
libs/commons-compress.jar   # Apache Commons Compress 1.11
libs/junrar.jar             # junrar 1.0.1
libs/xz.jar                 # XZ for Java 1.10
libs/commons-logging.jar    # Apache Commons Logging 1.2
```

### 为什么锁这几个老版本

因为应用要支持到 Android 5.0（API 21）。这几个库的新版本偷偷用了高版本才有的 Java API：

| 库 | 新版本的问题 |
|---|---|
| commons-compress 1.19+ | `SevenZFile` 用 `java.nio.file`（API 26） |
| junrar 5.0+ | 没有 `Archive(File)` 构造函数；`java.nio.file` / `java.time` |
| junrar 7.x+ | 依赖 `java.nio.file`（API 26） |
| slf4j 2.x | `LoggerFactory` 用 `MethodHandle`（API 26） |

这些在编译期完全不报错，只会在老手机上运行时炸 `NoClassDefFoundError`。所以 junrar 用 1.0.1（它依赖 commons-logging 而不是 slf4j），commons-compress 用 1.11。

### 编译

```bash
# 把四个 jar 放进 libs/，然后：
./build.sh
```

需要覆盖路径时用环境变量：

```bash
ANDROID_JAR=/path/to/android.jar \
D8_JAR=/path/to/d8.jar \
FRAMEWORK_RES=/path/to/framework-res.apk \
./build.sh
```

产物在 `dist/ComicConv.apk`。

**签名**：仓库里不含任何密钥。不指定的话脚本会现场生成一个自签名密钥，密码写进 `release.jks.pass`（已在 `.gitignore` 里）。要发布更新就换成你自己的：

```bash
CC_KEYSTORE=/path/to/my.jks CC_STOREPASS=xxx CC_KEYPASS=xxx CC_ALIAS=mykey ./build.sh
```

### 图标

`tools/make_icon.py` 生成全部图标（传统 PNG + 自适应图标前景层）。它不依赖 PIL，自己光栅化、自己写 PNG：

```bash
python3 tools/make_icon.py
```

## 已知限制

- **RAR5 不支持**：junrar 1.0.1 只认 RAR4，遇到 RAR5 会明确报错而不是静默失败
- **MOBI6 单图约 127KB 上限**：漫画页普遍超标，真无损模式下老款 Kindle 可能显示不出这些页。要兼容性优先就开「Kindle 兼容模式」（降采样，有损）。亚马逊已弃用 MOBI，新设备建议直接选 EPUB
- **PDF 输入依赖内存映射**：超出可用地址空间时会退回逐页栅格化（有损）
- **PDF 输出里的 PNG 不是字节一致**（原因见上）

## 测试情况

用真实漫画压缩包跑过回归（字节级比对）：

| 样本 | 结果 |
|---|---|
| ZIP 35 页 | PDF / EPUB / CBZ / MOBI / 图片 全部 35/35 字节一致 |
| 7z 130 页（141MB） | EPUB / CBZ / MOBI / 图片 130/130 字节一致 |
| CBR 187 页（105MB） | EPUB / CBZ / MOBI / 图片 187/187 字节一致 |
| TAR / TAR.GZ | 12/12 字节一致 |
| AZW3（含重复页和缩略图） | 去重正确，6 页 |

PDF 输出里那几张对不上的页都是 PNG/BMP，属于上面说的容器格式限制。

## 许可证

MIT，见 [LICENSE](LICENSE)。
