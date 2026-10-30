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
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class TaskSpecTest {
  private static TaskSpec.SignalSpec signal(String name, int width) {
    return new TaskSpec.SignalSpec(name, width);
  }

  @Test
  void booleanOracleUsesOnlyConfirmedExpressionsAndIsImmutable() {
    final var inputs = new ArrayList<>(List.of(signal("A", 1), signal("B", 1)));
    final var expressions = new LinkedHashMap<>(Map.of("Y", "A ^ B"));
    final var spec =
        new TaskSpec(
            TaskSpec.Kind.BOOLEAN_EXPRESSIONS, inputs, List.of(signal("Y", 1)), expressions);
    final var digest = spec.digest();
    inputs.clear();
    expressions.put("Y", "0");
    assertEquals(
        List.of(
            List.of("0", "0", "0"),
            List.of("0", "1", "1"),
            List.of("1", "0", "1"),
            List.of("1", "1", "0")),
        new ValidationPlan(spec).expectedRows());
    assertEquals(digest, spec.digest());
    assertThrows(UnsupportedOperationException.class, () -> spec.expressions().put("Y", "0"));
    assertThrows(UnsupportedOperationException.class, () -> spec.inputs().clear());
  }

  @Test
  void unsignedMultiplicationChecksAllProductsAndLowThenHighSliceOrder() {
    final var plan =
        new ValidationPlan(
            new TaskSpec(
                TaskSpec.Kind.UNSIGNED_MULTIPLICATION,
                List.of(signal("A", 4), signal("B", 4)),
                List.of(signal("LOW", 4), signal("HIGH", 4)),
                Map.of()));
    final var rows = plan.expectedRows();
    assertEquals(256, rows.size());
    for (var a = 0; a < 16; a++) {
      for (var b = 0; b < 16; b++) {
        final var row = rows.get(a * 16 + b);
        final var low = Integer.parseInt(String.join("", row.subList(8, 12)), 2);
        final var high = Integer.parseInt(String.join("", row.subList(12, 16)), 2);
        assertEquals(a * b, (high << 4) | low);
      }
    }
  }

  @Test
  void singleFullProductBusAndSplitProductAgree() {
    final var plan =
        new ValidationPlan(
            new TaskSpec(
                TaskSpec.Kind.UNSIGNED_MULTIPLICATION,
                List.of(signal("A", 4), signal("B", 4)),
                List.of(signal("P", 8)),
                Map.of()));
    assertEquals("11100001", String.join("", plan.expectedRows().get(255).subList(8, 16)));
  }

  @Test
  void rejectsUnsupportedWidthsPartialProductsAndInvalidExpressionBeforeConfirmation() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new TaskSpec(
                TaskSpec.Kind.UNSIGNED_MULTIPLICATION,
                List.of(signal("A", 6), signal("B", 6)),
                List.of(signal("P", 12)),
                Map.of()));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new TaskSpec(
                TaskSpec.Kind.UNSIGNED_MULTIPLICATION,
                List.of(signal("A", 4), signal("B", 4)),
                List.of(signal("P", 4)),
                Map.of()));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            TaskSpec.fromArguments(
                Map.of(
                    "kind",
                    "BOOLEAN_EXPRESSIONS",
                    "inputs",
                    List.of(Map.of("name", "A", "width", 1)),
                    "outputs",
                    List.of(Map.of("name", "Y", "width", 1)),
                    "expressions",
                    Map.of("Y", "UNDECLARED"))));
  }

  @Test
  void rowsCannotBeSubmittedAsTheAcceptanceOracle() {
    assertThrows(
        IllegalArgumentException.class, () -> TaskSpec.fromArguments(Map.of("rows", List.of())));
  }
}
