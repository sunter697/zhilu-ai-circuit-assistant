/*
 * Logisim-evolution - digital logic design tool and simulator
 * Copyright by the Logisim-evolution developers
 *
 * https://github.com/logisim-evolution/
 *
 * This is free software released under GNU GPLv3 license.
 */

package com.cburch.logisim.ai.vision;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.cburch.logisim.ai.util.SimpleJson;
import com.cburch.logisim.analyze.model.AnalyzerModel;
import com.cburch.logisim.analyze.model.Parser;
import com.cburch.logisim.analyze.model.Var;
import com.cburch.logisim.circuit.Circuit;
import com.cburch.logisim.std.gates.CircuitBuilder;
import com.cburch.logisim.std.gates.GateAttributes;
import com.cburch.logisim.std.wiring.Pin;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class RealHoldoutExpressionsTest {
  private static final Path PLAN =
      Path.of("vision-service", "evaluation", "real-holdout-expressions-v01.json");

  private static final Map<String, String> GATE_NAMES =
      Map.of(
          "AND Gate",
          "AND",
          "OR Gate",
          "OR",
          "NOT Gate",
          "NOT",
          "NAND Gate",
          "NAND",
          "NOR Gate",
          "NOR",
          "XOR Gate",
          "XOR",
          "XNOR Gate",
          "XNOR");

  @Test
  void expressionsParseAndBuildOnlySupportedTwoInputGates() throws Exception {
    final var plan =
        SimpleJson.parseObject(Files.readString(PLAN, StandardCharsets.UTF_8));
    final var cases = list(plan.get("cases"));
    final var coveredKinds = new java.util.HashSet<String>();
    assertEquals(26, cases.size());

    for (final var value : cases) {
      final var record = object(value);
      final var id = String.valueOf(record.get("id"));
      final var inputs = list(record.get("inputs")).stream().map(String::valueOf).toList();
      final var model = new AnalyzerModel();
      model.setVariables(
          inputs.stream().map(name -> new Var(name, 1)).toList(), List.of(new Var("Y", 1)));
      model.getOutputExpressions().enableUpdates();
      final var expressionText = String.valueOf(record.get("expression"));
      final var expression = Parser.parse(expressionText, model);
      model.getOutputExpressions().setExpression("Y", expression, expressionText);

      final var circuit = new Circuit(id, null, null);
      CircuitBuilder.build(circuit, model, true, false).execute();
      final var actualGateCounts = new LinkedHashMap<String, Integer>();
      var pinCount = 0;
      for (final var component : circuit.getNonWires()) {
        final var factory = component.getFactory();
        if (factory == Pin.FACTORY) {
          pinCount++;
          continue;
        }
        final var kind = GATE_NAMES.get(factory.getName());
        assertTrue(kind != null, () -> id + " generated unsupported gate " + factory.getName());
        actualGateCounts.merge(kind, 1, Integer::sum);
        coveredKinds.add(kind);
        if (!"NOT".equals(kind)) {
          assertEquals(
              2,
              component.getAttributeSet().getValue(GateAttributes.ATTR_INPUTS),
              id + " must use only two-input gates");
        }
      }
      assertEquals(inputs.size() + 1, pinCount, id + " input/output pin count");
      assertEquals(expectedCounts(record.get("expectedGateCounts")), actualGateCounts, id);
      assertTrue(circuit.getWires().size() > 0, id + " must contain wires");
    }

    assertEquals(Set.of("AND", "OR", "NOT", "NAND", "NOR", "XOR", "XNOR"), coveredKinds);
  }

  private static Map<String, Integer> expectedCounts(Object value) {
    final var result = new LinkedHashMap<String, Integer>();
    for (final var entry : object(value).entrySet()) {
      result.put(entry.getKey(), ((Number) entry.getValue()).intValue());
    }
    return result;
  }

  private static Map<String, Object> object(Object value) {
    assertTrue(value instanceof Map<?, ?>, "expected a JSON object");
    final var result = new LinkedHashMap<String, Object>();
    ((Map<?, ?>) value).forEach((key, item) -> result.put(String.valueOf(key), item));
    return result;
  }

  private static List<?> list(Object value) throws IOException {
    if (value instanceof List<?> list) return list;
    throw new IOException("expected a JSON array");
  }
}
