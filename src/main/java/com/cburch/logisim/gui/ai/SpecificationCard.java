/*
 * Logisim-evolution - digital logic design tool and simulator
 * Copyright by the Logisim-evolution developers
 *
 * https://github.com/logisim-evolution/
 *
 * This is free software released under GNU GPLv3 license
 */

package com.cburch.logisim.gui.ai;

import static com.cburch.logisim.ai.i18n.AiStrings.S;

import com.cburch.logisim.ai.i18n.AiStrings;
import com.cburch.logisim.ai.validation.TaskSpec;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.JTextArea;
import javax.swing.text.View;

/** A typed, readable view of the exact contract proposed for host acceptance. */
final class SpecificationCard extends JPanel {
  private static final long serialVersionUID = 1L;
  private static final int PADDING = 16;
  private final TaskSpec specification;
  private final JTextArea title = text("specificationTitle", 15, true);
  private final JTextArea status = text("specificationStatus", 13, false);
  private final JTextArea inputsHeading = text("specificationInputsHeading", 13, false);
  private final JTextArea inputs = text("specificationInputs", 14, false);
  private final JTextArea outputsHeading = text("specificationOutputsHeading", 13, false);
  private final JTextArea outputs = text("specificationOutputs", 14, false);
  private final JTextArea functionHeading = text("specificationFunctionHeading", 13, false);
  private final List<JTextArea> functionLines = new ArrayList<>();
  private final JTextArea acceptanceHeading = text("specificationAcceptanceHeading", 13, false);
  private final JTextArea acceptance = text("specificationAcceptance", 14, false);
  private JComponent actions;
  private String statusText = "";
  private int dividerY;

  SpecificationCard(TaskSpec specification) {
    this.specification = Objects.requireNonNull(specification);
    setLayout(null);
    setOpaque(false);
    setName("specificationCard");
    for (final var component :
        List.of(
            title,
            status,
            inputsHeading,
            inputs,
            outputsHeading,
            outputs,
            functionHeading,
            acceptanceHeading,
            acceptance)) {
      add(component);
    }
    refreshLanguage();
  }

  TaskSpec specification() {
    return specification;
  }

  void setStatus(String localizedText) {
    statusText = localizedText == null ? "" : localizedText;
    update(status, statusText);
    revalidate();
    repaint();
  }

  void setActionComponent(JComponent component) {
    if (actions == component) return;
    if (actions != null) remove(actions);
    actions = component;
    if (actions != null) add(actions);
    revalidate();
    repaint();
  }

  void refreshLanguage() {
    update(title, S.get("aiSpecCardTitle"));
    update(status, AiStrings.displayMessage(statusText));
    update(inputsHeading, S.get("aiSpecInputs"));
    update(outputsHeading, S.get("aiSpecOutputs"));
    update(inputs, signalText(specification.inputs()));
    update(outputs, signalText(specification.outputs()));
    update(functionHeading, S.get("aiSpecFunction"));
    update(acceptanceHeading, S.get("aiSpecAcceptanceHeading"));
    update(acceptance, S.get("aiSpecAcceptance", specification.exhaustiveRows()));
    final var descriptions = specification.functionDescription();
    while (functionLines.size() > descriptions.size()) {
      remove(functionLines.removeLast());
    }
    for (var i = 0; i < descriptions.size(); i++) {
      if (i == functionLines.size()) {
        final var line = text("specificationFunction-" + i, i == 0 ? 17 : 14, i == 0);
        functionLines.add(line);
        add(line);
      }
      update(functionLines.get(i), displayLine(descriptions.get(i)));
    }
    title.setForeground(AiTheme.foreground());
    inputs.setForeground(AiTheme.foreground());
    outputs.setForeground(AiTheme.foreground());
    acceptance.setForeground(AiTheme.foreground());
    for (final var heading :
        List.of(status, inputsHeading, outputsHeading, functionHeading, acceptanceHeading)) {
      heading.setForeground(AiTheme.muted());
    }
    for (var i = 0; i < functionLines.size(); i++) {
      final var line = functionLines.get(i);
      final boolean notation =
          specification.kind() == TaskSpec.Kind.BOOLEAN_EXPRESSIONS
              && i == functionLines.size() - 1;
      line.setFont(
          AiTheme.font(i == 0 ? 17 : notation ? 13 : 14)
              .deriveFont(i == 0 ? Font.BOLD : Font.PLAIN));
      line.setForeground(
          i == 0 ? AiTheme.accent() : notation ? AiTheme.muted() : AiTheme.foreground());
    }
    getAccessibleContext().setAccessibleName(title.getText());
    revalidate();
    repaint();
  }

  private static String signalText(List<TaskSpec.SignalSpec> signals) {
    return String.join(
        "   ",
        signals.stream()
            .map(s -> displayLine(S.get("aiSpecSignal", s.name(), s.width())))
            .toList());
  }

  private static String displayLine(String line) {
    final var stripped = line.strip();
    return stripped.startsWith("• ") ? stripped.substring(2) : stripped;
  }

