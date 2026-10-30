/*
 * Logisim-evolution - digital logic design tool and simulator
 * Copyright by the Logisim-evolution developers
 *
 * https://github.com/logisim-evolution/
 *
 * This is free software released under GNU GPLv3 license
 */

package com.cburch.logisim.ai.tools;

import com.cburch.logisim.ai.agent.AgentPermissionManager;
import com.cburch.logisim.ai.agent.AgentState;
import com.cburch.logisim.ai.agent.TaskBudget;
import com.cburch.logisim.ai.context.CircuitContextExtractor;
import com.cburch.logisim.ai.context.CircuitContextSession;
import com.cburch.logisim.ai.logging.AgentTaskLogger;
import com.cburch.logisim.ai.transaction.CircuitDigest;
import com.cburch.logisim.ai.validation.TaskSpec;
import com.cburch.logisim.ai.validation.ValidationEngine;
import com.cburch.logisim.ai.validation.ValidationPlan;
import com.cburch.logisim.ai.validation.ValidationStatus;
import com.cburch.logisim.ai.validation.VerificationEvidence;
import com.cburch.logisim.ai.workspace.AgentWorkspace;
import com.cburch.logisim.ai.workspace.WorkspaceManager;
import java.io.IOException;
import java.util.List;
import java.util.Map;

public final class ToolExecutionContext {
  private final AgentWorkspace workspace;
  private final WorkspaceManager workspaceManager;
  private final AgentPermissionManager permissions;
  private final TaskBudget budget;
  private final AgentTaskLogger logger;
  private final CircuitContextExtractor extractor = new CircuitContextExtractor();
  private final ValidationEngine validationEngine = new ValidationEngine();
  private CircuitContextSession circuitContext;
  private VerificationEvidence lastValidation = VerificationEvidence.notRun();
  private boolean modified;
  private boolean repairInProgress;
  private int repairAttempts;
  private String pendingBeforeCanonical;
  private TaskSpec pendingSpecification;
  private TaskSpec confirmedSpecification;
  private ValidationPlan validationPlan;
  private long workspaceVersion;
  private volatile Runnable executionGuard = () -> {};

  public ToolExecutionContext(
      AgentWorkspace workspace,
      WorkspaceManager workspaceManager,
      AgentPermissionManager permissions,
      TaskBudget budget,
      AgentTaskLogger logger) {
    this.workspace = workspace;
    this.workspaceManager = workspaceManager;
    this.permissions = permissions;
    this.budget = budget;
    this.logger = logger;
    this.circuitContext = extractor.extract(workspace.circuit());
  }

  public AgentWorkspace workspace() {
    return workspace;
  }

  public AgentTaskLogger logger() {
    return logger;
  }

  public ValidationEngine validationEngine() {
    return validationEngine;
  }

  public VerificationEvidence lastValidation() {
    return lastValidation;
  }

  public boolean modified() {
    return modified;
  }

  public int repairAttempts() {
    return repairAttempts;
  }

  public TaskSpec pendingSpecification() {
    return pendingSpecification;
  }

  public TaskSpec confirmedSpecification() {
    return confirmedSpecification;
  }

  public ValidationPlan validationPlan() {
    return validationPlan;
  }

  public long workspaceVersion() {
    return workspaceVersion;
  }

  public String verificationDigest() {
    return lastValidation.circuitDigest();
  }

  public void setExecutionGuard(Runnable guard) {
    executionGuard = java.util.Objects.requireNonNull(guard);
  }

  public void checkExecutionAllowed() {
    executionGuard.run();
  }

  public void checkWriteAllowed() {
    checkExecutionAllowed();
    if (!permissions.isEditsAllowed())
      throw new SecurityException("Circuit edit permission is not enabled for this session");
    if (confirmedSpecification == null)
      throw new SecurityException(
          "The user must explicitly confirm a functional specification before edits");
  }

  public void proposeSpecification(TaskSpec specification) {
    checkExecutionAllowed();
    if (confirmedSpecification != null || modified) {
      throw new IllegalStateException(
          "The confirmed specification is frozen for this task; start a new task to change it");
    }
    new ValidationPlan(specification); // Invalid proposals never replace the pending contract.
    pendingSpecification = specification;
    logger.event("SPECIFICATION_PROPOSED", AgentState.WAITING_FOR_USER, specification.toMap());
  }

