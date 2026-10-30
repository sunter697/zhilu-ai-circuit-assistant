# Logisim 原生电路视觉数据生成器

## 1. 目标与状态

该工具是完整版“阶段 B：原生数据生成”的实现。它不再拼接文档素材，而是使用
Logisim 自己的元件工厂、端口定义、`Wire`、导入规划器和电路绘制链路构造训练样本。

当前版本：`native-logisim-dataset/0.5`。

已支持：

- 输入引脚、输出引脚；
- AND、OR、NOT、NAND、NOR、XOR、XNOR；
- 二输入门的 `NARROW`、`MEDIUM`、`WIDE` 三种尺寸，以及 NOT 的 `NARROW`、`WIDE`
  两种合法尺寸；
- 随机组合逻辑树；
- 一个驱动端连接两个接收端的菱形扇出拓扑；
- 由两级“扇出—汇合”组成、包含两个 junction 的受控随机分层 DAG；
- 根据驱动端与最近接收端间距自动选择分支干线位置；
- T 形 junction 及其显式拓扑真值；
- 每个拓扑保留一张 `native-grid` 基准图，并支持浅网格、无网格、关闭抗锯齿、轻度重采样
  和灰白背景五种受控渲染变体；
- 按 `semanticHash` 分组的 `train`、`val`、`test` 划分；
- 全部进入 `test` 的冻结评估集，以及训练集排除评估语义哈希的防泄漏机制；
- PNG、YOLO 标签、Vision IR、JSONL 清单和统计摘要；
- 生成时和生成后的双重校验。

生成器源码位于：

```text
src/visionTools/java/com/cburch/logisim/ai/vision/tools/NativeVisionDatasetTool.java
```

它属于独立的 Gradle `visionTools` source set，不进入 Logisim 正式发行 JAR。

## 2. 为什么必须使用原生生成器

一周版生成器复用项目文档中的门电路图片，适合快速训练原型，但图片中的元件边界、引脚
外观、网格、文字和导线连接点与真实编辑器画面仍有差异。原生生成器直接创建可被 Logisim
理解的电路对象，再调用原生绘制链路输出图片，因此图像、端口和拓扑真值来自同一个电路
对象，能够避免“看起来连上了，但真值坐标没有连上”的标注错误。

## 3. CMD 使用方法

以下命令均在项目根目录执行。JDK 路径应按本机实际安装位置调整。

### 3.1 编译工具

```bat
set "JAVA_HOME=C:\Program Files\Java\jdk-23"
set "PATH=%JAVA_HOME%\bin;%PATH%"
gradlew.bat visionToolsClasses --no-daemon --console=plain
```

### 3.2 生成试验数据

先生成 100 个不同语义拓扑，每个拓扑 2 个渲染变体，共 200 张图片：

```bat
gradlew.bat generateVisionDataset --args="--output vision-service\build\native-dataset-100 --topologies 100 --variants 2 --seed 20261024" --no-daemon --console=plain
```

输出目录必须不存在。该限制用于防止误覆盖已经用于训练或评估的数据。

### 3.3 单独验证已有数据集

```bat
gradlew.bat generateVisionDataset --args="--validate-only vision-service\build\native-dataset-100" --no-daemon --console=plain
```

验证成功时输出 `"status": "OK"`，并列出样本数、独立语义拓扑数、每个拓扑的变体数、
数据划分和类别统计。

### 3.4 生成 500 张试生产集

生成 250 个语义拓扑、每个 2 个变体，共 500 张：

```bat
gradlew.bat generateVisionDataset --args="--output vision-service\build\native-dataset-500 --topologies 250 --variants 2 --seed 20261024" --no-daemon --console=plain
```

### 3.5 生成冻结评估集

先生成 100 个独立拓扑、每个 3 个变体的评估集。`--evaluation` 会把全部图片放入
`images/test`，并且 `data.yaml` 只提供 `test`：

```bat
gradlew.bat generateVisionDataset --args="--output vision-service\build\native-eval-v05-300 --topologies 100 --variants 3 --seed 20261028 --evaluation" --no-daemon --console=plain
```

该目录本身就是训练生成时的排除源。冻结后不得根据它反复调参，也不得重新生成同名目录。

### 3.6 扩大到正式训练规模

