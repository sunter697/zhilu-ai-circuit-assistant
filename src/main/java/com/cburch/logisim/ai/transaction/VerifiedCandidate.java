/*
 * Logisim-evolution - digital logic design tool and simulator
 * Copyright by the Logisim-evolution developers
 *
 * https://github.com/logisim-evolution/
 *
 * This is free software released under GNU GPLv3 license
 */

package com.cburch.logisim.ai.transaction;

import com.cburch.logisim.ai.util.SwingExecutor;
import com.cburch.logisim.ai.validation.ValidationPlan;
import com.cburch.logisim.ai.validation.VerificationEvidence;
import com.cburch.logisim.ai.workspace.AgentWorkspace;
import com.cburch.logisim.ai.workspace.WorkspaceSupport;
import com.cburch.logisim.circuit.Circuit;
import com.cburch.logisim.file.LogisimFile;
import com.cburch.logisim.proj.Project;
import java.util.UUID;

/** Immutable, task-owned review contract. Apply never recaptures a mutable workspace candidate. */
public final class VerifiedCandidate {
  private final String id = UUID.randomUUID().toString();
  private final AgentWorkspace workspace;
  private final Project sourceProject;
  private final LogisimFile sourceFile;
  private final Circuit sourceCircuit;
  private final CircuitSnapshot snapshot;
  private final String circuitDigest;
  private final String projectDigest;
  private final ValidationPlan validationPlan;
  private final VerificationEvidence evidence;

  private VerifiedCandidate(
      AgentWorkspace workspace,
      CircuitSnapshot snapshot,
      String circuitDigest,
      String projectDigest,
      ValidationPlan validationPlan,
      VerificationEvidence evidence) {
    this.workspace = workspace;
    this.sourceProject = workspace.sourceProject();
    this.sourceFile = workspace.sourceFile();
    this.sourceCircuit = workspace.sourceCircuit();
    this.snapshot = snapshot;
    this.circuitDigest = circuitDigest;
    this.projectDigest = projectDigest;
    this.validationPlan = validationPlan;
    this.evidence = evidence;
  }

  public static VerifiedCandidate prepare(
      AgentWorkspace workspace, ValidationPlan confirmedPlan, VerificationEvidence evidence) {
    if (workspace == null
        || confirmedPlan == null
        || evidence == null
        || !evidence.isTrustedPass()) {
      throw new IllegalArgumentException(
          "Review requires a confirmed specification and trusted PASS evidence");
    }
    return SwingExecutor.call(
        () -> {
          if (workspace.isClosed()) throw new IllegalStateException("Workspace is already closed");
          WorkspaceSupport.requireSupported(workspace.project().getLogisimFile());
          final var digest = CircuitDigest.sha256(workspace.circuit());
          if (!digest.equals(evidence.circuitDigest())
              || !confirmedPlan.specificationDigest().equals(evidence.specificationDigest())) {
            throw new IllegalStateException(
                "Validation evidence does not match the reviewed circuit and specification");
          }
          return new VerifiedCandidate(
              workspace,
              CircuitSnapshot.capture(workspace.circuit()),
              digest,
              CircuitDigest.projectSha256(workspace.project().getLogisimFile()),
              confirmedPlan,
              evidence);
        });
  }

  public String id() {
    return id;
  }

  public String circuitDigest() {
    return circuitDigest;
  }

  public String projectDigest() {
    return projectDigest;
  }

  public ValidationPlan validationPlan() {
    return validationPlan;
  }

  public VerificationEvidence evidence() {
    return evidence;
  }

  public CircuitSnapshot snapshot() {
    return snapshot;
  }

  boolean belongsTo(AgentWorkspace other) {
    return workspace == other
        && sourceProject == other.sourceProject()
        && sourceFile == other.sourceFile()
        && sourceCircuit == other.sourceCircuit();
  }

  boolean matchesWorkspace() {
    return !workspace.isClosed()
        && circuitDigest.equals(CircuitDigest.sha256(workspace.circuit()))
        && projectDigest.equals(CircuitDigest.projectSha256(workspace.project().getLogisimFile()));
  }
}
