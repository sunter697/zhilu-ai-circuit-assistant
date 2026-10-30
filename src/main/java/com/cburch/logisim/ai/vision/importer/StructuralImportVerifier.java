/*
 * Logisim-evolution - digital logic design tool and simulator
 * Copyright by the Logisim-evolution developers
 *
 * https://github.com/logisim-evolution/
 *
 * This is free software released under GNU GPLv3 license
 */

package com.cburch.logisim.ai.vision.importer;

import com.cburch.logisim.circuit.Circuit;
import com.cburch.logisim.circuit.Wire;
import com.cburch.logisim.comp.Component;
import com.cburch.logisim.data.Direction;
import com.cburch.logisim.data.Location;
import com.cburch.logisim.instance.StdAttr;
import com.cburch.logisim.std.gates.GateAttributes;
import com.cburch.logisim.std.wiring.Pin;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Compares a rebuilt circuit with the exact, host-approved import plan. */
public final class StructuralImportVerifier {
  public record Result(boolean valid, List<String> errors) {
    public Result {
      errors = List.copyOf(errors);
    }

    public void requireValid() {
      if (!valid) {
        throw new IllegalStateException(
            "Imported circuit failed structural verification: " + String.join("; ", errors));
      }
    }
  }

  public Result verify(Circuit circuit, CircuitImportPlan plan) {
    final var errors = new ArrayList<String>();
    if (circuit == null) return new Result(false, List.of("Circuit is required"));
    if (plan == null) return new Result(false, List.of("Import plan is required"));
    final var expectedComponentCount = plan.components().size() + plan.routingTunnels().size();
    if (circuit.getNonWires().size() != expectedComponentCount) {
      errors.add(
          "Component count differs: expected "
              + expectedComponentCount
              + ", found "
              + circuit.getNonWires().size());
    }
    if (circuit.getWires().size() != plan.wires().size()) {
      errors.add(
          "Wire count differs: expected "
              + plan.wires().size()
              + ", found "
              + circuit.getWires().size());
    }

    final var matched = new HashSet<Component>();
    for (final var planned : plan.components()) {
      final var candidates =
          circuit.getNonWires().stream()
              .filter(component -> matchesIdentity(component, planned))
              .toList();
      if (candidates.size() != 1) {
        errors.add(
            "Expected exactly one component for "
                + planned.id()
                + " at "
                + planned.anchor()
                + "; found "
                + candidates.size());
        continue;
      }
      final var component = candidates.getFirst();
      matched.add(component);
      compareAttributes(component, planned, errors);
      comparePorts(component, planned, errors);
    }
    for (final var tunnel : plan.routingTunnels()) {
      final var candidates =
          circuit.getNonWires().stream()
              .filter(component -> matchesTunnel(component, tunnel))
              .toList();
      if (candidates.size() != 1) {
        errors.add(
            "Expected exactly one routing tunnel for "
                + tunnel.id()
                + " at "
                + tunnel.location()
                + "; found "
                + candidates.size());
        continue;
      }
      matched.add(candidates.getFirst());
    }
    if (matched.size() != circuit.getNonWires().size()) {
      errors.add("Circuit contains components that are not present in the import plan");
    }

    final Set<WireKey> expectedWires = new HashSet<>();
    for (final var wire : plan.wires()) expectedWires.add(WireKey.of(wire.start(), wire.end()));
    final Set<WireKey> actualWires = new HashSet<>();
    for (final var wire : circuit.getWires()) actualWires.add(WireKey.of(wire));
    if (!expectedWires.equals(actualWires)) {
      errors.add("Wire geometry differs from the import plan");
    }
    for (final var net : plan.nets()) {
      for (final var endpoint : net.endpoints()) {
        final var location = plan.component(endpoint.componentId()).port(endpoint.portId()).location();
        final var point = Location.create(location.x(), location.y(), true);
        final var hasTunnel =
            plan.routingTunnels().stream()
                .anyMatch(
                    tunnel -> tunnel.netId().equals(net.id()) && tunnel.location().equals(location));
        if (circuit.getWires(point).isEmpty() && !hasTunnel) {
          errors.add(
              "Net "
                  + net.id()
                  + " does not reach port "
                  + endpoint.componentId()
                  + ":"
                  + endpoint.portId());
        }
      }
    }
    return new Result(errors.isEmpty(), errors);
  }