在 500 张试生产通过自动检查，并按三个拓扑家族分别完成人工抽查后，再生成 2500 个语义
拓扑、每个 3 个变体，总计 7500 张：

```bat
gradlew.bat generateVisionDataset --args="--output vision-service\build\native-dataset-v05-7500 --topologies 2500 --variants 3 --seed 20261027 --exclude-dataset vision-service\build\native-eval-v05-300" --no-daemon --console=plain
```

`--exclude-dataset` 可接收完整数据集目录、完整 `manifest.jsonl` 或只含 `semanticHash` 的
JSONL。不要省略该参数，也不要直接跳过小数据集检查。

### 3.7 生成带真值框的人工抽查表

下面的命令从 100 拓扑试生产集中固定抽取 30 张，并生成一张联系表：

```bat
py -3.12 vision-service\preview_dataset.py --dataset vision-service\build\native-dataset-100 --output vision-service\build\native-dataset-100-preview.png --count 30 --seed 20261024
```

抽查脚本同时兼容一周版和原生生成器清单，能够显示 XOR、XNOR 的彩色真值框以及原生数据的
拓扑编号和变体编号。输出文件必须不存在，以免覆盖上一轮人工检查证据。

使用 `--family` 可以只抽查一个拓扑家族。例如专门检查多级 DAG：

```bat
py -3.12 vision-service\preview_dataset.py --dataset vision-service\build\native-dataset-500 --output vision-service\build\native-dataset-500-layered-preview.png --count 30 --seed 20261024 --family layered-dag
```

当前可筛选的原生家族为 `tree`、`diamond-fanout` 和 `layered-dag`。

使用 `--render-profile` 可以专门检查某一种渲染变化：

```bat
py -3.12 vision-service\preview_dataset.py --dataset vision-service\build\native-dataset-v05-7500 --output vision-service\build\native-v05-soft-preview.png --count 30 --seed 20261028 --render-profile soft-resample
```

可用配置为 `native-grid`、`light-grid`、`gridless`、`crisp-grid`、`soft-resample` 和
`off-white-grid`。

### 3.8 在训练电脑上执行便携校验

`validate_native_dataset.py` 只使用 Python 标准库，不要求训练电脑安装 Java 或复制整个
Logisim 工程。正式训练前执行：

```bat
python vision-service\validate_native_dataset.py --dataset vision-service\build\native-dataset-v05-7500 --expect-manifest-sha256 f8318719ea30fe01ad5e0afdcbc77e75bfa75192831221d830fa43c913780b69 --exclude vision-service\build\native-eval-v05-300
```

它会复核每张图片 SHA-256、YOLO 与 IR 主体框、类别、计数、数据分组、渲染配置、摘要以及
冻结语义排除。输出 `"status": "OK"` 后才可开始训练。GPU 电脑的完整命令见
[`../vision-service/GPU_TRAINING_NATIVE_V05_CMD.md`](../vision-service/GPU_TRAINING_NATIVE_V05_CMD.md)。

## 4. 输出结构

```text
native-dataset-100/
├── images/
│   ├── train/
│   ├── val/
│   └── test/
├── labels/
│   ├── train/
│   ├── val/
│   └── test/
├── ir/
│   ├── train/
│   ├── val/
│   └── test/
├── data.yaml
├── manifest.jsonl
└── summary.json
```

- `images`：800×520 的 Logisim 原生浅色 ANSI 电路图；
- `labels`：9 类 YOLO 主体框标签；
- `ir`：包含真实元件、端口、线段、net 和 junction 的 `vision-ir/0.1`；
- `manifest.jsonl`：每张图片的种子、语义哈希、数据角色、渲染配置、路径和 SHA-256；
- `summary.json`：生成规模、候选拒绝原因、拓扑家族、数据分组、类别和三种门尺寸统计；
- `data.yaml`：训练时使用的数据集配置。

类别编号固定为：

| 编号 | 类别 |
|---:|---|
| 0 | INPUT_PIN |
| 1 | OUTPUT_PIN |
| 2 | AND |
| 3 | OR |
| 4 | NOT |
| 5 | NAND |
| 6 | NOR |
| 7 | XOR |
| 8 | XNOR |

## 5. 自动保证

生成期间，每个候选样本依次经过：

