# Logisim Circuit Vision：远程 NVIDIA 服务器训练教程

本文适用于以下环境：

- 本地电脑：Windows，使用 CMD；
- 远程服务器：Linux，能够通过 SSH 登录；
- 服务器具有一张 NVIDIA GPU；
- 使用 Python 3.10 至 3.13；
- 训练数据为 `vision-service/build/dataset-2000`；
- 模型使用固定版本 `ultralytics 8.4.158` 和 `YOLO11n`。

本文不会要求在服务器上安装 Java 或构建整个 Logisim 项目。服务器只需要 Python 训练代码、
数据集和模型输出目录。

## 1. 准备服务器登录信息

需要从服务器提供方获得：

- 服务器地址或 IP；
- SSH 用户名；
- SSH 端口，通常是 `22`；
- 密码或 SSH 私钥；
- GPU 型号和服务器计费方式；
- 数据盘是否会在关机或释放实例后保留。

不要把密码、私钥或云平台密钥写进项目、训练命令、Markdown 或 Git。

在本地 Windows CMD 中先检查 SSH 客户端：

```cmd
ssh -V
scp
```

如果 `ssh` 不存在，需要在 Windows“可选功能”中安装 OpenSSH Client，也可以使用 WinSCP
上传和下载文件。

## 2. 设置本地 CMD 变量

下面假设：

- 本地项目位于 `C:\path\to\logisim-ai-circuit-assistant`；
- SSH 用户名为 `ubuntu`；
- 服务器地址为 `203.0.113.10`；
- SSH 端口为 `22`。

在本地 CMD 中执行，并把用户名、地址、端口替换为真实值：

```cmd
set "LOCAL_PROJECT=C:\path\to\logisim-ai-circuit-assistant"
set "SERVER_USER=ubuntu"
set "SERVER_HOST=203.0.113.10"
set "SERVER_PORT=22"
```

测试登录：

```cmd
ssh -p %SERVER_PORT% %SERVER_USER%@%SERVER_HOST%
```

首次连接会询问是否信任服务器指纹。应先与服务器提供方给出的指纹核对，确认后输入
`yes`。登录成功后执行：

```bash
exit
```

返回本地 CMD。

如果使用私钥，可在 SSH 和 SCP 命令中增加：

```text
-i "C:\你的路径\server-key.pem"
```

例如：

```cmd
ssh -i "C:\path\to\server-key.pem" -p %SERVER_PORT% %SERVER_USER%@%SERVER_HOST%
```

## 3. 在本地制作训练压缩包

训练包只包含数据生成与校验代码、Python 包和正式数据集，不包含整个 Java 工程。

```cmd
cd /d "%LOCAL_PROJECT%"
tar -czf "%USERPROFILE%\Desktop\logisim-vision-training.tar.gz" -C vision-service pyproject.toml generate_dataset.py preview_dataset.py GPU_TRAINING_REMOTE_SERVER.md src build/dataset-2000
```

计算本地压缩包哈希：

```cmd
certutil -hashfile "%USERPROFILE%\Desktop\logisim-vision-training.tar.gz" SHA256
```

保存显示的 SHA-256，上传后需要再次比较。

如果 Windows 的 `tar` 不可用，也可以用 7-Zip 将上述文件和目录压缩成
`logisim-vision-training.tar.gz`，但解压后的根目录必须直接包含：

```text
generate_dataset.py
pyproject.toml
src/
build/dataset-2000/
```

## 4. 上传训练包

先在服务器创建工作目录：

```cmd
ssh -p %SERVER_PORT% %SERVER_USER%@%SERVER_HOST% "mkdir -p ~/logisim-vision"
```

上传：

```cmd
scp -P %SERVER_PORT% "%USERPROFILE%\Desktop\logisim-vision-training.tar.gz" %SERVER_USER%@%SERVER_HOST%:~/logisim-vision/
```

上传完成后登录服务器：

```cmd
ssh -p %SERVER_PORT% %SERVER_USER%@%SERVER_HOST%
```

从本节开始，除明确标注“本地 CMD”的命令外，其余命令都在服务器 Linux Shell 中执行。

