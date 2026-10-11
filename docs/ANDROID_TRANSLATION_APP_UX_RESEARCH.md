# Android 翻译软件跨应用入口调研

调研日期：2026-10-11。目标：为 Supertonic 3 + MNN 离线英语朗读 App 选择取字与发音入口。依据官方公开文档；未安装 APK、未做真机体验、未反编译。以下区分已公开的行为与设计推断。

最新选择：用户已确认 **Supertonic 3 MNN int8 + 本地 SQLite 词库与精选中英例句 + 居中卡片**，第一版已有独立安卓实现，尚待真机验收。详见 [已确认的实施方案](ANDROID_ENGLISH_READER_DESIGN.md)。

## 已确认的产品行为

| 产品 | 用户实际操作 | 官方明确说明的边界 | 对朗读 App 的启发 |
|---|---|---|---|
| Google Translate：Tap to Translate | 开启功能后，在其他 App 中选中文字、复制，再点击当前屏幕的 Google Translate 图标，选择语言 | 只支持文本，不支持图像；首次可能要求“显示在其他应用上层”；Android 10+ 文档提到 Paste，Pixel 6+ 提到 Show floating icon，不能把所有系统都理解为复制后自动弹球 | “复制 → 点悬浮球 → 发音”是一种可理解的备用入口，仍要求文字可复制 |
| DeepL Android | 选中文字后，长按选区，在菜单点 DeepL，或“分享 → DeepL”；DeepL 项可能在“…”内 | 文本进入 DeepL App 后显示翻译；也可以手动复制粘贴 | 最贴近“长按出现发音按钮”的可选文本入口；菜单是否出现、是否折叠需要逐 App 测试 |
| 有道词典 | 官方权限说明确认悬浮窗用于屏幕取词，剪切板用于查词、翻译等 | 公开文档没有完整说明当前 Android 屏幕取词的手势和内部取字路径 | 可以参考“悬浮工具 + 结果面板”，不能据此声称任意 App 的任意文字长按都可用 |