  private static JTextArea text(String name, float size, boolean bold) {
    final var area = new JTextArea();
    area.setName(name);
    area.setEditable(false);
    area.setOpaque(false);
    area.setBorder(null);
    area.setLineWrap(true);
    area.setWrapStyleWord(true);
    area.setFont(AiTheme.font(size).deriveFont(bold ? Font.BOLD : Font.PLAIN));
    return area;
  }

  private static void update(JTextArea text, String content) {
    if (text.getText().equals(content)) return;
    final var start = text.getSelectionStart();
    final var end = text.getSelectionEnd();
    text.setText(content);
    text.select(Math.min(start, content.length()), Math.min(end, content.length()));
  }

  private static int height(JTextArea text, int width) {
    if (text.getText().isBlank()) return 0;
    final var view = text.getUI().getRootView(text);
    view.setSize(Math.max(1, width), Integer.MAX_VALUE);
    return Math.max(
        text.getFontMetrics(text.getFont()).getHeight(),
        (int) Math.ceil(view.getPreferredSpan(View.Y_AXIS)));
  }

  private static int place(JTextArea text, int x, int y, int width, boolean layout) {
    final var height = height(text, width);
    if (layout) text.setBounds(x, y, Math.max(1, width), height);
    return height;
  }

  private int measure(int width, boolean layout) {
    final int inner = Math.max(1, width - PADDING * 2);
    var y = PADDING;
    // Leave room for the caret gutter: an exact string width makes JTextArea wrap the last glyph.
    final var titleWidth = title.getFontMetrics(title.getFont()).stringWidth(title.getText()) + 8;
    final var statusWidth =
        status.getFontMetrics(status.getFont()).stringWidth(status.getText()) + 8;
    if (!status.getText().isBlank() && titleWidth + statusWidth + 12 <= inner) {
      final var statusHeight =
          place(status, width - PADDING - statusWidth, y + 2, statusWidth, layout);
      y += Math.max(place(title, PADDING, y, inner - statusWidth - 12, layout), statusHeight + 2);
    } else {
      y += place(title, PADDING, y, inner, layout);
      if (!status.getText().isBlank()) y += 4;
      y += place(status, PADDING, y, inner, layout);
    }
    y += 18;
    final var labelWidth =
        Math.min(
            inner / 3,
            Math.max(
                inputsHeading
                        .getFontMetrics(inputsHeading.getFont())
                        .stringWidth(inputsHeading.getText())
                    + 8,
                outputsHeading
                        .getFontMetrics(outputsHeading.getFont())
                        .stringWidth(outputsHeading.getText())
                    + 8));
    if (inner < 200) {
      y += place(inputsHeading, PADDING, y, inner, layout) + 5;
      y += place(inputs, PADDING, y, inner, layout) + 10;
      y += place(outputsHeading, PADDING, y, inner, layout) + 5;
      y += place(outputs, PADDING, y, inner, layout);
    } else {
      final int signalsWidth = inner - labelWidth - 12;
      y +=
          Math.max(
              place(inputsHeading, PADDING, y + 1, labelWidth, layout),
              place(inputs, PADDING + labelWidth + 12, y, signalsWidth, layout));
      y += 9;
      y +=
          Math.max(
              place(outputsHeading, PADDING, y + 1, labelWidth, layout),
              place(outputs, PADDING + labelWidth + 12, y, signalsWidth, layout));
    }
    y += 18;
    if (layout) dividerY = y;
    y += 16;
    y += place(functionHeading, PADDING, y, inner, layout) + 7;
    for (var i = 0; i < functionLines.size(); i++) {
      y += place(functionLines.get(i), PADDING, y, inner, layout);
      if (i < functionLines.size() - 1) y += 7;
    }
    y += 18;
    y += place(acceptanceHeading, PADDING, y, inner, layout) + 7;
    y += place(acceptance, PADDING, y, inner, layout);
    if (actions != null && actions.isVisible()) {
      y += 20;
      actions.setSize(inner, Math.max(1, actions.getHeight()));
      final int actionHeight = actions.getPreferredSize().height;
      if (layout) {
        actions.setBounds(PADDING, y, inner, actionHeight);
        actions.doLayout();
      }
      y += actionHeight;
    }
    return y + PADDING;
  }

  @Override
  public Dimension getPreferredSize() {
    final var width = getWidth() > 0 ? getWidth() : 380;
    return new Dimension(width, measure(width, false));
  }

  @Override
  public Dimension getMinimumSize() {
    return new Dimension(0, getPreferredSize().height);
  }

  @Override
  public Dimension getMaximumSize() {
    return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
  }

  @Override
  public void doLayout() {
    measure(getWidth(), true);
  }

  @Override
  protected void paintComponent(Graphics graphics) {
    super.paintComponent(graphics);
    final var canvas = (Graphics2D) graphics.create();
    try {
      canvas.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
      canvas.setColor(AiTheme.surface());
      canvas.fillRoundRect(0, 0, Math.max(0, getWidth() - 1), Math.max(0, getHeight() - 1), 24, 24);
      canvas.setColor(AiTheme.border());
      canvas.drawRoundRect(0, 0, Math.max(0, getWidth() - 1), Math.max(0, getHeight() - 1), 24, 24);
      if (dividerY > 0) canvas.drawLine(PADDING, dividerY, getWidth() - PADDING, dividerY);
    } finally {
      canvas.dispose();
    }
  }
}
