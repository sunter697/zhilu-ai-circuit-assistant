# 完整版视觉评测与冻结证据

本目录只保留 `full-v09/0.1` 发布仍需引用的冻结评测、回归结果、真实留出协议和语义排除
清单。历史训练中间结果已移出公开源码包。

## 1. 文件说明

| 文件 | 作用 |
| --- | --- |
| `full-v05-1-component-evaluation.json` | 正式九类 ONNX 的冻结元件评测 |
| `full-v04-topology-internal-test.json` | 拓扑层内部隔离验证；文件名保留历史版本以维持发布哈希 |
| `full-v08-1-native-v05-end-to-end-regression.json` | 正式 ONNX、导线与拓扑的端到端修复后回归 |
| `full-v09-native-v05-scale-regression.json` | 50%–200% 统一缩放修复后回归 |
| `native-e2e-v04-eval-200-exclusion.jsonl` | 冻结端到端语义排除清单；被发布清单校验 |
| `real-holdout-v01-result.json` | 真实截图留出集基准结果 |
| `real-holdout-v01-regression-v07-profile-topology.json` | 当前真实截图配置路由与拓扑回归 |
| `real-holdout-expressions-v01.json` | 真实截图用例表达式定义 |
| `real-holdout-manifest.schema.json` | 留出集清单 JSON Schema |
| `real-holdout-manifest.example.json` | 留出集清单示例 |

其中部分文件仍带 `v04`、`v05` 名称，因为它们是冻结证据链中的稳定标识。只要
`RELEASE_MANIFEST.json` 仍引用这些文件，就不能仅为名称整洁而重命名或删除。

## 2. 正式模型与元件评测

正式模型 `models/best.onnx` 来自 `full-v05-1`，输入为 `1 × 3 × 800 × 800`，输出九类
元件。冻结原生集合与全部 2,500 个训练语义拓扑无交集。

| 指标 | 结果 |
| --- | ---: |
| 图片 / 独立语义拓扑 | 300 / 100 |
| TP / FP / FN | 2769 / 0 / 0 |
| 图片级完全匹配 | 300 / 300 |
| 平均匹配框 IoU | 0.999854 |

结果文件：`full-v05-1-component-evaluation.json`。

## 3. 拓扑隔离验证

`full-v04-topology-internal-test.json` 使用真值元件框把拓扑错误与检测框错误分离，验证黑色
导线、蓝色引脚引线、扇出、junction、网络端点和单位线段恢复。它属于算法层内部证据，
不能替代独立真实截图验收。

## 4. 原生端到端回归

正式九类 ONNX、配置路由、导线提取与拓扑组装在冻结 v0.5 集合上执行完整链路：

| 指标 | 结果 |
| --- | ---: |
| 图片 | 300 |
| 无 unresolved | 300 / 300 |
| exact graph match | 300 / 300 |
| CPU 平均耗时 | 约 557.89 ms/图 |

该集合参与过模板误合并和门体边缘短线问题定位，因此结果文件
`full-v08-1-native-v05-end-to-end-regression.json` 明确标记为 `post-fix-regression`，
`acceptanceEligible=false`。

## 5. 多尺度回归

36 张多尺寸原生图片分别按 50%、60%、80%、120%、160%、200% 缩放，随后执行真实 ONNX
推理和完整拓扑恢复，不使用真值元件框。

| 指标 | 结果 |
| --- | ---: |
| 识别运行 | 216 |
| 无 unresolved | 216 / 216 |
| exact graph match | 216 / 216 |
| 最大锚点误差 | 约 0.331 像素 |

结果文件：`full-v09-native-v05-scale-regression.json`。该集合也参与过问题定位，因此属于
修复后回归，不作为独立泛化成绩。

## 6. 真实截图留出协议

真实留出集必须声明 `datasetRole=independent-holdout`，并冻结图片与标注 SHA-256。评测器
分别报告元件类别、主体框、网络、junction、整图 exact graph match 和耗时。采集、冻结和
运行方法见 [`../REAL_HOLDOUT_BENCHMARK.md`](../REAL_HOLDOUT_BENCHMARK.md)。

T01–T20 历史截图已参与算法调试，只能作为回归集；新的泛化结论必须使用模型和阈值冻结后
另行采集、未参与调参的图片。

## 7. 完整性校验

项目根目录执行：

```bat
vision-service\build\release-env\Scripts\python.exe vision-service\verify_release.py
```

发布清单会校验本目录中被引用结果的字节数与 SHA-256。修改冻结结果后必须同步生成新的发布
版本和清单，不能静默覆盖历史指标。
