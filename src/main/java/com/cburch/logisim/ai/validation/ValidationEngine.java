/*
 * Logisim-evolution - digital logic design tool and simulator
 * Copyright by the Logisim-evolution developers
 *
 * https://github.com/logisim-evolution/
 *
 * This is free software released under GNU GPLv3 license
 */

package com.cburch.logisim.ai.validation;

import com.cburch.logisim.ai.transaction.CircuitDigest;
import com.cburch.logisim.analyze.model.AnalyzerModel;
import com.cburch.logisim.analyze.model.Entry;
import com.cburch.logisim.circuit.Analyze;
import com.cburch.logisim.circuit.Circuit;
import com.cburch.logisim.proj.Project;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Runs bounded verification using Logisim's own simulation/analysis code. */
public final class ValidationEngine {
  private static final Set<String> COMBINATIONAL_FACTORIES =
      Set.of(
          "com.cburch.logisim.std.wiring.Pin",
          "com.cburch.logisim.std.wiring.Constant",
          "com.cburch.logisim.std.wiring.Ground",
          "com.cburch.logisim.std.wiring.Power",
          "com.cburch.logisim.std.wiring.Tunnel",
          "com.cburch.logisim.std.wiring.Probe",
          "com.cburch.logisim.std.wiring.BitExtender",
          "com.cburch.logisim.circuit.SplitterFactory",
          "com.cburch.logisim.std.gates.Buffer",
          "com.cburch.logisim.std.gates.NotGate",
          "com.cburch.logisim.std.gates.AndGate",
          "com.cburch.logisim.std.gates.OrGate",
          "com.cburch.logisim.std.gates.XorGate",
          "com.cburch.logisim.std.gates.XnorGate",
          "com.cburch.logisim.std.gates.NandGate",
          "com.cburch.logisim.std.gates.NorGate",
          "com.cburch.logisim.std.gates.OddParityGate",
          "com.cburch.logisim.std.gates.EvenParityGate",
          "com.cburch.logisim.std.arith.Multiplier");
  private final int maxInputBits;

  public ValidationEngine() {
    this(10);
  }

  public ValidationEngine(int maxInputBits) {
    if (maxInputBits < 1 || maxInputBits > 10)
      throw new IllegalArgumentException("maxInputBits must be 1..10");
    this.maxInputBits = maxInputBits;
  }

  public VerificationEvidence verifyPlan(Project project, Circuit circuit, ValidationPlan plan) {
    return verify(project, circuit, plan);
  }

  /** The only acceptance entry point: generates expected values independently from the circuit. */
  public VerificationEvidence verify(Project project, Circuit circuit, ValidationPlan plan) {
    if (plan == null) return inconclusive("No user-confirmed validation plan is available");
    final var before = CircuitDigest.sha256(circuit);
    VerificationEvidence evidence;
    try {
      final var table = computeTable(project, circuit).getTruthTable();
      final var inputs = headers(table, true);
      final var outputs = headers(table, false);
      if (!sameColumns(inputs, plan.inputBits()) || !sameColumns(outputs, plan.outputBits())) {
        evidence =
            inconclusive(
                "Circuit interface differs from the confirmed specification. Inputs: "
                    + inputs
                    + "; outputs: "
                    + outputs);
      } else {
        final var failures = new ArrayList<Map<String, Object>>();
        var mismatchCount = 0;
        for (var row = 0; row < table.getRowCount(); row++) {
          checkInterrupted();
          final var assignment = new LinkedHashMap<String, Boolean>();
          for (var col = 0; col < inputs.size(); col++) {
            assignment.put(inputs.get(col), table.getInputEntry(row, col) == Entry.ONE);
          }
          final var expected = plan.expectedOutputs(assignment);
          for (var col = 0; col < outputs.size(); col++) {
            final var actual = entryText(table.getOutputEntry(row, col));
            final var wanted = expected.get(outputs.get(col)) ? "1" : "0";
            if (!wanted.equals(actual)) {
              mismatchCount++;
              if (failures.size() < 32)
                failures.add(
                    Map.of(
                        "row",
                        row,
                        "signal",
                        outputs.get(col),
                        "expected",
                        wanted,
                        "actual",
                        actual));
            }
          }
        }
        evidence =
            new VerificationEvidence(
                mismatchCount == 0 ? ValidationStatus.PASS : ValidationStatus.FAIL,
                mismatchCount == 0
                    ? "All "
                        + table.getRowCount()
                        + " exhaustive rows matched the confirmed specification."
                    : mismatchCount + " output mismatches (unknown/error values also fail).",
                List.of("Logisim simulation completed"),
                mismatchCount > 32 ? List.of("Only the first 32 mismatches are shown") : List.of(),
                failures);
      }
    } catch (TooManyInputsException e) {
      evidence = inconclusive(e.getMessage());
    } catch (Exception e) {
      evidence =
          new VerificationEvidence(
              ValidationStatus.ERROR, safeMessage(e), List.of(), List.of(), List.of());
    }
    final var after = CircuitDigest.sha256(circuit);
    if (!before.equals(after))
      return inconclusive("Circuit changed during verification; evidence was discarded");
    return new VerificationEvidence(
        evidence.status(),
        evidence.summary(),
        evidence.passedChecks(),
        evidence.warnings(),
        evidence.failures(),
        plan.specificationDigest(),
        after);
  }

