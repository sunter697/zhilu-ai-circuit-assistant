# 独立真实截图基准使用说明

## 1. 目的

旧的 `tests/fixtures/real-logisim/T01` 至 `T20` 已参与轮廓阈值校准，只能作为回归集，
不能用于证明模型对新图片的泛化能力。本基准要求在模型、置信度阈值、NMS、导线阈值和
拓扑规则全部冻结后，再独立采集图片和标注真值。

评测器会分别输出：

- 各类别元件 TP、FP、FN、Precision、Recall 和 F1；
- 元件数量整图正确率和 8 像素范围内的定位正确率；
- net Precision、Recall 和 F1；
- junction F1；
- 无未解决错误的图片比例；
- 整图 exact graph match；
- 单张平均识别耗时；
- 每张失败图片的分层诊断。

## 2. 不能违反的独立性规则

1. 先冻结 `best.onnx`、模型 SHA-256 和全部识别阈值，再采集图片。
2. 新图片不能进入训练、验证、数据增强素材或阈值选择。
3. 标注完成并冻结后才能第一次运行最终评测。
4. 查看最终结果后不得针对该批图片调整模型或规则；如果进行了调整，这批图片自动降级为
   回归集，必须另采一批新的独立留出集。
5. 不得把 T01–T20、Day 6 图片或原生生成器图片复制进此目录。

## 3. 推荐覆盖范围

正式基准至少 20 张，推荐 30 至 50 张，并满足：

- 9 类元件全部出现：INPUT_PIN、OUTPUT_PIN、AND、OR、NOT、NAND、NOR、XOR、XNOR；
- 至少 5 张包含扇出，至少 5 张包含 junction；
- 单级、双层和三层组合逻辑均有覆盖；
- 元件数量覆盖 4、8、12、20 和 25 个附近；
- 记录导出 PNG、画布截图、软件版本、主题和缩放比例；
- 第一版只纳入当前正式支持的浅色主题、ANSI 门、正向、正交单比特电路。

深色主题、IEC 门、旋转元件、总线、时序元件、照片和手绘图应建立单独基准，不能混入后
再用一个总分掩盖各域差异。

第一批自动布局截图采用已经过 Logisim 自身解析器和生成器验证的 R001–R022 共 22 条表达式；
R023–R026 是超过 6 输入后界面停用表达式页的可选压力用例，
见 [`REAL_HOLDOUT_EXPRESSIONS.md`](REAL_HOLDOUT_EXPRESSIONS.md)。

## 4. 目录格式

新建一个不提交训练目录的文件夹，例如：

```text
vision-service/build/real-holdout-v01/
  manifest.json
  images/
    R001.png
    R002.png
  annotations/
    R001.json
    R002.json
```

`manifest.json` 参考
[`evaluation/real-holdout-manifest.example.json`](evaluation/real-holdout-manifest.example.json)，
机器可读规则位于
[`evaluation/real-holdout-manifest.schema.json`](evaluation/real-holdout-manifest.schema.json)。

每个 annotation 使用 `vision-ir/0.1`。其中：

- `source.sha256` 必须等于对应 PNG 的 SHA-256；
- `source.width` 和 `source.height` 必须等于 PNG 尺寸；
- `components` 的类别、锚点和端口必须人工复核；
- `nets` 的端点和 junction 必须根据源电路人工复核；
- 真值中的 `unresolved` 必须为空。

清单中的 `expectedRuntime` 还会把本次实验绑定到固定的服务版本、模型 SHA-256 和检测模式。
只要模型文件或运行算法版本发生变化，就必须将旧集合降级为回归集，并为新的最终评测采集
新留出集。

本批图片有保存的 `.circ` 源工程时，应先用源工程生成真值草稿，不要调用识别模型：

```bat
python prepare_real_holdout.py ^
  --dataset build\real-holdout-v01 ^
  --source-project build\real-holdout-v01\source\real_holdout_v01.circ
```

该命令从 `.circ` 读取元件、端口和电气网络，并用背景网格与绿色导线进行确定性坐标对齐；
它只生成 `manifest.draft.json`、`annotations` 和 `review-overlays`，不会生成正式
`manifest.json`，也不会运行学习模型。仍须逐张检查叠加图、元件类型、端口、net 和 junction，
确认后才能冻结。没有源工程时可以先运行识别得到 IR 草稿，但必须人工逐项修正，不能把模型
输出原样当作真值。

## 5. 计算冻结哈希

在 `vision-service` 目录的 CMD 中执行：

```bat
certutil -hashfile build\real-holdout-v01\images\R001.png SHA256
certutil -hashfile build\real-holdout-v01\annotations\R001.json SHA256
```

将两条命令输出的 64 位小写十六进制值分别填入 `imageSha256` 和
`expectedIrSha256`。全部标注和清单完成后，将 `frozen` 设为 `true`，填写 `frozenAt`，此后
不要再修改图片、标注或清单。

## 6. 运行最终评测

先激活训练/评测环境，然后在 `vision-service` 目录执行：

```bat
python evaluate_real_holdout.py ^
  --dataset build\real-holdout-v01 ^
  --output evaluation\real-holdout-v01-result.json
```

默认要求至少 20 张图片，使用冻结的 9 类 ONNX 模型，并以 8 像素作为元件锚点和 junction
匹配容差。输出文件已存在时脚本会拒绝覆盖，避免静默替换历史结果。

开发清单验证可以临时使用 `--minimum-cases 1`，但这种结果的 `acceptance.eligible` 必须为
`false`，不能写入正式报告。

## 7. 正式验收条件

只有同时满足以下前提，`acceptance.passed` 才会给出布尔值：

- 至少 20 张；
- 9 类元件全部出现；
- 数据中至少存在一个 junction。

当前目标为：各已表示类别 Recall 不低于 98%，net F1 不低于 98%，junction F1 不低于
95%，exact graph match 不低于 90%，平均 CPU 耗时不超过 5 秒/图。若数据覆盖不足，
`acceptance.passed` 为 `null`，即使已有图片全部识别正确也不能宣称通过正式验收。