## 5. 校验并解压上传文件

```bash
cd ~/logisim-vision
sha256sum logisim-vision-training.tar.gz
```

比较服务器输出与本地 `certutil` 的 SHA-256。两者必须一致。

解压：

```bash
tar -xzf logisim-vision-training.tar.gz
```

检查数据集文件数：

```bash
find build/dataset-2000 -type f | wc -l
```

应输出：

```text
6003
```

检查目录：

```bash
pwd
ls -la
ls -la build/dataset-2000
```

## 6. 判断服务器类型

先执行：

```bash
hostname
which sbatch || true
nvidia-smi
```

### 6.1 普通 GPU 云服务器或独占服务器

如果直接执行 `nvidia-smi` 能看到 GPU，且管理员允许直接使用 GPU，可以继续第 7 节。

### 6.2 Slurm 集群

如果存在 `sbatch`，但登录节点执行 `nvidia-smi` 看不到 GPU，说明 GPU 必须通过调度系统
申请。不要在登录节点直接训练，先阅读第 19 节的 Slurm 方法。

## 7. 检查 GPU、磁盘和内存

```bash
nvidia-smi
df -h ~/logisim-vision
free -h
```

建议至少保留 10 GB 可用磁盘空间。数据集本身约 62 MB，但 PyTorch、CUDA 运行库、
虚拟环境、预训练权重和训练结果还需要额外空间。

保存服务器硬件信息：

```bash
nvidia-smi > ~/logisim-vision/training-gpu.txt
```

如果 `nvidia-smi` 报错，先联系服务器管理员或更换带 GPU 驱动的服务器镜像，不要继续安装
CPU 版 PyTorch。

## 8. 创建 Python 环境

检查 Python：

```bash
python3 --version
which python3
```

Python 3.10、3.11、3.12 或 3.13 均可。远程 Linux 服务器优先使用镜像中已经稳定安装的
3.11、3.12 或 3.13，不建议临时升级到 3.14。

创建 venv：

```bash
cd ~/logisim-vision
python3 -m venv .venv-train
source .venv-train/bin/activate
python --version
which python
python -m pip install --upgrade pip
```

`which python` 应指向：

```text
/home/你的用户名/logisim-vision/.venv-train/bin/python
```

如果提示缺少 `venv`，但服务器提供 Conda，可以改用：

```bash
conda create -n logisim-vision python=3.13 -y
conda activate logisim-vision
python --version
```

不要同时使用 venv 和 Conda，选择一种即可。

## 9. 安装 CUDA 版 PyTorch

先查看 `nvidia-smi` 右上角的 `CUDA Version`。下面三个命令只执行一个。

### 9.1 CUDA 12.8 或更高

```bash
python -m pip install torch torchvision torchaudio --index-url https://download.pytorch.org/whl/cu128
```

### 9.2 CUDA 12.6 或 12.7

```bash
python -m pip install torch torchvision torchaudio --index-url https://download.pytorch.org/whl/cu126
```

### 9.3 CUDA 11.8 至 12.5

```bash
python -m pip install torch torchvision torchaudio --index-url https://download.pytorch.org/whl/cu118
```