  private static boolean sameColumns(List<String> actual, List<String> expected) {
    return actual.size() == expected.size()
        && new HashSet<>(actual).equals(new HashSet<>(expected));
  }

  private static VerificationEvidence inconclusive(String message) {
    return new VerificationEvidence(
        ValidationStatus.INCONCLUSIVE, message, List.of(), List.of(message), List.of());
  }

  public Map<String, Object> truthTable(Project project, Circuit circuit, int maxRows) {
    final var result = new LinkedHashMap<String, Object>();
    try {
      final var model = computeTable(project, circuit);
      final var table = model.getTruthTable();
      final var columns = new ArrayList<String>();
      for (var i = 0; i < table.getInputColumnCount(); i++) columns.add(table.getInputHeader(i));
      for (var i = 0; i < table.getOutputColumnCount(); i++) columns.add(table.getOutputHeader(i));
      final var rows = new ArrayList<List<String>>();
      final var limit = Math.min(Math.max(maxRows, 1), table.getRowCount());
      for (var row = 0; row < limit; row++) {
        final var values = new ArrayList<String>();
        for (var col = 0; col < table.getInputColumnCount(); col++) {
          values.add(entryText(table.getInputEntry(row, col)));
        }
        for (var col = 0; col < table.getOutputColumnCount(); col++) {
          values.add(entryText(table.getOutputEntry(row, col)));
        }
        rows.add(values);
      }
      var nonBinary = false;
      for (var row = 0; row < table.getRowCount(); row++) {
        for (var col = 0; col < table.getOutputColumnCount(); col++) {
          final var value = table.getOutputEntry(row, col);
          nonBinary |= value != Entry.ZERO && value != Entry.ONE;
        }
      }
      result.put("status", nonBinary ? "NON_BINARY_OUTPUT" : "OK");
      result.put("diagnosticOnly", true);
      result.put("columns", columns);
      result.put("rows", rows);
      result.put("totalRows", table.getRowCount());
      result.put("truncated", table.getRowCount() > rows.size());
    } catch (TooManyInputsException e) {
      result.put("status", "INCONCLUSIVE");
      result.put("reason", e.getMessage());
    } catch (Exception e) {
      result.put("status", "ERROR");
      result.put("reason", safeMessage(e));
    }
    return result;
  }