  /** Host-only entry point: call in response to the user's explicit confirmation action. */
  public void acceptSpecification() {
    checkExecutionAllowed();
    if (pendingSpecification == null || confirmedSpecification != null || modified) {
      throw new IllegalStateException("No unmodified pending specification can be confirmed");
    }
    validationPlan = new ValidationPlan(pendingSpecification);
    confirmedSpecification = pendingSpecification;
    pendingSpecification = null;
    lastValidation = VerificationEvidence.notRun();
    logger.event(
        "SPECIFICATION_CONFIRMED",
        AgentState.PLANNING,
        Map.of(
            "specification",
            confirmedSpecification.toMap(),
            "digest",
            confirmedSpecification.digest()));
  }

  public void rejectPendingSpecification() {
    pendingSpecification = null;
  }

  public boolean hasTrustedPass() {
    return validationPlan != null
        && lastValidation.isTrustedPass()
        && lastValidation.specificationDigest().equals(validationPlan.specificationDigest())
        && lastValidation.circuitDigest().equals(CircuitDigest.sha256(workspace.circuit()));
  }

  public VerificationEvidence verifySpecification() {
    checkExecutionAllowed();
    if (validationPlan == null)
      throw new IllegalStateException("No user-confirmed specification is available");
    final var evidence =
        validationEngine.verifyPlan(workspace.project(), workspace.circuit(), validationPlan);
    checkExecutionAllowed();
    lastValidation = evidence;
    repairInProgress = false;
    logger.event("SPECIFICATION_VERIFIED", AgentState.VALIDATING, evidence.toMap());
    return evidence;
  }

  public CircuitContextSession circuitContext() {
    return circuitContext;
  }

  public CircuitContextSession refreshContext() {
    circuitContext = extractor.extract(workspace.circuit());
    return circuitContext;
  }

  public void beforeWrite() {
    checkWriteAllowed();
    pendingBeforeCanonical = CircuitDigest.canonical(workspace.circuit());
    if (lastValidation.status() == ValidationStatus.FAIL && !repairInProgress) {
      if (repairAttempts >= budget.maxRepairAttempts()) {
        throw new IllegalStateException(
            "Automatic repair budget exhausted (max " + budget.maxRepairAttempts() + ")");
      }
      repairAttempts++;
      repairInProgress = true;
      logger.event(
          "REPAIR_STARTED",
          AgentState.REPAIRING,
          java.util.Map.of("attempt", repairAttempts, "max", budget.maxRepairAttempts()));
    }
    // Invalidate BEFORE mutation: even a partially failed tool must not retain an earlier PASS.
    lastValidation = VerificationEvidence.notRun();
  }

  public void afterWrite() throws IOException {
    modified = true;
    workspaceVersion++;
    lastValidation = VerificationEvidence.notRun();
    final var afterCanonical = CircuitDigest.canonical(workspace.circuit());
    if (pendingBeforeCanonical != null) {
      logger.event(
          "CIRCUIT_CHANGED",
          AgentState.EXECUTING,
          CircuitDiff.compareCanonical(pendingBeforeCanonical, afterCanonical));
    }
    pendingBeforeCanonical = null;
    refreshContext();
    workspaceManager.persist(workspace);
  }

  /** Legacy diagnostic validation only. Model-authored rows are never an acceptance contract. */
  public void lockTruthTableSpec(
      List<String> inputs, List<String> outputs, List<List<String>> rows) {
    ValidationEngine.validateDiagnosticRows(inputs, outputs, rows);
  }

  public void recordValidation(VerificationEvidence evidence) {
    if (evidence == null || evidence.status() == ValidationStatus.NOT_RUN) {
      lastValidation = VerificationEvidence.notRun();
    } else if (evidence.status() == ValidationStatus.INCONCLUSIVE) {
      lastValidation =
          new VerificationEvidence(
              evidence.status(),
              evidence.summary(),
              evidence.passedChecks(),
              evidence.warnings(),
              evidence.failures());
    }
  }
}
