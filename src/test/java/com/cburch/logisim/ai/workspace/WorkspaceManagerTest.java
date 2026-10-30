/*
 * Logisim-evolution - digital logic design tool and simulator
 * Copyright by the Logisim-evolution developers
 *
 * https://github.com/logisim-evolution/
 *
 * This is free software released under GNU GPLv3 license
 */

package com.cburch.logisim.ai.workspace;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.cburch.logisim.ai.transaction.CircuitDigest;
import com.cburch.logisim.ai.transaction.FactoryIdentity;
import com.cburch.logisim.ai.util.SwingExecutor;
import com.cburch.logisim.circuit.CircuitAttributes;
import com.cburch.logisim.circuit.CircuitMutation;
import com.cburch.logisim.data.Location;
import com.cburch.logisim.file.Loader;
import com.cburch.logisim.file.LogisimFile;
import com.cburch.logisim.proj.Project;
import com.cburch.logisim.tools.Library;
import com.cburch.logisim.tools.Tool;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorkspaceManagerTest {
  @TempDir Path tempDir;

  @Test
  void cloneIsStructurallyEquivalentAndPersistedSeparately() throws Exception {
    final var file = LogisimFile.createNew(new Loader(null), null);
    final var source = new Project(file);
    try {
      for (final var circuit : file.getCircuits()) circuit.setProject(source);
      final var sourceCircuit = source.getCurrentCircuit();
      final var manager = new WorkspaceManager(tempDir);
      final var workspace = manager.create(source, sourceCircuit);
      try {
        assertNotSame(sourceCircuit, workspace.circuit());
        assertEquals(
            CircuitDigest.sha256(sourceCircuit), CircuitDigest.sha256(workspace.circuit()));
        assertTrue(java.nio.file.Files.exists(workspace.baselineFile()));
        assertTrue(java.nio.file.Files.exists(workspace.workspaceFile()));
      } finally {
        workspace.close();
      }
    } finally {
      source.getSimulator().shutDown();
      file.stopAutosaveThread(false);
    }
  }

  @Test
  void populatedWorkspaceReloadPreservesExactComponentsAndAttributes() throws Exception {
    final var loader = new Loader(null);
    final var file = LogisimFile.createNew(loader, null);
    file.addLibrary(loader.loadLibrary("#Gates"));
    final var project = new Project(file);
    file.getCircuits().forEach(circuit -> circuit.setProject(project));
    final var circuit = project.getCurrentCircuit();
    final var factory = new FactoryIdentity(List.of("Gates"), "AND Gate", false).resolve(file);
    final var component =
        factory.createComponent(Location.create(100, 100, true), factory.createAttributeSet());
    SwingExecutor.run(
        () -> {
          final var mutation = new CircuitMutation(circuit);
          mutation.add(component);
          mutation.execute();
        });
    try {
      try (var workspace = new WorkspaceManager(tempDir).create(project, circuit)) {
        assertNotSame(component, workspace.circuit().getNonWires().iterator().next());
        final var reopened =
            WorkspaceManager.loadSnapshot(
                Files.readAllBytes(workspace.workspaceFile()), circuit.getName());
        try {
          assertEquals(
              CircuitDigest.projectSha256(file),
              CircuitDigest.projectSha256(reopened.getLogisimFile()));
        } finally {
          WorkspaceManager.closeProject(reopened);
        }
      }
    } finally {
      WorkspaceManager.closeProject(project);
    }
  }

  @Test
  void externalLibraryIsRejectedBeforeTaskArtifactsAreWritten() throws Exception {
    final var file = LogisimFile.createNew(new Loader(null), null);
    final var project = new Project(file);
    file.getCircuits().forEach(circuit -> circuit.setProject(project));
    file.addLibrary(
        new Library() {
          @Override
          public String getName() {
            return "External fixture";
          }

          @Override
          public List<? extends Tool> getTools() {
            return List.of();
          }
        });
    try {
      assertThrows(
          IllegalArgumentException.class,
          () -> new WorkspaceManager(tempDir).create(project, project.getCurrentCircuit()));
      try (var children = Files.list(tempDir)) {
        assertEquals(0, children.count());
      }
    } finally {
      WorkspaceManager.closeProject(project);
    }
  }

  @Test
  void customAppearanceIsExplicitlyRefused() {
    final var file = LogisimFile.createNew(new Loader(null), null);
    final var project = new Project(file);
    file.getCircuits().forEach(circuit -> circuit.setProject(project));
    try {
      SwingExecutor.run(
          () -> {
            final var mutation = new CircuitMutation(project.getCurrentCircuit());
            mutation.setForCircuit(
                CircuitAttributes.APPEARANCE_ATTR, CircuitAttributes.APPEAR_CUSTOM);
            mutation.execute();
          });
      assertThrows(
          IllegalArgumentException.class,
          () -> new WorkspaceManager(tempDir).create(project, project.getCurrentCircuit()));
    } finally {
      WorkspaceManager.closeProject(project);
    }
  }

  @Test
  void fpgaMappingIsRefusedBeforeReplacingBoundComponents() {
    final var file = LogisimFile.createNew(new Loader(null), null);
    final var project = new Project(file);
    file.getCircuits().forEach(circuit -> circuit.setProject(project));
    try {
      project.getCurrentCircuit().addLoadedMap("fixture", Map.of());
      assertThrows(
          IllegalArgumentException.class,
          () -> new WorkspaceManager(tempDir).create(project, project.getCurrentCircuit()));
    } finally {
      WorkspaceManager.closeProject(project);
    }
  }
}
