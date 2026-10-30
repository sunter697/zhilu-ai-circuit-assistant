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
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.cburch.logisim.ai.util.SwingExecutor;
import com.cburch.logisim.ai.validation.TaskSpec;
import com.cburch.logisim.ai.validation.ValidationPlan;
import com.cburch.logisim.util.LocaleManager;
import java.awt.Component;
import java.awt.GridLayout;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import javax.swing.JButton;
import javax.swing.JPanel;
import javax.swing.JTextArea;
import javax.swing.text.View;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SpecificationCardTest {
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
  void typedCardDisplaysExactSignalsBehaviorAndAcceptanceWithoutChangingTheContract() {
    SwingExecutor.run(
        () -> {
          final var spec = booleanSpec("~(A XOR B)");
          final var machine = spec.toMap();
          final var digest = spec.digest();
          final var oracle = new ValidationPlan(spec).expectedRows();
          final var card = new SpecificationCard(spec);
          card.setStatus(S.get("aiSpecCardPending"));
          layout(card, 340);
          assertSame(spec, card.specification());
          assertEquals(4, spec.exhaustiveRows());
          assertEquals(
              S.get("aiSpecSignal", "A", 1).substring(2)
                  + "   "
                  + S.get("aiSpecSignal", "B", 1).substring(2),
              area(card, "specificationInputs").getText());
          assertEquals(
              S.get("aiSpecSignal", "Y", 1).substring(2),
              area(card, "specificationOutputs").getText());
          assertEquals(
              S.get("aiSpecAcceptance", 4), area(card, "specificationAcceptance").getText());
          assertEquals("Y = ¬(A⊻B)", area(card, "specificationFunction-0").getText());
          assertTrue(area(card, "specificationFunction-0").getText().contains("Y = ¬(A⊻B)"));
          assertTrue(area(card, "specificationFunction-1").getText().contains("equal: Y = 1"));
          assertFalse(allText(card).contains("\"kind\""));
          assertTrue(
              java.util.Arrays.stream(card.getComponents())
                  .filter(c -> c instanceof JTextArea)
                  .map(c -> (JTextArea) c)
                  .allMatch(c -> !c.isEditable() && c.getLineWrap()));
          assertEquals(machine, spec.toMap());
          assertEquals(digest, spec.digest());
          assertEquals(oracle, new ValidationPlan(spec).expectedRows());
          assertFits(card);
        });
  }

  @Test
  void multiplicationCardShowsLowToHighProductSlicesAndActualRows() {
    SwingExecutor.run(
        () -> {
          final var spec =
              new TaskSpec(
                  TaskSpec.Kind.UNSIGNED_MULTIPLICATION,
                  List.of(new TaskSpec.SignalSpec("A", 4), new TaskSpec.SignalSpec("B", 4)),
                  List.of(new TaskSpec.SignalSpec("LO", 4), new TaskSpec.SignalSpec("HI", 4)),
                  Map.of());
          final var card = new SpecificationCard(spec);
          layout(card, 340);
          assertEquals(256, spec.exhaustiveRows());
          assertEquals(
              S.get("aiSpecProductSlice", "LO", 3, 0).substring(2),
              area(card, "specificationFunction-1").getText());
          assertEquals(
              S.get("aiSpecProductSlice", "HI", 7, 4).substring(2),
              area(card, "specificationFunction-2").getText());
          assertEquals(
              S.get("aiSpecAcceptance", 256), area(card, "specificationAcceptance").getText());
          assertEquals("least-significant-first", spec.toMap().get("outputSliceOrder"));
          assertFits(card);
        });
  }

  @Test
  void narrowCardsGrowToFitWrappedTextAndActionsAndShrinkAgainAfterResize() {
    SwingExecutor.run(
        () -> {
          final var card = new SpecificationCard(booleanSpec("~(A XOR (A AND B))"));
          card.setStatus("Awaiting confirmation of the proposed functional specification");
          final var actions = new JPanel(new GridLayout(1, 2, 9, 0));
          actions.add(new JButton("Confirm"));
          actions.add(new JButton("Revise"));
          card.setActionComponent(actions);
          layout(card, 860);
          final int broadHeight = card.getHeight();
          assertFits(card);
          layout(card, 340);
          assertTrue(card.getHeight() > broadHeight);
          assertFits(card);
          layout(card, 210);
          assertFits(card);
          layout(card, 860);
          assertEquals(broadHeight, card.getHeight());
          assertFits(card);
        });
  }

  @Test
  void localeRefreshKeepsAttachedActionsWorkingAndOnlyChangesPresentation() {
    SwingExecutor.run(
        () -> {
          final var spec = booleanSpec("~(A XOR B)");
          final var machine = spec.toMap();
          final var digest = spec.digest();
          final var card = new SpecificationCard(spec);
          card.setStatus(S.get("aiSpecCardPending"));
          final var clickCount = new AtomicInteger();
          final var button = new JButton("Confirm");
          button.addActionListener(event -> clickCount.incrementAndGet());
          final var actions = new JPanel(new GridLayout(1, 1));
          actions.add(button);
          card.setActionComponent(actions);
          final var originalTitle = area(card, "specificationTitle");
          final var originalAcceptance = area(card, "specificationAcceptance");
          SwingExecutor.run(() -> LocaleManager.setLocale(Locale.SIMPLIFIED_CHINESE));
          card.refreshLanguage();
          layout(card, 340);
          assertSame(originalTitle, area(card, "specificationTitle"));
          assertSame(originalAcceptance, area(card, "specificationAcceptance"));
          assertEquals(S.get("aiSpecCardTitle"), originalTitle.getText());
          assertEquals(S.get("aiSpecCardPending"), area(card, "specificationStatus").getText());
          assertEquals(S.get("aiSpecAcceptance", 4), originalAcceptance.getText());
          assertSame(card, actions.getParent());
          button.doClick(0);
          assertEquals(1, clickCount.get());
          assertEquals(machine, spec.toMap());
          assertEquals(digest, spec.digest());
          assertFits(card);
        });
  }

  @Test
  void replacingAndRemovingActionComponentDetachesPreviousButtonsAndChangesHeight() {
    SwingExecutor.run(
        () -> {
          final var card = new SpecificationCard(booleanSpec("A XOR B"));
          final var first = new JPanel();
          first.add(new JButton("Confirm"));
          card.setActionComponent(first);
          layout(card, 340);
          final var withActions = card.getHeight();
          final var second = new JPanel();
          second.add(new JButton("Revise"));
          card.setActionComponent(second);
          assertNull(first.getParent());
          assertSame(card, second.getParent());
          card.setActionComponent(null);
          layout(card, 340);
          assertNull(second.getParent());
          assertTrue(card.getHeight() < withActions);
          assertFits(card);
        });
  }

  private static TaskSpec booleanSpec(String expression) {
    return TaskSpec.fromArguments(
        Map.of(
            "kind", "BOOLEAN_EXPRESSIONS",
            "inputs", List.of(Map.of("name", "A", "width", 1), Map.of("name", "B", "width", 1)),
            "outputs", List.of(Map.of("name", "Y", "width", 1)),
            "expressions", Map.of("Y", expression)));
  }

  private static void layout(SpecificationCard card, int width) {
    card.setSize(width, 1);
    card.setSize(width, card.getPreferredSize().height);
    card.doLayout();
  }

  private static JTextArea area(SpecificationCard card, String name) {
    return java.util.Arrays.stream(card.getComponents())
        .filter(c -> name.equals(c.getName()))
        .map(c -> (JTextArea) c)
        .findFirst()
        .orElseThrow();
  }

  private static String allText(SpecificationCard card) {
    return String.join(
        "\n",
        java.util.Arrays.stream(card.getComponents())
            .filter(c -> c instanceof JTextArea)
            .map(c -> ((JTextArea) c).getText())
            .toList());
  }

  private static void assertFits(SpecificationCard card) {
    for (final Component child : card.getComponents()) {
      assertTrue(child.getX() >= 0, child.getName());
      assertTrue(child.getY() >= 0, child.getName());
      assertTrue(child.getX() + child.getWidth() <= card.getWidth(), child.getName());
      assertTrue(child.getY() + child.getHeight() <= card.getHeight(), child.getName());
      if (child instanceof JTextArea area && !area.getText().isBlank()) {
        final var view = area.getUI().getRootView(area);
        view.setSize(area.getWidth(), Integer.MAX_VALUE);
        assertTrue(
            Math.ceil(view.getPreferredSpan(View.Y_AXIS)) <= area.getHeight(), area.getName());
        final var lastLine =
            assertDoesNotThrow(() -> area.modelToView2D(area.getDocument().getLength()));
        assertTrue(lastLine.getMaxY() <= area.getHeight() + 1, area.getName());
      }
    }
  }
}
