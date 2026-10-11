# Supertonic 3 / MNN 安卓跨应用英语朗读调研

核对日期：2026-10-11。用户目标：安装安卓 App 后，在其他应用中长按选字，点击“发音”；优先英语，期望接近有道的跨应用体验。

用户已确认采用 **Supertonic 3 MNN int8 + 本地 SQLite 词库和精选中英例句**。实施方案与待定项记录在 [安卓英语查词与朗读方案](ANDROID_ENGLISH_READER_DESIGN.md)。这项选择不代表已完成安卓实测。

后续开发已构建独立 arm64 APK，并验证本机 v3/int8 合成；配置采样率核对为 44,100 Hz。下文“未执行/未构建”的表述记录的是最初调研边界，最新验证结果见实施方案，安卓真机执行仍未完成。

## 结论和证据等级

| 问题 | 已确认的情况 | 尚未验证的部分 |
| --- | --- | --- |
| 模型名称 | 对应的 TTS 项目是 **Supertonic 3**。 | 用户原文为 “supersonic 3”，按上下文和找到的项目匹配。 |
| 英语和音色 | 英语代码 `en`；社区 MNN 项目提供 M1–M5、F1–F5 共 10 个预设音色。 | 任意口音选择、词典级音标控制不应由这些参数推断。 |
| v3 的 MNN 支持 | 社区 `vra/supertonic-mnn` 明确支持 v3、fp32/fp16/int8，链接到转换后的模型仓库。 | 本次没有下载权重、执行合成或验证安卓 JNI。 |
| 本仓库安卓基础 | 有 Supertonic C++、Kotlin/JNI、PCM 播放和系统 TTS 服务。 | 现有实现是否直接兼容 v3 的所有模型资产与预处理。 |
| 跨应用菜单 | Android 提供 `ACTION_PROCESS_TEXT` 文本处理接口。 | 来源应用是否提供这个菜单入口，需逐个应用和系统验证。 |
| 所有界面 | 需要选字菜单以外的取字入口扩大覆盖。 | 无障碍/OCR 都无法保证每个界面都可读。 |

