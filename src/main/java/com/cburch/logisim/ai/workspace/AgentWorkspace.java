/*
 * Logisim-evolution - digital logic design tool and simulator
 * Copyright by the Logisim-evolution developers
 *
 * https://github.com/logisim-evolution/
 *
 * This is free software released under GNU GPLv3 license
 */

package com.cburch.logisim.ai.workspace;

import com.cburch.logisim.circuit.Circuit;
import com.cburch.logisim.file.LogisimFile;
import com.cburch.logisim.proj.Project;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;

/** Isolated editable project for one AI task. */
public final class AgentWorkspace implements AutoCloseable {
  private final String taskId;
  private final Project sourceProject;
  private final Circuit sourceCircuit;
  private final LogisimFile sourceFile;
  private final byte[] baselineBytes;
  private final String sourceDigest;
  private final String sourceProjectDigest;
  private final String baselineCanonical;
  private final Project project;
  private final Circuit circuit;
  private final Path taskDirectory;
  private final Path baselineFile;
  private final Path workspaceFile;
  private final AtomicBoolean closed = new AtomicBoolean(false);

  AgentWorkspace(
      String taskId,
      Project sourceProject,
      Circuit sourceCircuit,
      LogisimFile sourceFile,
      byte[] baselineBytes,
      String sourceDigest,
      String sourceProjectDigest,
      String baselineCanonical,
      Project project,
      Circuit circuit,
      Path taskDirectory,
      Path baselineFile,
      Path workspaceFile) {
    this.taskId = taskId;
    this.sourceProject = sourceProject;
    this.sourceCircuit = sourceCircuit;
    this.sourceFile = sourceFile;
    this.baselineBytes = baselineBytes.clone();
    this.sourceDigest = sourceDigest;
    this.sourceProjectDigest = sourceProjectDigest;
    this.baselineCanonical = baselineCanonical;
    this.project = project;
    this.circuit = circuit;
    this.taskDirectory = taskDirectory;
    this.baselineFile = baselineFile;
    this.workspaceFile = workspaceFile;
  }

  public String taskId() {
    return taskId;
  }

  public Project sourceProject() {
    return sourceProject;
  }

  public Circuit sourceCircuit() {
    return sourceCircuit;
  }

  public LogisimFile sourceFile() {
    return sourceFile;
  }

  public byte[] baselineBytes() {
    return baselineBytes.clone();
  }

  public String sourceDigest() {
    return sourceDigest;
  }

  public String sourceProjectDigest() {
    return sourceProjectDigest;
  }

  public String baselineCanonical() {
    return baselineCanonical;
  }

  public Project project() {
    return project;
  }

  public Circuit circuit() {
    return circuit;
  }

  public Path taskDirectory() {
    return taskDirectory;
  }

  public Path baselineFile() {
    return baselineFile;
  }

  public Path workspaceFile() {
    return workspaceFile;
  }

  public boolean isClosed() {
    return closed.get();
  }

  @Override
  public void close() {
    if (closed.compareAndSet(false, true)) {
      project.getSimulator().shutDown();
      project.getLogisimFile().stopAutosaveThread(false);
    }
  }
}
