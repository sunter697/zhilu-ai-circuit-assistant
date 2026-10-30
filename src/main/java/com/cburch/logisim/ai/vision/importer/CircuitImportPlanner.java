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
import com.cburch.logisim.ai.vision.model.VisionIrValidator;
import com.cburch.logisim.comp.Component;
import com.cburch.logisim.data.Bounds;
import com.cburch.logisim.data.Location;
import com.cburch.logisim.file.LogisimFile;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Converts untrusted image-space recognition output into exact Logisim construction coordinates. */
public final class CircuitImportPlanner {
  private static final int LOGISIM_GRID = 10;
  private static final double MAX_SNAP_DISTANCE = 4.0;

  public CircuitImportPlan plan(VisionIr ir, LogisimFile file, String targetCircuitName) {
    return plan(ir, file, targetCircuitName, null);
  }

  /** Replans a corrected result, moving only wire terminals whose factory ports moved. */
  public CircuitImportPlan planRevised(
      VisionIr ir,
      LogisimFile file,
      String targetCircuitName,
      CircuitImportPlan previousPlan) {
    if (previousPlan == null) {
      throw new IllegalArgumentException("A previous import plan is required");
    }
    return plan(ir, file, targetCircuitName, previousPlan);
  }

  private static CircuitImportPlan plan(
      VisionIr ir,
      LogisimFile file,
      String targetCircuitName,
      CircuitImportPlan previousPlan) {
    VisionIrValidator.validateOrThrow(ir);
    if (file == null) throw new IllegalArgumentException("A target Logisim file is required");
    if (targetCircuitName == null || targetCircuitName.isBlank()) {
      throw new IllegalArgumentException("A target circuit name is required");
    }
    if (file.getCircuit(targetCircuitName) != null) {
      throw new IllegalArgumentException("A circuit already exists: " + targetCircuitName);
    }
    if (ir.unresolved().stream()
        .anyMatch(issue -> issue.severity() == VisionIr.IssueSeverity.ERROR)) {
      throw new IllegalArgumentException("Vision IR contains unresolved errors");
    }

    final var components = new ArrayList<CircuitImportPlan.PlannedComponent>();
    final var byId = new LinkedHashMap<String, CircuitImportPlan.PlannedComponent>();
    final var previews = new LinkedHashMap<String, Component>();
    final var usedLabels = new HashSet<String>();
    var inputNumber = 1;
    var outputNumber = 1;
    for (final var detected : ir.components()) {
      final var label =
          label(
              detected,
              detected.kind() == VisionIr.ComponentKind.INPUT_PIN
                  ? "I" + inputNumber++
                  : detected.kind() == VisionIr.ComponentKind.OUTPUT_PIN
                      ? "O" + outputNumber++
                      : "");
      if (!label.isBlank() && !usedLabels.add(label.toLowerCase(Locale.ROOT))) {
        throw new IllegalArgumentException("Duplicate component label: " + label);
      }
      final var preliminary = preliminary(detected, ir.profile(), label);
      final var preview = VisionComponentFactory.create(file, preliminary);
      final var planned = withPorts(preliminary, preview);
      components.add(planned);
      byId.put(planned.id(), planned);
      previews.put(planned.id(), preview);
    }
    rejectOverlaps(previews);

    final var nets = new ArrayList<CircuitImportPlan.PlannedNet>();
    final var assignedPorts = new HashSet<String>();
    for (final var detected : ir.nets()) {
      final var net = planNet(detected, ir.profile(), byId, assignedPorts, previousPlan);
      nets.add(net);
    }
    requireEveryPortConnected(components, assignedPorts);
    List<CircuitImportPlan.PlannedTunnel> routingTunnels = List.of();
    if (hasInterNetElectricalConnections(nets)) {
      final var tunneled = replaceUnsafeGeometryWithTunnels(nets, byId);
      nets.clear();
      nets.addAll(tunneled.nets());
      routingTunnels = tunneled.tunnels();
    }
    rejectInterNetConnections(nets);
    rejectWiresThroughComponents(nets, previews);

    return new CircuitImportPlan(
        ir.schemaVersion(),
        ir.source().sha256(),
        targetCircuitName,
        components,
        nets,
        routingTunnels,
        ir.semanticHash());
  }

