/*
 * Logisim-evolution - digital logic design tool and simulator
 * Copyright by the Logisim-evolution developers
 *
 * https://github.com/logisim-evolution/
 *
 * This is free software released under GNU GPLv3 license
 */

package com.cburch.logisim.ai.validation;

import static com.cburch.logisim.ai.i18n.AiStrings.S;

import com.cburch.logisim.ai.i18n.AiStrings;
import com.cburch.logisim.analyze.model.AnalyzerModel;
import com.cburch.logisim.analyze.model.Expression;
import com.cburch.logisim.analyze.model.Parser;
import com.cburch.logisim.analyze.model.ParserException;
import com.cburch.logisim.analyze.model.Var;
import java.util.ArrayList;
import java.util.List;

/** Human-readable contract display; the immutable machine contract remains authoritative. */
final class SpecPresentation {
  private SpecPresentation() {}

  static String describe(TaskSpec specification) {
    final var lines = new ArrayList<String>();
    lines.add(S.get("aiSpecConfirmDescription"));
    signals(lines, "aiSpecInputs", specification.inputs());
    signals(lines, "aiSpecOutputs", specification.outputs());
    lines.add(S.get("aiSpecFunction"));
    lines.addAll(functionDescription(specification));
    lines.add(S.get("aiSpecAcceptanceHeading"));
    lines.add(S.get("aiSpecAcceptance", specification.exhaustiveRows()));
    // Remember each localized line rather than opaque JSON, so existing chat follows locale
    // changes.
    return AiStrings.compose(lines.toArray(String[]::new));
  }

  static List<String> functionDescription(TaskSpec specification) {
    final var lines = new ArrayList<String>();
    if (specification.kind() == TaskSpec.Kind.UNSIGNED_MULTIPLICATION) {
      multiplication(lines, specification);
    } else {
      booleanExpressions(lines, specification);
    }
    return List.copyOf(lines);
  }

  private static void signals(
      List<String> lines, String heading, List<TaskSpec.SignalSpec> signals) {
    lines.add(S.get(heading));
    for (final var signal : signals) {
      lines.add(S.get("aiSpecSignal", signal.name(), signal.width()));
    }
  }

  private static void multiplication(List<String> lines, TaskSpec specification) {
    lines.add(
        S.get(
            "aiSpecUnsignedProduct",
            specification.inputs().get(0).name(),
            specification.inputs().get(1).name()));
    var offset = 0;
    for (final var output : specification.outputs()) {
      lines.add(S.get("aiSpecProductSlice", output.name(), offset + output.width() - 1, offset));
      offset += output.width();
    }
    lines.add(S.get("aiSpecProductOrder"));
  }

  private static void booleanExpressions(List<String> lines, TaskSpec specification) {
    final var model = new AnalyzerModel();
    model.setVariables(
        specification.inputs().stream().map(s -> new Var(s.name(), s.width())).toList(),
        specification.outputs().stream().map(s -> new Var(s.name(), s.width())).toList());
    for (final var output : TaskSpec.bits(specification.outputs())) {
      final Expression expression;
      try {
        expression = Parser.parse(specification.expressions().get(output), model);
        if (expression == null) throw new IllegalArgumentException("Empty expression: " + output);
      } catch (ParserException e) {
        throw new IllegalArgumentException("Invalid expression for " + output, e);
      }
      // Use the same parser as acceptance. String substitutions could corrupt names such as AND.
      lines.add(S.get("aiSpecFormula", output, expression.toString(Expression.Notation.LOGIC)));
      final var relation = simpleRelation(expression);
      if (relation != null) {
        lines.add(
            S.get(
                relation.equal() ? "aiSpecEquivalent" : "aiSpecDifferent",
                relation.first(),
                relation.second(),
                output,
                output));
      }
    }
    lines.add(S.get("aiSpecLogicNotation"));
  }

  /** Explain only exact two-variable parity relations, never guess meanings of compound terms. */
  private static Relation simpleRelation(Expression expression) {
    return expression.visit(
        new Expression.Visitor<Relation>() {
          @Override
          public Relation visitNot(Expression operand) {
            final var relation = operand.visit(this);
            return relation == null
                ? null
                : new Relation(relation.first(), relation.second(), !relation.equal());
          }

          @Override
          public Relation visitBinary(Expression first, Expression second, Expression.Op op) {
            if (op != Expression.Op.XOR && op != Expression.Op.XNOR && op != Expression.Op.EQ) {
              return null;
            }
            final var nameVisitor =
                new Expression.Visitor<String>() {
                  @Override
                  public String visitVariable(String name) {
                    return name;
                  }
                };
            final var firstName = first.visit(nameVisitor);
            final var secondName = second.visit(nameVisitor);
            return firstName == null || secondName == null
                ? null
                : new Relation(firstName, secondName, op != Expression.Op.XOR);
          }
        });
  }

  private record Relation(String first, String second, boolean equal) {}
}
