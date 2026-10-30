/*
 * Logisim-evolution - digital logic design tool and simulator
 * Copyright by the Logisim-evolution developers
 *
 * https://github.com/logisim-evolution/
 *
 * This is free software released under GNU GPLv3 license
 */

package com.cburch.logisim.ai.validation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.cburch.logisim.ai.i18n.AiStrings;
import com.cburch.logisim.ai.util.SwingExecutor;
import com.cburch.logisim.util.LocaleManager;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SpecPresentationTest {
  private Locale originalLocale;

  @BeforeEach
  void rememberLocale() {
    originalLocale = SwingExecutor.call(LocaleManager::getLocale);
    setLocale(Locale.SIMPLIFIED_CHINESE);
  }

  @AfterEach
  void restoreLocale() {
    setLocale(originalLocale);
  }

  private static void setLocale(Locale locale) {
    SwingExecutor.run(() -> LocaleManager.setLocale(locale));
  }

  private static TaskSpec booleanSpec(String expression) {
    return TaskSpec.fromArguments(
        Map.of(
            "kind", "BOOLEAN_EXPRESSIONS",
            "inputs", List.of(Map.of("name", "A", "width", 1), Map.of("name", "B", "width", 1)),
            "outputs", List.of(Map.of("name", "Y", "width", 1)),
            "expressions", Map.of("Y", expression)));
  }

  @Test
  void confirmedFunctionShowsSignalsMeaningAndActualAcceptanceWithoutChangingContract() {
    final var specification = booleanSpec("~(A XOR B)");
    final var contract = specification.toMap();
    final var digest = specification.digest();
    final var oracle = new ValidationPlan(specification).expectedRows();
    final var description = specification.describe();
    assertTrue(description.contains("输入\n• A：1 位\n• B：1 位"));
    assertTrue(description.contains("输出\n• Y：1 位"));
    assertTrue(description.contains("Y = ¬(A⊻B)"));
    assertTrue(description.contains("A 与 B 相同时，Y=1；不同时，Y=0"));
    assertTrue(description.contains("穷举全部 4 组"));
    assertTrue(description.contains("未知或错误输出均视为失败"));
    assertFalse(description.contains("\"kind\""));
    assertFalse(description.contains("10 个输入位"));
    assertEquals(contract, specification.toMap());
    assertEquals(digest, specification.digest());
    assertEquals(oracle, new ValidationPlan(specification).expectedRows());
  }

  @Test
  void equivalentAndNegatedParityNotationsHaveCorrectHumanMeaning() {
    for (final var expression : List.of("A EQUALS B", "NOT (A XOR B)", "~(~(A EQUALS B))")) {
      final var specification = booleanSpec(expression);
      assertTrue(specification.describe().contains("A 与 B 相同时，Y=1；不同时，Y=0"));
      assertEquals(
          List.of("1", "0", "0", "1"),
          new ValidationPlan(specification)
              .expectedRows().stream().map(row -> row.get(2)).toList());
    }
    final var different = booleanSpec("NOT (A EQUALS B)");
    assertTrue(different.describe().contains("A 与 B 不同时，Y=1；相同时，Y=0"));
    assertEquals(
        List.of("0", "1", "1", "0"),
        new ValidationPlan(different).expectedRows().stream().map(row -> row.get(2)).toList());
  }

  @Test
  void inputNamesThatLookLikeOperatorsRemainLiteralIdentifiers() {
    final var specification =
        TaskSpec.fromArguments(
            Map.of(
                "kind", "BOOLEAN_EXPRESSIONS",
                "inputs",
                    List.of(Map.of("name", "AND", "width", 1), Map.of("name", "OR", "width", 1)),
                "outputs", List.of(Map.of("name", "Y", "width", 1)),
                "expressions", Map.of("Y", "~(AND XOR OR)")));
    final var description = specification.describe();
    assertTrue(description.contains("Y = ¬(AND⊻OR)"));
    assertTrue(description.contains("AND 与 OR 相同时，Y=1"));
    assertEquals(
        List.of("1", "0", "0", "1"),
        new ValidationPlan(specification).expectedRows().stream().map(row -> row.get(2)).toList());
  }

  @Test
  void compoundTermsDoNotReceiveAnIncorrectTwoSignalExplanation() {
    final var specification = booleanSpec("~(A XOR (A AND B))");
    final var description = specification.describe();
    assertTrue(description.contains("∧"));
    assertFalse(description.contains("A 与 B 相同时"));
    assertFalse(description.contains("A 与 B 不同时"));
    assertTrue(description.contains("穷举全部 4 组"));
  }

  @Test
  void alreadyDisplayedSectionsFollowLocaleChangesWhileMachineContractStaysFixed() {
    final var specification = booleanSpec("~(A ^ B)");
    final var contract = specification.toMap();
    final var digest = specification.digest();
    setLocale(Locale.ENGLISH);
    final var english = specification.describe();
    assertTrue(english.contains("Inputs\n• A: 1 bit(s)"));
    assertTrue(english.contains("A and B equal: Y = 1; different: Y = 0."));
    setLocale(Locale.SIMPLIFIED_CHINESE);
    final var chinese = AiStrings.displayMessage(english);
    assertTrue(chinese.contains("输入\n• A：1 位"));
    assertTrue(chinese.contains("A 与 B 相同时，Y=1"));
    assertTrue(chinese.contains("穷举全部 4 组"));
    assertFalse(chinese.contains("Inputs"));
    assertFalse(chinese.contains("Check all"));
    assertEquals(contract, specification.toMap());
    assertEquals(digest, specification.digest());
    setLocale(Locale.ENGLISH);
    assertEquals(english, AiStrings.displayMessage(chinese));
  }

  @Test
  void multiplicationShowsPreciseLowToHighSlicesAndAll256Inputs() {
    final var specification =
        new TaskSpec(
            TaskSpec.Kind.UNSIGNED_MULTIPLICATION,
            List.of(new TaskSpec.SignalSpec("A", 4), new TaskSpec.SignalSpec("B", 4)),
            List.of(
                new TaskSpec.SignalSpec("LO", 3),
                new TaskSpec.SignalSpec("MID", 2),
                new TaskSpec.SignalSpec("HI", 3)),
            Map.of());
    final var description = specification.describe();
    assertTrue(description.contains("乘积 = A × B（无符号）"));
    assertTrue(description.contains("LO = 乘积位 [2:0]"));
    assertTrue(description.contains("MID = 乘积位 [4:3]"));
    assertTrue(description.contains("HI = 乘积位 [7:5]"));
    assertTrue(description.contains("输出按低位到高位分段"));
    assertTrue(description.contains("穷举全部 256 组"));
    assertEquals("least-significant-first", specification.toMap().get("outputSliceOrder"));
    assertEquals(256, new ValidationPlan(specification).expectedRows().size());
  }

  @Test
  void outputDisplayOrderUsesDeclaredSignalsInsteadOfExpressionMapOrder() {
    final var expressions = new LinkedHashMap<String, String>();
    expressions.put("Y", "A");
    expressions.put("Z", "~A");
    final var specification =
        new TaskSpec(
            TaskSpec.Kind.BOOLEAN_EXPRESSIONS,
            List.of(new TaskSpec.SignalSpec("A", 1)),
            List.of(new TaskSpec.SignalSpec("Z", 1), new TaskSpec.SignalSpec("Y", 1)),
            expressions);
    final var description = specification.describe();
    assertTrue(description.indexOf("• Z = ¬A") < description.indexOf("• Y = A"));
    assertTrue(description.contains("穷举全部 2 组"));
  }
}
