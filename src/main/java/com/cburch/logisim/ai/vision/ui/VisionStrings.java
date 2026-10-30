/*
 * Logisim-evolution - digital logic design tool and simulator
 * Copyright by the Logisim-evolution developers
 *
 * https://github.com/logisim-evolution/
 *
 * This is free software released under GNU GPLv3 license
 */

package com.cburch.logisim.ai.vision.ui;

import com.cburch.logisim.util.LocaleManager;
import java.util.Locale;
import java.util.Map;

/** Small one-week UI bundle; unsupported locales deliberately fall back to English. */
public final class VisionStrings {
  private static final Map<String, String> ENGLISH =
      Map.ofEntries(
          Map.entry("choose", "Circuit image"),
          Map.entry("chooseHint", "Select a Logisim PNG and recognize it locally."),
          Map.entry("chooserTitle", "Select a Logisim circuit image"),
          Map.entry("pngFilter", "PNG circuit images"),
          Map.entry("selected", "Selected circuit image: %s"),
          Map.entry("recognizing", "Recognizing components, wires, and topology…"),
          Map.entry("importing", "Creating and verifying the imported circuit…"),
          Map.entry("confirmTitle", "Confirm circuit image import"),
          Map.entry("reviewTitle", "Review circuit recognition"),
          Map.entry(
              "reviewSummary",
              "Review the overlay before importing. Recognized %d components and %d nets; warnings: %d; low-confidence items: %d; manual corrections: %d. Detector: %s. Target: %s."),
          Map.entry("reviewIssues", "Warnings and uncertain items"),
          Map.entry("reviewIssuesTab", "Issues"),
          Map.entry("reviewCorrectionsTab", "Corrections"),
          Map.entry(
              "correctionHint",
              "Safe corrections only: change a multi-input gate type or rename an input/output pin. Wiring cannot be edited here."),
          Map.entry("componentFixed", "%s (not editable)"),
          Map.entry("correctionFailed", "The correction could not be validated: %s"),
          Map.entry("overlayLayers", "Overlay:"),
          Map.entry("showComponents", "Components"),
          Map.entry("showWires", "Wires"),
          Map.entry("showJunctions", "Junctions"),
          Map.entry("reviewCancel", "Cancel"),
          Map.entry("reviewImport", "Import circuit"),
          Map.entry("noReviewIssues", "No warnings or low-confidence items."),
          Map.entry(
              "lowConfidenceComponent", "Low confidence: component %s (%s), %d%%"),
          Map.entry("lowConfidenceNet", "Low confidence: net %s, %d%%"),
          Map.entry("previewFailed", "The recognition preview could not be opened: %s"),
          Map.entry(
              "summary",
              "Recognized %d components and %d nets.\nWarnings: %d\nLow-confidence items: %d\nDetector: %s\n\nCreate new circuit \"%s\"?"),
          Map.entry(
              "imported",
              "Created circuit \"%s\" from the image: %d components, %d nets. The import is one undoable action."),
          Map.entry("errorTitle", "Circuit image recognition"),
          Map.entry(
              "serviceUnavailable",
              "The local vision service is not running. Start vision-service\\start_service.cmd, then try again."),
          Map.entry(
              "timeout",
              "Circuit image recognition timed out. Check the Python service console and try again."),
          Map.entry(
              "cancelled",
              "Circuit image recognition was cancelled; the project was not changed."),
          Map.entry(
              "permissionRequired",
              "Enable \"Allow edits this session\" before creating the recognized circuit."),
          Map.entry("rejected", "The image was rejected (%s): %s"),
          Map.entry("failed", "Circuit image recognition failed: %s"));

  private static final Map<String, String> CHINESE =
      Map.ofEntries(
          Map.entry("choose", "电路图"),
          Map.entry("chooseHint", "选择 Logisim PNG，使用本机图片识别服务识别。"),
          Map.entry("chooserTitle", "选择 Logisim 电路图"),
          Map.entry("pngFilter", "PNG 电路图"),
          Map.entry("selected", "已选择电路图：%s"),
          Map.entry("recognizing", "正在识别元件、导线和拓扑…"),
          Map.entry("importing", "正在创建并验证导入电路…"),
          Map.entry("confirmTitle", "确认导入电路图"),
          Map.entry("reviewTitle", "审核电路图识别结果"),
          Map.entry(
              "reviewSummary",
              "请先核对图片覆盖层再导入。识别到 %d 个元件、%d 条网络；警告 %d 项；低置信度 %d 项；人工修正 %d 项。检测方式：%s。目标电路：%s。"),
          Map.entry("reviewIssues", "警告和不确定项"),
          Map.entry("reviewIssuesTab", "问题"),
          Map.entry("reviewCorrectionsTab", "修正"),
          Map.entry(
              "correctionHint", "仅允许安全修正：修改多输入逻辑门类型，或重命名输入/输出引脚。此处不能修改连线。"),
          Map.entry("componentFixed", "%s（不可修改）"),
          Map.entry("correctionFailed", "修正未通过校验：%s"),
          Map.entry("overlayLayers", "显示："),
          Map.entry("showComponents", "元件"),
          Map.entry("showWires", "导线"),
          Map.entry("showJunctions", "连接点"),
          Map.entry("reviewCancel", "取消"),
          Map.entry("reviewImport", "导入电路"),
          Map.entry("noReviewIssues", "没有警告或低置信度项。"),
          Map.entry("lowConfidenceComponent", "低置信度：元件 %s（%s），%d%%"),
          Map.entry("lowConfidenceNet", "低置信度：网络 %s，%d%%"),
          Map.entry("previewFailed", "无法打开识别预览：%s"),
          Map.entry(
              "summary",
              "识别到 %d 个元件和 %d 条网络。\n警告：%d\n低置信度项：%d\n检测方式：%s\n\n是否创建新电路“%s”？"),
          Map.entry(
              "imported", "已从图片创建电路“%s”：%d 个元件，%d 条网络。可通过一次撤销恢复。"),
          Map.entry("errorTitle", "电路图识别"),
          Map.entry(
              "serviceUnavailable",
              "本机图片识别服务未启动。请先运行 vision-service\\start_service.cmd，再重试。"),
          Map.entry("timeout", "电路图识别超时。请检查 Python 服务控制台后重试。"),
          Map.entry("cancelled", "已取消电路图识别，工程未被修改。"),
          Map.entry("permissionRequired", "创建识别电路前，请启用“允许本次会话编辑”。"),
          Map.entry("rejected", "图片被拒绝（%s）：%s"),
          Map.entry("failed", "电路图识别失败：%s"));

  private VisionStrings() {}

  public static String get(String key, Object... arguments) {
    final var language = LocaleManager.getLocale().getLanguage();
    final var messages = language.equals(Locale.CHINESE.getLanguage()) ? CHINESE : ENGLISH;
    final var pattern = messages.get(key);
    if (pattern == null) throw new IllegalArgumentException("Unknown vision message: " + key);
    return arguments.length == 0
        ? pattern
        : String.format(LocaleManager.getLocale(), pattern, arguments);
  }
}
