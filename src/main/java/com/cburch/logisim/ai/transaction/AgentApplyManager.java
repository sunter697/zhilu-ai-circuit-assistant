/*
 * Logisim-evolution - digital logic design tool and simulator
 * Copyright by the Logisim-evolution developers
 *
 * https://github.com/logisim-evolution/
 *
 * This is free software released under GNU GPLv3 license
 */

package com.cburch.logisim.ai.transaction;

import com.cburch.logisim.ai.agent.AgentPermissionManager;
import com.cburch.logisim.ai.agent.TaskExecutionGuard;
import com.cburch.logisim.ai.i18n.AiStrings;
import com.cburch.logisim.ai.util.SwingExecutor;
import com.cburch.logisim.ai.validation.ValidationEngine;
import com.cburch.logisim.ai.workspace.AgentWorkspace;
import com.cburch.logisim.ai.workspace.WorkspaceManager;
import com.cburch.logisim.ai.workspace.WorkspaceSupport;
import com.cburch.logisim.circuit.Circuit;
import com.cburch.logisim.circuit.CircuitMutation;
import com.cburch.logisim.circuit.CircuitTransaction;
import com.cburch.logisim.circuit.Wire;
import com.cburch.logisim.comp.Component;
import com.cburch.logisim.data.AttributeOption;
import com.cburch.logisim.proj.Action;
import com.cburch.logisim.proj.Project;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import javax.swing.SwingUtilities;

/** Conflict-checked formal apply that preserves one-step native undo. */
public final class AgentApplyManager {
  public record ApplyResult(
      boolean applied, boolean conflict, String message, boolean recoveryFailed) {
    public ApplyResult(boolean applied, boolean conflict, String message) {
      this(applied, conflict, message, false);
    }
  }

  public ApplyResult apply(AgentWorkspace workspace, AgentPermissionManager permissions) {
    return new ApplyResult(
        false, false, "Apply requires a frozen, specification-verified candidate.");
  }

  public ApplyResult apply(
      AgentWorkspace workspace, VerifiedCandidate candidate, AgentPermissionManager permissions) {
    return apply(workspace, candidate, permissions, () -> {});
  }

  public ApplyResult apply(
      AgentWorkspace workspace,
      VerifiedCandidate candidate,
      AgentPermissionManager permissions,
      Runnable commitGuard) {
    return apply(workspace, candidate, permissions, commitGuard, Duration.ofSeconds(30));
  }

  public ApplyResult apply(
      AgentWorkspace workspace,
      VerifiedCandidate candidate,
      AgentPermissionManager permissions,
      Runnable commitGuard,
      Duration preflightLimit) {
    if (SwingUtilities.isEventDispatchThread()) {
      return new ApplyResult(false, false, "Apply preflight must run outside Swing EDT.");
    }
    if (workspace == null || candidate == null || permissions == null) {
      return new ApplyResult(false, false, "A verified candidate and task workspace are required.");
    }
    final var initialCheck =
        SwingExecutor.call(() -> checkState(workspace, candidate, permissions));
    if (initialCheck != null) return initialCheck;
    try {
      commitGuard.run();
      boundedPreflight(workspace, candidate, commitGuard, preflightLimit);
    } catch (CancellationException | TaskExecutionGuard.BudgetExceededException e) {
      throw e;
    } catch (Exception e) {
      return new ApplyResult(false, false, "Apply preflight failed: " + safeMessage(e));
    }

    return SwingExecutor.call(
        () -> {
          commitGuard.run();
          // Permission, identity and both versions are checked in the same EDT turn as the
          // mutation.
          final var finalCheck = checkState(workspace, candidate, permissions);
          if (finalCheck != null) return finalCheck;
          final var target = workspace.sourceCircuit();
          final var baseline = CircuitSnapshot.capture(target);
          final var original = OriginalContents.capture(target);
          final var baselineDigest = CircuitDigest.sha256(target);
          final var targetMutation =
              candidate.snapshot().buildMutation(target, workspace.sourceFile());
          CircuitTransaction rollback = null;
          try {
            final var result = targetMutation.execute();
            rollback = result.getReverseTransaction();
            if (!CircuitDigest.sha256(target).equals(candidate.circuitDigest())
                || !CircuitDigest.projectSha256(workspace.sourceFile())
                    .equals(candidate.projectDigest())) {
              throw new IllegalStateException("Formal result differs from the reviewed candidate");
            }
            final var action =
                new PreAppliedAction(
                    "AI Circuit Agent",
                    targetMutation,
                    result.getReverseTransaction(),
                    target,
                    workspace.sourceFile(),
                    baseline,
                    candidate.snapshot(),
                    original,
                    OriginalContents.capture(target),
                    baselineDigest,
                    candidate.circuitDigest());
            workspace.sourceProject().recordAppliedAction(action);
            return new ApplyResult(
                true, false, "The reviewed workspace was applied as one undoable Logisim action.");
          } catch (RuntimeException applyError) {
            try {
              if (rollback != null) rollback.execute();
              if (!baselineDigest.equals(CircuitDigest.sha256(target))
                  || !original.isPresent(target)) {
                original.restore(target, baseline);
              }
              if (!baselineDigest.equals(CircuitDigest.sha256(target))
                  || !workspace
                      .sourceProjectDigest()
                      .equals(CircuitDigest.projectSha256(workspace.sourceFile()))) {
                throw new IllegalStateException("Restored circuit does not match its baseline");
              }
              return new ApplyResult(
                  false,
                  false,
                  "Formal apply failed and the original circuit was restored: "
                      + safeMessage(applyError));
            } catch (RuntimeException restoreError) {
              return new ApplyResult(
                  false,
                  false,
                  "Formal apply failed and automatic restoration also failed. Save a copy before"
                      + " continuing. Apply error: "
                      + safeMessage(applyError)
                      + "; restore error: "
                      + safeMessage(restoreError),
                  true);
            }
          }
        });
  }