  private static CircuitImportPlan.PlannedComponent preliminary(
      VisionIr.DetectedComponent detected, VisionIr.Profile profile, String label) {
    final var inputCount = detected.kind().expectedInputCount(detected.attributes());
    final var bitWidth = integerAttribute(detected.attributes(), "bitWidth", 1);
    return new CircuitImportPlan.PlannedComponent(
        detected.id(),
        detected.kind(),
        factoryName(detected.kind()),
        transform(detected.anchor(), profile),
        detected.facing(),
        label,
        inputCount,
        bitWidth,
        gateSize(detected),
        detected.confidence(),
        List.of());
  }

  private static CircuitImportPlan.PlannedComponent withPorts(
      CircuitImportPlan.PlannedComponent planned, Component preview) {
    final var expectedCount =
        planned.kind() == VisionIr.ComponentKind.INPUT_PIN
                || planned.kind() == VisionIr.ComponentKind.OUTPUT_PIN
            ? 1
            : planned.inputCount() + 1;
    if (preview.getEnds().size() != expectedCount) {
      throw new IllegalArgumentException(
          "Factory port count differs from Vision IR for " + planned.id());
    }
    final var ports = new ArrayList<CircuitImportPlan.PlannedPort>();
    for (var index = 0; index < preview.getEnds().size(); index++) {
      final var end = preview.getEnd(index);
      final var id = portId(planned.kind(), index);
      final var direction =
          end.isOutput() ? VisionIr.PortDirection.OUTPUT : VisionIr.PortDirection.INPUT;
      ports.add(
          new CircuitImportPlan.PlannedPort(
              id,
              direction,
              new CircuitImportPlan.GridPoint(
                  end.getLocation().getX(), end.getLocation().getY()),
              end.getWidth().getWidth()));
    }
    return new CircuitImportPlan.PlannedComponent(
        planned.id(),
        planned.kind(),
        planned.factoryName(),
        planned.anchor(),
        planned.facing(),
        planned.label(),
        planned.inputCount(),
        planned.bitWidth(),
        planned.gateSize(),
        planned.confidence(),
        ports);
  }