  public VerificationEvidence verifyTruthTable(
      Project project,
      Circuit circuit,
      List<String> inputNames,
      List<String> outputNames,
      List<List<String>> expectedRows) {
    try {
      validateDiagnosticRows(inputNames, outputNames, expectedRows);
      final var model = computeTable(project, circuit);
      final var table = model.getTruthTable();
      final var actualInputs = headers(table, true);
      final var actualOutputs = headers(table, false);
      final var warnings = new ArrayList<String>();
      warnings.add("Diagnostic only: model-authored expected rows never authorize Apply");
      if (!actualInputs.equals(inputNames)) {
        return new VerificationEvidence(
            ValidationStatus.INCONCLUSIVE,
            "The expected input columns do not match the circuit interface.",
            List.of(),
            List.of("Circuit inputs: " + actualInputs + "; expected: " + inputNames),
            List.of());
      }
      if (!actualOutputs.equals(outputNames)) {
        return new VerificationEvidence(
            ValidationStatus.INCONCLUSIVE,
            "The expected output columns do not match the circuit interface.",
            List.of(),
            List.of("Circuit outputs: " + actualOutputs + "; expected: " + outputNames),
            List.of());
      }
      if (expectedRows.size() != table.getRowCount()) {
        return new VerificationEvidence(
            ValidationStatus.INCONCLUSIVE,
            "The supplied truth table is not exhaustive for the current input width.",
            List.of(),
            List.of(
                "Expected " + table.getRowCount() + " rows but received " + expectedRows.size()),
            List.of());
      }
      final var failures = new ArrayList<Map<String, Object>>();
      final var expectedWidth = inputNames.size() + outputNames.size();
      for (var row = 0; row < table.getRowCount(); row++) {
        final var expected = expectedRows.get(row);
        if (expected.size() != expectedWidth) {
          return new VerificationEvidence(
              ValidationStatus.INCONCLUSIVE,
              "A supplied truth-table row has the wrong number of values.",
              List.of(),
              List.of(
                  "Row " + row + " has " + expected.size() + " values; expected " + expectedWidth),
              List.of());
        }
        for (var col = 0; col < inputNames.size(); col++) {
          final var actual = entryText(table.getInputEntry(row, col));
          if (!matches(expected.get(col), actual)) {
            return new VerificationEvidence(
                ValidationStatus.INCONCLUSIVE,
                "Expected rows are not ordered according to Logisim's exhaustive input order.",
                List.of(),
                List.of(
                    "Row "
                        + row
                        + " input "
                        + inputNames.get(col)
                        + " is "
                        + actual
                        + " but expected row says "
                        + expected.get(col)),
                List.of());
          }
        }
        for (var col = 0; col < outputNames.size(); col++) {
          final var actual = entryText(table.getOutputEntry(row, col));
          final var expectedValue = expected.get(inputNames.size() + col);
          if (!matches(expectedValue, actual)) {
            final var failure = new LinkedHashMap<String, Object>();
            failure.put("row", row);
            failure.put("signal", outputNames.get(col));
            failure.put("expected", expectedValue);
            failure.put("actual", actual);
            failures.add(failure);
          }
        }
      }
      if (failures.isEmpty()) {
        return new VerificationEvidence(
            ValidationStatus.PASS,
            "All " + table.getRowCount() + " exhaustive truth-table rows matched.",
            List.of(
                "Logisim simulation completed",
                "Truth table " + table.getRowCount() + "/" + table.getRowCount() + " rows matched"),
            warnings,
            failures);
      }
      return new VerificationEvidence(
          ValidationStatus.FAIL,
          failures.size()
              + " output mismatch(es) were found in exhaustive truth-table verification.",
          List.of("Logisim simulation completed"),
          warnings,
          failures);
    } catch (TooManyInputsException | IllegalArgumentException e) {
      return new VerificationEvidence(
          ValidationStatus.INCONCLUSIVE,
          e.getMessage(),
          List.of(),
          List.of(e.getMessage()),
          List.of());
    } catch (Exception e) {
      return new VerificationEvidence(
          ValidationStatus.ERROR, safeMessage(e), List.of(), List.of(), List.of());
    }
  }

