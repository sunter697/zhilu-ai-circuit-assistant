/*
 * Logisim-evolution - digital logic design tool and simulator
 * Copyright by the Logisim-evolution developers
 *
 * https://github.com/logisim-evolution/
 *
 * This is free software released under GNU GPLv3 license
 */

package com.cburch.logisim.ai.workspace;

import com.cburch.logisim.ai.transaction.CircuitDigest;
import com.cburch.logisim.ai.util.SimpleJson;
import com.cburch.logisim.ai.util.SwingExecutor;
import com.cburch.logisim.circuit.Circuit;
import com.cburch.logisim.file.Loader;
import com.cburch.logisim.file.LogisimFile;
import com.cburch.logisim.proj.Project;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.UUID;
import javax.swing.SwingUtilities;

/** Creates, persists and tears down task-scoped circuit workspaces. */
public final class WorkspaceManager {
  private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
  private final Path taskRoot;

  public WorkspaceManager() {
    this(defaultTaskRoot());
  }

  public WorkspaceManager(Path taskRoot) {
    this.taskRoot = taskRoot;
  }

  public Path taskRoot() {
    return taskRoot;
  }

  public AgentWorkspace create(Project sourceProject, Circuit sourceCircuit) throws IOException {
    if (sourceProject == null || sourceCircuit == null) {
      throw new IllegalArgumentException("A source project and circuit are required");
    }
    final var captured =
        SwingExecutor.call(
            () -> {
              final var sourceFile = sourceProject.getLogisimFile();
              if (sourceProject.getCurrentCircuit() != sourceCircuit
                  || !sourceFile.contains(sourceCircuit)) {
                throw new IllegalStateException(
                    "The active circuit changed before workspace capture");
              }
              WorkspaceSupport.requireSupported(sourceFile);
              return new CapturedProject(
                  sourceFile,
                  sourceCircuit.getName(),
                  CircuitDigest.sha256(sourceCircuit),
                  CircuitDigest.projectSha256(sourceFile),
                  CircuitDigest.canonical(sourceCircuit),
                  serialize(sourceFile));
            });
    final var taskId =
        STAMP.format(LocalDateTime.now()) + "-" + UUID.randomUUID().toString().substring(0, 8);
    final var directory = taskRoot.resolve(taskId);
    Files.createDirectories(directory);
    final var baseline = directory.resolve("baseline.circ");
    final var workspace = directory.resolve("workspace.circ");
    final var sourceDigest = captured.circuitDigest();
    final var sourceProjectDigest = captured.projectDigest();
    Files.write(baseline, captured.bytes());
    Files.writeString(
        directory.resolve("metadata.json"),
        SimpleJson.stringify(
            Map.of(
                "taskId",
                taskId,
                "createdAt",
                Instant.now().toString(),
                "sourceCircuit",
                captured.circuitName(),
                "baselineCircuitDigest",
                sourceDigest,
                "baselineProjectDigest",
                sourceProjectDigest,
                "baselineArtifact",
                "baseline.circ",
                "workspaceArtifact",
                "workspace.circ")),
        java.nio.charset.StandardCharsets.UTF_8,
        StandardOpenOption.CREATE,
        StandardOpenOption.TRUNCATE_EXISTING,
        StandardOpenOption.WRITE);

    final var cloneProject = loadSnapshot(captured.bytes(), captured.circuitName());
    try {
      final var cloneFile = cloneProject.getLogisimFile();
      final var cloneCircuit = cloneProject.getCurrentCircuit();
      if (!CircuitDigest.projectSha256(cloneFile).equals(sourceProjectDigest)) {
        throw new IOException("Workspace reload changed the project; AI editing was refused");
      }
      writeProject(cloneFile, workspace);
      return new AgentWorkspace(
          taskId,
          sourceProject,
          sourceCircuit,
          captured.file(),
          captured.bytes(),
          sourceDigest,
          sourceProjectDigest,
          captured.canonical(),
          cloneProject,
          cloneCircuit,
          directory,
          baseline,
          workspace);
    } catch (Exception error) {
      closeProject(cloneProject);
      if (error instanceof IOException io) throw io;
      throw new IOException("Could not prepare isolated workspace", error);
    }
  }

