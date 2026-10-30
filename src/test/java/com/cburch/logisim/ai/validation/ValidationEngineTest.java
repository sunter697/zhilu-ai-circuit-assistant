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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.cburch.logisim.ai.agent.AgentPermissionManager;
import com.cburch.logisim.ai.agent.TaskBudget;
import com.cburch.logisim.ai.logging.AgentTaskLogger;
import com.cburch.logisim.ai.tools.ToolExecutionContext;
import com.cburch.logisim.ai.transaction.CircuitDigest;
import com.cburch.logisim.ai.util.SwingExecutor;
import com.cburch.logisim.ai.workspace.WorkspaceManager;
import com.cburch.logisim.circuit.Circuit;
import com.cburch.logisim.circuit.CircuitMutation;
import com.cburch.logisim.circuit.Wire;
import com.cburch.logisim.comp.Component;
import com.cburch.logisim.data.BitWidth;
import com.cburch.logisim.data.Location;
import com.cburch.logisim.file.Loader;
import com.cburch.logisim.file.LogisimFile;
import com.cburch.logisim.instance.StdAttr;
import com.cburch.logisim.proj.Project;
import com.cburch.logisim.std.memory.DFlipFlop;
import com.cburch.logisim.std.wiring.Pin;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ValidationEngineTest {
  @TempDir Path tempDir;

  private static final class Fixture implements AutoCloseable {
    final LogisimFile file;
    final Project project;
    final Circuit circuit;

    Fixture() {
      final var loader = new Loader(null);
      file = LogisimFile.createNew(loader, null);
      file.addLibrary(loader.loadLibrary("#Wiring"));
      project = new Project(file);
      circuit = project.getCurrentCircuit();
      circuit.setProject(project);
    }

    Component pin(String name, int x, int y, boolean output, int width) {
      final var attrs = Pin.FACTORY.createAttributeSet();
      attrs.setValue(StdAttr.LABEL, name);
      attrs.setValue(StdAttr.WIDTH, BitWidth.create(width));
      attrs.setValue(Pin.ATTR_TYPE, output ? Pin.OUTPUT : Pin.INPUT);
      final var pin = Pin.FACTORY.createComponent(Location.create(x, y, true), attrs);
      add(pin);
      return pin;
    }

    void add(Component component) {
      SwingExecutor.run(
          () -> {
            final var mutation = new CircuitMutation(circuit);
            mutation.add(component);
            mutation.execute();
          });
    }

    void identity(boolean connected) {
      pin("A", 100, 100, false, 1);
      pin("Y", 200, 100, true, 1);
      if (connected)
        add(Wire.create(Location.create(100, 100, true), Location.create(200, 100, true)));
    }

    @Override
    public void close() {
      project.getSimulator().shutDown();
      file.stopAutosaveThread(false);
    }
  }

  private static TaskSpec identitySpec() {
    return new TaskSpec(
        TaskSpec.Kind.BOOLEAN_EXPRESSIONS,
        List.of(new TaskSpec.SignalSpec("A", 1)),
        List.of(new TaskSpec.SignalSpec("Y", 1)),
        Map.of("Y", "A"));
  }

  @Test
  void trustedPassBindsActualSimulationToSpecificationAndCircuit() {
    try (var f = new Fixture()) {
      f.identity(true);
      final var evidence =
          new ValidationEngine().verify(f.project, f.circuit, new ValidationPlan(identitySpec()));
      assertEquals(ValidationStatus.PASS, evidence.status(), evidence.summary());
      assertTrue(evidence.isTrustedPass());
      assertEquals(identitySpec().digest(), evidence.specificationDigest());
      assertEquals(CircuitDigest.sha256(f.circuit), evidence.circuitDigest());
    }
  }

  @Test
  void floatingOutputCannotPassEvenWithWildcardDiagnosticRows() {
    try (var f = new Fixture()) {
      f.identity(false);
      final var engine = new ValidationEngine();
      assertEquals(
          ValidationStatus.FAIL,
          engine.verify(f.project, f.circuit, new ValidationPlan(identitySpec())).status());
      final var diagnostic =
          engine.verifyTruthTable(
              f.project,
              f.circuit,
              List.of("A"),
              List.of("Y"),
              List.of(List.of("0", "x"), List.of("1", "x")));
      assertEquals(ValidationStatus.INCONCLUSIVE, diagnostic.status());
      assertFalse(diagnostic.isTrustedPass());
    }
  }

  @Test
  void diagnosticPassHasNoAuthorityAndInvalidFirstRowsDoNotLockLaterRows() {
    try (var f = new Fixture()) {
      f.identity(true);
      final var engine = new ValidationEngine();
      assertEquals(
          ValidationStatus.INCONCLUSIVE,
          engine
              .verifyTruthTable(
                  f.project, f.circuit, List.of("A"), List.of("Y"), List.of(List.of("0", "0")))
              .status());
      final var valid =
          engine.verifyTruthTable(
              f.project,
              f.circuit,
              List.of("A"),
              List.of("Y"),
              List.of(List.of("0", "0"), List.of("1", "1")));
      assertEquals(ValidationStatus.PASS, valid.status());
      assertFalse(valid.isTrustedPass());
    }
  }

  @Test
  void hiddenSequentialComponentMakesAcceptanceInconclusive() {
    try (var f = new Fixture()) {
      f.identity(true);
      final var dff = new DFlipFlop();
      f.add(dff.createComponent(Location.create(400, 300, true), dff.createAttributeSet()));
      final var evidence =
          new ValidationEngine().verify(f.project, f.circuit, new ValidationPlan(identitySpec()));
      assertEquals(ValidationStatus.INCONCLUSIVE, evidence.status());
      assertTrue(evidence.summary().contains("Unsupported component"));
    }
  }

  @Test
  void driverConflictFailsInsteadOfPassingAsDontCare() {
    try (var f = new Fixture()) {
      f.identity(true);
      f.pin("B", 150, 100, false, 1);
      final var spec =
          new TaskSpec(
              TaskSpec.Kind.BOOLEAN_EXPRESSIONS,
              List.of(new TaskSpec.SignalSpec("A", 1), new TaskSpec.SignalSpec("B", 1)),
              List.of(new TaskSpec.SignalSpec("Y", 1)),
              Map.of("Y", "A"));
      final var evidence =
          new ValidationEngine().verify(f.project, f.circuit, new ValidationPlan(spec));
      assertEquals(ValidationStatus.FAIL, evidence.status(), evidence.summary());
      assertTrue(evidence.failures().stream().anyMatch(row -> "error".equals(row.get("actual"))));
    }
  }

  @Test
  void contextRequiresConfirmationAndRejectsInjectedDiagnosticPass() throws Exception {
    try (var f = new Fixture()) {
      f.identity(true);
      final var manager = new WorkspaceManager(tempDir);
      try (var workspace = manager.create(f.project, f.circuit)) {
        final var permissions = new AgentPermissionManager();
        permissions.setEditsAllowed(true);
        final var context =
            new ToolExecutionContext(
                workspace,
                manager,
                permissions,
                TaskBudget.defaults(),
                new AgentTaskLogger(workspace.taskId(), workspace.taskDirectory()));
        assertThrows(SecurityException.class, context::beforeWrite);
        context.proposeSpecification(identitySpec());
        assertThrows(SecurityException.class, context::beforeWrite);
        context.recordValidation(
            new VerificationEvidence(
                ValidationStatus.PASS,
                "fake",
                List.of(),
                List.of(),
                List.of(),
                identitySpec().digest(),
                CircuitDigest.sha256(workspace.circuit())));
        assertFalse(context.hasTrustedPass());
        context.acceptSpecification();
        assertThrows(
            IllegalStateException.class, () -> context.proposeSpecification(identitySpec()));
        assertTrue(context.verifySpecification().isTrustedPass());
        assertTrue(context.hasTrustedPass());
        context.beforeWrite();
        assertFalse(context.hasTrustedPass());
        assertEquals(ValidationStatus.NOT_RUN, context.lastValidation().status());
        context.setExecutionGuard(
            () -> {
              throw new IllegalStateException("cancelled");
            });
        assertThrows(IllegalStateException.class, context::checkWriteAllowed);
      }
    }
  }

  @Test
  void rejectsCircuitsBeyondTenInputBitsBeforeSimulation() {
    try (var f = new Fixture()) {
      f.pin("A", 100, 100, false, 11);
      f.pin("Y", 200, 100, true, 1);
      final var evidence =
          new ValidationEngine().verify(f.project, f.circuit, new ValidationPlan(identitySpec()));
      assertEquals(ValidationStatus.INCONCLUSIVE, evidence.status());
      assertTrue(evidence.summary().contains("1..10"));
    }
  }
}
