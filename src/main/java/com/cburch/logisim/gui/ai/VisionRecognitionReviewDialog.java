/*
 * Logisim-evolution - digital logic design tool and simulator
 * Copyright by the Logisim-evolution developers
 *
 * https://github.com/logisim-evolution/
 *
 * This is free software released under GNU GPLv3 license
 */

package com.cburch.logisim.gui.ai;

import com.cburch.logisim.ai.vision.model.VisionIr;
import com.cburch.logisim.ai.vision.ui.VisionImportController;
import com.cburch.logisim.ai.vision.ui.VisionStrings;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dialog;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Insets;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTabbedPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;

/** Modal review and constrained correction of a recognition result before project mutation. */
final class VisionRecognitionReviewDialog {
  private static final double LOW_CONFIDENCE_THRESHOLD = 0.75;
  private static final VisionIr.ComponentKind[] EDITABLE_GATE_KINDS = {
    VisionIr.ComponentKind.AND,
    VisionIr.ComponentKind.OR,
    VisionIr.ComponentKind.NAND,
    VisionIr.ComponentKind.NOR,
    VisionIr.ComponentKind.XOR,
    VisionIr.ComponentKind.XNOR
  };

  private VisionRecognitionReviewDialog() {}

  static VisionImportController.PreparedImport show(
      Component parent,
      VisionImportController.PreparedImport prepared,
      Function<
              Map<String, VisionImportController.ComponentCorrection>,
              VisionImportController.PreparedImport>
          reviser) {
    final VisionOverlayView overlay;
    try {
      overlay = new VisionOverlayView(prepared.sourceImage(), prepared.recognition());
    } catch (IOException exception) {
      JOptionPane.showMessageDialog(
          parent,
          VisionStrings.get("previewFailed", exception.getMessage()),
          VisionStrings.get("errorTitle"),
          JOptionPane.ERROR_MESSAGE);
      return null;
    }

    final var accepted = new AtomicReference<VisionImportController.PreparedImport>();
    final var owner = SwingUtilities.getWindowAncestor(parent);
    final var dialog =
        new JDialog(
            owner,
            VisionStrings.get("reviewTitle"),
            Dialog.ModalityType.APPLICATION_MODAL);
    dialog.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);
    dialog.setLayout(new BorderLayout(12, 12));
    dialog.getRootPane().setBorder(BorderFactory.createEmptyBorder(14, 14, 14, 14));

    final var summary = new JTextArea(summary(prepared));
    summary.setEditable(false);
    summary.setFocusable(false);
    summary.setOpaque(false);
    summary.setLineWrap(true);
    summary.setWrapStyleWord(true);
    summary.setFont(AiTheme.font(13));
    summary.setForeground(AiTheme.foreground());
    summary.setBorder(BorderFactory.createEmptyBorder(0, 2, 2, 2));
    dialog.add(summary, BorderLayout.NORTH);

    final var previewFrame = new JPanel(new BorderLayout());
    previewFrame.setBackground(AiTheme.background());
    previewFrame.setBorder(BorderFactory.createLineBorder(AiTheme.border()));
    previewFrame.add(overlay, BorderLayout.CENTER);
    dialog.add(previewFrame, BorderLayout.CENTER);

    final var details = new JTextArea(reviewDetails(prepared.recognition()));
    details.setEditable(false);
    details.setFocusable(false);
    details.setLineWrap(true);
    details.setWrapStyleWord(true);
    details.setFont(AiTheme.font(12));
    details.setForeground(AiTheme.foreground());
    details.setBackground(AiTheme.surface());
    details.setMargin(new Insets(8, 8, 8, 8));
    final var detailScroll = new JScrollPane(details);
    detailScroll.setBorder(BorderFactory.createEmptyBorder());
    final var correctionEditor = new CorrectionEditor(prepared.recognition());
    final var correctionScroll = new JScrollPane(correctionEditor);
    correctionScroll.setBorder(BorderFactory.createEmptyBorder());
    correctionScroll.getVerticalScrollBar().setUnitIncrement(16);
    final var tabs = new JTabbedPane();
    tabs.setFont(AiTheme.font(12));
    tabs.addTab(VisionStrings.get("reviewIssuesTab"), detailScroll);
    tabs.addTab(VisionStrings.get("reviewCorrectionsTab"), correctionScroll);
    tabs.setPreferredSize(new Dimension(330, 360));
    tabs.setBorder(BorderFactory.createLineBorder(AiTheme.border()));
    dialog.add(tabs, BorderLayout.EAST);

