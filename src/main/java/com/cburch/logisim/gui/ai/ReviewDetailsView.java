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
import com.cburch.logisim.ai.validation.ValidationStatus;
import com.cburch.logisim.std.arith.ArithmeticLibrary;
import com.cburch.logisim.std.gates.GatesLibrary;
import com.cburch.logisim.std.wiring.WiringLibrary;
import com.cburch.logisim.tools.AddTool;
import com.cburch.logisim.tools.Library;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.event.ActionEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTabbedPane;
import javax.swing.JTextArea;
import javax.swing.JToggleButton;
import javax.swing.KeyStroke;
import javax.swing.Scrollable;
import javax.swing.SwingConstants;
import javax.swing.text.View;

/**
 * Read-only review: user-facing evidence first, exact machine data behind an explicit disclosure.
 */
final class ReviewDetailsView extends JPanel {
  private static final long serialVersionUID = 1L;
  private static final List<Library> PRESENTATION_LIBRARIES =
      List.of(new WiringLibrary(), new GatesLibrary(), new ArithmeticLibrary());
  private final ReviewDetailsModel model;
  private final JTabbedPane tabs = new JTabbedPane();
  private final FlowColumn overview = new FlowColumn(false, 0, 12);
  private final FlowColumn changes = new FlowColumn(false, 0, 12);
  private final FlowColumn verification = new FlowColumn(true, 16, 8);
  private final FlowColumn advanced = new FlowColumn(true, 16, 10);
  private final FlowColumn advancedContent = new FlowColumn(false, 0, 9);
  private final JTextArea status = text("reviewStatus", 16, true);
  private final JTextArea summary = text("reviewSummary", 14, false);
  private final JTextArea eligibility = text("reviewEligibility", 13, false);
  private final JTextArea verificationTitle = text("reviewVerificationTitle", 14, true);
  private final JTextArea passed = text("reviewPassedChecks", 14, false);
  private final JTextArea warnings = text("reviewWarnings", 14, false);
  private final JTextArea failures = text("reviewFailures", 14, false);
  private final JTextArea overviewChanges = text("reviewChangeSummary", 14, false);
  private final JTextArea changeHint = text("reviewChangeHint", 13, false);
  private final JTextArea changeSummary = text("reviewChangesSummary", 14, true);
  private final JTextArea noChanges = text("reviewNoChanges", 14, false);
  private final JTextArea noSpecification = text("reviewNoSpecification", 14, false);
  private final JTextArea metadata = text("reviewMetadata", 13, false);
  private final JTextArea raw = text("reviewRawJson", 13, false);
  private final JTextArea rawTitle = text("reviewRawTitle", 13, true);
  private final JToggleButton disclosure = new JToggleButton();
  private final SpecificationCard specification;
  private final List<RecordView> records = new ArrayList<>();
  private boolean applyAvailable;

