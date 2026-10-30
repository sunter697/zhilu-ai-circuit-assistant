/*
 * Logisim-evolution - digital logic design tool and simulator
 * Copyright by the Logisim-evolution developers
 *
 * https://github.com/logisim-evolution/
 *
 * This is free software released under GNU GPLv3 license
 */

package com.cburch.logisim.ai.vision.importer;

import com.cburch.logisim.ai.util.SwingExecutor;
import com.cburch.logisim.circuit.Circuit;
import com.cburch.logisim.circuit.CircuitMutation;
import com.cburch.logisim.circuit.Wire;
import com.cburch.logisim.comp.Component;
import com.cburch.logisim.data.Location;
import com.cburch.logisim.file.LogisimFileActions;
import com.cburch.logisim.proj.Project;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;

/** Deterministically builds a validated image import as a new, one-step-undo circuit. */
public final class CircuitImportBuilder {
  public record Result(Circuit circuit, Map<String, Component> components) {
    public Result {
      components = Map.copyOf(components);
    }
  }

  private final StructuralImportVerifier verifier;

  public CircuitImportBuilder() {
    this(new StructuralImportVerifier());
  }

  CircuitImportBuilder(StructuralImportVerifier verifier) {
    this.verifier = verifier;
  }

  /** Builds and verifies a detached circuit without changing the project file. */
  public Result buildDetached(Project project, CircuitImportPlan plan) {
    if (project == null) throw new IllegalArgumentException("A target project is required");
    if (plan == null) throw new IllegalArgumentException("An import plan is required");
    final var file = project.getLogisimFile();
    if (file.getCircuit(plan.targetCircuitName()) != null) {
      throw new IllegalArgumentException("A circuit already exists: " + plan.targetCircuitName());
    }

    final var circuit = new Circuit(plan.targetCircuitName(), file, project);
    final var components = new LinkedHashMap<String, Component>();
    final var additions = new ArrayList<Component>();
    for (final var planned : plan.components()) {
      final var component = VisionComponentFactory.create(file, planned);
      components.put(planned.id(), component);
      additions.add(component);
    }
    for (final var planned : plan.routingTunnels()) {
      additions.add(VisionComponentFactory.createTunnel(file, planned));
    }
    for (final var planned : plan.wires()) {
      additions.add(
          Wire.create(location(planned.start()), location(planned.end())));
    }
    SwingExecutor.run(
        () -> {
          final var mutation = new CircuitMutation(circuit);
          mutation.addAll(additions);
          mutation.execute();
        });
    verifier.verify(circuit, plan).requireValid();
    return new Result(circuit, components);
  }

  /** Adds the already validated circuit to the project as a single undoable action. */
  public Result buildAndAdd(Project project, CircuitImportPlan plan) {
    final var result = buildDetached(project, plan);
    SwingExecutor.run(
        () -> {
          if (project.getLogisimFile().getCircuit(plan.targetCircuitName()) != null) {
            throw new IllegalStateException(
                "A circuit was added while the image import was being prepared: "
                    + plan.targetCircuitName());
          }
          project.doAction(LogisimFileActions.addCircuit(result.circuit()));
        });
    return result;
  }

  private static Location location(CircuitImportPlan.GridPoint point) {
    return Location.create(point.x(), point.y(), true);
  }
}
