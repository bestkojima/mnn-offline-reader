# MNN Offline Reader · 离线发音

[![Android build](https://github.com/bestkojima/mnn-offline-reader/actions/workflows/android.yml/badge.svg)](https://github.com/bestkojima/mnn-offline-reader/actions/workflows/android.yml)

长按选中文字，点击系统菜单中的“发音”，打开居中卡片查词、看音标和例句，并在手机上合成语音。

An Android app for selected-text pronunciation, offline English dictionary lookup, and on-device TTS powered by MNN. Speech resources are downloaded from ModelScope rather than bundled in the APK.

## 安装与使用

在 [Releases](https://github.com/bestkojima/mnn-offline-reader/releases) 下载测试版 APK。要求 **Android 6.0 / API 23 或更高，arm64-v8a**；当前没有 32 位或 x86 APK。

1. 安装后打开 App，进入“下载语音”，按需下载中文或英语及其他语言资源，建议使用 Wi-Fi。
2. 在浏览器、阅读器等应用中长按并选中文字，点击“发音”。该入口可能藏在“更多”里，也可分享文字到本 App。
3. 在卡片中查看释义、音标和精选例句，点击“发音”或例句旁的“读”。可切换语言、音色及语速，收藏到生词本。

来源应用决定是否支持 Android `PROCESS_TEXT` 菜单。**本版处理可选中文字和分享文字，无法保证所有界面都能取字**；图片文字、不可选文字和禁止分享的页面需要后续 OCR/取字方案。

## 当前功能

| 功能 | 实现 |
| --- | --- |
| 跨应用入口 | Android `PROCESS_TEXT` 和 `SEND text/plain`，居中卡片 |
| 中文发音 | Kokoro-82M v1.0 MNN，女声 `zf_xiaoxiao`、男声 `zm_yunxi` |
| 英语及其他语言 | Supertonic 3 MNN int8，31 种语言、F1–F5 / M1–M5 十个音色 |
| 语言识别 | 本地 ML Kit 检测，可手动覆盖短词、专名和混合文字的判断 |
| 英语词库 | SQLite，49,664 词条、52,040 词形关联，释义与音标离线查询 |
| 精选例句 | 12 个词的 24 条自编中英例句；尚未覆盖整个词库 |
| 朗读控制 | 0.75× / 1.0× / 1.25× 语速，停止、新请求替换、音频焦点管理 |
| 生词本 | 与只读词库分开存储，历史最多保留 200 次查询 |

Supertonic 的 31 种语言：`en ko ja ar bg cs da de el es et fi fr hi hr hu id it lt lv nl pl pt ro ru sk sl sv tr uk vi`。中文由 Kokoro 处理；释义、音标和例句目前仅提供英语词库。

中文前处理使用词典最长匹配、多音词、词内三声变调及常用数字/日期规范化，可处理词典收录的中英混排。不能保证所有多音字语境、稀有字、数学表达式或英文专名；未收录内容会提示，避免静默漏读。当前采用 **v1.0**，并未切换到 v1.1-zh。

## 模型下载与离线使用

APK **不内置 TTS 权重、音色和中文音素词典**。英语数据库、许可证与轻量离线语言识别仍随包提供。安装包目前约 18 MB，精确大小以 Release 附件为准。

| 下载包 | 资源大小 | ModelScope 仓库 | 固定 revision |
| --- | ---: | --- | --- |
| 中文语音 | 109,906,786 字节，约 110 MB | [Kokoro-82M-v1.0-MNN](https://modelscope.cn/models/dr3334/Kokoro-82M-v1.0-MNN) | `e7f9c985f3118427cb6646b9962ee4b91c536b0f` |
| 英语及其他语言 | 113,383,402 字节，约 113 MB | [Supertonic-3-INT8-MNN](https://modelscope.cn/models/dr3334/Supertonic-3-INT8-MNN) | `1dfd5693db1b62b2c49331b7674f9dee5c69385a` |

- 下载只在用户点击下载页按钮后启动，一次下载一组，支持移动网络；离开下载页会暂停。
- 断网、暂停和重启 App 后保留内部 `.part` 文件；再次点击“下载 / 继续”，通过 HTTPS `Range` 请求恢复。
- 206 响应必须匹配断点和总长；服务器忽略断点返回 200 时重新下载，避免错误拼接。
- 按固定清单校验大小和 SHA-256，成功后原子重命名；校验失败的模型不会载入，可重新下载。
- 完成下载后，检测、查词和语音合成都在本地运行。模型请求只包含文件路径和 revision，不上传选中文字。

两套下载资源合计约 **223.3 MB**，不保留第二份完整语音模型。安装总空间还包括 App、约 9 MB 的英语词库副本、原生库及运行缓存，不能只按 APK 大小估算。

无录音、悬浮窗或无障碍权限；网络权限用于模型下载。本版没有后台下载前台服务、OCR、云端查词或整句翻译。

## 从源码构建

需要 Git、JDK 17、Python 3、curl，以及 Android SDK 35、NDK `28.1.13356709`、CMake `3.31.6`。Gradle Wrapper 为 `8.11.1`，Android Gradle Plugin 为 `8.9.3`。

```bash
git clone --recurse-submodules https://github.com/bestkojima/mnn-offline-reader.git
cd mnn-offline-reader

# 如果克隆时没有初始化子模块：
git submodule update --init --depth 1

# 设置 ANDROID_HOME，或在 Android Studio 中选择 SDK。
sdkmanager 'platforms;android-35' 'build-tools;35.0.0' \
  'ndk;28.1.13356709' 'cmake;3.31.6'

# 只准备英语词库，构建时不下载 TTS 模型。
bash scripts/prepare_assets.sh
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug :app:assembleDebugAndroidTest
```

APK 输出：`app/build/outputs/apk/debug/app-debug.apk`。调试版仅供测试，正式发布需要配置自己的稳定签名；来自不同构建环境的 debug APK 可能无法直接覆盖安装。

MNN 通过 Git 子模块固定在公开上游 commit `cda4a6f441b8ed58cc5c6f389359e5851e9d6322`。CMake 默认使用 `vendor/MNN`，也可通过 `-PmnnRoot=/absolute/path/to/MNN` 指定已有源码目录。

**MNN 从源码编译，native 使用 `-O3 -DNDEBUG`，包括 debug APK。** 启用 `MNN_LOW_MEMORY` 与 `MNN_ARM82`，静态链接到 JNI 的 `libenglish_reader.so`；不是下载预编译 MNN `.so`。ML Kit 语言识别使用其供应商运行库。Java → JNI → MNN Module API；Supertonic 输出 44.1 kHz，Kokoro 输出 24 kHz 单声道 PCM16。两个引擎按语言切换，切换时释放旧引擎；内存 PCM 缓存上限 10 MiB。

依赖下载困难时，可选择 Google Maven 镜像：

```bash
MNN_MAVEN_MIRROR=https://maven.aliyun.com/repository/google ./gradlew :app:assembleDebug
```

词库脚本固定 ECDICT commit 和 SHA-256，源 CSV 缓存在 `models/english-reader-dictionary/`。模型、缓存、生成的数据库、构建输出和本机配置均不进入 Git。构建会拒绝 assets 中的 TTS 模型二进制与未完成文件。

## 测试与验证边界

```bash
python3 scripts/test_dictionary.py
python3 scripts/test_kokoro_assets.py
./gradlew :app:testDebugUnitTest :app:lintDebug

# 连接 arm64 手机，先在下载页下载两套资源，再执行设备测试：
./gradlew :app:connectedDebugAndroidTest
```

已通过 **29 项 Java 单元测试、4 项 Python 测试、构建及 lint**。其中 10 项下载测试使用真实本地 HTTP 响应，覆盖暂停恢复、200/206 行为、校验失败、损坏修复与离线复用。实际生产 Java 下载器已从 ModelScope 下载全部 26 个资源文件，完成真实中断后换新下载器恢复与逐文件校验。

此前开发机已完成 Supertonic 多语言和 Kokoro 中文的真实 MNN 合成、取消与恢复。APK 签名、ZIP 16 KB 对齐及三个 native ELF 的 16 KB LOAD 对齐已检查。

**尚未进行安卓真机验收。** 设备端下载、来源应用菜单、卡片交互、语言检测、JNI 播放、主观音质、出声延迟与峰值内存仍需验证；开发机通过不能视为手机性能结论。已有验证记录见 [docs/verification-download.json](docs/verification-download.json)。首次 Release 复用仓库拆分前已构建的下载版 APK；按用户要求，发布独立仓库时不再完成新一轮构建和测试。

GitHub Actions 提供手动构建工作流：初始化 MNN、生成词库并运行构建/单元测试/lint，保留 APK 与报告。上传代码不会自动触发该工作流；它不执行真机测试，也不发布 TTS 权重。

## 项目结构

```text
app/src/main/java/     卡片、查词、语言检测、下载和播放调度
app/src/main/cpp/      Supertonic / Kokoro 引擎及 JNI
app/src/main/assets/   下载清单、许可；构建时生成英语词库
data/                 上游资源锁文件、精选例句、ModelScope 发布记录
scripts/              词库构建、资源验证和维护者发布工具
vendor/MNN/           固定版本 MNN 子模块
docs/                 设计、中文模型候选与研究记录
```

## 来源与许可

App 源码采用 [Apache-2.0](LICENSE)，MNN 原始声明保留在 [NOTICE](NOTICE) 与 App 内许可页。模型、词库和 SDK 各自的许可独立适用：

| 组件 | 来源与许可 |
| --- | --- |
| MNN | [alibaba/MNN](https://github.com/alibaba/MNN)，Apache-2.0 |
| Supertonic 3 | [Supertone 原模型](https://huggingface.co/Supertone/supertonic-3)，BigScience Open RAIL-M；[MNN int8 转换](https://huggingface.co/yunfengwang/supertonic-tts-mnn) |
| Kokoro v1.0 | [hexgrad/Kokoro-82M](https://huggingface.co/hexgrad/Kokoro-82M)，Apache-2.0；[MNN 转换](https://huggingface.co/wangjazz/kokoro-82m-mnn) |
| Kokoro 前处理与音色 | [固定词典](https://huggingface.co/csukuangfj/kokoro-multi-lang-v1_0)、[音色来源](https://huggingface.co/onnx-community/Kokoro-82M-v1.0-ONNX)，完整声明随下载资源和 App 提供 |
| 英语词库 | [ECDICT](https://github.com/skywind3000/ECDICT)，MIT；精选例句由本项目编写 |
| 语言识别 | [ML Kit](https://developers.google.com/ml-kit/language/identification/android)，使用供应商 SDK 及其条款 |

上游来源 commit、文件大小及 SHA-256 保存在 `data/model-lock.json`、`data/kokoro-lock.json` 和 `data/modelscope-release.json`；下载所用 ModelScope commit 保存在 `app/src/main/assets/model-catalog.json`。

进一步阅读：[设计记录](docs/ANDROID_ENGLISH_READER_DESIGN.md) · [中文 TTS 候选](docs/CHINESE_TTS_MODEL_CANDIDATES.md) · [跨应用交互研究](docs/ANDROID_TRANSLATION_APP_UX_RESEARCH.md)。