1. Vision IR 字段、坐标、端口和引用校验；
2. `CircuitImportPlanner` 的网格、连通性、单驱动、多接收端、跨 net 接触和穿过元件检查；
3. `CircuitImportBuilder` 的 Logisim 原生元件与 `Wire` 构建；
4. Logisim 原生绘制链路输出 PNG；
5. 图片 SHA-256、YOLO 行数、类别范围和 IR 对应关系复核；
6. 清单中的元件、net、junction 数量必须与 IR 实际内容一致；
7. `tree`、`diamond-fanout`、`layered-dag` 必须分别包含 0、1、2 个 junction；
8. 相同 `semanticHash` 的所有变体必须位于同一个数据分组。

随机布局不满足几何约束时，候选会被整体拒绝，不会写入数据集。拒绝数量和原因记录在
`summary.json`，便于后续发现生成器偏差。

Gradle 任务为该工具设置了独立的 Java Preferences 根目录，并固定 ANSI 门形状、引脚外观、
元件颜色和自动保存选项。因此生成过程不会读取或改写用户平时使用 Logisim 时的界面偏好。

## 6. 已完成冒烟验证

固定种子 `20261027` 已生成并校验 12 个独立语义拓扑、每个 3 个变体，共 36 张图片：

- 9 组随机树形拓扑；
- 3 组带 T 形 junction 的菱形扇出拓扑；
- 36/36 图片、YOLO 标签和 Vision IR 一致；
- 12/12 语义哈希没有跨集合泄漏；
- 9 个元件类别均在样本中出现；
- 门尺寸统计为 `NARROW=59`、`MEDIUM=34`、`WIDE=57`；
- 使用真值框回放组件、导线和端口后，36/36 拓扑无未解析错误且 net 端点完全一致；
- 候选拒绝数为 0。
- 使用相同参数再次生成后，对比 75 个冒烟输出文件，SHA-256 差异为 0。

冒烟数据写在 Gradle `build` 目录中，只用于本地验证，不作为源码提交内容。

### 6.1 100 个拓扑试生产

在冒烟验证之后，固定种子又完成了 100 个独立语义拓扑、每个 2 个变体的试生产：

| 指标 | 结果 |
|---|---:|
| 图片总数 | 200 |
| 独立语义拓扑 | 100 |
| 树形拓扑 | 81 |
| 菱形扇出拓扑 | 19 |
| train | 152 |
| val | 26 |
| test | 22 |
| 候选尝试 | 105 |
| 几何/拓扑校验拒绝 | 0 |
| 输出文件 | 603 |
| 输出体积 | 约 3.82 MB |

9 个类别均有样本，且 200/200 图片通过独立验证模式。5 次额外候选尝试来自语义哈希去重，
不是无效电路。该结果证明第一版生成器能够稳定扩大到百级拓扑；它不能代替下一步的人工
标注抽查，也不能直接证明 5000 张规模不存在类别或拓扑分布偏差。

使用当前最终代码和相同参数完整重复百级试生产后，两份数据集各包含 603 个文件；逐文件
比较 SHA-256，差异为 0。

本轮已另外生成固定抽样的 30 张真值框联系表并完成初步视觉检查，未发现明显的元件框偏移、
断线或错误 junction。正式进入 5000 张生产前，仍应对不同拓扑家族进行分层抽样，而不是
只做一次随机抽样。

### 6.2 第二增量和 500 张试生产

版本 `0.2` 新增受控随机 `layered-dag`。每个该类拓扑串联两个“扇出—汇合”阶段，包含
5 个输入引脚、6 个二输入门、1 个输出引脚、11 个 net 和 2 个显式 junction。它不是任意
DAG，而是能够被安全正交布线、可由 Logisim 原生重建并能自动证明真值一致的串并联 DAG。

固定种子 `20261024` 的 500 张试生产结果如下：

| 指标 | 结果 |
|---|---:|
| 图片总数 | 500 |
| 独立语义拓扑 | 250 |
| 树形拓扑 | 163 |
| 单级菱形扇出 | 42 |
| 双层 DAG | 45 |
| train / val / test | 342 / 72 / 86 |
| junction 总数 | 264 |
| 候选尝试 | 279 |
| 几何/拓扑校验拒绝 | 0 |
| 输出文件 | 1503 |
| 输出体积 | 约 9.56 MB |

