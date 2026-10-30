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
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.cburch.logisim.ai.util.SimpleJson;
import com.cburch.logisim.ai.util.SwingExecutor;
import com.cburch.logisim.ai.validation.TaskSpec;
import com.cburch.logisim.ai.validation.ValidationStatus;
import com.cburch.logisim.util.LocaleManager;
import java.awt.Component;
import java.awt.Container;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import javax.swing.AbstractButton;
import javax.swing.JPanel;
import javax.swing.JTabbedPane;
import javax.swing.JTextArea;
import javax.swing.JToggleButton;
import javax.swing.text.View;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ReviewDetailsViewTest {
  private Locale originalLocale;

  @BeforeEach
  void rememberLocale() {
    originalLocale = SwingExecutor.call(LocaleManager::getLocale);
    SwingExecutor.run(() -> LocaleManager.setLocale(Locale.ENGLISH));
  }

  @AfterEach
  void restoreLocale() {
    SwingExecutor.run(() -> LocaleManager.setLocale(originalLocale));
  }

  @Test
  void detachedSnapshotPreservesFullJsonAndCannotMutateOriginalReview() {
    final var details = details(booleanSpec());
    final var nested = new LinkedHashMap<>(Map.of("path", "debug/path", "text", "x".repeat(8000)));
    details.put("debug", nested);
    final var expected = SimpleJson.parse(SimpleJson.stringify(details));
    final var model = new ReviewDetailsModel(details);
    nested.put("text", "changed later");
    details.put("taskId", "new task");
    assertEquals(expected, SimpleJson.parse(model.rawJson()));
    assertTrue(model.rawJson().length() > 8000);
    assertThrows(UnsupportedOperationException.class, () -> model.snapshot().put("taskId", "bad"));
    assertThrows(
        UnsupportedOperationException.class,
        () -> ReviewDetailsModel.object(model.snapshot().get("debug")).put("text", "bad"));
  }

  @Test
  void canonicalRecordsKeepFactoryIdentityCoordinatesAttributesAndDistinctOperations() {
    final var details = details(booleanSpec());
    details.put(
        "diff",
        Map.of(
            "added",
            List.of(
                component(
                    List.of("library", List.of("Wiring"), "Pin"),
                    20,
                    50,
                    Map.of("label", "A", "width", "1")),
                component(
                    List.of("com.cburch.logisim.std.gates.XnorGate", "XNOR Gate"),
                    100,
                    100,
                    Map.of()),
                "wire|20,50|100,50|none",
                "circuit=\"main\"",
                "circuitAttributes={\"appearance\":\"classic\"}"),
            "removed",
            List.of(
                component(
                    List.of("library", List.of("Wiring"), "Pin"), 10, 50, Map.of("label", "A"))),
            "changed",
            true));
    final var model = new ReviewDetailsModel(details);
    assertEquals(6, model.changes().size());
    assertEquals(2, model.count(true, ReviewDetailsModel.ObjectKind.COMPONENT));
    assertEquals(1, model.count(false, ReviewDetailsModel.ObjectKind.COMPONENT));
    assertEquals(1, model.count(true, ReviewDetailsModel.ObjectKind.WIRE));
    assertEquals(2, model.count(true, ReviewDetailsModel.ObjectKind.CIRCUIT));
    final var pin = model.changes().get(1);
    assertEquals("Pin", pin.factoryName());
    assertEquals("20", pin.x());
    assertEquals("50", pin.y());
    assertEquals("A", pin.attributes().get("label"));
    assertEquals("XNOR Gate", model.changes().get(2).factoryName());
    assertEquals("100", model.changes().get(3).endX());
    assertTrue(model.changes().stream().allMatch(ReviewDetailsModel.ChangeRecord::parsed));
  }

  @Test
  void malformedRecordsStayVisibleAsOtherWithoutInventedComponentsOrRoutes() {
    final var malformed =
        List.of(
            "component|not-json",
            "wire|1,2|3|none",
            "circuit=17",
            component(List.of("library", List.of("Wiring"), "Pin"), 1.5, 50, Map.of()),
            "unexpected|payload");
    final var details = details(booleanSpec());
    details.put("diff", Map.of("added", malformed, "removed", List.of()));
    final var model = new ReviewDetailsModel(details);
    assertEquals(
        malformed, model.changes().stream().map(ReviewDetailsModel.ChangeRecord::text).toList());
    assertTrue(
        model.changes().stream()
            .allMatch(
                record ->
                    !record.parsed() && record.kind() == ReviewDetailsModel.ObjectKind.OTHER));
    assertEquals(0, model.count(true, ReviewDetailsModel.ObjectKind.COMPONENT));
    SwingExecutor.run(
        () -> {
          final var view = new ReviewDetailsView(details, false);
          assertTrue(allText(view).contains("component|not-json"));
          assertTrue(allText(view).contains(S.get("aiReviewUnavailableRecord")));
          ((JTabbedPane) named(view, "reviewTabs")).setSelectedIndex(1);
          assertFalse(visibleText(view).contains("component|not-json"));
        });
  }

  @Test
  void currentEligibilityFollowsHostPermissionWithoutAddingMutationActions() {
    SwingExecutor.run(
        () -> {
          final var view = new ReviewDetailsView(details(booleanSpec()), true);
          assertEquals(S.get("aiReviewCanApply"), area(view, "reviewEligibility").getText());
          view.setApplyAvailable(false);
          assertEquals(S.get("aiReviewCannotApply"), area(view, "reviewEligibility").getText());
          view.setApplyAvailable(true);
          assertEquals(S.get("aiReviewCanApply"), area(view, "reviewEligibility").getText());
          assertTrue(
              descendants(view).stream()
                  .filter(component -> component instanceof AbstractButton)
                  .map(component -> (AbstractButton) component)
                  .filter(button -> button.getText() != null && !button.getText().isBlank())
                  .allMatch(component -> component instanceof JToggleButton));
        });
  }

  @Test
  void incompleteOrContradictoryPassDoesNotShowGreenPassOrAvailableApply() {
    SwingExecutor.run(
        () -> {
          final var good = details(booleanSpec());
          final var noEvidence = new LinkedHashMap<>(good);
          noEvidence.put(
              "verification", Map.of("status", "PASS", "summary", "Claim without digests"));
          final var mismatch = new LinkedHashMap<>(good);
          mismatch.put("candidateDigest", "different-candidate");
          final var contradictory = new LinkedHashMap<>(good);
          final var evidence =
              new LinkedHashMap<>(ReviewDetailsModel.object(good.get("verification")));
          evidence.put(
              "failures", List.of(Map.of("row", 0, "signal", "Y", "expected", "1", "actual", "x")));
          contradictory.put("verification", evidence);
          for (final var details : List.of(noEvidence, mismatch, contradictory)) {
            final var view = new ReviewDetailsView(details, true);
            assertFalse(new ReviewDetailsModel(details).trustedPass());
            assertTrue(
                area(view, "reviewStatus")
                    .getText()
                    .contains(aiStringsStatus(ValidationStatus.INCONCLUSIVE)));
            assertNotEquals(AiTheme.success(), area(view, "reviewStatus").getForeground());
            assertEquals(S.get("aiReviewCannotApply"), area(view, "reviewEligibility").getText());
          }
        });
  }

  @Test
  void exactSpecificationKeepsActualFourAndTwoHundredFiftySixRows() {
    SwingExecutor.run(
        () -> {
          for (final var spec : List.of(booleanSpec(), multiplicationSpec())) {
            final var before = spec.toMap();
            final var digest = spec.digest();
            final var view = new ReviewDetailsView(details(spec), true);
            assertEquals(
                S.get("aiSpecAcceptance", spec.exhaustiveRows()),
                area(view, "specificationAcceptance").getText());
            assertEquals(S.get("aiSpecCardRecorded"), area(view, "specificationStatus").getText());
            assertEquals(before, spec.toMap());
            assertEquals(digest, spec.digest());
            assertFalse(visibleText(view).contains("\"kind\""));
          }
        });
  }

  @Test
  void technicalDisclosureTabAndRawSelectionSurviveLocaleRefreshAndRefold() {
    SwingExecutor.run(
        () -> {
          final var view = new ReviewDetailsView(details(booleanSpec()), true);
          final var toggle = (JToggleButton) named(view, "reviewAdvancedToggle");
          final var advanced = (JPanel) named(view, "reviewAdvancedContent");
          final var tabs = (JTabbedPane) named(view, "reviewTabs");
          final var raw = area(view, "reviewRawJson");
          assertFalse(advanced.isVisible());
          toggle.doClick();
          assertTrue(advanced.isVisible());
          raw.select(8, 37);
          final var selected = raw.getSelectedText();
          final var payload = raw.getText();
          tabs.setSelectedIndex(1);
          LocaleManager.setLocale(Locale.SIMPLIFIED_CHINESE);
          view.refreshLanguage();
          assertEquals(1, tabs.getSelectedIndex());
          assertTrue(toggle.isSelected());
          assertTrue(advanced.isVisible());
          assertEquals(selected, raw.getSelectedText());
          assertEquals(payload, raw.getText());
          assertEquals(S.get("aiReviewChanges"), tabs.getTitleAt(1));
          toggle.doClick();
          assertFalse(advanced.isVisible());
          assertEquals(selected, raw.getSelectedText());
        });
  }

  @Test
  void recordAttributesDefaultToFoldedAndKeepSelectionAndExpansionAcrossLocales() {
    SwingExecutor.run(
        () -> {
          final var details = details(booleanSpec());
          details.put(
              "diff",
              Map.of(
                  "added",
                  List.of(
                      component(
                          List.of("library", List.of("Wiring"), "Pin"),
                          20,
                          50,
                          Map.of("label", "A", "width", "4", "facing", "east"))),
                  "removed",
                  List.of()));
          final var view = new ReviewDetailsView(details, true);
          final var tabs = (JTabbedPane) named(view, "reviewTabs");
          tabs.setSelectedIndex(1);
          final var toggle = (JToggleButton) named(view, "reviewAttributesToggle");
          final var attributes = area(view, "reviewAttributes");
          assertFalse(toggle.isSelected());
          assertFalse(attributes.isVisible());
          assertFalse(visibleText(view).contains("facing: east"));
          assertTrue(attributes.getText().contains("label: A"));
          assertTrue(attributes.getText().contains("width: 4"));
          toggle.doClick();
          assertTrue(attributes.isVisible());
          assertTrue(visibleText(view).contains("facing: east"));
          attributes.select(2, 8);
          final var selected = attributes.getSelectedText();
          LocaleManager.setLocale(Locale.SIMPLIFIED_CHINESE);
          view.refreshLanguage();
          assertTrue(toggle.isSelected());
          assertTrue(attributes.isVisible());
          assertEquals(selected, attributes.getSelectedText());
          assertEquals("▾ " + S.get("aiReviewAttributes"), toggle.getText());
          toggle.doClick();
          assertFalse(attributes.isVisible());
          assertEquals(selected, attributes.getSelectedText());
        });
  }

  @Test
  void counterexamplesShowRowSignalExpectedAndActualUnknownOutputWithoutHidingFailure() {
    SwingExecutor.run(
        () -> {
          final var details = details(booleanSpec());
          final var evidence =
              new LinkedHashMap<>(ReviewDetailsModel.object(details.get("verification")));
          evidence.put("status", "FAIL");
          evidence.put("summary", "1 output mismatches (unknown/error values also fail).");
          evidence.put("warnings", List.of("A diagnostic note"));
          evidence.put(
              "failures", List.of(Map.of("row", 2, "signal", "Y", "expected", "0", "actual", "x")));
          details.put("verification", evidence);
          final var view = new ReviewDetailsView(details, false);
          assertEquals(
              S.get("aiReviewFailures") + "\n" + S.get("aiReviewFailureRow", 2, "Y", "0", "x"),
              area(view, "reviewFailures").getText());
          assertTrue(area(view, "reviewFailures").isVisible());
          assertTrue(area(view, "reviewWarnings").getText().contains("A diagnostic note"));
          assertNotEquals(AiTheme.success(), area(view, "reviewStatus").getForeground());
        });
  }

  @Test
  void tabsAndCardsFitAtMinimumAndPreferredWidthsAndRemainReadOnly() {
    SwingExecutor.run(
        () -> {
          final var details = details(multiplicationSpec());
          details.put(
              "diff",
              Map.of(
                  "added",
                  List.of(
                      component(
                          List.of("library", List.of("Wiring"), "Pin"),
                          20,
                          50,
                          Map.of(
                              "label", "a_long_label_".repeat(8), "width", "4", "facing", "east")),
                      "wire|20,50|100,50|none"),
                  "removed",
                  List.of()));
          final var view = new ReviewDetailsView(details, true);
          for (final var locale :
              List.of(Locale.ENGLISH, Locale.SIMPLIFIED_CHINESE, Locale.GERMAN)) {
            LocaleManager.setLocale(locale);
            view.refreshLanguage();
            for (final int width : List.of(520, 720)) {
              for (final int tab : List.of(0, 1)) {
                ((JTabbedPane) named(view, "reviewTabs")).setSelectedIndex(tab);
                view.setSize(width, 700);
                for (var pass = 0; pass < 3; pass++) layout(view);
                for (final var component : descendants(view)) {
                  if (!(component instanceof JTextArea text)
                      || !effectivelyVisible(text)
                      || text.getName().equals("reviewRawJson")) continue;
                  assertFalse(text.isEditable());
                  assertTrue(text.getWidth() > 0, text.getName());
                  assertTrue(
                      text.getX() >= 0
                          && text.getX() + text.getWidth() <= text.getParent().getWidth(),
                      text.getName());
                  final var root = text.getUI().getRootView(text);
                  root.setSize(text.getWidth(), Integer.MAX_VALUE);
                  assertTrue(
                      text.getHeight() >= Math.ceil(root.getPreferredSpan(View.Y_AXIS)),
                      text.getName());
                }
              }
            }
          }
        });
  }

  private static String aiStringsStatus(ValidationStatus status) {
    return com.cburch.logisim.ai.i18n.AiStrings.validationStatus(status);
  }

  static Map<String, Object> details(TaskSpec spec) {
    final var result = new LinkedHashMap<String, Object>();
    result.put("taskId", "task-123");
    result.put("candidateId", "candidate-123");
    result.put("candidateDigest", "candidate-digest");
    result.put("baselineDigest", "baseline-digest");
    result.put("specification", spec.toMap());
    result.put(
        "verification",
        Map.of(
            "status",
            "PASS",
            "summary",
            "All "
                + spec.exhaustiveRows()
                + " exhaustive rows matched the confirmed specification.",
            "passedChecks",
            List.of(
                "Logisim simulation completed",
                "Truth table "
                    + spec.exhaustiveRows()
                    + "/"
                    + spec.exhaustiveRows()
                    + " rows matched"),
            "warnings",
            List.of(),
            "failures",
            List.of(),
            "specificationDigest",
            spec.digest(),
            "circuitDigest",
            "candidate-digest"));
    result.put("diff", Map.of("added", List.of(), "removed", List.of(), "changed", false));
    return result;
  }

  private static TaskSpec booleanSpec() {
    return new TaskSpec(
        TaskSpec.Kind.BOOLEAN_EXPRESSIONS,
        List.of(new TaskSpec.SignalSpec("A", 1), new TaskSpec.SignalSpec("B", 1)),
        List.of(new TaskSpec.SignalSpec("Y", 1)),
        Map.of("Y", "~(A XOR B)"));
  }

  private static TaskSpec multiplicationSpec() {
    return new TaskSpec(
        TaskSpec.Kind.UNSIGNED_MULTIPLICATION,
        List.of(new TaskSpec.SignalSpec("A", 4), new TaskSpec.SignalSpec("B", 4)),
        List.of(new TaskSpec.SignalSpec("LO", 4), new TaskSpec.SignalSpec("HI", 4)),
        Map.of());
  }

  private static String component(List<?> identity, Number x, Number y, Map<String, ?> attributes) {
    return "component|" + SimpleJson.stringify(List.of(identity, x, y, attributes));
  }

  private static List<Component> descendants(Container root) {
    final var result = new ArrayList<Component>();
    for (final var component : root.getComponents()) {
      result.add(component);
      if (component instanceof Container container) result.addAll(descendants(container));
    }
    return result;
  }

  private static Component named(Container root, String name) {
    return descendants(root).stream()
        .filter(component -> name.equals(component.getName()))
        .findFirst()
        .orElseThrow();
  }

  private static JTextArea area(Container root, String name) {
    return (JTextArea) named(root, name);
  }

  private static String allText(Container root) {
    return String.join(
        "\n",
        descendants(root).stream()
            .filter(component -> component instanceof JTextArea)
            .map(component -> ((JTextArea) component).getText())
            .toList());
  }

  private static boolean effectivelyVisible(Component component) {
    for (Component current = component; current != null; current = current.getParent())
      if (!current.isVisible()) return false;
    return true;
  }

  private static String visibleText(Container root) {
    return String.join(
        "\n",
        descendants(root).stream()
            .filter(component -> component instanceof JTextArea && effectivelyVisible(component))
            .map(component -> ((JTextArea) component).getText())
            .toList());
  }

  private static void layout(Container container) {
    container.doLayout();
    for (final var component : container.getComponents())
      if (component instanceof Container child && child.isVisible()) layout(child);
  }
}