  /**
   * Deletes only the retained editable workspace after explicit user cleanup. Baseline, metadata,
   * report and event logs remain available for audit.
   */
  public void deleteRetainedWorkspace(AgentWorkspace workspace) throws IOException {
    if (workspace == null) return;
    final var root = taskRoot.toAbsolutePath().normalize();
    final var workspaceFile = workspace.workspaceFile().toAbsolutePath().normalize();
    if (!workspaceFile.startsWith(root) || workspaceFile.equals(root)) {
      throw new SecurityException("Refusing to delete an AI workspace outside the task root");
    }
    Files.deleteIfExists(workspaceFile);
  }

  public void persist(AgentWorkspace workspace) throws IOException {
    if (workspace == null || workspace.isClosed()) return;
    writeProject(workspace.project().getLogisimFile(), workspace.workspaceFile());
  }

  public static void writeProject(LogisimFile file, Path path) throws IOException {
    Files.createDirectories(path.getParent());
    final var bytes = SwingExecutor.call(() -> serialize(file));
    Files.write(path, bytes);
  }

  /** Rehydrates immutable bytes on a worker; the formal mutable project is never read here. */
  public static Project loadSnapshot(byte[] bytes, String circuitName) throws IOException {
    if (SwingUtilities.isEventDispatchThread()) {
      throw new IllegalStateException("Snapshot loading must run outside Swing EDT");
    }
    final var loader = new SnapshotLoader(null);
    final var file = LogisimFile.load(new ByteArrayInputStream(bytes), loader);
    if (file == null) throw new IOException("Logisim could not reload the project snapshot");
    file.stopAutosaveThread(false);
    Project project = null;
    try {
      project = new Project(file);
      for (final var circuit : file.getCircuits()) circuit.setProject(project);
      final var circuit = findCircuit(file, circuitName);
      if (circuit == null) throw new IOException("Snapshot lost circuit " + circuitName);
      project.setCurrentCircuit(circuit);
      WorkspaceSupport.requireSupported(file);
      return project;
    } catch (RuntimeException | IOException error) {
      if (project != null) closeProject(project);
      file.stopAutosaveThread(false);
      throw error;
    }
  }

  public static void closeProject(Project project) {
    project.getSimulator().shutDown();
    project.getLogisimFile().stopAutosaveThread(false);
  }

  private static byte[] serialize(LogisimFile file) {
    WorkspaceSupport.requireSupported(file);
    final var out = new ByteArrayOutputStream();
    file.write(out, new SnapshotLoader(file.getLoader()));
    return out.toByteArray();
  }

  private record CapturedProject(
      LogisimFile file,
      String circuitName,
      String circuitDigest,
      String projectDigest,
      String canonical,
      byte[] bytes) {}

  /** Convert Logisim loader dialogs/save errors into task failures instead of hidden modals. */
  private static final class SnapshotLoader extends Loader {
    private final Loader descriptorSource;

    SnapshotLoader(Loader descriptorSource) {
      super(null);
      this.descriptorSource = descriptorSource;
    }

    @Override
    public String getDescriptor(com.cburch.logisim.tools.Library library) {
      return descriptorSource == null
          ? super.getDescriptor(library)
          : descriptorSource.getDescriptor(library);
    }

    @Override
    public void showError(String description) {
      throw new IllegalStateException("Logisim snapshot error: " + description);
    }
  }

  private static Circuit findCircuit(LogisimFile file, String name) {
    for (final var circuit : file.getCircuits()) {
      if (circuit.getName().equals(name)) return circuit;
    }
    return null;
  }

  private static Path defaultTaskRoot() {
    final var override = System.getProperty("logisim.ai.taskRoot");
    if (override != null && !override.isBlank())
      return Path.of(override).toAbsolutePath().normalize();
    return Path.of(System.getProperty("user.home"), ".logisim-evolution", "ai", "tasks");
  }
}
