# 安卓 MNN 中文 TTS 候选模型

核对日期：2026-10-11。场景：跨应用选中文字，点击卡片发音；安卓离线、短词和短句的出声延迟优先。

## 已确定的选择

用户已选择 **先接入 Kokoro**。当前接入选择 **Kokoro v1.0**，参考已有 MNN 中英链路与模块输入接口；App 自身使用 Java 词典前处理与 JNI/MNN Module API；下面的版本、性能与许可信息用于保留后续替换候选。已完成 App 接入和开发机实际合成；最终构建证据见下方记录，安卓真机尚未执行。

路由方案：检测/手动选择中文时使用 Kokoro；其余受支持的语言继续使用 Supertonic 3 MNN int8。Supertonic 3 官方列出 31 种语言，不包含中文，不能通过增加 `<zh>` 标记获得已支持的中文能力。[Supertonic 3 官方语言列表](https://huggingface.co/Supertone/supertonic-3#supported-languages)

## 候选对比

| 候选 | 已有的 MNN 接入证据 | 速度相关证据及限制 | 当前决定 |
| --- | --- | --- | --- |
| **Kokoro-82M v1.0** | 上游 MNN 仓库已有中英混合 C API 示例、文本前处理和 `Module::load` / `onForward` 实现；示例使用 v1.0 资产布局。[示例](https://github.com/alibaba/MNN/blob/master/apps/frameworks/sherpa-mnn/c-api-examples/kokoro-tts-zh-en-c-api.c)、[模型实现](https://github.com/alibaba/MNN/blob/master/apps/frameworks/sherpa-mnn/sherpa-mnn/csrc/offline-tts-kokoro-model.cc) | 官方为 82M 参数、无扩散的解码架构；这些特征支持把它列为速度候选，但尚无本 App 同机安卓延迟数据。[官方模型卡](https://huggingface.co/hexgrad/Kokoro-82M) | **先接入**，按需加载中文引擎。 |
| **Kokoro-82M v1.1-zh** | 官方中文专版，支持英语、中文，共 103 个音色；当前仓库 v1.0 示例不能证明 v1.1-zh 兼容。[官方模型卡](https://huggingface.co/hexgrad/Kokoro-82M-v1.1-zh) | 仍为 82M、无扩散；官方也说明其减少了部分旧音色，因此不能把版本号视为各项全面升级。[官方说明](https://huggingface.co/hexgrad/Kokoro-82M-v1.1-zh) | 后续质量对照，先核对转换图、token、前处理和音色文件。 |
| **Matcha / matcha-icefall-zh-baker** | 本仓库有中文示例，使用三步声学模型与独立声码器。[MNN 示例](https://github.com/alibaba/MNN/blob/master/apps/frameworks/sherpa-mnn/c-api-examples/matcha-tts-zh-c-api.c) | 官方发布的中文单女声模型为 22,050 Hz；文档的 RTF 来自树莓派和 sherpa-onnx，不能外推本 App 的 MNN 安卓速度。[模型与测试说明](https://k2-fsa.github.io/sherpa/onnx/tts/pretrained_models/matcha.html#matcha-icefall-zh-baker-chinese-1-female-speaker) | 留作性能对照；Baker 数据的用途限制见下文。 |
| **Bert-VITS2-MNN 蒸馏版** | 社区项目提供离线安卓、JNI/AAR、中文分词和蒸馏 BERT 接入。[作者项目](https://github.com/Voine/Bert-VITS2-MNN) | 作者报告：骁龙 888、22,050 Hz、int8 权重量化模块约 29.7 MB；指定中英混合短句端到端约 **1,856 ms**，生成约 **5.20 s** 音频，RTF 约 0.357。该体积不能视为全部 App/前处理资产体积，速度也未在本 App 复测。[原始测试记录](https://github.com/Voine/Bert-VITS2-MNN/blob/master/README_EN.md#-on-device-inference-performance) | 备选；需保留 BERT 前处理成本及具体基模用途限制。 |
| **MeloTTS** | 官方支持中文和中英混合，但官方 Python/CPU 支持不能直接等同于已完成 MNN 安卓移植。[官方项目](https://github.com/myshell-ai/MeloTTS) | 作者声明 CPU 可实时合成；未提供可用于本 App 横向比较的同机 MNN 安卓数字。[官方说明](https://github.com/myshell-ai/MeloTTS#introduction) | 后续候选，需验证导出、算子和中文前处理。 |
| **Qwen3-TTS** | 上游 MNN 仓库已有导出、音频生成实现和 `qwen3_tts_demo`；当前示例需要参考音频。[上游 MNN 使用说明](https://github.com/alibaba/MNN/blob/master/docs/transformers/llm.md)、[示例源码](https://github.com/alibaba/MNN/blob/master/transformers/llm/engine/demo/qwen3_tts_demo.cpp) | 官方系列为 0.6B/1.7B，支持中文、流式和声音克隆；参数规模明显大于 82M，不能用服务器流式延迟承诺手机速度。[官方模型说明](https://github.com/QwenLM/Qwen3-TTS#released-models-description-and-download) | 更适合后续音色/表现力探索；基于规模与当前短句需求，本轮不作为速度优先接入对象。 |

## 权重和代码许可要分别记录

- Kokoro v1.0 与 v1.1-zh 官方权重均标注 **Apache-2.0**；发行时还要携带所用转换包、前处理库及词典的实际许可/声明。[v1.0 模型卡](https://huggingface.co/hexgrad/Kokoro-82M)、[v1.1-zh 模型卡](https://huggingface.co/hexgrad/Kokoro-82M-v1.1-zh)
- Matcha 中文现成模型使用 Baker 数据，官方文档明确该数据 **仅限非商业用途**。不能因为推理库开源就认为该现成模型适合商用。[官方用途提示](https://k2-fsa.github.io/sherpa/onnx/tts/pretrained_models/matcha.html#matcha-icefall-zh-baker-chinese-1-female-speaker)
- Bert-VITS2-MNN 的代码、用户自训练模型和作者提供的 22 kHz 基模要分别看待；作者说明后者使用游戏角色音频，**仅供学习/非商业使用**。上述速度结果对应其特定模块，并不解决替换权重的授权问题。[基模说明](https://github.com/Voine/Bert-VITS2-MNN/blob/master/README_EN.md#-22-khz-base-model)
- MeloTTS 官方说明其库为 MIT；后续若选用具体权重，还需记录对应模型卡与下载来源。[官方许可说明](https://github.com/myshell-ai/MeloTTS#license)

## Kokoro 接入与验证边界

中文路径需完成文本规范化、分词/词典与音素编码，再运行声学模型；不复用 Supertonic 的语言标记编码。仓库中英示例配置了 `dict`、`lexicon-zh.txt`、`lexicon-us-en.txt`、`tokens.txt` 和 `voices.bin`，实际 `.mnn` 文件、音色顺序及配套数据需共同锁定。[现有配置](https://github.com/alibaba/MNN/blob/master/apps/frameworks/sherpa-mnn/c-api-examples/kokoro-tts-zh-en-c-api.c)

Kokoro 的音色和采样率属于自己的模型配置；不沿用 Supertonic F1/M1 的含义或 44,100 Hz。两个引擎复用卡片、串行合成调度和播放器，缓存键包含引擎/音色/语速；按需加载以控制内存。短词语言检测有歧义时允许手动选中文，中英混合输入需单独验证。

完成接入后，在同一部手机、相同线程数与编译优化配置下记录：冷启动出声时间、模型已加载后的短句出声时间、生成耗时/音频时长（RTF）、峰值内存、APK 和安装后数据体积。测试包含短词、多音字、数字日期、标点、中英混合和长段落分块。桌面合成成功、构建成功与安卓真机播放成功分别记录；当前没有候选模型的同机安卓横向测试，不能宣称 Kokoro 已确定最快。

## 本轮接入记录

实际采用 `wangjazz/kokoro-82m-mnn` revision `c161c9db569b944c35e06f5c3042e4c575aee4a9` 的 `tts.mnn`，配套 `csukuangfj/kokoro-multi-lang-v1_0` 固定词典和 ONNX community v1.0 的两个中文音色。没有把 sherpa-onnx/eSpeak 整体链接进 App；只使用现有的 MNN 运行库，通过明确的 `style/input_ids/speed → waveform` Module 接口合成。[转换资产](https://huggingface.co/wangjazz/kokoro-82m-mnn)、[MNN 模块参考](https://github.com/wangzhaode/mnn-tts/blob/main/src/tts.cpp)。

词典最长匹配用于已收录多音词，词内三声变调、日期和常用数值由 Java 处理，整段主要语言为中文时可处理词典已收录的中英混排。未知字符和英文词提示用户，不静默跳过；该方案不承诺所有上下文多音字和专名正确。模型/词典/音色锁文件及完整许可随应用提供，Kokoro 必要资产共 **109,906,786 字节**。

开发机完成中文女声、男声、多音词、数字日期及中英混排的真实 MNN 合成，输出 24 kHz 单声道 PCM16 且能量非零；合成中途取消及随后恢复通过。这证明资产与推理接口可以工作，不代表手机延迟、全部中文语境发音或主观音质已验收。

本轮交付证据：19 项 Java 单元测试、4 项 Python 资产/数据库测试、lint 通过；arm64 APK 及设备测试 APK 构建完成，签名和 16 KB 对齐检查通过。模型内置版历史 APK 约 234.4 MB。环境无连接安卓设备，尚未执行设备端语言检测、JNI 播放及性能测试，因此本轮仍不能对候选模型给出手机速度排名。


## 2026-10-12：资源交付方式调整

用户选择语音资源通过下载获取，不再内置 APK。当前 Kokoro v1.0 的既有权重、词典和两个音色已原样上传至 [ModelScope](https://modelscope.cn/models/dr3334/Kokoro-82M-v1.0-MNN)，固定 commit `e7f9c985f3118427cb6646b9962ee4b91c536b0f`，下载总大小 109,906,786 字节。Supertonic 同样单独下载。App 支持暂停、持久断点续传、大小与 SHA-256 校验；下载完成后离线合成。没有重新转换或量化模型，前述模型选择、前处理与真机验收边界保持有效。[实现与发布记录](../README.md)
