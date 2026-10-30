# Logisim Circuit Vision（完整版 full-v09）

本目录是“智路”项目的电路图识别模块，负责元件检测、导线提取、拓扑恢复、冻结评测和
可选的 Python HTTP 服务。正式桌面程序默认在 Java 进程内通过 ONNX Runtime 推理，不要求
用户安装 Python；Python 服务主要用于算法调试、回归验证和兼容旧链路。

## 1. 当前发布能力

- 识别 INPUT、OUTPUT、AND、OR、NOT、NAND、NOR、XOR、XNOR 九类元件；
- 支持 Logisim ANSI 风格、浅色背景、单比特组合逻辑和正交导线；
- 恢复点到点网络、合法扇出、T 形 junction，以及交叉但不连接的网络；
- 对 50%、60%、80%、120%、160%、200% 统一缩放执行完整 ONNX 与拓扑回归；
- 输出稳定的 `vision-ir/0.1`，由 Java 端继续执行审核、规划、结构校验和事务化导入；
- 无法可靠恢复的图片返回结构化 `unresolved`，不会静默生成电气连接错误的电路。

正式模型为 `models/best.onnx`，来自 `full-v05-1` 训练；输入为
`1 × 3 × 800 × 800`。模型训练在第 32 个 epoch 早停，最佳权重来自第 7 个 epoch。

`models/legacy-week1.onnx` 虽保留了历史文件名，但不是废弃文件。完整版的配置路由器仅在
检测到旧版深绿色导线截图调色板时调用它，以避免正式九类模型在旧截图引脚域上的回归。
模型来源、哈希、评测边界和许可说明见 [`models/MODEL_CARD.md`](models/MODEL_CARD.md)。

## 2. 目录结构

```text
vision-service/
├── src/logisim_vision/       Python 识别、拓扑与服务实现
├── models/                   正式 ONNX 与兼容模型
├── evaluation/               当前冻结评测证据和数据协议
├── tests/                    单元、回归和端到端测试
├── RELEASE_MANIFEST.json     发布版本、环境、模型与制品哈希
├── requirements-runtime.txt 冻结运行依赖
├── verify_release.py         发布完整性校验
└── start_service.cmd         可选 HTTP 服务启动器
```

`tests/fixtures/day6` 同样是冻结兼容资产：其路径已被发布清单、演示条目和端到端回归引用。
目录名不代表当前仍停留在阶段版本，保留名称是为了维持已发布哈希与可追溯性。

`generate_dataset.py`、`preview_dataset.py` 和 `src/logisim_vision/dataset.py` 是旧截图兼容回归
使用的确定性小型生成器。正式模型训练数据由项目的 Java 原生生成器生产，二者用途不同。

## 3. 运行环境与发布校验

冻结发布环境使用 Python 3.12。在项目根目录的 CMD 中执行：

```bat
py -3.12 -m venv vision-service\build\release-env
vision-service\build\release-env\Scripts\python.exe -m pip install -r vision-service\requirements-runtime.txt
vision-service\build\release-env\Scripts\python.exe vision-service\verify_release.py
```

校验器会检查 Python 版本、冻结依赖、两个模型、评测 JSON、回归图片和服务启动器的
字节数与 SHA-256。全部通过时输出 `RELEASE CHECK PASSED`。

## 4. 可选 HTTP 服务

默认桌面版不需要启动 Python 服务。需要调试 Python 路径时，在项目根目录运行：

```bat
vision-service\start_service.cmd
```

服务只监听 `127.0.0.1:8765`：

- `GET /health`：返回服务、协议、模型和检测模式；
- `POST /recognize`：接收字段名为 `image` 的 PNG multipart 上传，返回 `vision-ir/0.1`。

同时启动多个服务实例会导致端口冲突，启动器会直接报错，不会让请求随机落到旧进程。

## 5. 正式原生数据与训练

正式训练数据由 `src/visionTools` 中的 Java 原生渲染生成器生产，直接复用 Logisim 元件
外观和引脚几何。v0.5 数据覆盖九类元件、二输入门 NARROW/MEDIUM/WIDE、NOT
NARROW/WIDE、三类拓扑和六种受控渲染配置。

- 训练集：2,500 个独立拓扑、7,500 张图片；
- 冻结评估集：100 个独立拓扑、300 张图片；
- 训练与评估语义哈希交集：0；
- 训练清单 SHA-256：
  `f8318719ea30fe01ad5e0afdcbc77e75bfa75192831221d830fa43c913780b69`；
- 评估清单 SHA-256：
  `c934966287b52d985b2a047730ff4c52632fb4629d1ec01f3ea6f607a1944dd0`。

生成器设计见
[`../docs/vision-native-dataset-generator.md`](../docs/vision-native-dataset-generator.md)，当前 GPU
训练命令见 [`GPU_TRAINING_NATIVE_V05_CMD.md`](GPU_TRAINING_NATIVE_V05_CMD.md)，远程服务器
流程见 [`GPU_TRAINING_REMOTE_SERVER.md`](GPU_TRAINING_REMOTE_SERVER.md)。迁移数据后先使用
`validate_native_dataset.py` 校验图片哈希、YOLO 标签、Vision IR、数据划分和排除清单。

## 6. 当前评测证据

机器可读结果统一保存在 [`evaluation/`](evaluation/)：

- 元件冻结评测：300/300 图片完全匹配，TP=2769、FP=0、FN=0，平均匹配框 IoU
  约 0.999854；
- 原生端到端修复后回归：300/300 exact graph，300/300 无 unresolved，CPU 平均约
  558 ms/图；
- 多尺度修复后回归：六个缩放倍率共 216/216 exact graph，最大锚点误差约 0.331 像素；
- 独立真实截图协议：校验用途声明、图片与标注哈希，并分别统计元件、网络、junction、
  exact graph 和耗时。

端到端与多尺度集合参与过问题定位，所以清单将它们标记为 `post-fix-regression` 且
`acceptanceEligible=false`。这些数字是确定性回归证据，不应表述为开放世界电路图的无偏
泛化成绩。完整口径见 [`evaluation/README.md`](evaluation/README.md)。

## 7. 测试

在项目根目录准备冻结运行依赖和测试扩展：

```bat
py -3.12 -m venv vision-service\build\test-env
vision-service\build\test-env\Scripts\python.exe -m pip install -r vision-service\requirements-runtime.txt -e ".\vision-service[test]"
vision-service\build\test-env\Scripts\python.exe -m pytest vision-service\tests -q
```

Java 侧 AI 与视觉集成测试：

```bat
gradlew.bat test --tests "com.cburch.logisim.ai.*" --tests "com.cburch.logisim.gui.ai.*"
```

## 8. 支持边界

当前实现面向受控 Logisim 数字组合电路，不是通用 PCB、模拟原理图、手绘图或安全关键型
识别系统。旋转元件、深色主题、总线、任意第三方符号、照片透视、严重压缩和高密度交叉可能
需要人工修正或被拓扑校验拒绝。识别结果必须经过审核窗口确认后再导入。