    final var showComponents = new JCheckBox(VisionStrings.get("showComponents"), true);
    final var showWires = new JCheckBox(VisionStrings.get("showWires"), true);
    final var showJunctions = new JCheckBox(VisionStrings.get("showJunctions"), true);
    for (final var toggle : java.util.List.of(showComponents, showWires, showJunctions)) {
      toggle.setOpaque(false);
      toggle.setFont(AiTheme.font(12));
    }
    showComponents.addActionListener(
        event -> overlay.setShowComponents(showComponents.isSelected()));
    showWires.addActionListener(event -> overlay.setShowWires(showWires.isSelected()));
    showJunctions.addActionListener(
        event -> overlay.setShowJunctions(showJunctions.isSelected()));

    final var toggles = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
    toggles.setOpaque(false);
    toggles.add(new JLabel(VisionStrings.get("overlayLayers")));
    toggles.add(showComponents);
    toggles.add(showWires);
    toggles.add(showJunctions);

    final var cancel = new JButton(VisionStrings.get("reviewCancel"));
    final var apply = new JButton(VisionStrings.get("reviewImport"));
    AiTheme.styleButton(cancel, false);
    AiTheme.styleButton(apply, true);
    cancel.addActionListener(event -> dialog.dispose());
    apply.addActionListener(
        event -> {
          try {
            accepted.set(reviser.apply(correctionEditor.corrections()));
            dialog.dispose();
          } catch (RuntimeException exception) {
            JOptionPane.showMessageDialog(
                dialog,
                VisionStrings.get(
                    "correctionFailed",
                    exception.getMessage() == null
                        ? exception.getClass().getSimpleName()
                        : exception.getMessage()),
                VisionStrings.get("errorTitle"),
                JOptionPane.ERROR_MESSAGE);
          }
        });
    final var buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
    buttons.setOpaque(false);
    buttons.add(cancel);
    buttons.add(apply);