  ReviewDetailsView(Map<String, Object> details, boolean applyAvailable) {
    model = new ReviewDetailsModel(details);
    this.applyAvailable = applyAvailable;
    setName("reviewDetailsView");
    setLayout(new BorderLayout(0, 14));
    setBackground(AiTheme.background());
    setBorder(BorderFactory.createEmptyBorder(16, 16, 16, 16));
    setPreferredSize(new Dimension(720, 700));
    setMinimumSize(new Dimension(0, 0));

    final var header = new FlowColumn(true, 16, 7);
    header.setName("reviewHeader");
    header.add(status);
    header.add(summary);
    header.add(eligibility);
    add(header, BorderLayout.NORTH);

    tabs.setName("reviewTabs");
    tabs.setFont(AiTheme.font(14));
    tabs.setBackground(AiTheme.background());
    overview.setName("reviewOverview");
    changes.setName("reviewChanges");
    specification =
        model.specification() == null ? null : new SpecificationCard(model.specification());
    if (specification == null) overview.add(noSpecification);
    else overview.add(specification);
    verification.add(verificationTitle);
    verification.add(passed);
    verification.add(warnings);
    verification.add(failures);
    overview.add(verification);
    final var changeCard = new FlowColumn(true, 16, 0);
    changeCard.add(overviewChanges);
    overview.add(changeCard);

    changes.add(changeSummary);
    changes.add(changeHint);
    if (model.changes().isEmpty()) changes.add(noChanges);
    for (final var record : model.changes()) {
      final var card = new RecordView(record);
      records.add(card);
      changes.add(card);
    }

    disclosure.setName("reviewAdvancedToggle");
    AiTheme.styleQuietButton(disclosure);
    disclosure.setHorizontalAlignment(SwingConstants.LEFT);
    disclosure.addActionListener(
        event -> {
          advancedContent.setVisible(disclosure.isSelected());
          updateDisclosure();
          overview.revalidate();
          overview.repaint();
        });
    disclosure.getInputMap().put(KeyStroke.getKeyStroke("ENTER"), "toggleReviewDetails");
    disclosure
        .getActionMap()
        .put(
            "toggleReviewDetails",
            new AbstractAction() {
              private static final long serialVersionUID = 1L;

              @Override
              public void actionPerformed(ActionEvent event) {
                disclosure.doClick();
              }
            });
    advanced.setName("reviewAdvanced");
    advancedContent.setName("reviewAdvancedContent");
    advancedContent.setVisible(false);
    raw.setLineWrap(false);
    raw.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 13));
    raw.setOpaque(true);
    raw.setBackground(AiTheme.background());
    raw.setText(model.rawJson());
    raw.setCaretPosition(0);
    final var rawScroll = scroll(raw, "reviewRawScroll");
    rawScroll.setPreferredSize(new Dimension(380, 240));
    advancedContent.add(metadata);
    advancedContent.add(rawTitle);
    advancedContent.add(rawScroll);
    advanced.add(disclosure);
    advanced.add(advancedContent);
    overview.add(advanced);
    tabs.addTab("", scroll(overview, "reviewOverviewScroll"));
    tabs.addTab("", scroll(changes, "reviewChangesScroll"));
    add(tabs, BorderLayout.CENTER);
    refreshLanguage();
  }

  void setApplyAvailable(boolean available) {
    applyAvailable = available;
    refreshEligibility();
    revalidate();
    repaint();
  }

  String rawJson() {
    return model.rawJson();
  }

  void refreshLanguage() {
    tabs.setTitleAt(0, S.get("aiReviewOverview"));
    tabs.setTitleAt(1, S.get("aiReviewChanges"));
    final var displayStatus =
        model.status() == ValidationStatus.PASS && !model.trustedPass()
            ? ValidationStatus.INCONCLUSIVE
            : model.status();
    update(
        status, S.get("aiReviewVerification") + " · " + AiStrings.validationStatus(displayStatus));
    status.setForeground(model.trustedPass() ? AiTheme.success() : AiTheme.foreground());
    final var sourceSummary = ReviewDetailsModel.text(model.verification().get("summary"));
    update(
        summary,
        sourceSummary.isBlank()
            ? S.get("aiReviewNoEvidence")
            : AiStrings.displayMessage(sourceSummary));
    refreshEligibility();
    if (specification != null) {
      specification.setStatus(S.get("aiSpecCardRecorded"));
      specification.refreshLanguage();
    }
    update(noSpecification, S.get("aiReviewMissingSpecification"));
    update(verificationTitle, S.get("aiReviewVerification"));
    update(passed, section("aiReviewPassedChecks", model.verification().get("passedChecks")));
    passed.setVisible(!ReviewDetailsModel.list(model.verification().get("passedChecks")).isEmpty());
    update(warnings, section("aiReviewWarnings", model.verification().get("warnings")));
    warnings.setVisible(!ReviewDetailsModel.list(model.verification().get("warnings")).isEmpty());
    final var failureRows = new ArrayList<String>();
    for (final var value : ReviewDetailsModel.list(model.verification().get("failures"))) {
      final var row = ReviewDetailsModel.object(value);
      if (row.keySet().containsAll(List.of("row", "signal", "expected", "actual"))) {
        failureRows.add(
            S.get(
                "aiReviewFailureRow",
                row.get("row"),
                row.get("signal"),
                row.get("expected"),
                row.get("actual")));
      } else
        failureRows.add(
            value instanceof String text ? AiStrings.displayMessage(text) : simpleJsonText(value));
    }
    update(
        failures,
        failureRows.isEmpty()
            ? model.verification().get("failures") instanceof List<?>
                ? S.get("aiReviewNoFailures")
                : S.get("aiReviewNoEvidence")
            : S.get("aiReviewFailures") + "\n" + String.join("\n", failureRows));
    failures.setForeground(failureRows.isEmpty() ? AiTheme.muted() : AiTheme.foreground());
    update(overviewChanges, changeSummary());
    update(changeSummary, changeSummary());
    update(changeHint, S.get("aiReviewChangeRecordHint"));
    changeHint.setForeground(AiTheme.muted());
    update(noChanges, S.get("aiReviewNoChanges"));
    for (final var record : records) record.refreshLanguage();
    updateDisclosure();
    update(metadata, metadata());
    metadata.setForeground(AiTheme.muted());
    update(rawTitle, S.get("aiReviewRawData"));
    getAccessibleContext().setAccessibleName(S.get("aiReviewDetails"));
    revalidate();
    repaint();
  }

  private void refreshEligibility() {
    update(
        eligibility,
        S.get(applyAvailable && model.trustedPass() ? "aiReviewCanApply" : "aiReviewCannotApply"));
    eligibility.setForeground(AiTheme.muted());
  }

  private void updateDisclosure() {
    disclosure.setText((disclosure.isSelected() ? "▾ " : "▸ ") + S.get("aiReviewAdvanced"));
    disclosure.getAccessibleContext().setAccessibleName(S.get("aiReviewAdvanced"));
  }

  private String changeSummary() {
    return S.get(
        "aiReviewChangeSummary",
        (int) model.count(true, ReviewDetailsModel.ObjectKind.COMPONENT),
        (int) model.count(true, ReviewDetailsModel.ObjectKind.WIRE),
        (int) model.count(false, ReviewDetailsModel.ObjectKind.COMPONENT),
        (int) model.count(false, ReviewDetailsModel.ObjectKind.WIRE),
        (int)
            model.changes().stream()
                .filter(
                    record ->
                        record.kind() != ReviewDetailsModel.ObjectKind.COMPONENT
                            && record.kind() != ReviewDetailsModel.ObjectKind.WIRE)
                .count());
  }

  private String metadata() {
    final var lines = new ArrayList<String>();
    meta(lines, "aiReviewTask", model.snapshot().get("taskId"));
    meta(lines, "aiReviewCandidate", model.snapshot().get("candidateId"));
    meta(lines, "aiReviewBaselineDigest", model.snapshot().get("baselineDigest"));
    meta(lines, "aiReviewCandidateDigest", model.snapshot().get("candidateDigest"));
    meta(lines, "aiReviewSpecDigest", model.verification().get("specificationDigest"));
    meta(lines, "aiReviewEvidenceDigest", model.verification().get("circuitDigest"));
    return String.join("\n", lines);
  }

  private static void meta(List<String> lines, String key, Object value) {
    final var text = ReviewDetailsModel.text(value);
    if (!text.isBlank()) lines.add(S.get(key) + ": " + text);
  }

  private static String section(String heading, Object items) {
    return S.get(heading)
        + "\n"
        + String.join(
            "\n",
            ReviewDetailsModel.list(items).stream()
                .map(value -> "• " + AiStrings.displayMessage(ReviewDetailsModel.text(value)))
                .toList());
  }

  private static String simpleJsonText(Object value) {
    return com.cburch.logisim.ai.util.SimpleJson.stringify(value);
  }

  private static final class RecordView extends FlowColumn {
    private static final long serialVersionUID = 1L;
    private final ReviewDetailsModel.ChangeRecord record;
    private final JTextArea title = text("reviewChangeTitle", 14, true);
    private final JTextArea description = text("reviewChangeDescription", 14, false);
    private final JToggleButton attributesToggle = new JToggleButton();
    private final JTextArea attributes = text("reviewAttributes", 13, false);

    RecordView(ReviewDetailsModel.ChangeRecord record) {
      super(true, 16, 8);
      this.record = record;
      setName(
          "reviewChange-"
              + (record.added() ? "added" : "removed")
              + "-"
              + record.kind().name().toLowerCase(java.util.Locale.ROOT));
      add(title);
      add(description);
      if (!record.attributes().isEmpty()) {
        attributesToggle.setName("reviewAttributesToggle");
        AiTheme.styleQuietButton(attributesToggle);
        attributesToggle.setHorizontalAlignment(SwingConstants.LEFT);
        attributesToggle.addActionListener(
            event -> {
              attributes.setVisible(attributesToggle.isSelected());
              refreshAttributeDisclosure();
              revalidate();
              repaint();
            });
        attributesToggle.getInputMap().put(KeyStroke.getKeyStroke("ENTER"), "toggleAttributes");
        attributesToggle
            .getActionMap()
            .put(
                "toggleAttributes",
                new AbstractAction() {
                  private static final long serialVersionUID = 1L;

                  @Override
                  public void actionPerformed(ActionEvent event) {
                    attributesToggle.doClick();
                  }
                });
        attributes.setVisible(false);
        attributes.setForeground(AiTheme.muted());
        attributes.setText(
            String.join(
                "\n",
                record.attributes().entrySet().stream()
                    .map(entry -> entry.getKey() + ": " + ReviewDetailsModel.text(entry.getValue()))
                    .toList()));
        attributes.setCaretPosition(0);
        add(attributesToggle);
        add(attributes);
      }
    }

    void refreshLanguage() {
      final var kind =
          switch (record.kind()) {
            case COMPONENT -> "aiReviewComponent";
            case WIRE -> "aiReviewWire";
            case CIRCUIT -> "aiReviewCircuit";
            case OTHER -> "aiReviewOther";
          };
      var label = S.get(record.added() ? "aiReviewAdded" : "aiReviewRemoved") + " · " + S.get(kind);
      if (!record.factoryName().isBlank()) label += " · " + factoryDisplay(record);
      final var componentLabel = ReviewDetailsModel.text(record.attributes().get("label"));
      if (!componentLabel.isBlank()) label += " · “" + componentLabel + "”";
      ReviewDetailsView.update(title, label);
      title.setForeground(record.added() ? AiTheme.accent() : AiTheme.foreground());
      final var body = new ArrayList<String>();
      if (!record.parsed()) body.add(S.get("aiReviewUnavailableRecord"));
      if (record.kind() == ReviewDetailsModel.ObjectKind.COMPONENT)
        body.add(S.get("aiReviewPosition", record.x(), record.y()));
      if (record.kind() == ReviewDetailsModel.ObjectKind.WIRE)
        body.add(S.get("aiReviewWireRoute", record.x(), record.y(), record.endX(), record.endY()));
      if (record.parsed()
          && record.kind() != ReviewDetailsModel.ObjectKind.WIRE
          && !record.text().isBlank()) body.add(record.text());
      ReviewDetailsView.update(description, String.join("\n", body));
      description.setVisible(!description.getText().isBlank());
      refreshAttributeDisclosure();
    }

    private void refreshAttributeDisclosure() {
      attributesToggle.setText(
          (attributesToggle.isSelected() ? "▾ " : "▸ ") + S.get("aiReviewAttributes"));
      attributesToggle.getAccessibleContext().setAccessibleName(S.get("aiReviewAttributes"));
    }
  }

  private static String factoryDisplay(ReviewDetailsModel.ChangeRecord record) {
    for (final var library : PRESENTATION_LIBRARIES) {
      for (final var tool : library.getTools()) {
        if (!(tool instanceof AddTool add)
            || !add.getFactory().getName().equals(record.factoryName())) continue;
        final var identity = record.identity();
        final boolean canonical =
            identity.size() == 3
                && identity.get(0).equals("library")
                && identity.get(1) instanceof List<?> path
                && path.contains(library.getName());
        final boolean fallback =
            identity.size() == 2 && identity.get(0).equals(add.getFactory().getClass().getName());
        if (canonical || fallback) return tool.getDisplayName();
      }
    }
    return record.factoryName();
  }

  private static JTextArea text(String name, float size, boolean bold) {
    final var text = new JTextArea();
    text.setName(name);
    text.setEditable(false);
    text.setOpaque(false);
    text.setBorder(null);
    text.setLineWrap(true);
    text.setWrapStyleWord(true);
    text.setFont(AiTheme.font(size).deriveFont(bold ? Font.BOLD : Font.PLAIN));
    text.setForeground(AiTheme.foreground());
    return text;
  }

  private static void update(JTextArea text, String content) {
    if (text.getText().equals(content)) return;
    final var start = text.getSelectionStart();
    final var end = text.getSelectionEnd();
    text.setText(content);
    text.select(Math.min(start, content.length()), Math.min(end, content.length()));
  }

  private static JScrollPane scroll(JComponent content, String name) {
    final var pane = new JScrollPane(content);
    pane.setName(name);
    pane.setBorder(BorderFactory.createEmptyBorder());
    pane.setBackground(AiTheme.background());
    pane.getViewport().setBackground(AiTheme.background());
    pane.getVerticalScrollBar().setUnitIncrement(20);
    if (content instanceof FlowColumn)
      pane.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
    return pane;
  }

  /** Width-aware vertical flow, allowing specification and prose to grow inside a scrolling tab. */
  private static class FlowColumn extends JPanel implements Scrollable {
    private static final long serialVersionUID = 1L;
    private final boolean card;
    private final int padding;
    private final int gap;

    FlowColumn(boolean card, int padding, int gap) {
      this.card = card;
      this.padding = padding;
      this.gap = gap;
      setLayout(null);
      setOpaque(false);
    }

    private int measure(int width, boolean layout) {
      final int inner = Math.max(1, width - padding * 2);
      var y = padding;
      var count = 0;
      for (final var child : getComponents()) {
        if (!child.isVisible()) continue;
        if (count++ > 0) y += gap;
        child.setSize(inner, Math.max(1, child.getHeight()));
        final int height;
        if (child instanceof JTextArea text) {
          final var view = text.getUI().getRootView(text);
          view.setSize(inner, Integer.MAX_VALUE);
          height =
              text.getText().isBlank() ? 0 : (int) Math.ceil(view.getPreferredSpan(View.Y_AXIS));
        } else height = child.getPreferredSize().height;
        if (layout) child.setBounds(padding, y, inner, height);
        y += height;
      }
      return y + padding;
    }

    @Override
    public Dimension getPreferredSize() {
      final var parent = getParent();
      final var parentInsets = parent == null ? null : parent.getInsets();
      final int width =
          parent != null && parent.getLayout() instanceof BorderLayout
              ? Math.max(1, parent.getWidth() - parentInsets.left - parentInsets.right)
              : getWidth() > 0
                  ? getWidth()
                  : getParent() != null && getParent().getWidth() > 0
                      ? getParent().getWidth()
                      : 520;
      return new Dimension(width, measure(width, false));
    }

    @Override
    public Dimension getMinimumSize() {
      return new Dimension(0, 0);
    }

    @Override
    public void doLayout() {
      measure(getWidth(), true);
    }

    @Override
    protected void paintComponent(Graphics graphics) {
      super.paintComponent(graphics);
      if (!card) return;
      final var canvas = (Graphics2D) graphics.create();
      try {
        canvas.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        canvas.setColor(AiTheme.surface());
        canvas.fillRoundRect(
            0, 0, Math.max(0, getWidth() - 1), Math.max(0, getHeight() - 1), 22, 22);
        canvas.setColor(AiTheme.border());
        canvas.drawRoundRect(
            0, 0, Math.max(0, getWidth() - 1), Math.max(0, getHeight() - 1), 22, 22);
      } finally {
        canvas.dispose();
      }
    }

    @Override
    public Dimension getPreferredScrollableViewportSize() {
      return getPreferredSize();
    }

    @Override
    public int getScrollableUnitIncrement(Rectangle visibleRect, int orientation, int direction) {
      return 20;
    }

    @Override
    public int getScrollableBlockIncrement(Rectangle visibleRect, int orientation, int direction) {
      return Math.max(20, visibleRect.height - 20);
    }

    @Override
    public boolean getScrollableTracksViewportWidth() {
      return true;
    }

    @Override
    public boolean getScrollableTracksViewportHeight() {
      return false;
    }
  }
}
