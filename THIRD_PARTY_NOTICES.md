# Third-party notices

本文件记录“智路：自然语言与视觉驱动的电路生成与编辑”直接使用或随仓库分发的主要
第三方项目、模型与运行库。它用于来源披露，不替代各组件自身的许可证文本，也不构成法律意见。

## 基础项目

- **Logisim-evolution** — 本项目的基础代码与桌面电路仿真平台。
  上游仓库：<https://github.com/logisim-evolution/logisim-evolution>；许可证：GNU GPL v3。
  本仓库保留了 `LICENSE.md`、`README_UPSTREAM.md`、源码版权头和 `docs/credits.md`。
- 原项目所使用的 Java 库及其版本以 `build.gradle.kts` 为准；上游贡献者与鸣谢信息以
  `README_UPSTREAM.md` 和 `docs/credits.md` 为准。

## 视觉模型与推理

- **Ultralytics YOLO / YOLO11n** — 版本 8.4.158 用于训练与导出本项目的九类元件检测模型。
  Ultralytics 官方提供 AGPL-3.0 与企业许可两种路径。仓库内的 ONNX 权重由本项目训练，
  但使用者仍应根据自己的分发和商业使用方式核对 Ultralytics 对代码与模型的适用条款。
  默认运行链路不安装也不导入 Ultralytics。
- **ONNX Runtime** — Java 与可选 Python 推理运行时；许可证：MIT。
- **ONNX 模型文件** — `vision-service/models/best.onnx` 与
  `vision-service/models/legacy-week1.onnx`。来源、适用范围、评测口径和哈希见
  `vision-service/models/MODEL_CARD.md`。

## Python 运行时依赖

正式冻结版本及其直接/传递依赖记录在 `vision-service/requirements-runtime.txt` 和
`vision-service/RELEASE_MANIFEST.json`，包括 NumPy（BSD）、Pillow（HPND）、
ONNX Runtime（MIT）、FlatBuffers（Apache-2.0）、Protocol Buffers（BSD）与 Packaging
（Apache-2.0/BSD）。具体版权和许可证文本以各软件包发行物为准。

## 外部 AI 接口

本仓库不捆绑任何云端大模型、服务商账号或 API 密钥。AI 对话与工具调用通过用户自行配置的、
兼容 OpenAI Chat Completions 和 `tool_calls` 的接口完成；该外部服务的条款、费用和数据政策
由用户选择的服务商决定。

## 仓库许可边界

除单独标注的第三方组件、模型和素材外，本衍生项目源码按照根目录 `LICENSE.md` 所示的
GNU GPL v3 发布。第三方许可证与原始版权声明继续有效，根目录许可证不会覆盖或替代它们。