  private record OriginalContents(
      List<Component> components, Map<Wire, AttributeOption> wirePositions) {
    static OriginalContents capture(Circuit circuit) {
      final var components = new ArrayList<>(circuit.getNonWires());
      components.addAll(circuit.getWires());
      final var positions = new LinkedHashMap<Wire, AttributeOption>();
      for (var wire : circuit.getWires()) positions.put(wire, circuit.getWireBusWidthPos(wire));
      return new OriginalContents(List.copyOf(components), Map.copyOf(positions));
    }

    boolean isPresent(Circuit circuit) {
      return components.stream().allMatch(circuit::contains);
    }

    void restore(Circuit target, CircuitSnapshot baseline) {
      buildMutation(target, baseline).execute();
    }

    CircuitMutation buildMutation(Circuit target, CircuitSnapshot baseline) {
      final var mutation = new CircuitMutation(target);
      baseline.addCircuitAttributes(mutation, target);
      mutation.clear();
      mutation.addAll(components);
      for (var entry : wirePositions.entrySet()) {
        if (entry.getValue() != Wire.BUS_WIDTH_POS_NONE) {
          mutation.set(entry.getKey(), Wire.BUS_WIDTH_POS_ATTR, entry.getValue());
        }
      }
      return mutation;
    }
  }

  private static void boundedPreflight(
      AgentWorkspace workspace, VerifiedCandidate candidate, Runnable guard, Duration limit)
      throws Exception {
    final var executor =
        Executors.newSingleThreadExecutor(
            runnable -> {
              final var thread = new Thread(runnable, "Logisim-AI-Preflight");
              thread.setDaemon(true);
              return thread;
            });
    final var future =
        executor.submit(
            () -> {
              preflight(workspace, candidate);
              return null;
            });
    final long deadline = System.nanoTime() + limit.toNanos();
    try {
      while (true) {
        guard.run();
        final long remaining = deadline - System.nanoTime();
        if (remaining <= 0)
          throw new TaskExecutionGuard.BudgetExceededException("Apply preflight budget exhausted");
        try {
          future.get(Math.min(remaining, TimeUnit.MILLISECONDS.toNanos(100)), TimeUnit.NANOSECONDS);
          return;
        } catch (TimeoutException e) {
          // Commit is never run in this background future; timed-out preflight cannot apply.
        } catch (ExecutionException e) {
          if (e.getCause() instanceof Exception cause) throw cause;
          throw new RuntimeException(e.getCause());
        }
      }
    } finally {
      if (!future.isDone()) future.cancel(true);
      executor.shutdownNow();
    }
  }