模型资料：[官方模型卡](https://huggingface.co/Supertone/supertonic-3)。社区实现及命令行参数：[vra/supertonic-mnn](https://github.com/vra/supertonic-mnn)。模型发布位置：[yunfengwang/supertonic-tts-mnn](https://huggingface.co/yunfengwang/supertonic-tts-mnn/tree/main)。这是社区 MNN 接入，不能等同于官方安卓 v3 SDK 已验证。

## 命令行选择音色

下面按社区 CLI 的版本、语言、音色参数组合，生成英语 F1 音色的 WAV；是开发机验证用法，本次未执行：

```bash
pip install supertonic-mnn
echo "Hello, this is an English pronunciation test." | supertonic-mnn --version v3 --lang en --voice F1 -o hello.wav
```

`--version v3` 选择模型版本，`--lang en` 选择语言，`--voice F1` 选择音色。安卓产品应把参数变成设置，通过 Kotlin → JNI → MNN 调用；没有必要嵌入 Python 命令行环境。参数依据：[社区 README](https://github.com/vra/supertonic-mnn#quick-usage)。

官方模型支持 31 种语言，列表包含英语、不包含中文。不要把语言无关模式当作中文质量承诺。此限制不影响用户当前的英语目标。[官方语言列表](https://huggingface.co/Supertone/supertonic-3#supported-languages)。

## 本地代码可以复用什么

- `apps/frameworks/mnn_tts/src/supertonic/mnn_supertonic_tts_impl.cpp`：`Process(text)`、`SetSpeakerId()`、四个 `Module::load` 模型：duration predictor、text encoder、vector estimator、vocoder；默认读取 `mnn_models/fp16/`。
- `apps/frameworks/mnn_tts/android/java/com/taobao/meta/avatar/tts/TtsService.kt`：已有初始化、`process()` 返回 `ShortArray` 和 `setSpeakerId()` 的 JNI 包装。
- `apps/frameworks/mnn_tts/demo/android/src/main/java/com/alibaba/mnn/tts/demo/audio/AudioChunksPlayer.kt`：PCM16 单声道 `AudioTrack` 播放。
- `apps/frameworks/mnn_tts/demo/android/src/main/AndroidManifest.xml`：已注册 `com.mnn.tts.demo.MnnTtsService` 系统 TTS 服务，当前没有注册 `PROCESS_TEXT`。

适配检查：当前 C++ SDK 的 `Process(text)` 不接收语言参数，编码过程中也没有自动添加语言标记。因此应对照 v3 官方/社区的文本规范化和语言标记逻辑；还需检查 Unicode indexer、各模型输入输出、目录结构和 voice-style 维度。不能以“有四个同名模型”认定兼容。

采样率应来自所用模型配置。C++ `Process()` 返回采样率与 PCM，但 `src/android/tts_service.cpp` 只返回 PCM；安卓已注册的系统 TTS 服务从顶层 `config.json` 读取 `sample_rate`，必须与模型内部 `tts.json` 一致。本次未确认 v3 的实际采样率，避免硬编码旧默认值。

## 跨应用入口

### 1. 可选中文字：长按 → 发音

注册标签为“发音”的导出 Activity，声明 `PROCESS_TEXT`、`DEFAULT` 和 `text/plain`：

```xml
<activity android:name=".PronounceActivity"
    android:label="发音" android:exported="true">
    <intent-filter>
        <action android:name="android.intent.action.PROCESS_TEXT" />
        <category android:name="android.intent.category.DEFAULT" />
        <data android:mimeType="text/plain" />
    </intent-filter>
</activity>
```

通过 `intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString()` 获取文本。该接口从 API 23 提供；来源应用决定实际是否出现，以及是否折叠到“更多”。只用于朗读时不要返回替换后的文本。[Android Intent 文档](https://developer.android.com/reference/android/content/Intent#ACTION_PROCESS_TEXT)。应用可以自定义选字菜单，故需测试来源应用：[TextView 文档](https://developer.android.com/reference/android/widget/TextView#setCustomSelectionActionModeCallback(android.view.ActionMode.Callback))。

### 2. 无法选字：悬浮入口 → 主动取字 → 发音

建议作为覆盖扩展：用户主动点击悬浮按钮，读取来源应用暴露的无障碍文字，允许用户确认目标片段，然后提交给同一个朗读引擎。无障碍服务可以读取 UI 层级，但节点可能为空或缺少文字；不应承诺替每个应用修改长按菜单。[无障碍服务文档](https://developer.android.com/guide/topics/ui/accessibility/service)。

普通应用悬浮窗需要用户授予“显示在其他应用上层”权限；若由无障碍服务提供 overlay，应按该服务对应 API 实现。[悬浮窗权限文档](https://developer.android.com/reference/android/Manifest.permission#SYSTEM_ALERT_WINDOW)。

### 3. 图片/未暴露文字：授权截图 → OCR → 发音

可以增加用户主动触发的框选 OCR。通过 MediaProjection 获取屏幕时，每次会话需要用户同意；`FLAG_SECURE` 窗口禁止截图，应在不可获取内容时提示原因。[屏幕获取文档](https://developer.android.com/media/grow/media-projection)，[FLAG_SECURE](https://developer.android.com/reference/android/view/WindowManager.LayoutParams#FLAG_SECURE)。

后续安卓调研补充：用户启用无障碍服务后，也可采用 API 30+ 的 `AccessibilityService.takeScreenshot`，或 API 34+ 的 `takeScreenshotOfWindow`；这条路线需要服务的截图能力声明，不走 MediaProjection 的会话授权。详见 [翻译软件与安卓入口调研](ANDROID_TRANSLATION_APP_UX_RESEARCH.md)。

## 建议开发顺序

1. 固定 v3 模型和代码版本，在开发机验证英语短词、句子、数字和专有名词的音频。
2. 安卓 arm64 原生接入，对照相同输入验证音色、时长、采样率，记录冷启动/热启动延迟和峰值内存；不能把桌面性能作为手机指标。
3. 接入 `PROCESS_TEXT` 和分享文本入口，提供音色、语速、播放/停止；单一后台任务队列复用已加载模型，避免连续点击导致并发合成。
4. 加入悬浮取字和可选 OCR，分别测试浏览器、阅读器、聊天界面、图片及受保护页面。

## 上游维护状态

官方仓库在 2026-09-09 归档，开发和支持已结束；代码和模型仍有归档发布位置。旧 SDK 的自动下载可能使用原命名空间。实际开发应固定资产版本与校验，使用本地模型目录并保留许可证。[官方归档说明](https://github.com/supertone-oss-archive/supertonic)。

验证边界：本次完成了网络资料和本地源码核对；没有构建 APK、运行模型、连接安卓设备或测量性能。以上开发顺序是根据这些证据给出的工程建议。

## 后续容量核对（2026-10-11）

直接读取社区模型仓库的 Hugging Face 文件列表，按一个精度目录内四个 `.mnn` 文件，加 `tts.json`、`unicode_indexer.json` 和十个音色 JSON 汇总；以下 MB 按 1,000,000 字节计：

| 精度 | 实际文件合计 | 约合 MiB |
| --- | --- | --- |
| int8 | 113.4 MB | 108.1 MiB |
| fp16 | 202.6 MB | 193.3 MiB |
| fp32 | 400.4 MB | 381.8 MiB |

来源：[模型仓库](https://huggingface.co/yunfengwang/supertonic-tts-mnn/tree/main)，[v3 文件列表 API](https://huggingface.co/api/models/yunfengwang/supertonic-tts-mnn/tree/main/v3?recursive=true&limit=1000)。不需要同时下载三个精度或 v1/v2。上述大小是存储容量，不是运行峰值内存；尚未验证 int8 音质和安卓兼容性。

ECDICT `master/ecdict.csv` 的 HTTP Content-Length 为 65,933,428 字节，约 65.9 MB；GitHub 页面显示 62.9 MB（数值对应 MiB）。这是原始 CSV，不是 SQLite 转换后的大小，也不是 Release 的 340 万词增强包。[CSV 文件](https://github.com/skywind3000/ECDICT/blob/master/ecdict.csv)，[增强包 Release](https://github.com/skywind3000/ECDICT/releases)。

第一版容量规划：精选词库和中英例句的 SQLite 数据（含索引）可先按约 50–150 MB 预算；按用户选择的 int8 模型，完整应用可先按约 200–400 MB 安装后空间规划。它们是工程估算，取决于词条数量、释义长度、例句数量、索引、App 原生库和缓存，必须在实际导入与构建后测量。此前 fp16 的 300–500 MB 仅作为比较方案。音频按需合成并限制缓存；大规模预存音频会显著扩大容量。下载压缩包大小、解压后的安装占用和运行内存应分别记录。

## 多语言处理和编译核对（2026-10-11）

独立 App 已将固定英语包装扩展为语言检测后添加 `<lang>…</lang>`，并提供 31 种语言的手动覆盖。v3 的语言无关 `na` 是备用模式，不承诺列表外语言的质量。后续已接入独立 Kokoro v1.0 中文引擎：中文使用音素编码，经 JNI/MNN 合成，不添加 Supertonic 语言标记；接入记录和候选比较见[中文 TTS 文档](CHINESE_TTS_MODEL_CANDIDATES.md)。英语词库方案不随朗读语言增加重复词库。[官方语言列表](https://huggingface.co/Supertone/supertonic-3#supported-languages)。

识别使用 ML Kit bundled language-id 17.0.6，原文在设备上处理，检测模型随 APK 打包；不使用需要通过 Play Services 下载模型的 unbundled 版本。识别整段的主要语言，短词与同形词可手动纠正。合并 manifest 去掉网络权限。[官方 Android 接入文档](https://developers.google.com/ml-kit/language/identification/android)。

当前 APK 的 MNN 由仓库源码构建：native Release，实际编译含 `-O3 -DNDEBUG`；低内存和 ARM82 打开，静态链接至 JNI `libenglish_reader.so`，不是使用外部预编译 MNN `.so`。编译优化不代表已得到手机延迟和内存结论，真机测试仍待进行。


## 下载交付更新（2026-10-12）

当前 App 不再内置 TTS 权重和音色；已发布固定版本到 ModelScope，并接入持久断点续传、完整 SHA-256 校验和离线加载。英语词库与轻量语言检测仍随 App 提供，网络权限用于用户发起的语音包下载。此前关于内置模型与无网络权限的段落属于历史方案。发布仓库、固定 commit、当前 APK 大小及验证记录见 [App README](../README.md)。