  private static CircuitImportPlan.GateSize gateSize(VisionIr.DetectedComponent detected) {
    final var defaultSize =
        detected.kind() == VisionIr.ComponentKind.NOT
            ? CircuitImportPlan.GateSize.WIDE
            : CircuitImportPlan.GateSize.MEDIUM;
    if (!detected.kind().isGate()) return defaultSize;
    final var raw = detected.attributes().get("size");
    if (raw == null) return defaultSize;
    if (!(raw instanceof String value)) {
      throw new IllegalArgumentException("Component size must be a string: " + detected.id());
    }
    try {
      return CircuitImportPlan.GateSize.valueOf(value.toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException exception) {
      throw new IllegalArgumentException(
          "Unsupported component size for " + detected.id() + ": " + value, exception);
    }
  }

  private static CircuitImportPlan.PlannedNet planNet(
      VisionIr.DetectedNet detected,
      VisionIr.Profile profile,
      Map<String, CircuitImportPlan.PlannedComponent> components,
      Set<String> assignedPorts,
      CircuitImportPlan previousPlan) {
    final var endpoints = new ArrayList<CircuitImportPlan.PortRef>();
    final var endpointMoves = new HashMap<CircuitImportPlan.GridPoint, CircuitImportPlan.GridPoint>();
    var drivers = 0;
    for (final var detectedEndpoint : detected.endpoints()) {
      final var component = components.get(detectedEndpoint.componentId());
      if (component == null) {
        throw new IllegalArgumentException(
            "Unknown component in net " + detected.id() + ": " + detectedEndpoint.componentId());
      }
      final var port = component.port(detectedEndpoint.portId());
      if (port.direction() == VisionIr.PortDirection.OUTPUT) drivers++;
      final var key = component.id() + ":" + port.id();
      if (!assignedPorts.add(key)) {
        throw new IllegalArgumentException("Port belongs to more than one net: " + key);
      }
      endpoints.add(new CircuitImportPlan.PortRef(component.id(), port.id()));
      if (previousPlan != null) {
        final var previousLocation =
            previousPlan.component(component.id()).port(port.id()).location();
        if (!previousLocation.equals(port.location())) {
          final var existing = endpointMoves.putIfAbsent(previousLocation, port.location());
          if (existing != null && !existing.equals(port.location())) {
            throw new IllegalArgumentException(
                "Corrected ports at " + previousLocation + " require conflicting wire moves");
          }
        }
      }
    }
    if (drivers != 1) {
      throw new IllegalArgumentException(
          "Net " + detected.id() + " must have exactly one driver; found " + drivers);
    }

    final var wires = new ArrayList<CircuitImportPlan.PlannedWire>();
    final var unique = new LinkedHashSet<WireKey>();
    for (final var segment : detected.segments()) {
      final var transformedStart = transform(segment.start(), profile);
      final var transformedEnd = transform(segment.end(), profile);
      final var start = endpointMoves.getOrDefault(transformedStart, transformedStart);
      final var end = endpointMoves.getOrDefault(transformedEnd, transformedEnd);
      final var key = WireKey.of(start, end);
      if (start.equals(end)) {
        throw new IllegalArgumentException("Net " + detected.id() + " contains a zero-length wire");
      }
      if (start.x() != end.x() && start.y() != end.y()) {
        throw new IllegalArgumentException("Net " + detected.id() + " contains a diagonal wire");
      }
      if (unique.add(key)) {
        wires.add(new CircuitImportPlan.PlannedWire(detected.id(), key.start(), key.end()));
      }
    }
    requireConnectedGeometry(detected.id(), endpoints, wires, components);
    return new CircuitImportPlan.PlannedNet(
        detected.id(), endpoints, wires, detected.confidence());
  }

  private static void requireConnectedGeometry(
      String netId,
      List<CircuitImportPlan.PortRef> endpoints,
      List<CircuitImportPlan.PlannedWire> wires,
      Map<String, CircuitImportPlan.PlannedComponent> components) {
    final var graph = new HashMap<CircuitImportPlan.GridPoint, Set<CircuitImportPlan.GridPoint>>();
    for (final var wire : wires) {
      graph.computeIfAbsent(wire.start(), ignored -> new HashSet<>()).add(wire.end());
      graph.computeIfAbsent(wire.end(), ignored -> new HashSet<>()).add(wire.start());
    }
    for (final var endpoint : endpoints) {
      final var location = components.get(endpoint.componentId()).port(endpoint.portId()).location();
      if (!graph.containsKey(location)) {
        throw new IllegalArgumentException(
            "Net "
                + netId
                + " geometry does not terminate at port "
                + endpoint.componentId()
                + ":"
                + endpoint.portId()
                + " at "
                + location);
      }
    }
    if (graph.isEmpty()) throw new IllegalArgumentException("Net " + netId + " has no wires");
    final var reached = new HashSet<CircuitImportPlan.GridPoint>();
    final var queue = new ArrayDeque<CircuitImportPlan.GridPoint>();
    queue.add(graph.keySet().iterator().next());
    while (!queue.isEmpty()) {
      final var point = queue.removeFirst();
      if (!reached.add(point)) continue;
      queue.addAll(graph.getOrDefault(point, Set.of()));
    }
    if (reached.size() != graph.size()) {
      throw new IllegalArgumentException("Net " + netId + " contains disconnected wire segments");
    }
  }

  private static void requireEveryPortConnected(
      List<CircuitImportPlan.PlannedComponent> components, Set<String> assignedPorts) {
    final var missing = new ArrayList<String>();
    for (final var component : components) {
      for (final var port : component.ports()) {
        final var key = component.id() + ":" + port.id();
        if (!assignedPorts.contains(key)) missing.add(key);
      }
    }
    if (!missing.isEmpty()) {
      throw new IllegalArgumentException("Unconnected component ports: " + missing);
    }
  }

  private static void rejectOverlaps(Map<String, Component> previews) {
    final var entries = new ArrayList<>(previews.entrySet());
    for (var i = 0; i < entries.size(); i++) {
      for (var j = i + 1; j < entries.size(); j++) {
        if (overlaps(entries.get(i).getValue().getBounds(), entries.get(j).getValue().getBounds())) {
          throw new IllegalArgumentException(
              "Component bodies overlap: "
                  + entries.get(i).getKey()
                  + " and "
                  + entries.get(j).getKey());
        }
      }
    }
  }

  private static void rejectInterNetConnections(List<CircuitImportPlan.PlannedNet> nets) {
    for (var i = 0; i < nets.size(); i++) {
      for (var j = i + 1; j < nets.size(); j++) {
        for (final var left : nets.get(i).wires()) {
          for (final var right : nets.get(j).wires()) {
            if (electricallyConnects(left, right)) {
              throw new IllegalArgumentException(
                  "Separate nets "
                      + nets.get(i).id()
                      + " and "
                      + nets.get(j).id()
                      + " would become electrically connected");
            }
          }
        }
      }
    }
  }

  private static boolean hasInterNetElectricalConnections(
      List<CircuitImportPlan.PlannedNet> nets) {
    for (var i = 0; i < nets.size(); i++) {
      for (var j = i + 1; j < nets.size(); j++) {
        for (final var left : nets.get(i).wires()) {
          for (final var right : nets.get(j).wires()) {
            if (electricallyConnects(left, right)) return true;
          }
        }
      }
    }
    return false;
  }

  /**
   * Replaces only electrically unsafe image-space contacts with Logisim tunnel connections.
   *
   * <p>Logisim keeps perpendicular mid-segment crossings separate, so those can retain their source
   * geometry. Endpoint contacts and collinear overlaps do connect; for each such conflict this
   * method tunnels one of the two nets and leaves all unaffected nets as ordinary wires.
   */
  private static TunnelRouting replaceUnsafeGeometryWithTunnels(
      List<CircuitImportPlan.PlannedNet> nets,
      Map<String, CircuitImportPlan.PlannedComponent> components) {
    final var tunneledIds = new LinkedHashSet<String>();
    for (var leftIndex = 0; leftIndex < nets.size(); leftIndex++) {
      final var left = nets.get(leftIndex);
      if (tunneledIds.contains(left.id())) continue;
      for (var rightIndex = leftIndex + 1; rightIndex < nets.size(); rightIndex++) {
        final var right = nets.get(rightIndex);
        if (tunneledIds.contains(right.id()) || !electricallyConnects(left, right)) continue;
        final var selected =
            left.endpoints().size() <= right.endpoints().size() ? left : right;
        tunneledIds.add(selected.id());
        if (selected == left) break;
      }
    }

    final var tunneledNets = new ArrayList<CircuitImportPlan.PlannedNet>();
    final var tunnels = new ArrayList<CircuitImportPlan.PlannedTunnel>();
    var netIndex = 1;
    for (final var net : nets) {
      if (!tunneledIds.contains(net.id())) {
        tunneledNets.add(net);
        continue;
      }
      final var label = "VNET_" + netIndex++;
      var endpointIndex = 1;
      for (final var endpoint : net.endpoints()) {
        final var component = components.get(endpoint.componentId());
        final var port = component.port(endpoint.portId());
        tunnels.add(
            new CircuitImportPlan.PlannedTunnel(
                "__vision_tunnel_" + net.id() + "_" + endpointIndex++,
                net.id(),
                label,
                port.location(),
                tunnelFacing(component, port),
                port.bitWidth()));
      }
      tunneledNets.add(
          new CircuitImportPlan.PlannedNet(
              net.id(), net.endpoints(), List.of(), net.confidence()));
    }
    return new TunnelRouting(List.copyOf(tunneledNets), List.copyOf(tunnels));
  }

  private static VisionIr.Facing tunnelFacing(
      CircuitImportPlan.PlannedComponent component, CircuitImportPlan.PlannedPort port) {
    return port.direction() == VisionIr.PortDirection.INPUT
        ? component.facing()
        : opposite(component.facing());
  }

  private static VisionIr.Facing opposite(VisionIr.Facing facing) {
    return switch (facing) {
      case EAST -> VisionIr.Facing.WEST;
      case WEST -> VisionIr.Facing.EAST;
      case NORTH -> VisionIr.Facing.SOUTH;
      case SOUTH -> VisionIr.Facing.NORTH;
    };
  }

  private static void rejectWiresThroughComponents(
      List<CircuitImportPlan.PlannedNet> nets, Map<String, Component> previews) {
    for (final var net : nets) {
      for (final var wire : net.wires()) {
        for (final var entry : previews.entrySet()) {
          if (terminatesAtComponentPort(net, wire, entry.getKey(), entry.getValue())) continue;
          if (crossesInterior(wire, entry.getValue().getBounds())) {
            throw new IllegalArgumentException(
                "Wire in net " + net.id() + " crosses component body " + entry.getKey());
          }
        }
      }
    }
  }

  private static boolean terminatesAtComponentPort(
      CircuitImportPlan.PlannedNet net,
      CircuitImportPlan.PlannedWire wire,
      String componentId,
      Component component) {
    if (net.endpoints().stream().noneMatch(endpoint -> endpoint.componentId().equals(componentId))) {
      return false;
    }
    return component.getEnds().stream()
        .map(
            end ->
                new CircuitImportPlan.GridPoint(
                    end.getLocation().getX(), end.getLocation().getY()))
        .anyMatch(location -> wire.start().equals(location) || wire.end().equals(location));
  }

  private static boolean crossesInterior(
      CircuitImportPlan.PlannedWire wire, Bounds bounds) {
    final var minX = bounds.getX();
    final var maxX = (long) bounds.getX() + bounds.getWidth();
    final var minY = bounds.getY();
    final var maxY = (long) bounds.getY() + bounds.getHeight();
    if (wire.start().x() == wire.end().x()) {
      return wire.start().x() > minX
          && wire.start().x() < maxX
          && Math.max(Math.min(wire.start().y(), wire.end().y()), minY)
              < Math.min(Math.max(wire.start().y(), wire.end().y()), maxY);
    }
    return wire.start().y() > minY
        && wire.start().y() < maxY
        && Math.max(Math.min(wire.start().x(), wire.end().x()), minX)
            < Math.min(Math.max(wire.start().x(), wire.end().x()), maxX);
  }

  private static boolean electricallyConnects(
      CircuitImportPlan.PlannedNet left, CircuitImportPlan.PlannedNet right) {
    for (final var leftWire : left.wires()) {
      for (final var rightWire : right.wires()) {
        if (electricallyConnects(leftWire, rightWire)) return true;
      }
    }
    return false;
  }

  private static boolean electricallyConnects(
      CircuitImportPlan.PlannedWire left, CircuitImportPlan.PlannedWire right) {
    if (!intersects(left, right)) return false;
    final var leftVertical = left.start().x() == left.end().x();
    final var rightVertical = right.start().x() == right.end().x();
    if (leftVertical == rightVertical) return true;

    final var vertical = leftVertical ? left : right;
    final var horizontal = leftVertical ? right : left;
    final var crossing =
        new CircuitImportPlan.GridPoint(vertical.start().x(), horizontal.start().y());
    return isEndpoint(left, crossing) || isEndpoint(right, crossing);
  }

  private static boolean isEndpoint(
      CircuitImportPlan.PlannedWire wire, CircuitImportPlan.GridPoint point) {
    return wire.start().equals(point) || wire.end().equals(point);
  }

  private static boolean intersects(
      CircuitImportPlan.PlannedWire left, CircuitImportPlan.PlannedWire right) {
    final var leftVertical = left.start().x() == left.end().x();
    final var rightVertical = right.start().x() == right.end().x();
    if (leftVertical && rightVertical) {
      return left.start().x() == right.start().x()
          && overlaps(
              left.start().y(), left.end().y(), right.start().y(), right.end().y());
    }
    if (!leftVertical && !rightVertical) {
      return left.start().y() == right.start().y()
          && overlaps(
              left.start().x(), left.end().x(), right.start().x(), right.end().x());
    }
    final var vertical = leftVertical ? left : right;
    final var horizontal = leftVertical ? right : left;
    return between(vertical.start().x(), horizontal.start().x(), horizontal.end().x())
        && between(horizontal.start().y(), vertical.start().y(), vertical.end().y());
  }

  private static boolean overlaps(int a0, int a1, int b0, int b1) {
    return Math.max(Math.min(a0, a1), Math.min(b0, b1))
        <= Math.min(Math.max(a0, a1), Math.max(b0, b1));
  }

  private static boolean between(int value, int end0, int end1) {
    return value >= Math.min(end0, end1) && value <= Math.max(end0, end1);
  }

  private static boolean overlaps(Bounds left, Bounds right) {
    return Math.max(left.getX(), right.getX())
            < Math.min(
                (long) left.getX() + left.getWidth(), (long) right.getX() + right.getWidth())
        && Math.max(left.getY(), right.getY())
            < Math.min(
                (long) left.getY() + left.getHeight(),
                (long) right.getY() + right.getHeight());
  }

  private static CircuitImportPlan.GridPoint transform(
      VisionIr.Point imagePoint, VisionIr.Profile profile) {
    return new CircuitImportPlan.GridPoint(
        snap(imagePoint.x(), profile.offsetX(), profile.scale()),
        snap(imagePoint.y(), profile.offsetY(), profile.scale()));
  }

  private static int snap(double imageCoordinate, double offset, double scale) {
    final var circuitCoordinate = (imageCoordinate - offset) / scale;
    final var snapped = Math.round(circuitCoordinate / LOGISIM_GRID) * (long) LOGISIM_GRID;
    if (Math.abs(circuitCoordinate - snapped) > MAX_SNAP_DISTANCE) {
      throw new IllegalArgumentException(
          "Coordinate is too far from the Logisim grid: " + imageCoordinate);
    }
    try {
      return Math.toIntExact(snapped);
    } catch (ArithmeticException e) {
      throw new IllegalArgumentException("Coordinate is outside the supported range", e);
    }
  }

  private static String label(VisionIr.DetectedComponent component, String defaultLabel) {
    final var value = component.attributes().get("label");
    return value instanceof String text && !text.isBlank() ? text.trim() : defaultLabel;
  }

  private static int integerAttribute(
      Map<String, Object> attributes, String name, int defaultValue) {
    final var value = attributes.get(name);
    return value instanceof Number number ? number.intValue() : defaultValue;
  }

  private static String factoryName(VisionIr.ComponentKind kind) {
    return switch (kind) {
      case INPUT_PIN, OUTPUT_PIN -> "Pin";
      case AND -> "AND Gate";
      case OR -> "OR Gate";
      case NOT -> "NOT Gate";
      case NAND -> "NAND Gate";
      case NOR -> "NOR Gate";
      case XOR -> "XOR Gate";
      case XNOR -> "XNOR Gate";
    };
  }

  private static String portId(VisionIr.ComponentKind kind, int index) {
    if (kind == VisionIr.ComponentKind.INPUT_PIN) return "out";
    if (kind == VisionIr.ComponentKind.OUTPUT_PIN) return "in";
    return index == 0 ? "out" : "in" + (index - 1);
  }

  private record TunnelRouting(
      List<CircuitImportPlan.PlannedNet> nets,
      List<CircuitImportPlan.PlannedTunnel> tunnels) {}

  private record WireKey(
      CircuitImportPlan.GridPoint start, CircuitImportPlan.GridPoint end) {
    static WireKey of(CircuitImportPlan.GridPoint first, CircuitImportPlan.GridPoint second) {
      return compare(first, second) <= 0 ? new WireKey(first, second) : new WireKey(second, first);
    }

    private static int compare(
        CircuitImportPlan.GridPoint left, CircuitImportPlan.GridPoint right) {
      final var x = Integer.compare(left.x(), right.x());
      return x != 0 ? x : Integer.compare(left.y(), right.y());
    }
  }
}