    final var footer = new JPanel(new BorderLayout(8, 0));
    footer.setOpaque(false);
    footer.add(toggles, BorderLayout.CENTER);
    footer.add(buttons, BorderLayout.EAST);
    dialog.add(footer, BorderLayout.SOUTH);
    dialog.getRootPane().setDefaultButton(apply);
    dialog.setMinimumSize(new Dimension(900, 620));
    dialog.setPreferredSize(new Dimension(1160, 760));
    dialog.pack();
    dialog.setLocationRelativeTo(parent);
    dialog.setVisible(true);
    return accepted.get();
  }

  private static String summary(VisionImportController.PreparedImport prepared) {
    return VisionStrings.get(
        "reviewSummary",
        prepared.componentCount(),
        prepared.netCount(),
        prepared.warningCount(),
        prepared.lowConfidenceCount(),
        prepared.correctionCount(),
        prepared.detectorMode(),
        prepared.plan().targetCircuitName());
  }

  private static final class CorrectionEditor extends JPanel {
    private final Map<String, VisionIr.DetectedComponent> components = new LinkedHashMap<>();
    private final Map<String, JComboBox<VisionIr.ComponentKind>> kindEditors =
        new LinkedHashMap<>();
    private final Map<String, JTextField> labelEditors = new LinkedHashMap<>();

    CorrectionEditor(VisionIr recognition) {
      setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
      setBackground(AiTheme.surface());
      setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));

      final var hint = new JTextArea(VisionStrings.get("correctionHint"));
      hint.setEditable(false);
      hint.setFocusable(false);
      hint.setOpaque(false);
      hint.setLineWrap(true);
      hint.setWrapStyleWord(true);
      hint.setFont(AiTheme.font(12));
      hint.setForeground(AiTheme.muted());
      hint.setMaximumSize(new Dimension(Integer.MAX_VALUE, 54));
      add(hint);
      add(Box.createVerticalStrut(8));

      for (final var component : recognition.components()) {
        components.put(component.id(), component);
        add(editorRow(component));
        add(Box.createVerticalStrut(6));
      }
      add(Box.createVerticalGlue());
    }

    Map<String, VisionImportController.ComponentCorrection> corrections() {
      final var corrections =
          new LinkedHashMap<String, VisionImportController.ComponentCorrection>();
      for (final var entry : components.entrySet()) {
        final var component = entry.getValue();
        final var kindEditor = kindEditors.get(entry.getKey());
        if (kindEditor != null) {
          final var kind = (VisionIr.ComponentKind) kindEditor.getSelectedItem();
          if (kind != component.kind()) {
            corrections.put(
                entry.getKey(), new VisionImportController.ComponentCorrection(kind, null));
          }
        }
        final var labelEditor = labelEditors.get(entry.getKey());
        if (labelEditor != null) {
          final var original = String.valueOf(component.attributes().getOrDefault("label", ""));
          if (!labelEditor.getText().strip().equals(original)) {
            corrections.put(
                entry.getKey(),
                new VisionImportController.ComponentCorrection(null, labelEditor.getText()));
          }
        }
      }
      return corrections;
    }

    private JPanel editorRow(VisionIr.DetectedComponent component) {
      final var row = new JPanel(new BorderLayout(8, 3));
      row.setOpaque(false);
      row.setBorder(
          BorderFactory.createCompoundBorder(
              BorderFactory.createLineBorder(AiTheme.border()),
              BorderFactory.createEmptyBorder(6, 7, 6, 7)));
      row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 64));
      final var identity =
          new JLabel(
              component.id()
                  + "  ·  "
                  + Math.round(component.confidence() * 100)
                  + "%");
      identity.setFont(AiTheme.font(11));
      identity.setForeground(AiTheme.muted());
      row.add(identity, BorderLayout.NORTH);

      if (isEditableGate(component.kind())) {
        final var editor = new JComboBox<>(EDITABLE_GATE_KINDS);
        editor.setSelectedItem(component.kind());
        editor.setFont(AiTheme.font(12));
        kindEditors.put(component.id(), editor);
        row.add(editor, BorderLayout.CENTER);
      } else if (component.kind() == VisionIr.ComponentKind.INPUT_PIN
          || component.kind() == VisionIr.ComponentKind.OUTPUT_PIN) {
        final var editor =
            new JTextField(String.valueOf(component.attributes().getOrDefault("label", "")));
        editor.setFont(AiTheme.font(12));
        labelEditors.put(component.id(), editor);
        row.add(editor, BorderLayout.CENTER);
      } else {
        final var fixed = new JLabel(VisionStrings.get("componentFixed", component.kind()));
        fixed.setFont(AiTheme.font(12));
        fixed.setForeground(AiTheme.foreground());
        row.add(fixed, BorderLayout.CENTER);
      }
      return row;
    }
  }

  private static boolean isEditableGate(VisionIr.ComponentKind kind) {
    for (final var editable : EDITABLE_GATE_KINDS) {
      if (editable == kind) return true;
    }
    return false;
  }

  private static String reviewDetails(VisionIr recognition) {
    final var details = new StringBuilder();
    for (final var issue : recognition.unresolved()) {
      if (!details.isEmpty()) details.append('\n');
      details
          .append('[')
          .append(issue.severity())
          .append("] ")
          .append(issue.code())
          .append(": ")
          .append(issue.message());
    }
    for (final var component : recognition.components()) {
      if (component.confidence() >= LOW_CONFIDENCE_THRESHOLD) continue;
      if (!details.isEmpty()) details.append('\n');
      details.append(
          VisionStrings.get(
              "lowConfidenceComponent",
              component.id(),
              component.kind(),
              Math.round(component.confidence() * 100)));
    }
    for (final var net : recognition.nets()) {
      if (net.confidence() >= LOW_CONFIDENCE_THRESHOLD) continue;
      if (!details.isEmpty()) details.append('\n');
      details.append(
          VisionStrings.get(
              "lowConfidenceNet", net.id(), Math.round(net.confidence() * 100)));
    }
    return details.isEmpty() ? VisionStrings.get("noReviewIssues") : details.toString();
  }
}
