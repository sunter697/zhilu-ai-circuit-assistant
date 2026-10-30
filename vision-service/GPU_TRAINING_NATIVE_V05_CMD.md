# 完整版 v0.5：多尺寸、多缩放 9 类模型训练教程（Windows CMD）

本轮目标是在 v0.4 九类模型基础上增加 Logisim 合法元件尺寸，并通过训练增强覆盖不同界面
缩放。所有命令均按现有目录 `C:\path\to\LogisimVisionTraining` 和 CMD 编写。

## 1. 本轮数据契约

| 内容 | 固定值 |
|---|---:|
| 生成器 | `native-logisim-dataset/0.5` |
| 训练集 | `native-dataset-v05-7500` |
| 训练拓扑 / 图片 | 2500 / 7500 |
| 冻结评估集 | `native-eval-v05-300` |
| 评估拓扑 / 图片 | 100 / 300 |
| 类别 | 9 |
| 二输入门尺寸 | NARROW / MEDIUM / WIDE |
| NOT 尺寸 | NARROW / WIDE |

v0.4 的模型和数据保留作基线，不要覆盖。v0.5 训练集已经排除评估集的 100 个语义拓扑。

固定清单 SHA-256：

```text
训练集 f8318719ea30fe01ad5e0afdcbc77e75bfa75192831221d830fa43c913780b69
评估集 c934966287b52d985b2a047730ff4c52632fb4629d1ec01f3ea6f607a1944dd0
```

## 2. 复制到 GPU 电脑

从项目电脑复制：

```text
vision-service\build\native-dataset-v05-7500\
vision-service\build\native-eval-v05-300\
vision-service\validate_native_dataset.py
```

复制后的结构：

```text
C:\path\to\LogisimVisionTraining\
├── .venv-train\
└── vision-service\
    ├── validate_native_dataset.py
    └── build\
        ├── native-dataset-v05-7500\
        └── native-eval-v05-300\
```

不要只复制 `images` 和 `labels`；`ir`、`manifest.jsonl`、`summary.json` 和 `data.yaml`
都必须复制。训练集应有 22503 个文件，评估集应有 903 个文件。

## 3. 激活现有 CUDA 环境

```cmd
set "TRAIN_ROOT=C:\path\to\LogisimVisionTraining"
set "VISION_ROOT=%TRAIN_ROOT%\vision-service"
call "%TRAIN_ROOT%\.venv-train\Scripts\activate.bat"
cd /d "%VISION_ROOT%"
python --version
where python
nvidia-smi
python -c "import torch; print('torch =', torch.__version__); print('CUDA =', torch.version.cuda); print('available =', torch.cuda.is_available()); print('GPU =', torch.cuda.get_device_name(0) if torch.cuda.is_available() else 'NONE')"
python -m pip show ultralytics
```

`where python` 第一项应是 `C:\path\to\LogisimVisionTraining\.venv-train\Scripts\python.exe`，
并且 `available = True`。

## 4. 完整性校验

训练集：

```cmd
python validate_native_dataset.py --dataset build\native-dataset-v05-7500 --expect-manifest-sha256 f8318719ea30fe01ad5e0afdcbc77e75bfa75192831221d830fa43c913780b69 --exclude build\native-eval-v05-300
```

评估集：

```cmd
python validate_native_dataset.py --dataset build\native-eval-v05-300 --expect-manifest-sha256 c934966287b52d985b2a047730ff4c52632fb4629d1ec01f3ea6f607a1944dd0
```

两个命令都必须输出 `"status": "OK"`。训练集必须显示 2500 个独立拓扑、7500 张图片，
`train / val / test = 5262 / 1125 / 1113`；评估集必须显示 100 个独立拓扑、300 张图片。
训练集 `gateSizes` 应为 `NARROW=12704`、`MEDIUM=11289`、`WIDE=13075`。

复制到另一台电脑后，打开两个 `data.yaml`，将 `path:` 分别改为：

```yaml
path: "D:/LogisimVisionTraining/vision-service/build/native-dataset-v05-7500"
```

和：

```yaml
path: "D:/LogisimVisionTraining/vision-service/build/native-eval-v05-300"
```

评估集原始 YAML 只有 `test`。为了兼容 Ultralytics，再创建
`build\native-eval-v05-300\data-ultralytics.yaml`，内容为：

```yaml
path: "D:/LogisimVisionTraining/vision-service/build/native-eval-v05-300"
train: images/test
val: images/test
test: images/test
names:
  0: INPUT_PIN
  1: OUTPUT_PIN
  2: AND
  3: OR
  4: NOT
  5: NAND
  6: NOR
  7: XOR
  8: XNOR
```

该兼容 YAML 只能用于 `split=test` 的评估，不能用于训练。

## 5. 三轮冒烟训练

