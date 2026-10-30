# 独立真实截图表达式清单 v0.1

## 1. 使用目的

以下共设计 26 条表达式用于采集模型冻结后的独立 Logisim 软件截图。首轮正式基准固定采用
R001–R022 共 22 张；R023–R026 保留为可选压力样例。它们没有进入训练集、
旧 T01–T20 调参集或 Day 6 回归集，覆盖全部 9 个视觉类别（输入、输出引脚和 7 种门）、
多层拓扑、输入扇出、junction 以及 3–25 个元件的不同规模。

机器可读原始清单位于
[`evaluation/real-holdout-expressions-v01.json`](evaluation/real-holdout-expressions-v01.json)。
项目自动测试使用 Logisim 自身的表达式解析器和电路生成器逐条构建，确认语法、门类型、
二输入约束和预计数量均正确。

## 2. 每张图的固定生成步骤

对首轮 R001–R022 逐条执行，不能先识别一部分再只保留成功图片：

1. 打开“项目 → 组合分析”。
2. 在“输入和输出”页，只添加该行列出的输入变量，并添加一个输出变量 `Y`；全部位宽为 1。
3. 在“表达式”页双击 `Y`，粘贴该行表达式。不要再执行最小化。
4. 点击“生成电路”，电路名使用 `RH_R001`、`RH_R002`……；如果当前版本不允许连字符，
   文件、目录和电路名统一使用下划线即可。
5. **勾选“仅使用二输入门”**。
6. **不要勾选“仅使用 NAND 门”**。
7. 使用浅色主题、ANSI 门、100% 缩放，不移动元件和导线。
8. 保持输入默认状态，使用软件的 PNG 导出功能导出当前电路，不进行二次裁剪、缩放、压缩
   或标色。
9. 首轮文件名严格使用 `R001.png`……`R022.png`，放入
   `vision-service\build\real-holdout-v01\images`。
10. 电路可以保存在同一个工程中；最终将工程另存为
    `vision-service\build\real-holdout-v01\source\real_holdout_v01.circ`，后续以它为真值来源。

`NOT (A AND B)` 会由生成器建立真正的 NAND，而不是 AND 后接 NOT；`NOT (A OR B)` 会建立
真正的 NOR；`A EQUALS B` 会建立 XNOR。英文关键字不区分大小写，但建议原样复制。

## 3. 表达式

| 编号 | 输入 | 表达式 | 预计元件 | 重点 |
|---|---|---|---:|---|
| R001 | A,B | `A AND B` | 4 | AND 基线 |
| R002 | A,B | `A OR B` | 4 | OR 基线 |
| R003 | A | `NOT A` | 3 | NOT 小目标 |
| R004 | A,B | `NOT (A AND B)` | 4 | NAND 基线 |
| R005 | A,B | `NOT (A OR B)` | 4 | NOR 基线 |
| R006 | A,B | `A XOR B` | 4 | XOR 基线 |
| R007 | A,B | `A EQUALS B` | 4 | XNOR 基线 |
| R008 | A,B,C | `(A AND B) OR (A AND C)` | 7 | A 扇出 |
| R009 | A,B,C | `(A OR B) AND (A OR C)` | 7 | A 扇出与双 OR |
| R010 | A,B,C | `(NOT (A AND B)) OR C` | 6 | NAND→OR |
| R011 | A,B,C | `(NOT (A OR B)) AND C` | 6 | NOR→AND |
| R012 | A,B,C | `(A XOR B) AND (A XOR C)` | 7 | XOR 与 A 扇出 |
| R013 | A,B,C,D | `(A EQUALS B) OR (C EQUALS D)` | 8 | 双 XNOR |
| R014 | A,B,C,D | `(A AND B) XOR (C OR D)` | 8 | AND/OR/XOR |
| R015 | A,B,C,D | `NOT ((A XOR B) OR (C AND D))` | 8 | XOR/AND→NOR |
| R016 | A,B,C,D | `NOT ((A EQUALS B) AND (C OR D))` | 8 | XNOR/OR→NAND |
| R017 | A–F | `((A AND B) OR (C AND D)) XOR (E OR F)` | 12 | 三层六输入 |
| R018 | A–E | `((A XOR B) AND (C EQUALS D)) OR (NOT E)` | 11 | 五类门混合 |
| R019 | A–D | `(NOT (A AND B)) XOR (NOT (C OR D))` | 8 | NAND/NOR 对比 |
| R020 | A–F | `((A AND B) OR (A AND C)) AND ((D XOR E) OR (D EQUALS F))` | 14 | A/D 双扇出 |
| R021 | A–E | `((A XOR B) OR (A EQUALS C)) AND ((NOT D) OR (D AND E))` | 13 | A/D 双扇出与 NOT |
| R022 | A–F | `((NOT (A AND B)) OR (A XOR C)) EQUALS ((D OR E) AND (D OR F))` | 14 | NAND/XOR/XNOR |
| R023 | A–J | `(((A AND B) OR (C XOR D)) AND ((E EQUALS F) OR (NOT (G AND H)))) XOR (I OR J)` | 20 | 大型混合图 |
| R024 | A–J | `(((A OR B) AND (C EQUALS D)) OR ((NOT (E OR F)) AND (G XOR H))) EQUALS (I AND J)` | 20 | XNOR 根节点 |
| R025 | A–L | `(((A AND B) XOR (C OR D)) AND ((E EQUALS F) OR (NOT (G AND H)))) OR ((I XOR J) AND (NOT (K OR L)))` | 24 | 全类别压力图 |
| R026 | A–L | `(((NOT A) OR (B AND C)) XOR ((D EQUALS E) AND (F OR G))) AND (((H XOR I) OR (NOT (J AND K))) EQUALS L)` | 25 | 当前规模上限 |

预计元件数包含输入引脚、一个输出引脚和所有逻辑门。R008、R009、R012、R020、R021、R022
包含显式重复输入，生成器会建立输入干线和扇出连接，主要用于 junction 与多接收端网络测试。

R023–R026 有 10–12 个输入。当前组合分析器在输入超过 6 个时会停用“表达式”和“最小化”
页的自动计算，以避免 Quine–McCluskey 最小化使界面长时间无响应，所以它们在界面中显示为
灰色。这不是解析器或 `CircuitBuilder` 不支持：项目自动测试仍能用同一解析器和生成器构建
26/26。由于该限制在第一次识别前已经记录，首轮预先固定为 R001–R022，不属于看过结果后
删除失败样本。22 张已经覆盖全部 9 类、6 张扇出图、6 张 junction 图，满足正式评测资格。

## 4. 采集后的下一步

全部 22 张 PNG 生成后先停止，不要点击电路图识别，也不要根据识别结果删除或重做某张图。
下一步应：

1. 为每张图片保存对应源电路，并执行 `prepare_real_holdout.py` 生成 `vision-ir/0.1` 真值草稿；
2. 人工核对元件类型、锚点、端口、net 和 junction；
3. 生成图片与标注 SHA-256，写入 `manifest.json`；
4. 冻结整个目录；
5. 最后只运行一次 `evaluate_real_holdout.py`。

这 22 张图能够形成有效的“独立自动布局截图”基准，但不能代表人工随意布线、深色主题、
旋转门、总线、照片或手绘图。若要对完整版做更强的泛化声明，应在不改模型的前提下再加入
5–10 张人工布线图片，并在第一次评测前一起完成标注和冻结。
