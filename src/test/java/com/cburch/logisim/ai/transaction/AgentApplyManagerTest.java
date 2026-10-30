/*
 * Logisim-evolution - digital logic design tool and simulator
 * Copyright by the Logisim-evolution developers
 *
 * https://github.com/logisim-evolution/
 *
 * This is free software released under GNU GPLv3 license
 */

package com.cburch.logisim.ai.transaction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.cburch.logisim.ai.agent.AgentPermissionManager;
import com.cburch.logisim.ai.agent.TaskExecutionGuard;
import com.cburch.logisim.ai.util.SwingExecutor;
import com.cburch.logisim.ai.validation.TaskSpec;
import com.cburch.logisim.ai.validation.ValidationEngine;
import com.cburch.logisim.ai.validation.ValidationPlan;
import com.cburch.logisim.ai.validation.ValidationStatus;
import com.cburch.logisim.ai.validation.VerificationEvidence;
import com.cburch.logisim.ai.workspace.AgentWorkspace;
import com.cburch.logisim.ai.workspace.WorkspaceManager;
import com.cburch.logisim.circuit.Circuit;
import com.cburch.logisim.circuit.CircuitMutation;
import com.cburch.logisim.circuit.Wire;
import com.cburch.logisim.data.Location;
import com.cburch.logisim.file.Loader;
import com.cburch.logisim.file.LogisimFile;
import com.cburch.logisim.instance.StdAttr;
import com.cburch.logisim.proj.Project;
import com.cburch.logisim.proj.ProjectEvent;
import com.cburch.logisim.proj.ProjectListener;
import com.cburch.logisim.std.wiring.Pin;
import com.cburch.logisim.util.StringUtil;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AgentApplyManagerTest {
  @TempDir Path tempDir;

  @Test
  void reviewedWorkspaceAppliesAsOneUndoableAction() throws Exception {
    try (var f = new Fixture();
        var workspace = f.workspace()) {
      final var baseline = CircuitDigest.sha256(f.circuit);
      final var candidate = verifiedIdentity(workspace);
      final var result = new AgentApplyManager().apply(workspace, candidate, permissions());
      assertTrue(result.applied(), result.message());
      assertEquals(candidate.circuitDigest(), CircuitDigest.sha256(f.circuit));
      f.project.undoAction();
      assertEquals(baseline, CircuitDigest.sha256(f.circuit));
      f.project.redoAction();
      assertEquals(candidate.circuitDigest(), CircuitDigest.sha256(f.circuit));
    }
  }

  @Test
  void userEditAfterPreflightIsNotOverwritten() throws Exception {
    try (var f = new Fixture();
        var workspace = f.workspace()) {
      final var candidate = verifiedIdentity(workspace);
      final var result =
          new AgentApplyManager()
              .apply(
                  workspace,
                  candidate,
                  permissions(),
                  () -> {
                    if (SwingUtilities.isEventDispatchThread()) addGate(f.circuit, f.file, 300);
                  });
      assertFalse(result.applied());
      assertTrue(result.conflict());
      assertEquals(3, f.circuit.getNonWires().size());
      assertTrue(f.circuit.getWires().isEmpty());
    }
  }

  @Test
  void permissionRevokedAfterPreflightStopsCommit() throws Exception {
    try (var f = new Fixture();
        var workspace = f.workspace()) {
      final var candidate = verifiedIdentity(workspace);
      final var permission = permissions();
      final var baseline = CircuitDigest.sha256(f.circuit);
      final var result =
          new AgentApplyManager()
              .apply(
                  workspace,
                  candidate,
                  permission,
                  () -> {
                    if (SwingUtilities.isEventDispatchThread()) permission.setEditsAllowed(false);
                  });
      assertFalse(result.applied());
      assertEquals(baseline, CircuitDigest.sha256(f.circuit));
    }
  }

  @Test
  void workspaceChangeInvalidatesFrozenReview() throws Exception {
    try (var f = new Fixture();
        var workspace = f.workspace()) {
      final var candidate = verifiedIdentity(workspace);
      addGate(workspace.circuit(), workspace.project().getLogisimFile(), 300);
      final var result = new AgentApplyManager().apply(workspace, candidate, permissions());
      assertFalse(result.applied());
      assertTrue(result.conflict());
      assertTrue(f.circuit.getWires().isEmpty());
    }
  }

  @Test
  void forgedEvidenceCannotSkipPreflightSimulation() throws Exception {
    try (var f = new Fixture();
        var workspace = f.workspace()) {
      final var plan = identityPlan();
      final var fake =
          new VerificationEvidence(
              ValidationStatus.PASS,
              "Forged",
              List.of(),
              List.of(),
              List.of(),
              plan.specificationDigest(),
              CircuitDigest.sha256(workspace.circuit()));
      final var candidate = VerifiedCandidate.prepare(workspace, plan, fake);
      final var result = new AgentApplyManager().apply(workspace, candidate, permissions());
      assertFalse(result.applied());
      assertTrue(result.message().contains("verification failed"), result.message());
      assertTrue(f.circuit.getWires().isEmpty());
    }
  }

  @Test
  void snapshotPreservesExactSubcircuitIdentity() throws Exception {
    try (var f = new Fixture()) {
      final var first = new Circuit("Foo_Bar", f.file, f.project);
      final var second = new Circuit("FooBar", f.file, f.project);
      f.file.addCircuit(first);
      f.file.addCircuit(second);
      final var factory = second.getSubcircuitFactory();
      final var component =
          factory.createComponent(Location.create(300, 300, true), factory.createAttributeSet());
      SwingExecutor.run(
          () -> {
            final var mutation = new CircuitMutation(f.circuit);
            mutation.add(component);
            mutation.execute();
          });
      final var baseline = CircuitDigest.sha256(f.circuit);
      final var snapshot = CircuitSnapshot.capture(f.circuit);
      SwingExecutor.run(() -> snapshot.buildMutation(f.circuit, f.file).execute());
      assertEquals(baseline, CircuitDigest.sha256(f.circuit));
      assertTrue(f.circuit.getNonWires().stream().anyMatch(c -> c.getFactory() == factory));
    }
  }

  @Test
  void registrationFailureRestoresCircuitDirtyStateAndRedoHistory() throws Exception {
    try (var f = new Fixture()) {
      final var factory = new FactoryIdentity(List.of("Gates"), "OR Gate", false).resolve(f.file);
      final var gate =
          factory.createComponent(Location.create(300, 300, true), factory.createAttributeSet());
      final var laterGate =
          factory.createComponent(Location.create(400, 300, true), factory.createAttributeSet());
      final var previousMutation = new CircuitMutation(f.circuit);
      previousMutation.add(gate);
      final var previous =
          previousMutation.toAction(StringUtil.constantGetter("Previous user edit"));
      final var laterMutation = new CircuitMutation(f.circuit);
      laterMutation.add(laterGate);
      final var later = laterMutation.toAction(StringUtil.constantGetter("Later user edit"));
      SwingExecutor.run(
          () -> {
            f.project.doAction(previous);
            f.project.doAction(later);
            f.project.undoAction();
          });
      try (var workspace = f.workspace()) {
        final var wasDirty = f.project.isFileDirty();
        final var baseline = CircuitDigest.sha256(f.circuit);
        final var candidate = verifiedIdentity(workspace);
        final ProjectListener rejectingObserver =
            event -> {
              if (event.getAction() == ProjectEvent.ACTION_COMPLETE) {
                throw new IllegalStateException("Injected observer failure");
              }
            };
        f.project.addProjectListener(rejectingObserver);
        final var result = new AgentApplyManager().apply(workspace, candidate, permissions());
        f.project.removeProjectListener(rejectingObserver);
        assertFalse(result.applied());
        assertEquals(baseline, CircuitDigest.sha256(f.circuit), result.message());
        assertEquals(wasDirty, f.project.isFileDirty());
        assertFalse(result.recoveryFailed(), result.message());
        assertSame(later, f.project.getLastRedoAction());
        assertSame(previous, f.project.getLastAction());
        assertTrue(
            f.circuit.contains(gate),
            "Existing history must retain the original component instance");
        SwingExecutor.run(f.project::undoAction);
        assertEquals(2, f.circuit.getNonWires().size());
        assertFalse(f.circuit.contains(gate));
        SwingExecutor.run(f.project::redoAction);
        assertTrue(f.circuit.contains(gate));
        SwingExecutor.run(f.project::redoAction);
        assertTrue(f.circuit.contains(laterGate));
        assertEquals(4, f.circuit.getNonWires().size());
      }
    }
  }

  @Test
  void exhaustedPreflightBudgetCannotCommitLater() throws Exception {
    try (var f = new Fixture();
        var workspace = f.workspace()) {
      final var baseline = CircuitDigest.sha256(f.circuit);
      final var candidate = verifiedIdentity(workspace);
      assertThrows(
          TaskExecutionGuard.BudgetExceededException.class,
          () ->
              new AgentApplyManager()
                  .apply(workspace, candidate, permissions(), () -> {}, Duration.ofNanos(1)));
      SwingExecutor.run(() -> {});
      assertEquals(baseline, CircuitDigest.sha256(f.circuit));
      assertEquals(null, f.project.getLastAction());
    }
  }

  @Test
  void annotatedWireSurvivesApplyUndoAndRedo() throws Exception {
    try (var f = new Fixture();
        var workspace = f.workspace()) {
      final var baseline = CircuitDigest.sha256(f.circuit);
      verifiedIdentity(workspace);
      final var wire = workspace.circuit().getWires().iterator().next();
      SwingExecutor.run(
          () -> {
            final var mutation = new CircuitMutation(workspace.circuit());
            mutation.set(wire, Wire.BUS_WIDTH_POS_ATTR, Wire.BUS_WIDTH_POS_CENTER);
            mutation.execute();
          });
      final var plan = identityPlan();
      final var evidence =
          new ValidationEngine().verifyPlan(workspace.project(), workspace.circuit(), plan);
      final var candidate = VerifiedCandidate.prepare(workspace, plan, evidence);
      final var result = new AgentApplyManager().apply(workspace, candidate, permissions());
      assertTrue(result.applied(), result.message());
      assertEquals(candidate.circuitDigest(), CircuitDigest.sha256(f.circuit));
      assertEquals(
          Wire.BUS_WIDTH_POS_CENTER,
          f.circuit.getWireBusWidthPos(f.circuit.getWires().iterator().next()));
      f.project.undoAction();
      assertEquals(baseline, CircuitDigest.sha256(f.circuit));
      f.project.redoAction();
      assertEquals(candidate.circuitDigest(), CircuitDigest.sha256(f.circuit));
    }
  }

  @Test
  void mixedNativeReplacementAndAttributeMutationRetainsUndoLog() {
    try (var f = new Fixture()) {
      final var baseline = CircuitDigest.sha256(f.circuit);
      final var factory = new FactoryIdentity(List.of("Gates"), "OR Gate", false).resolve(f.file);
      final var gate =
          factory.createComponent(Location.create(300, 300, true), factory.createAttributeSet());
      final var mutation = new CircuitMutation(f.circuit);
      mutation.add(gate);
      mutation.set(gate, StdAttr.LABEL, "compound");
      f.project.doAction(mutation.toAction(StringUtil.constantGetter("Compound user mutation")));
      final var applied = CircuitDigest.sha256(f.circuit);
      f.project.undoAction();
      assertEquals(baseline, CircuitDigest.sha256(f.circuit));
      f.project.redoAction();
      assertEquals(applied, CircuitDigest.sha256(f.circuit));
    }
  }

  @Test
  void simulationOptionChangeInvalidatesBaseline() throws Exception {
    try (var f = new Fixture();
        var workspace = f.workspace()) {
      final var candidate = verifiedIdentity(workspace);
      f.file
          .getOptions()
          .getAttributeSet()
          .setValue(com.cburch.logisim.file.Options.ATTR_SIM_LIMIT, 2000);
      final var result = new AgentApplyManager().apply(workspace, candidate, permissions());
      assertFalse(result.applied());
      assertTrue(result.conflict());
      assertEquals(
          2000,
          f.file
              .getOptions()
              .getAttributeSet()
              .getValue(com.cburch.logisim.file.Options.ATTR_SIM_LIMIT));
    }
  }

  private static ValidationPlan identityPlan() {
    return new ValidationPlan(
        new TaskSpec(
            TaskSpec.Kind.BOOLEAN_EXPRESSIONS,
            List.of(new TaskSpec.SignalSpec("A", 1)),
            List.of(new TaskSpec.SignalSpec("Y", 1)),
            Map.of("Y", "A")));
  }

  private static VerifiedCandidate verifiedIdentity(AgentWorkspace workspace) {
    SwingExecutor.run(
        () -> {
          final var mutation = new CircuitMutation(workspace.circuit());
          mutation.add(
              Wire.create(Location.create(100, 100, true), Location.create(200, 100, true)));
          mutation.execute();
        });
    final var plan = identityPlan();
    final var evidence =
        new ValidationEngine().verifyPlan(workspace.project(), workspace.circuit(), plan);
    assertTrue(evidence.isTrustedPass(), evidence.summary());
    return VerifiedCandidate.prepare(workspace, plan, evidence);
  }

  private static AgentPermissionManager permissions() {
    final var permission = new AgentPermissionManager();
    permission.setEditsAllowed(true);
    return permission;
  }

  private static void addGate(Circuit circuit, LogisimFile file, int x) {
    final var factory = new FactoryIdentity(List.of("Gates"), "OR Gate", false).resolve(file);
    SwingExecutor.run(
        () -> {
          final var mutation = new CircuitMutation(circuit);
          mutation.add(
              factory.createComponent(Location.create(x, 300, true), factory.createAttributeSet()));
          mutation.execute();
        });
  }

  private final class Fixture implements AutoCloseable {
    private final LogisimFile file;
    private final Project project;
    private final Circuit circuit;

    private Fixture() {
      final var loader = new Loader(null);
      file = LogisimFile.createNew(loader, null);
      file.addLibrary(loader.loadLibrary("#Wiring"));
      file.addLibrary(loader.loadLibrary("#Gates"));
      project = new Project(file);
      circuit = project.getCurrentCircuit();
      circuit.setProject(project);
      SwingExecutor.run(
          () -> {
            final var mutation = new CircuitMutation(circuit);
            for (var name : List.of("A", "Y")) {
              final var attrs = Pin.FACTORY.createAttributeSet();
              attrs.setValue(StdAttr.LABEL, name);
              attrs.setValue(Pin.ATTR_TYPE, name.equals("Y") ? Pin.OUTPUT : Pin.INPUT);
              mutation.add(
                  Pin.FACTORY.createComponent(
                      Location.create(name.equals("A") ? 100 : 200, 100, true), attrs));
            }
            mutation.execute();
          });
    }

    private AgentWorkspace workspace() throws Exception {
      return new WorkspaceManager(tempDir).create(project, circuit);
    }

    @Override
    public void close() {
      WorkspaceManager.closeProject(project);
    }
  }
}