来源：[Google 官方操作说明](https://support.google.com/translate/answer/6350658)、[DeepL 官方跨应用说明](https://support.deepl.com/hc/en-us/articles/4416373738386-Translate-texts-from-other-apps-with-DeepL-mobile-app)、[有道官方权限说明](https://c.youdao.com/dict/law/authority.html)。

DeepL 还明确提供原文与译文的语音播放，语速档为 0.5、0.75、Standard、1.25，设置页可试听并保存。这说明取字后的“小面板 → 播放/停止 → 语速设置”与主流工具的认知接近；官方这一页不说明其底层语音引擎。[DeepL 官方语速说明](https://support.deepl.com/hc/en-us/articles/16061175379356-Change-the-voice-speed-in-the-DeepL-mobile-apps)

## 有道实现证据与缺口

有道权限表同时列出多平台，必须逐行看平台：悬浮窗、剪切板列 Android/iOS；屏幕录制与辅助功能两项列的是 **Mac**，用途包括截屏识别、划词和 AIBox。不能把这两行直接当作 Android 版使用无障碍服务或截图 OCR 的证据，也不能由 iOS 栏反推出存在 Android 同款系统悬浮窗。[官方权限表](https://c.youdao.com/dict/law/authority.html)

隐私政策（页面更新日期 2026-08-10）把粘贴板信息列入日志，并说明拍照翻译、划词翻译等会处理用户选择或上传的文本、图片、语音等。它证明这些输入类型被使用，但没有披露 Android 截屏 API、OCR 模型、是否读取无障碍节点等实现细节。[官方隐私政策](https://c.youdao.com/dict/law/youdao_dict_privacy.html)

搜索中出现的 `youadao.com` 和 `youdao.im` 不属于已核验的 `youdao.com` 官方域名，本调研未采用其“设置路径”“全屏取词”等说法。官方旧手机版页面出现英文单词发音介绍，但页脚为 2013 年，不能用于断言 2026 年 Android 界面布局。[旧官方手机版页面](https://cidian.youdao.com/mobile_other.html)

## 用于本项目的设计推断

以下是建议实现的行为，**不是上述闭源软件内部实现的事实**：

1. 可选文字：用户原有长按选字菜单中增加“发音”，接收选中文字后立即进入本地 TTS。具体 Android `PROCESS_TEXT` 接入由 Android API 调研核验。
2. 分享入口：其他 App 的“分享文本”可以进入朗读面板，给自定义选字菜单提供另一条路径。
3. 悬浮球：点击后展示粘贴、取字/框选与发音按钮。可复制文字走显式粘贴；不能复制的文字需要另一种取字机制，不能靠悬浮窗权限本身读取屏幕。
4. 结果面板：展示原文、发音/停止、音色与语速；OCR 输入允许修正后再读，避免把识别错词直接合成。

“长按可选文本菜单”和“全屏悬浮取字”应分别验收；确认第二种方案的截图、无障碍、系统限制之后，才承诺覆盖范围。产品调研仅证明现有工具采用了多个入口，未证明有普通第三方 App 可无条件控制所有界面的长按。

## 可核验的开源实现

TranslateYou 给出了直接可读的选字面板实现：Manifest 中导出的 `ShareActivity` 使用 Dialog 主题，接收 `PROCESS_TEXT` 的 `text/plain`、`SEND` 的 `text/plain` 和 `image/*`；未声明悬浮窗权限或无障碍服务。`TranslationActivity` 从 `EXTRA_TEXT` 或 `EXTRA_PROCESS_TEXT` 获取 `CharSequence`，`ShareActivity` 展示 Compose `AlertDialog`，关闭对话框即结束 Activity。这证明“其他 App 选字 → 朗读/翻译卡片”可以通过系统 Activity 和对话框呈现，不需要悬浮窗权限。其代码为 GPL-3.0，本项目宜参考接入方式，并按项目许可决定是否复用代码。[Manifest](https://github.com/you-apps/TranslateYou/blob/master/app/src/main/AndroidManifest.xml)、[输入接收](https://github.com/you-apps/TranslateYou/blob/master/app/src/main/java/com/bnyro/translate/ui/TranslationActivity.kt)、[对话框](https://github.com/you-apps/TranslateYou/blob/master/app/src/main/java/com/bnyro/translate/ui/ShareActivity.kt)

ScreenTrans 提供另一种实际架构：Manifest 声明 `SYSTEM_ALERT_WINDOW` 与 `FOREGROUND_SERVICE_MEDIA_PROJECTION`，`FloatingService` 使用 `mediaProjection` 前台服务类型，没有声明无障碍服务；README 描述悬浮区域 OCR 翻译。可据此研究“悬浮入口 + 截屏 + OCR”，但不能反推有道采用完全相同的方式。[ScreenTrans Manifest](https://github.com/longipinnatus/ScreenTrans/blob/main/app/src/main/AndroidManifest.xml)、[项目说明](https://github.com/longipinnatus/ScreenTrans)

## 后续真机验收建议

选择浏览器正文、聊天文字、PDF 文本、图片内英文、游戏/Canvas 文字各一类，分别记录：是否能长按选字、菜单项是否出现、分享是否携带正文、悬浮按钮是否被系统隐藏、取字是否准确。记录 Android 版本与目标 App 版本，因为官方帮助不能代替具体手机上的体验验证。

## Android 开发实现对照

| 入口/能力 | 系统实现 | 用户需要开启什么 | 核心边界 |
|---|---|---|---|
| 选字“发音” | 导出 Activity 接收 `ACTION_PROCESS_TEXT` / `EXTRA_PROCESS_TEXT`，对话框主题显示卡片 | 此入口不需要悬浮窗或无障碍权限 | API 23+；菜单由来源应用生成，可能被隐藏或不支持 |
| 分享“发音” | 接收 `ACTION_SEND`，MIME 为 `text/plain`，读取 `EXTRA_TEXT` | 此入口不需要悬浮窗或无障碍权限 | 来源应用可能分享链接而非所选正文，需预览 |
| 普通常驻悬浮球 | `WindowManager` + `TYPE_APPLICATION_OVERLAY` | 显示在其他应用上层 | 只能提供 UI，不自动获得文字、截图或全局长按 |
| 无障碍读取文字 | `AccessibilityService` + `canRetrieveWindowContent`，检查节点 `text` 及选区起止 | 用户在系统设置开启服务 | 节点可能不提供文字/选区；不可直接把整节点当作选中单词 |
| 授权录屏取图 | `MediaProjection` → VirtualDisplay / ImageReader → Bitmap | 系统屏幕获取授权；对应前台服务 | 授权按会话，持续会话内可以按需截帧，不是每次 OCR 都重新授权 |
| 无障碍取图 | `AccessibilityService.takeScreenshot` (API 30+)；`takeScreenshotOfWindow` (API 34+) | 服务开启、声明截图能力 | 不使用 MediaProjection 授权流程；仍受安全窗口和截图失败限制 |

API 来源：[文本处理](https://developer.android.com/reference/android/content/Intent#ACTION_PROCESS_TEXT)、[分享接收](https://developer.android.com/training/sharing/receive)、[应用悬浮窗](https://developer.android.com/reference/android/view/WindowManager.LayoutParams#TYPE_APPLICATION_OVERLAY)、[无障碍选区](https://developer.android.com/reference/android/view/accessibility/AccessibilityNodeInfo#getTextSelectionStart())。

### 剪贴板不能依赖后台持续监听

Android 10+ 限制剪贴板访问：通常只有当前获得焦点的应用或默认输入法可以读。启动前台服务并不等于拿到窗口焦点。因此“任何应用复制 → 后台服务自动读剪贴板 → 自动发音”不能作为现代 Android 通用方案。应设计明确的分享、选字入口，或用户点击后打开获得焦点的面板，显式粘贴；各厂商行为仍需验证。[Android 10 剪贴板限制](https://developer.android.com/about/versions/10/privacy/changes#clipboard-data)。

### 截图与 OCR 的两条路线

普通应用路线：用户点悬浮入口 → 请求或复用仍有效的 MediaProjection 会话 → 取一帧 → 框选区域 → OCR。在面向 Android 14+ 的应用中配置 `mediaProjection` 前台服务和对应权限；当系统终止会话时释放资源并再次获取授权。不要把旧 token 用于创建新会话。[MediaProjection 文档](https://developer.android.com/media/grow/media-projection)。

已启用无障碍服务路线：用 API 30 的 `takeScreenshot()` 获取屏幕；API 34 的 `takeScreenshotOfWindow()` 可以针对来源窗口取图，避免自己的无障碍悬浮层进入图片。该路线必须声明截图能力，属于用户启用无障碍服务后的能力，不要求每次调用再走 MediaProjection 对话框。[截图 API](https://developer.android.com/reference/android/accessibilityservice/AccessibilityService#takeScreenshot(int,%20java.util.concurrent.Executor,%20android.accessibilityservice.AccessibilityService.TakeScreenshotCallback))。已连接服务可以使用 `TYPE_ACCESSIBILITY_OVERLAY` 展示取字框；它与普通应用悬浮窗是不同类型。[无障碍悬浮层](https://developer.android.com/reference/android/view/WindowManager.LayoutParams#TYPE_ACCESSIBILITY_OVERLAY)。

受保护窗口、无效窗口、截图间隔等失败必须返回可理解的状态；不要把失败截图送给 OCR。[安全窗口](https://developer.android.com/reference/android/view/WindowManager.LayoutParams#FLAG_SECURE)。

英语 OCR 的简单备选是 ML Kit 的 bundled Latin 模型：模型随 APK 提供，免首次动态下载；按官方当前文档该 SDK 要求 API 23+。也可以评估 MNN/PaddleOCR 方案，但应根据目标机型的体积与识别效果选择，不必为了 TTS 使用 MNN 而要求 OCR 同样使用 MNN。[ML Kit 安卓 OCR](https://developers.google.com/ml-kit/vision/text-recognition/v2/android)。

### 为什么不直接全局监听每一次长按

普通悬浮层收到的是落在自身窗口上的触摸，不能靠悬浮窗权限改写其他应用的菜单。无障碍手势/触摸 APIs 也不是无影响的全局被动监听：例如 API 34 `onMotionEvent` 文档说明，被服务请求的事件来源不会再发送给系统其余部分。建议用户先进入明确的取字模式，再框选/点击目标，避免破坏来源应用的长按与滚动。[触摸事件 API](https://developer.android.com/reference/android/accessibilityservice/AccessibilityService#onMotionEvent(android.view.MotionEvent))。

## 推荐的第一版交互

默认路径：长按英文选区 → “发音” → 当前应用上方的朗读卡片；可设置收到文本后自动读，也可以点击播放。卡片提供原文、播放/停止、重播、音色、语速和关闭；关闭即返回原界面。实际音色与语速值传给 Supertonic 3 MNN 原生引擎。

备用路径：选区菜单没有入口时使用“分享 → 发音”。图片或不可选英文则通过可选悬浮球进入“框选取字”，OCR 后允许修正内容。三类输入汇入同一个合成队列，模型复用，新的明确播放请求可替换旧请求；停止播放与取消合成需分别实现。

先验收长按卡片与分享，再按真机结果选择截图路线。系统 TTS 引擎可作为额外接入方式，让支持 Android TTS 的阅读器选择本引擎；注册系统 TTS 本身不会在每个应用长按菜单新增按钮。[系统 TTS 服务](https://developer.android.com/reference/android/speech/tts/TextToSpeechService)。