  private static ApplyResult checkState(
      AgentWorkspace workspace, VerifiedCandidate candidate, AgentPermissionManager permissions) {
    if (!permissions.isEditsAllowed()) {
      return new ApplyResult(false, false, "Edit permission has been revoked for this session.");
    }
    if (!candidate.belongsTo(workspace) || workspace.isClosed()) {
      return new ApplyResult(
          false, true, "The candidate does not belong to this active workspace.");
    }
    final var source = workspace.sourceProject();
    if (source.getLogisimFile() != workspace.sourceFile()
        || source.getCurrentCircuit() != workspace.sourceCircuit()
        || !workspace.sourceFile().contains(workspace.sourceCircuit())) {
      return new ApplyResult(false, true, "The formal project or active circuit changed.");
    }
    try {
      WorkspaceSupport.requireSupported(source.getLogisimFile());
      WorkspaceSupport.requireSupported(workspace.project().getLogisimFile());
    } catch (RuntimeException e) {
      return new ApplyResult(false, false, safeMessage(e));
    }
    if (!candidate.matchesWorkspace()) {
      return new ApplyResult(
          false, true, "The workspace changed after Review; its evidence is stale.");
    }
    if (!CircuitDigest.sha256(workspace.sourceCircuit()).equals(workspace.sourceDigest())
        || !CircuitDigest.projectSha256(workspace.sourceFile())
            .equals(workspace.sourceProjectDigest())) {
      return new ApplyResult(
          false, true, "The formal project changed; user edits will not be overwritten.");
    }
    return null;
  }

  private static void preflight(AgentWorkspace workspace, VerifiedCandidate candidate)
      throws IOException {
    final var cloneProject =
        WorkspaceManager.loadSnapshot(
            workspace.baselineBytes(), workspace.sourceCircuit().getName());
    try {
      final var target = cloneProject.getCurrentCircuit();
      SwingExecutor.run(
          () ->
              candidate.snapshot().buildMutation(target, cloneProject.getLogisimFile()).execute());
      if (!candidate.circuitDigest().equals(CircuitDigest.sha256(target))
          || !candidate
              .projectDigest()
              .equals(CircuitDigest.projectSha256(cloneProject.getLogisimFile()))) {
        throw new IOException("Preflight reconstruction differs from the frozen candidate");
      }
      final var evidence =
          new ValidationEngine().verifyPlan(cloneProject, target, candidate.validationPlan());
      if (!evidence.isTrustedPass()
          || !candidate.circuitDigest().equals(evidence.circuitDigest())) {
        throw new IOException("Preflight specification verification failed: " + evidence.summary());
      }
    } finally {
      WorkspaceManager.closeProject(cloneProject);
    }
  }

  /**
   * Project.doAction sees a no-op first doIt because the forward transaction has already succeeded.
   */
  private static final class PreAppliedAction extends Action {
    private final String name;
    private CircuitTransaction forward;
    private CircuitTransaction reverse;
    private boolean firstDo = true;
    private final Circuit target;
    private final com.cburch.logisim.file.LogisimFile file;
    private final CircuitSnapshot baseline;
    private final CircuitSnapshot candidate;
    private final OriginalContents baselineContents;
    private final OriginalContents candidateContents;
    private final String baselineDigest;
    private final String candidateDigest;

    PreAppliedAction(
        String name,
        CircuitTransaction forward,
        CircuitTransaction reverse,
        Circuit target,
        com.cburch.logisim.file.LogisimFile file,
        CircuitSnapshot baseline,
        CircuitSnapshot candidate,
        OriginalContents baselineContents,
        OriginalContents candidateContents,
        String baselineDigest,
        String candidateDigest) {
      this.name = name;
      this.forward = forward;
      this.reverse = reverse;
      this.target = target;
      this.file = file;
      this.baseline = baseline;
      this.candidate = candidate;
      this.baselineContents = baselineContents;
      this.candidateContents = candidateContents;
      this.baselineDigest = baselineDigest;
      this.candidateDigest = candidateDigest;
    }

    @Override
    public void doIt(Project proj) {
      if (firstDo) {
        firstDo = false;
        return;
      }
      final var result = forward.execute();
      reverse = result.getReverseTransaction();
      if (!candidateDigest.equals(CircuitDigest.sha256(target))) {
        reverse =
            candidateContents.buildMutation(target, candidate).execute().getReverseTransaction();
        if (!candidateDigest.equals(CircuitDigest.sha256(target)))
          throw new IllegalStateException("Redo restoration differs from the reviewed candidate");
      }
    }

    @Override
    public String getName() {
      return AiStrings.S.get("aiApplyActionName");
    }

    @Override
    public void undo(Project proj) {
      if (reverse == null) return;
      final var result = reverse.execute();
      forward = result.getReverseTransaction();
      if (!baselineDigest.equals(CircuitDigest.sha256(target))) {
        baselineContents.restore(target, baseline);
        forward = candidateContents.buildMutation(target, candidate);
        if (!baselineDigest.equals(CircuitDigest.sha256(target)))
          throw new IllegalStateException("Undo restoration differs from its baseline");
      }
    }
  }

  private static String safeMessage(Throwable error) {
    return error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
  }
}