  private AnalyzerModel computeTable(Project project, Circuit circuit) {
    checkInterrupted();
    for (final var component : circuit.getNonWires()) {
      if (!COMBINATIONAL_FACTORIES.contains(component.getFactory().getClass().getName())) {
        throw new TooManyInputsException(
            "Unsupported component for combinational acceptance: "
                + component.getFactory().getName());
      }
    }
    final var labels = Analyze.getPinLabels(circuit);
    var inputs = 0;
    var outputs = 0;
    for (final var entry : labels.entrySet()) {
      if (com.cburch.logisim.std.wiring.Pin.FACTORY.isInputPin(entry.getKey())) {
        inputs +=
            entry.getKey().getAttributeValue(com.cburch.logisim.instance.StdAttr.WIDTH).getWidth();
      } else outputs++;
    }
    if (inputs > maxInputBits || inputs == 0 || outputs == 0) {
      throw new TooManyInputsException(
          "Acceptance requires 1.."
              + maxInputBits
              + " input bits and an output; found "
              + inputs
              + " input bits and "
              + outputs
              + " outputs.");
    }
    final var model = new AnalyzerModel();
    Analyze.computeTable(model, project, circuit, labels);
    checkInterrupted();
    return model;
  }

  private static List<String> headers(
      com.cburch.logisim.analyze.model.TruthTable table, boolean inputs) {
    final var result = new ArrayList<String>();
    final var count = inputs ? table.getInputColumnCount() : table.getOutputColumnCount();
    for (var i = 0; i < count; i++)
      result.add(inputs ? table.getInputHeader(i) : table.getOutputHeader(i));
    return result;
  }

  private static boolean matches(String expected, String actual) {
    return ("0".equals(expected) || "1".equals(expected)) && expected.equals(actual);
  }

  /** Checks a diagnostic table before use; malformed tables are never frozen as a contract. */
  public static void validateDiagnosticRows(
      List<String> inputs, List<String> outputs, List<List<String>> rows) {
    if (inputs == null
        || outputs == null
        || rows == null
        || inputs.isEmpty()
        || outputs.isEmpty()
        || inputs.size() > ValidationPlan.MAX_INPUT_BITS) {
      throw new IllegalArgumentException(
          "Diagnostic tables require 1..10 input bits and at least one output");
    }
    final var names = new HashSet<String>();
    for (final var name : inputs) {
      if (name == null || name.isBlank() || !names.add(name))
        throw new IllegalArgumentException("Invalid input columns");
    }
    for (final var name : outputs) {
      if (name == null || name.isBlank() || !names.add(name))
        throw new IllegalArgumentException("Invalid output columns");
    }
    if (rows.size() != (1 << inputs.size()))
      throw new IllegalArgumentException("Diagnostic table must be exhaustive");
    for (var row = 0; row < rows.size(); row++) {
      final var values = rows.get(row);
      if (values == null || values.size() != inputs.size() + outputs.size())
        throw new IllegalArgumentException("Invalid diagnostic row width");
      for (final var value : values) {
        if (!"0".equals(value) && !"1".equals(value))
          throw new IllegalArgumentException("Only explicit 0/1 expected values are supported");
      }
      for (var col = 0; col < inputs.size(); col++) {
        final var bit = Integer.toString((row >>> (inputs.size() - col - 1)) & 1);
        if (!bit.equals(values.get(col)))
          throw new IllegalArgumentException("Diagnostic rows must be in binary input order");
      }
    }
  }

  private static void checkInterrupted() {
    if (Thread.currentThread().isInterrupted())
      throw new IllegalStateException("Verification cancelled");
  }

  private static String entryText(Entry entry) {
    if (entry == Entry.ONE) return "1";
    if (entry == Entry.ZERO) return "0";
    if (entry == Entry.OSCILLATE_ERROR) return "oscillating";
    if (entry == Entry.BUS_ERROR) return "error";
    return "x";
  }

  private static String safeMessage(Throwable e) {
    return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
  }

  private static final class TooManyInputsException extends RuntimeException {
    TooManyInputsException(String message) {
      super(message);
    }
  }
}
