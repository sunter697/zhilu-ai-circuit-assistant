/*
 * Logisim-evolution - digital logic design tool and simulator
 * Copyright by the Logisim-evolution developers
 *
 * https://github.com/logisim-evolution/
 *
 * This is free software released under GNU GPLv3 license
 */

package com.cburch.logisim.ai.validation;

import com.cburch.logisim.analyze.model.AnalyzerModel;
import com.cburch.logisim.analyze.model.Expression;
import com.cburch.logisim.analyze.model.Parser;
import com.cburch.logisim.analyze.model.ParserException;
import com.cburch.logisim.analyze.model.Var;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** A deterministic oracle compiled from the confirmed contract, never from simulated outputs. */
public final class ValidationPlan {
  public static final int MAX_INPUT_BITS = 10;
  private final TaskSpec specification;
  private final Map<String, Expression> expressions;

  public ValidationPlan(TaskSpec specification) {
    this.specification = java.util.Objects.requireNonNull(specification);
    final var model = new AnalyzerModel();
    model.setVariables(
        specification.inputs().stream().map(s -> new Var(s.name(), s.width())).toList(),
        specification.outputs().stream().map(s -> new Var(s.name(), s.width())).toList());
    final var compiled = new LinkedHashMap<String, Expression>();
    for (final var entry : specification.expressions().entrySet()) {
      try {
        final var parsed = Parser.parse(entry.getValue(), model);
        if (parsed == null)
          throw new IllegalArgumentException("Empty expression: " + entry.getKey());
        compiled.put(entry.getKey(), parsed);
      } catch (ParserException e) {
        throw new IllegalArgumentException(
            "Invalid expression for " + entry.getKey() + ": " + e.getMessage(), e);
      }
    }
    expressions = Map.copyOf(compiled);
  }

  public TaskSpec specification() {
    return specification;
  }

  public String specificationDigest() {
    return specification.digest();
  }

  public List<String> inputBits() {
    return TaskSpec.bits(specification.inputs());
  }

  public List<String> outputBits() {
    return TaskSpec.bits(specification.outputs());
  }

  public Map<String, Boolean> expectedOutputs(Map<String, Boolean> inputs) {
    if (!inputs.keySet().equals(new java.util.HashSet<>(inputBits()))
        || inputs.containsValue(null)) {
      throw new IllegalArgumentException(
          "Oracle inputs must exactly match the confirmed specification");
    }
    final var result = new LinkedHashMap<String, Boolean>();
    if (specification.kind() == TaskSpec.Kind.UNSIGNED_MULTIPLICATION) {
      final int product =
          operand(specification.inputs().get(0), inputs)
              * operand(specification.inputs().get(1), inputs);
      var offset = 0;
      for (final var signal : specification.outputs()) {
        for (var bit = 0; bit < signal.width(); bit++) {
          result.put(signal.bitName(bit), ((product >>> (offset + bit)) & 1) != 0);
        }
        offset += signal.width();
      }
    } else {
      final var evaluator = new Evaluator(inputs);
      for (final var entry : expressions.entrySet())
        result.put(entry.getKey(), entry.getValue().visit(evaluator));
    }
    return Map.copyOf(result);
  }

  private static int operand(TaskSpec.SignalSpec signal, Map<String, Boolean> inputs) {
    var value = 0;
    for (var bit = signal.width() - 1; bit >= 0; bit--)
      value = (value << 1) | (inputs.get(signal.bitName(bit)) ? 1 : 0);
    return value;
  }

  public List<List<String>> expectedRows() {
    final var inputBits = inputBits();
    final var outputBits = outputBits();
    final var rows = new ArrayList<List<String>>();
    for (var row = 0; row < (1 << inputBits.size()); row++) {
      final var inputs = new LinkedHashMap<String, Boolean>();
      final var values = new ArrayList<String>();
      for (var column = 0; column < inputBits.size(); column++) {
        final boolean bit = ((row >>> (inputBits.size() - column - 1)) & 1) != 0;
        inputs.put(inputBits.get(column), bit);
        values.add(bit ? "1" : "0");
      }
      final var outputs = expectedOutputs(inputs);
      for (final var output : outputBits) values.add(outputs.get(output) ? "1" : "0");
      rows.add(List.copyOf(values));
    }
    return List.copyOf(rows);
  }

  private record Evaluator(Map<String, Boolean> inputs) implements Expression.Visitor<Boolean> {
    @Override
    public Boolean visitVariable(String name) {
      final var value = inputs.get(name);
      if (value == null) throw new IllegalArgumentException("Unknown oracle variable: " + name);
      return value;
    }

    @Override
    public Boolean visitConstant(int value) {
      return (value & 1) != 0;
    }

    @Override
    public Boolean visitNot(Expression a) {
      return !a.visit(this);
    }

    @Override
    public Boolean visitAnd(Expression a, Expression b) {
      return a.visit(this) & b.visit(this);
    }

    @Override
    public Boolean visitOr(Expression a, Expression b) {
      return a.visit(this) | b.visit(this);
    }

    @Override
    public Boolean visitXor(Expression a, Expression b) {
      return a.visit(this) ^ b.visit(this);
    }

    @Override
    public Boolean visitXnor(Expression a, Expression b) {
      return a.visit(this).equals(b.visit(this));
    }

    @Override
    public Boolean visitEq(Expression a, Expression b) {
      return a.visit(this).equals(b.visit(this));
    }
  }
}
