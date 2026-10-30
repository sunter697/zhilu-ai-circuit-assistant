/*
 * Logisim-evolution - digital logic design tool and simulator
 * Copyright by the Logisim-evolution developers
 *
 * https://github.com/logisim-evolution/
 *
 * This is free software released under GNU GPLv3 license
 */

package com.cburch.logisim.ai.vision.importer;

import com.cburch.logisim.ai.vision.model.VisionIr;
import java.util.List;

/** Fully validated, project-independent instructions for creating one imported circuit. */
public record CircuitImportPlan(
    String schemaVersion,
    String sourceSha256,
    String targetCircuitName,
    List<PlannedComponent> components,
    List<PlannedNet> nets,
    List<PlannedTunnel> routingTunnels,
    String semanticHash) {
  public CircuitImportPlan {
    components = List.copyOf(components);
    nets = List.copyOf(nets);
    routingTunnels = List.copyOf(routingTunnels);
    semanticHash = semanticHash == null ? "" : semanticHash;
  }

  public record GridPoint(int x, int y) {}

  public record PlannedPort(
      String id, VisionIr.PortDirection direction, GridPoint location, int bitWidth) {}

  public enum GateSize {
    NARROW,
    MEDIUM,
    WIDE
  }

  public record PlannedComponent(
      String id,
      VisionIr.ComponentKind kind,
      String factoryName,
      GridPoint anchor,
      VisionIr.Facing facing,
      String label,
      int inputCount,
      int bitWidth,
      GateSize gateSize,
      double confidence,
      List<PlannedPort> ports) {
    public PlannedComponent {
      ports = List.copyOf(ports);
    }

    public PlannedPort port(String id) {
      return ports.stream()
          .filter(port -> port.id().equals(id))
          .findFirst()
          .orElseThrow(
              () ->
                  new IllegalArgumentException(
                      "Unknown planned port " + this.id + ":" + id));
    }
  }

  public record PortRef(String componentId, String portId) {}

  public record PlannedWire(String netId, GridPoint start, GridPoint end) {}

  public record PlannedNet(
      String id, List<PortRef> endpoints, List<PlannedWire> wires, double confidence) {
    public PlannedNet {
      endpoints = List.copyOf(endpoints);
      wires = List.copyOf(wires);
    }
  }

  /** A Logisim-native virtual connection used when raster wire crossings cannot be drawn safely. */
  public record PlannedTunnel(
      String id,
      String netId,
      String label,
      GridPoint location,
      VisionIr.Facing facing,
      int bitWidth) {}

  public PlannedComponent component(String id) {
    return components.stream()
        .filter(component -> component.id().equals(id))
        .findFirst()
        .orElseThrow(() -> new IllegalArgumentException("Unknown planned component: " + id));
  }

  public List<PlannedWire> wires() {
    return nets.stream().flatMap(net -> net.wires().stream()).toList();
  }
}