如果 PyTorch 官网选择器给出的命令与本文不同，以官网为准：
[PyTorch Get Started](https://pytorch.org/get-started/locally/)。

## 10. 验证 CUDA

```bash
python -c "import torch; print('torch =', torch.__version__); print('CUDA runtime =', torch.version.cuda); print('CUDA available =', torch.cuda.is_available()); print('GPU count =', torch.cuda.device_count()); print('GPU =', torch.cuda.get_device_name(0) if torch.cuda.is_available() else 'NONE')"
```

必须看到：

```text
CUDA available = True
GPU count = 1
GPU = NVIDIA 显卡名称
```

`GPU count` 大于 1 也没有问题，但本项目只使用一张卡 `device=0`。

如果 `CUDA available = False`，不要继续。通常是安装了 CPU 版 PyTorch、驱动版本不匹配，
或者当前 Shell 没有被分配 GPU。

## 11. 安装固定训练依赖

```bash
python -m pip install ultralytics==8.4.158 Pillow==12.2.0
yolo checks
python -m pip freeze > ~/logisim-vision/training-environment.txt
```

训练期间不要执行 `pip install -U ultralytics`。

如果服务器导入 OpenCV 时提示 `libGL.so.1` 缺失，优先使用服务器提供的深度学习镜像，
或联系管理员安装系统图形运行库；不要在共享服务器上擅自使用 root 修改系统。

## 12. 自动验证数据集

```bash
cd ~/logisim-vision
python generate_dataset.py --validate-only build/dataset-2000
```

必须得到：

```text
samples: 2000
uniqueSemanticHashes: 2000
train: 1726
val: 274
```

如果校验失败，停止训练并重新上传压缩包。

## 13. 自动更新 data.yaml 的服务器路径

```bash
cd ~/logisim-vision
export VISION_ROOT="$HOME/logisim-vision"
sed -i "1c path: \"$VISION_ROOT/build/dataset-2000\"" build/dataset-2000/data.yaml
cat build/dataset-2000/data.yaml
```

输出应类似：

```yaml
path: "/home/你的用户名/logisim-vision/build/dataset-2000"
train: images/train
val: images/val
names:
  0: INPUT_PIN
  1: OUTPUT_PIN
  2: AND
  3: OR
  4: NOT
  5: NAND
  6: NOR
```

## 14. 使用 tmux 防止 SSH 断线终止训练

检查 tmux：

```bash
tmux -V
```

创建训练会话：

```bash
tmux new -s logisim-train
```

进入 tmux 后，重新设置目录、变量和环境：

```bash
cd ~/logisim-vision
export VISION_ROOT="$HOME/logisim-vision"
source .venv-train/bin/activate
```

如果使用的是 Conda，则用：

```bash
cd ~/logisim-vision
export VISION_ROOT="$HOME/logisim-vision"
conda activate logisim-vision
```

在 tmux 中按 `Ctrl+B`，松开后再按 `D`，可以退出但保持训练继续运行。

重新连接 SSH 后恢复界面：

```bash
tmux attach -t logisim-train
```

查看现有会话：

```bash
tmux ls
```

如果服务器没有 tmux，可使用第 18 节的 `nohup` 方法。

## 15. 两轮冒烟训练

在 tmux 中执行：

```bash
yolo detect train model=yolo11n.pt data="$VISION_ROOT/build/dataset-2000/data.yaml" epochs=2 imgsz=640 batch=4 device=0 workers=2 project="$VISION_ROOT/runs" name=smoke seed=20261021 deterministic=True cache=False degrees=0 perspective=0 flipud=0 fliplr=0 mosaic=0 plots=True
```

第一次运行会联网下载 `yolo11n.pt`。

成功标准：

- 日志显示 `CUDA:0` 或 NVIDIA GPU 名称；
- 完成两个 epoch；
- 没有 `CUDA out of memory`；
- 生成 `~/logisim-vision/runs/smoke`；
- `runs/smoke/weights` 中存在权重文件。

冒烟训练失败时不要运行正式训练。

## 16. 正式训练

```bash
yolo detect train model=yolo11n.pt data="$VISION_ROOT/build/dataset-2000/data.yaml" epochs=100 patience=20 imgsz=800 batch=-1 device=0 workers=4 project="$VISION_ROOT/runs" name=submission-1 seed=20261021 deterministic=True save_period=10 cache=False degrees=0 translate=0.05 scale=0.10 shear=0 perspective=0 flipud=0 fliplr=0 mosaic=0 mixup=0 hsv_h=0 hsv_s=0.10 hsv_v=0.10 plots=True
```

参数说明：

- `device=0`：仅使用第一张 GPU；
- `batch=-1`：自动使用约 60% 的显存；
- `imgsz=800`：保留 NOT 等小目标细节；
- `patience=20`：连续 20 轮没有改善时提前停止；
- `save_period=10`：每 10 轮保留一次检查点；
- 禁用旋转、透视、翻转和 mosaic；
- `seed=20261021`：固定随机种子。

训练开始并确认正常后，可以按 `Ctrl+B`、`D` 离开 tmux，然后安全断开 SSH。

## 17. 监控训练

重新登录服务器后，可以使用以下命令。

查看 GPU：

```bash
watch -n 2 nvidia-smi
```

查看训练目录：

```bash
ls -lah ~/logisim-vision/runs/submission-1
ls -lah ~/logisim-vision/runs/submission-1/weights
```

重新进入训练窗口：

```bash
tmux attach -t logisim-train
```

如果出现 `CUDA out of memory`，按以下顺序修改正式训练命令：

1. `batch=-1` 改为 `batch=4`；
2. 仍然失败时改为 `batch=2`；
3. 仍然失败时将 `imgsz=800` 改为 `imgsz=640`。

## 18. 没有 tmux 时使用 nohup

只有服务器确实没有 tmux 时才使用本节。

```bash
cd ~/logisim-vision
export VISION_ROOT="$HOME/logisim-vision"
source .venv-train/bin/activate
nohup yolo detect train model=yolo11n.pt data="$VISION_ROOT/build/dataset-2000/data.yaml" epochs=100 patience=20 imgsz=800 batch=-1 device=0 workers=4 project="$VISION_ROOT/runs" name=submission-1 seed=20261021 deterministic=True save_period=10 cache=False degrees=0 translate=0.05 scale=0.10 shear=0 perspective=0 flipud=0 fliplr=0 mosaic=0 mixup=0 hsv_h=0 hsv_s=0.10 hsv_v=0.10 plots=True > "$VISION_ROOT/train-submission-1.log" 2>&1 &
echo $! > "$VISION_ROOT/train-submission-1.pid"
```

查看日志：

```bash
tail -f ~/logisim-vision/train-submission-1.log
```

查看进程：

```bash
ps -fp "$(cat ~/logisim-vision/train-submission-1.pid)"
```

## 19. Slurm 集群训练方法

如果服务器要求通过 `sbatch` 申请 GPU，先向管理员确认：

- GPU 分区名称；
- `--gres` 或 `--gpus` 的写法；
- 最大运行时间；
- 是否需要 `module load cuda`；
- Python/Conda 环境是否可从计算节点访问。

创建脚本：

```bash
cd ~/logisim-vision
nano train.slurm
```

填入以下模板。`gpu` 分区名需要根据服务器实际情况修改：

```bash
#!/usr/bin/env bash
#SBATCH --job-name=logisim-vision
#SBATCH --partition=gpu
#SBATCH --gres=gpu:1
#SBATCH --cpus-per-task=4
#SBATCH --mem=16G
#SBATCH --time=08:00:00
#SBATCH --output=slurm-%j.out
#SBATCH --error=slurm-%j.err

set -euo pipefail

VISION_ROOT="$HOME/logisim-vision"
cd "$VISION_ROOT"
source .venv-train/bin/activate

nvidia-smi
python -c "import torch; print(torch.cuda.is_available()); print(torch.cuda.get_device_name(0))"

yolo detect train model=yolo11n.pt data="$VISION_ROOT/build/dataset-2000/data.yaml" epochs=100 patience=20 imgsz=800 batch=-1 device=0 workers=4 project="$VISION_ROOT/runs" name=submission-1 seed=20261021 deterministic=True save_period=10 cache=False degrees=0 translate=0.05 scale=0.10 shear=0 perspective=0 flipud=0 fliplr=0 mosaic=0 mixup=0 hsv_h=0 hsv_s=0.10 hsv_v=0.10 plots=True
```

保存后提交：

```bash
sbatch train.slurm
```

查看任务：

```bash
squeue -u "$USER"
```

查看日志，将 `<JOB_ID>` 替换为任务编号：

```bash
tail -f "slurm-<JOB_ID>.out"
```

取消任务：

```bash
scancel <JOB_ID>
```

如果集群时间限制导致训练中断，使用第 20 节从 `last.pt` 续训。

## 20. 中断后恢复训练

恢复普通服务器或 tmux 中断的训练：

```bash
cd ~/logisim-vision
export VISION_ROOT="$HOME/logisim-vision"
source .venv-train/bin/activate
yolo detect train model="$VISION_ROOT/runs/submission-1/weights/last.pt" resume=True
```

必须使用 `last.pt`，不要用 `best.pt` 恢复训练。

Slurm 用户可以把训练脚本最后的 `yolo` 命令临时替换成：

```bash
yolo detect train model="$VISION_ROOT/runs/submission-1/weights/last.pt" resume=True
```

然后再次执行 `sbatch train.slurm`。

## 21. 独立验证最佳模型

```bash
cd ~/logisim-vision
export VISION_ROOT="$HOME/logisim-vision"
source .venv-train/bin/activate
yolo detect val model="$VISION_ROOT/runs/submission-1/weights/best.pt" data="$VISION_ROOT/build/dataset-2000/data.yaml" imgsz=800 batch=8 device=0 plots=True project="$VISION_ROOT/runs" name=submission-1-val
```

重点检查：

- 每类 precision 和 recall；
- mAP50 与 mAP50-95；
- `confusion_matrix.png`；
- NAND/NOR 混淆；
- NOT 小目标漏检；
- 输入、输出引脚误检。

合成验证集指标不能代替真实测试。权重下载回本地后，还要测试至少 20 张真实 Logisim
导出图片。

## 22. 查看验证集预测图

```bash
yolo detect predict model="$VISION_ROOT/runs/submission-1/weights/best.pt" source="$VISION_ROOT/build/dataset-2000/images/val" imgsz=800 conf=0.25 device=0 save=True project="$VISION_ROOT/runs" name=submission-1-preview
```

可以通过 SCP 或 WinSCP 下载部分预测图进行人工检查。

## 23. 导出 ONNX

```bash
python -m pip install onnx onnxruntime onnxslim
yolo export model="$VISION_ROOT/runs/submission-1/weights/best.pt" format=onnx imgsz=800 batch=1 device=cpu dynamic=False simplify=True
```

确认模型文件：

```bash
ls -lh "$VISION_ROOT/runs/submission-1/weights/best.pt"
ls -lh "$VISION_ROOT/runs/submission-1/weights/last.pt"
ls -lh "$VISION_ROOT/runs/submission-1/weights/best.onnx"
```

## 24. 在服务器上用 CPU 测试 ONNX

```bash
yolo detect predict model="$VISION_ROOT/runs/submission-1/weights/best.onnx" source="$VISION_ROOT/build/dataset-2000/images/val" imgsz=800 conf=0.25 device=cpu save=True project="$VISION_ROOT/runs" name=submission-1-onnx-preview
```

能够正常生成预测结果，说明 ONNX 可以在没有 NVIDIA GPU 的本地电脑运行。

## 25. 打包训练结果

更新环境记录：

```bash
cd ~/logisim-vision
source .venv-train/bin/activate
python -m pip freeze > training-environment.txt
nvidia-smi > training-gpu.txt
```

打包完整训练结果：

```bash
tar -czf submission-1-results.tar.gz runs/submission-1 runs/submission-1-val runs/submission-1-preview training-environment.txt training-gpu.txt
sha256sum submission-1-results.tar.gz > submission-1-results.tar.gz.sha256
ls -lh submission-1-results.tar.gz submission-1-results.tar.gz.sha256
```

如果某个预览目录不存在，删除打包命令中的相应目录后重新执行。

## 26. 下载到本地 Windows

退出服务器：

```bash
exit
```

以下命令回到本地 Windows CMD 执行：

```cmd
set "LOCAL_PROJECT=C:\path\to\logisim-ai-circuit-assistant"
set "SERVER_USER=ubuntu"
set "SERVER_HOST=203.0.113.10"
set "SERVER_PORT=22"
if not exist "%LOCAL_PROJECT%\vision-service\build\remote-result" mkdir "%LOCAL_PROJECT%\vision-service\build\remote-result"
scp -P %SERVER_PORT% %SERVER_USER%@%SERVER_HOST%:~/logisim-vision/submission-1-results.tar.gz "%LOCAL_PROJECT%\vision-service\build\remote-result\"
scp -P %SERVER_PORT% %SERVER_USER%@%SERVER_HOST%:~/logisim-vision/submission-1-results.tar.gz.sha256 "%LOCAL_PROJECT%\vision-service\build\remote-result\"
```

进入下载目录并检查：

```cmd
cd /d "%LOCAL_PROJECT%\vision-service\build\remote-result"
certutil -hashfile submission-1-results.tar.gz SHA256
type submission-1-results.tar.gz.sha256
```

两个 SHA-256 必须一致。

解压：

```cmd
tar -xzf submission-1-results.tar.gz
```

## 27. 将最佳模型放回项目

在本地 Windows CMD 执行：

```cmd
cd /d "%LOCAL_PROJECT%"
if not exist "vision-service\models" mkdir "vision-service\models"
copy /Y "vision-service\build\remote-result\runs\submission-1\weights\best.pt" "vision-service\models\best.pt"
copy /Y "vision-service\build\remote-result\runs\submission-1\weights\best.onnx" "vision-service\models\best.onnx"
```

最终模型位置：

```text
vision-service\models\best.pt
vision-service\models\best.onnx
```

完整训练记录保留在：

```text
vision-service\build\remote-result\runs\submission-1
```

提交版会纳入运行必需的 `models\best.onnx`；`models\best.pt` 和完整训练缓存仍被
`.gitignore` 排除。请把 `best.pt`、`last.pt` 和完整 `submission-1` 另行备份。

## 28. 云服务器停止与费用检查

确认以下文件已经下载且哈希一致后，才可以停止或释放服务器：

- `best.pt`；
- `last.pt`；
- `best.onnx`；
- `results.csv`；
- `args.yaml`；
- 混淆矩阵和训练曲线；
- `training-environment.txt`；
- `training-gpu.txt`。

如果使用按小时计费的 GPU 云服务器：

1. 确认训练进程已经退出；
2. 确认结果下载完成；
3. 确认是否还存在收费的数据盘、公网 IP 或快照；
4. 根据云平台说明选择“停止”或“释放”；
5. 不要只关闭 SSH 窗口，关闭 SSH 不会停止云服务器计费。

## 29. 常见问题

### SSH 断开后训练也停止

说明训练没有运行在 tmux、nohup 或 Slurm 中。重新登录，检查 `last.pt`，按第 20 节恢复。

### `torch.cuda.is_available()` 为 `False`

- 普通服务器：检查驱动和 PyTorch CUDA 安装命令；
- Slurm 集群：确认当前处于 GPU 计算节点，而不是登录节点；
- 云服务器：确认创建的是 GPU 实例，而不是普通 CPU 实例。

### `CUDA out of memory`

依次改为 `batch=4`、`batch=2`，最后才将 `imgsz=800` 改为 `640`。

### `No space left on device`

```bash
df -h
du -sh ~/logisim-vision/*
```

不要删除 `runs/submission-1/weights`。优先删除无用的冒烟预测结果，或更换更大的数据盘。

### 数据路径错误

```bash
cat ~/logisim-vision/build/dataset-2000/data.yaml
ls ~/logisim-vision/build/dataset-2000/images/train | head
ls ~/logisim-vision/build/dataset-2000/labels/train | head
```

确认 `data.yaml` 第一行是服务器绝对路径。

### 服务器不能访问外网，无法下载 yolo11n.pt

在能联网的电脑上下载 `yolo11n.pt`，通过 SCP 上传到 `~/logisim-vision/yolo11n.pt`，
然后保持训练命令中的 `model=yolo11n.pt`。不要改成随机初始化模型。

## 参考资料

- [PyTorch Windows/Linux CUDA 安装](https://pytorch.org/get-started/locally/)
- [Ultralytics 安装](https://docs.ultralytics.com/quickstart/)
- [YOLO11](https://docs.ultralytics.com/models/yolo11/)
- [训练参数](https://docs.ultralytics.com/modes/train/)
- [验证](https://docs.ultralytics.com/modes/val/)
- [ONNX 导出](https://docs.ultralytics.com/modes/export/)
