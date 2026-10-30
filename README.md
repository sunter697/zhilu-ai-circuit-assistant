# 智路：自然语言与视觉驱动的电路生成与编辑

> 基于 Logisim-evolution 的多模态 AI 电路设计、重建与验证系统。

本项目基于开源项目 [Logisim-evolution](https://github.com/logisim-evolution/logisim-evolution)
进行二次开发，面向电路教学、资料数字化与智能 EDA 前端探索，将自然语言交互、视觉识别、
拓扑重建和可验证电路编辑整合到同一桌面工作流中。

核心能力包括：

- 自然语言生成与修改：根据用户描述生成电路、分析逻辑，并在多轮对话中继续调整；
- 电路图识别：选择 PNG 电路图，识别元件、导线、引脚和拓扑并导入 Logisim；
- 识别审核：导入前查看识别覆盖层、修正门类型和引脚名称；
- 事务化安全编辑：AI 在隔离工作副本中操作，结构验证和用户审查通过后才原子应用；
- 一次撤销：AI 应用的整轮修改可以通过一次撤销恢复；
- 本地视觉推理：正式 ONNX 模型随项目发布，默认不需要 Python 服务；
- 多尺度识别：支持 INPUT、OUTPUT、AND、OR、NOT、NAND、NOR、XOR、XNOR 九类元件，
  并覆盖 50%–200% 缩放回归场景。

本仓库是 Logisim-evolution 的衍生项目，不是其官方发行版。根目录的
[`README_UPSTREAM.md`](README_UPSTREAM.md) 保留原项目说明；本文件说明本项目的环境、
启动、配置和测试方法。

## 1. 源码包说明

这是一份可重新构建的干净源码包，已经保留：

- Java 主程序、AI 助手和图片导入源码；
- Gradle Wrapper；
- Python 视觉服务源码；
- 正式 ONNX 模型和旧截图兼容模型；
- Java/Python 测试、演示图片、设计文档和评测报告。

为缩小体积，源码包不包含：

- .git、.idea、.gradle；
- Java build、bin 等构建产物；
- Python 虚拟环境和缓存；
- 训练数据集、训练输出、best.pt 和 last.pt。

这些被排除的内容不影响程序运行。默认图片识别在 Java 进程内使用
vision-service\models\best.onnx，不需要安装 Python。

## 2. 环境要求

### 必需环境

- Windows 10/11、Linux 或 macOS；
- 64 位 JDK 21 或更高版本；
- 首次构建时可以访问 Maven Central 和 Gradle 下载站点；
- 至少约 2 GB 可用磁盘空间用于依赖和构建缓存。

本项目已在 Windows 和 JDK 23 上验证。JDK 21 是推荐的最低版本，JDK 17 不能编译本项目。

先检查 Java：

    java -version
    javac -version

两个命令都应显示 21 或更高版本。

### 可选环境

- 使用 Python HTTP 视觉服务时需要 Python 3.12；
- 使用 AI 对话功能时需要一个兼容 OpenAI Chat Completions 与 tool_calls 的模型接口。

只使用 Logisim 原有功能或本地电路图识别时，不需要 API 密钥。

## 3. Windows 快速启动

以下命令均在 CMD 中执行。先进入源码根目录：

    cd /d C:\path\to\logisim-ai-circuit-assistant

如果源码被复制到其他位置，请把上述路径替换为实际的项目根目录。

如果 java -version 已经显示 JDK 21 或更高版本，直接运行：

    gradlew.bat run

首次运行会下载 Gradle 和 Java 依赖，耗时取决于网络。看到 Logisim 主窗口后即启动成功。

如果系统安装了多个 JDK，可以在当前 CMD 中临时指定：

    set "JAVA_HOME=C:\path\to\jdk-23"
    set "PATH=%JAVA_HOME%\bin;%PATH%"
    gradlew.bat run

JAVA_HOME 必须指向 JDK 根目录，不能指向 bin 目录。

### PowerShell

    cd 'C:\path\to\logisim-ai-circuit-assistant'
    $env:JAVA_HOME = 'C:\path\to\jdk-23'
    $env:Path = "$env:JAVA_HOME\bin;$env:Path"
    .\gradlew.bat run

### IntelliJ IDEA

1. 选择“打开”，打开本 README 所在的项目根目录；
2. 将 Gradle JVM 设置为 JDK 21 或更高版本；
3. 等待 Gradle 导入和依赖下载完成；
4. 打开右侧 Gradle 面板；
5. 执行 Tasks → application → run。

不要直接运行某个测试类来启动软件，也不要把 JRE 当作 Gradle JVM。

## 4. Linux 和 macOS 启动

在项目根目录执行：

    chmod +x gradlew
    ./gradlew run

如果需要指定 JDK：

    export JAVA_HOME=/path/to/jdk-21
    export PATH="$JAVA_HOME/bin:$PATH"
    ./gradlew run

## 5. 图片识别的使用方法

默认采用 Java 内嵌识别模式，不需要先启动 Python 服务。

1. 启动程序；
2. 打开或新建一个 Logisim 工程；
3. 在右侧“电路助手”中打开“权限”，允许本次会话编辑；
4. 点击输入区旁的“电路图”按钮；
5. 选择 PNG 电路图；
6. 在识别审核窗口检查元件、端口和导线；
7. 必要时修正门类型或输入/输出名称；
8. 点击“导入电路”。

导入成功后会新建 Vision_Imported 电路。取消审核、图片变化、协议错误或拓扑校验失败时，
原工程不会被修改。

正式模型位于：

    vision-service\models\best.onnx

旧版绿色导线截图兼容模型位于：

    vision-service\models\legacy-week1.onnx

开发环境会直接读取 best.onnx；构建发布 JAR 时，Gradle 也会将正式模型打包进资源。

## 6. AI 助手配置

点击右侧“电路助手”顶部的“设置”，填写：

- 接口地址：完整的 Chat Completions 地址，例如
  https://api.openai.com/v1/chat/completions；
- 模型：接口支持的模型名称；
- API 密钥：只在本次会话内使用，不写入任务日志。

也可以在启动前设置环境变量：

    set "LOGISIM_AI_ENDPOINT=https://api.openai.com/v1/chat/completions"
    set "LOGISIM_AI_MODEL=你的模型名称"
    set "LOGISIM_AI_API_KEY=你的API密钥"
    gradlew.bat run

接口必须兼容 OpenAI Chat Completions 请求格式，并正确返回 tool_calls；仅支持普通文本对话、
不支持工具调用的接口无法执行电路修改。

AI 修改电路前会先提出规格。用户确认规格后，AI 才在工作副本中修改；验证通过后仍需用户
在审查页点击“应用”。这是正常的安全流程。

## 7. 可选：使用 Python HTTP 视觉服务

默认内嵌模式已经可以完成识别。只有需要调试 Python 实现、兼容旧链路或排查 Java ONNX
运行环境时，才需要本节。

### 7.1 创建 Python 运行环境

在项目根目录的 CMD 中执行：

    py -3.12 -m venv vision-service\build\release-env
    vision-service\build\release-env\Scripts\python.exe -m pip install --upgrade pip
    vision-service\build\release-env\Scripts\python.exe -m pip install -r vision-service\requirements-runtime.txt
    vision-service\build\release-env\Scripts\python.exe vision-service\verify_release.py

最后一条命令应输出 RELEASE CHECK PASSED。

### 7.2 启动服务

打开第一个 CMD：

    cd /d C:\path\to\logisim-ai-circuit-assistant
    vision-service\start_service.cmd

保持窗口开启。服务只监听本机：

    http://127.0.0.1:8765

可在另一个 CMD 中检查：

    curl http://127.0.0.1:8765/health

### 7.3 强制 Java 使用 HTTP 模式

打开第二个 CMD：

    cd /d C:\path\to\logisim-ai-circuit-assistant
    set "LOGISIM_VISION_MODE=http"
    gradlew.bat run

如果 8765 端口被占用，请先关闭旧的 Python 视觉服务进程，不要同时启动多个实例。

恢复默认内嵌模式时，关闭该 CMD 后重新启动程序即可。也可以显式设置：

    set "LOGISIM_VISION_MODE=embedded"
    gradlew.bat run

## 8. 构建可运行 JAR

在项目根目录执行：

    gradlew.bat shadowJar

生成文件：

    build\libs\logisim-evolution-5.1.0dev-all.jar

运行：

    java --enable-native-access=ALL-UNNAMED -jar build\libs\logisim-evolution-5.1.0dev-all.jar

发布 JAR 已包含正式 best.onnx 模型；使用默认内嵌识别时不必另外复制模型文件。

## 9. 测试

运行全部 Java 测试：

    gradlew.bat test

上游 `HexFileTest` 还会调用外部命令 `xxd`。GitHub Actions 会自动安装；在 Windows 本地运行全量测试时，需先安装 `xxd` 并确保它位于 `PATH`。

只运行 AI 与图片识别相关测试：

    gradlew.bat test --tests "com.cburch.logisim.ai.*" --tests "com.cburch.logisim.gui.ai.*"

运行代码检查：

    gradlew.bat checkstyleMain checkstyleTest

运行 Python 测试需要先安装测试依赖：

    py -3.12 -m venv vision-service\build\test-env
    vision-service\build\test-env\Scripts\python.exe -m pip install -e "vision-service[test]"
    vision-service\build\test-env\Scripts\python.exe -m pytest vision-service\tests -q

## 10. 主要目录

    src\main\java\com\cburch\logisim\ai
        AI 任务控制、工具调用、验证、事务和视觉导入

    src\main\java\com\cburch\logisim\gui\ai
        AI 助手侧边栏、设置、权限、审核和识别预览界面

    src\main\java\com\cburch\logisim\ai\vision
        Java 内嵌 ONNX 检测、拓扑恢复、HTTP 客户端和导入规划

    src\visionTools
        原生 Logisim 训练数据生成工具

    vision-service
        Python 识别服务、模型、测试、训练和评测脚本

    vision-service\models
        运行时 ONNX 模型

    docs
        完整版规划、实现报告和原生数据集设计文档

    src\test
        Java 回归测试

## 11. 常见问题

### gradlew.bat 提示找不到 Java

确认安装的是 JDK 21 或更高版本，并检查：

    echo %JAVA_HOME%
    where java
    java -version

### 第一次运行停在 Downloading

Gradle Wrapper 和依赖需要联网下载。检查代理、防火墙以及对
services.gradle.org、repo.maven.apache.org 的访问。

### 图片识别提示找不到模型

确认文件存在：

    vision-service\models\best.onnx

若从其他目录运行开发版本，请先 cd 到项目根目录。发布 JAR 会使用打包模型。

### 图片按钮提示服务不可用

默认 auto 模式会优先使用内嵌识别。如果本机 ONNX Runtime 不兼容，auto 模式可能回退到
HTTP。此时按第 7 节启动 Python 服务，或检查控制台中的 ONNX Runtime 错误。

### AI 助手能分析但不能修改

打开“权限”，启用本次会话编辑；确认模型接口支持 tool_calls，并在提出规格后完成确认。

### 修改后电路没有立刻变化

AI 首先修改独立工作副本。必须在审查页确认验证结果并点击“应用”，正式电路才会变化。

## 12. 文档与许可

- 项目实现总报告：docs\logisim-ai-circuit-vision-implementation-report.md
- 完整版规划：docs\ai-circuit-vision-full-plan.md
- 视觉模块说明：vision-service\README.md
- 模型卡：vision-service\models\MODEL_CARD.md
- 原项目说明：README_UPSTREAM.md
- 第三方与模型来源：THIRD_PARTY_NOTICES.md
- 开源许可证：LICENSE.md

本项目继承 Logisim-evolution 的 GNU General Public License v3。原项目版权、贡献者与第三方
依赖信息以 LICENSE.md、README_UPSTREAM.md、THIRD_PARTY_NOTICES.md 和 docs\credits.md 为准。