29 次额外候选尝试均由语义哈希去重造成。增强验证器对 500 张图片重新检查了清单计数、图片
哈希、YOLO、IR、分组和各家族 junction 数量，结果为 `OK`。另外按三个家族各随机抽取
30 张生成真值框联系表，共人工检查 90 张，未发现明显断线、跨 net 接触、错误 junction 或
主体框偏移。

版本 `0.2` 的 50 拓扑冒烟集使用相同参数完整生成两次，两份数据各 303 个文件；逐文件
SHA-256 差异为 0。

### 6.3 第三增量：受控渲染变化

版本 `0.3` 规定每个拓扑的 `variant=0` 必须使用 `native-grid`，其余变体从五种增广配置中
确定性选择。所有变化都保持 800×520 画布和电路坐标不变，因此不需要伪造或近似变换
YOLO、端口和线段真值。`soft-resample` 只把整张图缩小到 88% 后恢复原尺寸，用于模拟轻度
截图缩放，不做裁剪或坐标移动。

30 个拓扑、每个 6 个变体的冒烟集共 180 张，覆盖全部六种配置并通过增强验证。相同参数
生成两次，两份数据各 543 个文件，逐文件 SHA-256 差异为 0。按六种配置各抽查 12 张，
未发现明显框漂移或线路失真。

### 6.4 第四增量：冻结评估集和正式训练集

版本 `0.4` 新增 `--evaluation` 和 `--exclude-dataset`。冻结评估集结果：

| 指标 | 结果 |
|---|---:|
| 独立拓扑 / 图片 | 100 / 200 |
| tree / diamond-fanout / layered-dag | 59 / 21 / 20 |
| junction | 122 |
| 数据分组 | test 200 |
| 输出文件 / 体积 | 603 / 约 4.05 MB |

正式训练集已使用轻量排除清单生成并通过二次校验：

| 指标 | 结果 |
|---|---:|
| 独立拓扑 / 图片 | 2500 / 5000 |
| train / val / test | 3550 / 750 / 700 |
| tree / diamond-fanout / layered-dag 图片 | 3316 / 208 / 1476 |
| junction | 3160 |
| 候选尝试 / 语义重复 / 命中排除 | 3767 / 991 / 276 |
| 几何或拓扑拒绝 | 0 |
| 输出文件 / 体积 | 15003 / 约 111.23 MB |

六种渲染配置分别有 2500、500、502、493、501、504 张，增广分布均衡。训练集与冻结评估
集的语义哈希交集为 0。正式集 `manifest.jsonl` 的 SHA-256 为：

```text
28230c17c230b00a1c624278a2b20b514b591a75f5d49a4bbb89c7525bcec0d9
```

已按三个拓扑家族各抽查 30 张，并按六种渲染配置各抽查 12 张，共生成 9 张联系表、162 个
抽查位置，未发现明显断线、跨 net 接触、错误 junction 或主体框偏移。冻结规则和排除清单
详见 [`../vision-service/evaluation/README.md`](../vision-service/evaluation/README.md)。

## 7. 当前限制与下一增量

当前版本已完成阶段 B 的数据生产工作：

- 只有浅色主题、ANSI、向右、单比特和固定画布；
- 原生渲染暂不显示仿真信号颜色；
- 多级拓扑是可证明安全的两级串并联 DAG，尚未覆盖任意随机 DAG；
- 当前 Vision IR 和 Logisim 交互式布线语义不能可靠表达“视觉相交但电气不连接”的普通
  十字线，因此原生合法样本不生成这种歧义图形；它应作为后续外部图片难例单独标注和评估；
- 已覆盖若干不改变坐标的浅色渲染变化，但尚未覆盖深色主题、真实窗口裁剪、任意缩放、
  JPEG 压缩、手机照片和手绘图；
- 冻结评估集仍由同一原生生成器产生，不能替代真实截图独立基准；
- 5000 张数据已经就绪，但尚未训练和评估新的 9 类模型。

下一阶段进入模型训练：使用正式训练集训练新的 9 类检测器，在冻结原生评估集上只做一次
最终评估，并另建真实截图基准。模型指标通过后，再扩展 Java 导线提取和全 Java ONNX 推理。