先从 v0.4 的最佳权重迁移。九个类别没有变化，检测头可以完整复用：

```cmd
yolo detect train model="%VISION_ROOT%\runs\full-v04-1\weights\best.pt" data="%VISION_ROOT%\build\native-dataset-v05-7500\data.yaml" epochs=3 imgsz=800 batch=4 device=0 workers=2 project="%VISION_ROOT%\runs" name=full-v05-smoke seed=20261028 deterministic=True cache=False degrees=0 translate=0.03 scale=0.30 shear=0 perspective=0 flipud=0 fliplr=0 mosaic=0 mixup=0 plots=True
```

如果上一轮 PT 不在该位置，先改成它的实际路径；若确实没有 PT，再使用 `yolo11n.pt`，不能
使用旧七类权重，也不能用 ONNX 文件继续训练。

冒烟成功标准：完成 3 个 epoch、显示 `CUDA:0`、检测头为九类，并生成
`runs\full-v05-smoke\weights\best.pt`。

## 6. 正式训练

RTX 4060 8 GB 先用：

```cmd
yolo detect train model="%VISION_ROOT%\runs\full-v04-1\weights\best.pt" data="%VISION_ROOT%\build\native-dataset-v05-7500\data.yaml" epochs=120 patience=25 imgsz=800 batch=8 device=0 workers=4 project="%VISION_ROOT%\runs" name=full-v05-1 seed=20261028 deterministic=True save_period=10 cache=False degrees=0 translate=0.05 scale=0.50 shear=0 perspective=0 flipud=0 fliplr=0 mosaic=0 mixup=0 hsv_h=0 hsv_s=0.08 hsv_v=0.08 plots=True
```

这里的 `scale=0.50` 是关键变化，用于在固定 800 像素训练画布内模拟约 50%～150% 的目标
尺度；生成器本身又提供三种门尺寸。运行时网格和端口几何另有 50%～200% 的确定性缩放
支持。200% 端到端能力必须由训练后的缩放回归集确认，不能只看训练 mAP 宣称完成。

显存不足时依次把 `batch=8` 改为 `4`、`2`；Windows 卡在数据加载时把 `workers=4` 改为
`0`。不要开启旋转、翻转、透视、mosaic 或 mixup，它们会制造 Logisim 不可能出现的方向和
拓扑。

中断恢复：

```cmd
yolo detect train model="%VISION_ROOT%\runs\full-v05-1\weights\last.pt" resume=True
```

## 7. 评估、预览与导出

内部测试：

```cmd
yolo detect val model="%VISION_ROOT%\runs\full-v05-1\weights\best.pt" data="%VISION_ROOT%\build\native-dataset-v05-7500\data.yaml" split=test imgsz=800 batch=8 device=0 plots=True project="%VISION_ROOT%\runs" name=full-v05-1-internal-test
```

冻结评估集只能在模型和阈值全部固定后运行一次：

```cmd
yolo detect val model="%VISION_ROOT%\runs\full-v05-1\weights\best.pt" data="%VISION_ROOT%\build\native-eval-v05-300\data-ultralytics.yaml" split=test imgsz=800 batch=8 device=0 plots=True project="%VISION_ROOT%\runs" name=full-v05-1-frozen-eval
```

人工预览：

```cmd
yolo detect predict model="%VISION_ROOT%\runs\full-v05-1\weights\best.pt" source="%VISION_ROOT%\build\native-dataset-v05-7500\images\test" imgsz=800 conf=0.25 device=0 save=True project="%VISION_ROOT%\runs" name=full-v05-1-preview
```

导出：

```cmd
python -m pip install onnx onnxruntime onnxslim
yolo export model="%VISION_ROOT%\runs\full-v05-1\weights\best.pt" format=onnx imgsz=800 batch=1 device=cpu dynamic=False simplify=True
yolo detect predict model="%VISION_ROOT%\runs\full-v05-1\weights\best.onnx" source="%VISION_ROOT%\build\native-dataset-v05-7500\images\test" imgsz=800 conf=0.25 device=cpu save=True project="%VISION_ROOT%\runs" name=full-v05-1-onnx-preview
certutil -hashfile "%VISION_ROOT%\runs\full-v05-1\weights\best.onnx" SHA256
```

## 8. 带回项目电脑

至少复制：

```text
runs\full-v05-1\
runs\full-v05-1-internal-test\
runs\full-v05-1-frozen-eval\
runs\full-v05-1-preview\
runs\full-v05-1-onnx-preview\
```

先放入 `vision-service\build\training\full-v05-1\`，完成 Python、Java、尺寸和缩放回归后再
替换 `vision-service\models\best.onnx`。保留 `best.pt`、`last.pt`、训练参数、GPU 信息和所有
评估图，不要只带回 ONNX。
