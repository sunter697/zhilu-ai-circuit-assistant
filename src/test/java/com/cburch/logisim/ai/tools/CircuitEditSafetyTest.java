/*
 * Logisim-evolution - digital logic design tool and simulator
 * Copyright by the Logisim-evolution developers
 *
 * https://github.com/logisim-evolution/
 *
 * This is free software released under GNU GPLv3 license
 */

package com.cburch.logisim.ai.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.cburch.logisim.ai.agent.AgentPermissionManager;
import com.cburch.logisim.ai.agent.TaskBudget;
import com.cburch.logisim.ai.logging.AgentTaskLogger;
import com.cburch.logisim.ai.transaction.CircuitDigest;
import com.cburch.logisim.ai.util.SwingExecutor;
import com.cburch.logisim.ai.validation.TaskSpec;
import com.cburch.logisim.ai.workspace.WorkspaceManager;
import com.cburch.logisim.circuit.CircuitMutation;
import com.cburch.logisim.circuit.Wire;
import com.cburch.logisim.data.Location;
import com.cburch.logisim.file.Loader;
import com.cburch.logisim.file.LogisimFile;
import com.cburch.logisim.instance.StdAttr;
import com.cburch.logisim.proj.Project;
import com.cburch.logisim.std.wiring.Pin;
import com.cburch.logisim.std.wiring.Tunnel;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CircuitEditSafetyTest {
  @TempDir Path tempDir;

  @Test
  void tunnelRenameCannotMergeTwoSeparatelyDrivenNets() throws Exception {
    final var loader = new Loader(null);
    final var file = LogisimFile.createNew(loader, null);
    file.addLibrary(loader.loadLibrary("#Wiring"));
    final var project = new Project(file);
    final var circuit = project.getCurrentCircuit();
    circuit.setProject(project);
    try {
      SwingExecutor.run(
          () -> {
            final var mutation = new CircuitMutation(circuit);
            for (var name : List.of("A", "B", "Y")) {
              final var attrs = Pin.FACTORY.createAttributeSet();
              attrs.setValue(StdAttr.LABEL, name);
              attrs.setValue(Pin.ATTR_TYPE, name.equals("Y") ? Pin.OUTPUT : Pin.INPUT);
              mutation.add(
                  Pin.FACTORY.createComponent(
                      Location.create(
                          name.equals("Y") ? 300 : 20, name.equals("B") ? 150 : 50, true),
                      attrs));
            }
            for (var name : List.of("X", "Z")) {
              final var attrs = Tunnel.FACTORY.createAttributeSet();
              attrs.setValue(StdAttr.LABEL, name);
              mutation.add(
                  Tunnel.FACTORY.createComponent(
                      Location.create(100, name.equals("X") ? 50 : 150, true), attrs));
            }
            mutation.add(
                Wire.create(Location.create(20, 50, true), Location.create(100, 50, true)));
            mutation.add(
                Wire.create(Location.create(100, 50, true), Location.create(300, 50, true)));
            mutation.add(
                Wire.create(Location.create(20, 150, true), Location.create(100, 150, true)));
            mutation.execute();
          });
      final var manager = new WorkspaceManager(tempDir);
      try (var workspace = manager.create(project, circuit)) {
        final var permissions = new AgentPermissionManager();
        permissions.setEditsAllowed(true);
        final var context =
            new ToolExecutionContext(
                workspace,
                manager,
                permissions,
                TaskBudget.defaults(),
                new AgentTaskLogger(workspace.taskId(), workspace.taskDirectory()));
        context.proposeSpecification(
            new TaskSpec(
                TaskSpec.Kind.BOOLEAN_EXPRESSIONS,
                List.of(new TaskSpec.SignalSpec("A", 1), new TaskSpec.SignalSpec("B", 1)),
                List.of(new TaskSpec.SignalSpec("Y", 1)),
                Map.of("Y", "A")));
        context.acceptSpecification();
        assertTrue(context.verifySpecification().isTrustedPass());
        final var tunnelId =
            context.circuitContext().components().entrySet().stream()
                .filter(
                    entry ->
                        entry.getValue().getFactory() instanceof Tunnel
                            && "Z"
                                .equals(entry.getValue().getAttributeSet().getValue(StdAttr.LABEL)))
                .findFirst()
                .orElseThrow()
                .getKey();
        final var digest = CircuitDigest.sha256(workspace.circuit());
        final var tools = StandardCircuitTools.createRegistry();
        assertThrows(
            IllegalArgumentException.class,
            () ->
                tools
                    .require("set_attribute")
                    .execute(
                        Map.of("componentId", tunnelId, "attribute", "label", "value", "X"),
                        context));
        assertThrows(
            IllegalArgumentException.class,
            () ->
                tools
                    .require("replace_component")
                    .execute(
                        Map.of(
                            "componentId",
                            tunnelId,
                            "type",
                            "Tunnel",
                            "attributes",
                            Map.of("label", "X")),
                        context));
        assertEquals(digest, CircuitDigest.sha256(workspace.circuit()));
        assertTrue(context.hasTrustedPass());
      }
    } finally {
      WorkspaceManager.closeProject(project);
    }
  }

  @Test
  void rejectedGeometryChangesAndImplicitConnectionsPreserveCircuitAndEvidence() throws Exception {
    final var loader = new Loader(null);
    final var file = LogisimFile.createNew(loader, null);
    file.addLibrary(loader.loadLibrary("#Wiring"));
    final var project = new Project(file);
    file.getCircuits().forEach(circuit -> circuit.setProject(project));
    try {
      final var manager = new WorkspaceManager(tempDir);
      try (var workspace = manager.create(project, project.getCurrentCircuit())) {
        final var permissions = new AgentPermissionManager();
        permissions.setEditsAllowed(true);
        final var context =
            new ToolExecutionContext(
                workspace,
                manager,
                permissions,
                TaskBudget.defaults(),
                new AgentTaskLogger(workspace.taskId(), workspace.taskDirectory()));
        context.proposeSpecification(
            new TaskSpec(
                TaskSpec.Kind.BOOLEAN_EXPRESSIONS,
                List.of(new TaskSpec.SignalSpec("A", 1)),
                List.of(new TaskSpec.SignalSpec("Y", 1)),
                Map.of("Y", "A")));
        context.acceptSpecification();
        final var tools = StandardCircuitTools.createRegistry();
        tools
            .require("add_component")
            .execute(
                Map.of("type", "Pin", "x", 100, "y", 100, "attributes", Map.of("label", "A")),
                context);
        tools
            .require("add_component")
            .execute(
                Map.of(
                    "type",
                    "Pin",
                    "x",
                    200,
                    "y",
                    100,
                    "attributes",
                    Map.of("label", "Y", "type", "output")),
                context);
        tools
            .require("connect")
            .execute(
                Map.of(
                    "fromComponentId",
                    "cmp_001",
                    "fromPort",
                    "p0",
                    "toComponentId",
                    "cmp_002",
                    "toPort",
                    "p0"),
                context);
        assertTrue(context.verifySpecification().isTrustedPass());
        final var digest = CircuitDigest.sha256(workspace.circuit());
        assertThrows(
            IllegalArgumentException.class,
            () ->
                tools
                    .require("set_attribute")
                    .execute(
                        Map.of("componentId", "cmp_001", "attribute", "width", "value", "2"),
                        context));
        assertThrows(
            IllegalArgumentException.class,
            () ->
                tools
                    .require("set_attribute")
                    .execute(
                        Map.of("componentId", "cmp_001", "attribute", "type", "value", "output"),
                        context));
        final var existingWire = workspace.circuit().getWires().iterator().next();
        final var onWire = existingWire.getEnd0();
        assertThrows(
            IllegalArgumentException.class,
            () ->
                tools
                    .require("add_component")
                    .execute(
                        Map.of("type", "Pin", "x", onWire.getX(), "y", onWire.getY()), context));
        assertThrows(
            IllegalArgumentException.class,
            () ->
                tools
                    .require("replace_component")
                    .execute(
                        Map.of(
                            "componentId",
                            "cmp_001",
                            "type",
                            "Pin",
                            "attributes",
                            Map.of("width", "2")),
                        context));
        assertEquals(digest, CircuitDigest.sha256(workspace.circuit()));
        assertTrue(context.hasTrustedPass());
      }
    } finally {
      WorkspaceManager.closeProject(project);
    }
  }
}