  private static boolean matchesIdentity(
      Component component, CircuitImportPlan.PlannedComponent planned) {
    return component.getFactory().getName().equals(planned.factoryName())
        && component.getLocation().getX() == planned.anchor().x()
        && component.getLocation().getY() == planned.anchor().y();
  }

  private static boolean matchesTunnel(
      Component component, CircuitImportPlan.PlannedTunnel planned) {
    final var attributes = component.getAttributeSet();
    return component.getFactory().getName().equals("Tunnel")
        && component.getLocation().getX() == planned.location().x()
        && component.getLocation().getY() == planned.location().y()
        && planned.label().equals(attributes.getValue(StdAttr.LABEL))
        && attributes.getValue(StdAttr.WIDTH).getWidth() == planned.bitWidth()
        && attributes.getValue(StdAttr.FACING) == direction(planned.facing());
  }

  private static void compareAttributes(
      Component component,
      CircuitImportPlan.PlannedComponent planned,
      List<String> errors) {
    final var attributes = component.getAttributeSet();
    if (attributes.containsAttribute(StdAttr.LABEL)
        && !planned.label().equals(attributes.getValue(StdAttr.LABEL))) {
      errors.add("Label differs for component " + planned.id());
    }
    if (attributes.containsAttribute(StdAttr.WIDTH)
        && attributes.getValue(StdAttr.WIDTH).getWidth() != planned.bitWidth()) {
      errors.add("Bit width differs for component " + planned.id());
    }
    if (attributes.containsAttribute(StdAttr.FACING)
        && attributes.getValue(StdAttr.FACING) != direction(planned.facing())) {
      errors.add("Facing differs for component " + planned.id());
    }
    if (attributes.containsAttribute(GateAttributes.ATTR_INPUTS)
        && attributes.getValue(GateAttributes.ATTR_INPUTS) != planned.inputCount()) {
      errors.add("Input count differs for component " + planned.id());
    }
    if (attributes.containsAttribute(Pin.ATTR_TYPE)) {
      final var expected =
          planned.kind() == com.cburch.logisim.ai.vision.model.VisionIr.ComponentKind.OUTPUT_PIN
              ? Pin.OUTPUT
              : Pin.INPUT;
      if (attributes.getValue(Pin.ATTR_TYPE) != expected) {
        errors.add("Pin direction differs for component " + planned.id());
      }
    }
  }

  private static Direction direction(
      com.cburch.logisim.ai.vision.model.VisionIr.Facing facing) {
    return switch (facing) {
      case EAST -> Direction.EAST;
      case WEST -> Direction.WEST;
      case NORTH -> Direction.NORTH;
      case SOUTH -> Direction.SOUTH;
    };
  }

  private static void comparePorts(
      Component component,
      CircuitImportPlan.PlannedComponent planned,
      List<String> errors) {
    if (component.getEnds().size() != planned.ports().size()) {
      errors.add("Port count differs for component " + planned.id());
      return;
    }
    for (var index = 0; index < component.getEnds().size(); index++) {
      final var actual = component.getEnd(index);
      final var expected = planned.ports().get(index);
      if (actual.getLocation().getX() != expected.location().x()
          || actual.getLocation().getY() != expected.location().y()
          || actual.getWidth().getWidth() != expected.bitWidth()
          || actual.isOutput() != (expected.direction()
              == com.cburch.logisim.ai.vision.model.VisionIr.PortDirection.OUTPUT)) {
        errors.add("Port differs for component " + planned.id() + ":" + expected.id());
      }
    }
  }

  private record WireKey(int x0, int y0, int x1, int y1) {
    static WireKey of(CircuitImportPlan.GridPoint first, CircuitImportPlan.GridPoint second) {
      return normalized(first.x(), first.y(), second.x(), second.y());
    }

    static WireKey of(Wire wire) {
      return normalized(
          wire.getEnd0().getX(),
          wire.getEnd0().getY(),
          wire.getEnd1().getX(),
          wire.getEnd1().getY());
    }

    private static WireKey normalized(int x0, int y0, int x1, int y1) {
      return x0 < x1 || (x0 == x1 && y0 <= y1)
          ? new WireKey(x0, y0, x1, y1)
          : new WireKey(x1, y1, x0, y0);
    }
  }
}
