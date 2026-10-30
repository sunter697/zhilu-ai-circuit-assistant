/*
 * Logisim-evolution - digital logic design tool and simulator
 * Copyright by the Logisim-evolution developers
 *
 * https://github.com/logisim-evolution/
 *
 * This is free software released under GNU GPLv3 license
 */

package com.cburch.logisim.ai.vision.runtime;

import com.cburch.logisim.ai.vision.model.VisionIr;
import com.cburch.logisim.ai.vision.runtime.OrthogonalWireExtractor.GridNode;
import com.cburch.logisim.ai.vision.runtime.OrthogonalWireExtractor.GridProfile;
import com.cburch.logisim.ai.vision.runtime.OrthogonalWireExtractor.WireEdge;
import com.cburch.logisim.ai.vision.runtime.OrthogonalWireExtractor.WireExtraction;
import java.awt.image.BufferedImage;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/** Builds Vision IR from native-v04 component detections and raster wire pixels. */
public final class NativeCircuitTopologyRecognizer {
  private final OrthogonalWireExtractor wireExtractor;

  public NativeCircuitTopologyRecognizer() {
    this(new OrthogonalWireExtractor());
  }

  NativeCircuitTopologyRecognizer(OrthogonalWireExtractor wireExtractor) {
    this.wireExtractor = wireExtractor;
  }

  public VisionIr recognize(
      BufferedImage image,
      String sourceSha256,
      List<OnnxComponentDetector.Detection> rawDetections) {
    checkCancelled();
    final var detections = roundedDetections(rawDetections);
    final var extraction = wireExtractor.extract(image, detections);
    checkCancelled();
    final var components = components(detections, extraction.profile());
    return assemble(image, sourceSha256, components, extraction);
  }

  private static List<OnnxComponentDetector.Detection> roundedDetections(
      List<OnnxComponentDetector.Detection> detections) {
    return detections.stream()
        .map(
            detection ->
                new OnnxComponentDetector.Detection(
                    detection.kind(),
                    new OnnxComponentDetector.PixelBox(
                        Math.rint(detection.box().x0()),
                        Math.rint(detection.box().y0()),
                        Math.rint(detection.box().x1()),
                        Math.rint(detection.box().y1())),
                    detection.confidence()))
        .toList();
  }

  private static List<Component> components(
      List<OnnxComponentDetector.Detection> detections, GridProfile profile) {
    final var counters = new EnumMap<VisionIr.ComponentKind, Integer>(VisionIr.ComponentKind.class);
    final var sorted = new ArrayList<>(detections);
    sorted.sort(
        Comparator.comparingDouble(
                (OnnxComponentDetector.Detection detection) -> detection.box().x0())
            .thenComparingDouble(detection -> detection.box().y0())
            .thenComparing(detection -> detection.kind().name()));
    final var result = new ArrayList<Component>();
    for (final var detection : sorted) {
      checkCancelled();
      final var count = counters.merge(detection.kind(), 1, Integer::sum);
      final var id =
          detection.kind().name().toLowerCase(Locale.ROOT) + "_%03d".formatted(count);
      final var geometry = portGeometry(detection, profile);
      final var ports =
          geometry.ports.stream()
              .map(port -> new Port(id, port.id, port.direction, port.node))
              .toList();
      result.add(new Component(id, detection, geometry.anchor, ports, geometry.gateSize));
    }
    return List.copyOf(result);
  }

  private static PortGeometry portGeometry(
      OnnxComponentDetector.Detection detection, GridProfile profile) {
    final var box = detection.box();
    final var centerY = (box.y0() + box.y1()) / 2.0;
    if (detection.kind() == VisionIr.ComponentKind.OUTPUT_PIN) {
      final var anchor = profile.nearestNode(box.x1(), centerY);
      return new PortGeometry(
          anchor,
          List.of(new RawPort("in", VisionIr.PortDirection.INPUT, anchor)),
          null);
    }

    final var anchor = profile.nearestNode(box.x1(), centerY);
    if (detection.kind() == VisionIr.ComponentKind.INPUT_PIN) {
      return new PortGeometry(
          anchor,
          List.of(new RawPort("out", VisionIr.PortDirection.OUTPUT, anchor)),
          null);
    }
    final var inputX = profile.nearestNode(box.x0(), centerY).x();
    if (detection.kind() == VisionIr.ComponentKind.NOT) {
      return new PortGeometry(
          anchor,
          List.of(
              new RawPort("out", VisionIr.PortDirection.OUTPUT, anchor),
              new RawPort(
                  "in0", VisionIr.PortDirection.INPUT, new GridNode(inputX, anchor.y()))),
          notGateSize((box.x1() - box.x0()) / profile.scale()));
    }
    final var gateSize = multiInputGateSize((box.y1() - box.y0()) / profile.scale());
    final var inputSteps = gateSize.equals("NARROW") ? 1 : 2;
    final var topInput = new GridNode(inputX, anchor.y() - inputSteps);
    final var bottomInput = new GridNode(inputX, anchor.y() + inputSteps);
    return new PortGeometry(
        anchor,
        List.of(
            new RawPort("out", VisionIr.PortDirection.OUTPUT, anchor),
            new RawPort("in0", VisionIr.PortDirection.INPUT, topInput),
            new RawPort("in1", VisionIr.PortDirection.INPUT, bottomInput)),
        gateSize);
  }

  static String multiInputGateSize(double logicalHeight) {
    final var narrowDistance = Math.abs(logicalHeight - 30.0);
    final var mediumDistance = Math.abs(logicalHeight - 50.0);
    final var wideDistance = Math.abs(logicalHeight - 70.0);
    if (narrowDistance <= mediumDistance && narrowDistance <= wideDistance) return "NARROW";
    return mediumDistance <= wideDistance ? "MEDIUM" : "WIDE";
  }

  static String notGateSize(double logicalWidth) {
    return Math.abs(logicalWidth - 20.0) < Math.abs(logicalWidth - 30.0)
        ? "NARROW"
        : "WIDE";
  }

  private static VisionIr assemble(
      BufferedImage image,
      String sourceSha256,
      List<Component> components,
      WireExtraction extraction) {
    final var byRegion = new TreeMap<String, List<Port>>();
    final var issues = new ArrayList<VisionIr.UnresolvedIssue>();
    for (final var component : components) {
      checkCancelled();
      for (final var port : component.ports) {
        final var regionId = extraction.nodeRegions().get(port.node);
        if (regionId == null) {
          issues.add(
              issue(
                  "UNCONNECTED_PORT",
                  "No wire reaches " + port.componentId + ":" + port.id,
                  port.componentId));
        } else {
          byRegion.computeIfAbsent(regionId, ignored -> new ArrayList<>()).add(port);
        }
      }
    }

    final var regionsById = new HashMap<String, OrthogonalWireExtractor.WireRegion>();
    for (final var region : extraction.regions()) regionsById.put(region.id(), region);
    final var nets = new ArrayList<VisionIr.DetectedNet>();
    for (final var entry : byRegion.entrySet()) {
      checkCancelled();
      final var unique = new LinkedHashMap<String, Port>();
      for (final var port : entry.getValue()) {
        unique.putIfAbsent(port.componentId + ":" + port.id, port);
      }
      final var ports = new ArrayList<>(unique.values());
      ports.sort(
          Comparator.comparing((Port port) -> port.direction != VisionIr.PortDirection.OUTPUT)
              .thenComparing(port -> port.componentId)
              .thenComparing(port -> port.id));
      final var driverCount =
          ports.stream().filter(port -> port.direction == VisionIr.PortDirection.OUTPUT).count();
      if (driverCount != 1) {
        issues.add(
            issue(
                driverCount > 1 ? "MULTIPLE_DRIVERS" : "MISSING_DRIVER",
                "Wire region " + entry.getKey() + " has " + driverCount + " drivers",
                entry.getKey()));
        continue;
      }
      if (ports.size() < 2) {
        issues.add(
            issue(
                "UNCONNECTED_WIRE",
                "Wire region " + entry.getKey() + " reaches only one component port",
                entry.getKey()));
        continue;
      }
      final var region = regionsById.get(entry.getKey());
      final var terminals = new HashSet<GridNode>();
      for (final var port : ports) terminals.add(port.node);
      final var driver =
          ports.stream()
              .filter(port -> port.direction == VisionIr.PortDirection.OUTPUT)
              .findFirst()
              .orElseThrow();
      final var usedEdges = terminalTreeEdges(region.edges(), driver.node, terminals);
      final var degrees = new HashMap<GridNode, Integer>();
      for (final var edge : usedEdges) {
        degrees.merge(edge.first(), 1, Integer::sum);
        degrees.merge(edge.second(), 1, Integer::sum);
      }
      final var junctions =
          degrees.entrySet().stream()
              .filter(degree -> degree.getValue() >= 3 && !terminals.contains(degree.getKey()))
              .map(Map.Entry::getKey)
              .sorted()
              .map(extraction.profile()::imagePoint)
              .toList();
      nets.add(
          new VisionIr.DetectedNet(
              "net_%03d".formatted(nets.size() + 1),
              ports.stream()
                  .map(port -> new VisionIr.NetEndpoint(port.componentId, port.id))
                  .toList(),
              OrthogonalWireExtractor.mergeSegments(usedEdges, extraction.profile()),
              junctions,
              0.94));
    }

    for (final var region : extraction.regions()) {
      checkCancelled();
      if (!byRegion.containsKey(region.id())
          && region.edges().size() > 3
          && !insideComponentArtifact(
              region.nodes(), components, extraction.profile())) {
        issues.add(
            issue(
                "UNMATCHED_WIRE",
                "Wire region " + region.id() + " does not reach a component port",
                region.id()));
      }
    }

    final var detectedComponents =
        components.stream().map(component -> detectedComponent(component, extraction.profile())).toList();
    final var semanticHash = semanticHash(detectedComponents, nets);
    return new VisionIr(
        VisionIr.VERSION,
        new VisionIr.Source(sourceSha256, image.getWidth(), image.getHeight()),
        new VisionIr.Profile(
            "light",
            "ansi",
            extraction.profile().spacing(),
            extraction.profile().scale(),
            extraction.profile().offsetX(),
            extraction.profile().offsetY()),
        detectedComponents,
        nets,
        issues,
        semanticHash);
  }

  private static boolean insideComponentArtifact(
      Set<GridNode> nodes, List<Component> components, GridProfile profile) {
    if (nodes.isEmpty()) return false;
    for (final var component : components) {
      final var box = component.detection.box();
      final var inputPin =
          profile.nativeV04()
              && component.detection.kind() == VisionIr.ComponentKind.INPUT_PIN;
      final var xMargin =
          inputPin ? 2.0 * profile.spacing() : profile.nativeV04() ? profile.spacing() : 1.0;
      final var rightMargin = profile.nativeV04() ? profile.spacing() : 1.0;
      final var yMargin = profile.nativeV04() ? profile.spacing() : 1.0;
      var inside = true;
      for (final var node : nodes) {
        final var point = profile.imagePoint(node);
        if (point.x() < box.x0() - xMargin
            || point.x() > box.x1() + rightMargin
            || point.y() < box.y0() - yMargin
            || point.y() > box.y1() + yMargin) {
          inside = false;
          break;
        }
      }
      if (inside) return true;
    }
    return false;
  }

  private static VisionIr.DetectedComponent detectedComponent(
      Component component, GridProfile profile) {
    final Map<String, Object> attributes = new LinkedHashMap<>();
    attributes.put("bitWidth", 1);
    if (component.detection.kind().isGate()) {
      attributes.put("inputs", component.detection.kind() == VisionIr.ComponentKind.NOT ? 1 : 2);
      attributes.put("size", component.gateSize);
    }
    final var box = component.detection.box();
    return new VisionIr.DetectedComponent(
        component.id,
        component.detection.kind(),
        new VisionIr.Box(box.x0(), box.y0(), box.x1(), box.y1()),
        profile.imagePoint(component.anchor),
        VisionIr.Facing.EAST,
        attributes,
        component.ports.stream()
            .map(
                port ->
                    new VisionIr.DetectedPort(
                        port.id, port.direction, profile.imagePoint(port.node)))
            .toList(),
        component.detection.confidence());
  }

  private static VisionIr.UnresolvedIssue issue(String code, String message, String subjectId) {
    return new VisionIr.UnresolvedIssue(
        code, message, VisionIr.IssueSeverity.ERROR, subjectId);
  }

  private static String semanticHash(
      List<VisionIr.DetectedComponent> components, List<VisionIr.DetectedNet> nets) {
    final var canonical = new StringBuilder("{\"components\":[");
    for (var componentIndex = 0; componentIndex < components.size(); componentIndex++) {
      if (componentIndex > 0) canonical.append(',');
      final var component = components.get(componentIndex);
      canonical
          .append("[\"")
          .append(component.id())
          .append("\",\"")
          .append(component.kind())
          .append("\",[")
          .append(component.anchor().x())
          .append(',')
          .append(component.anchor().y())
          .append("]]");
    }
    canonical.append("],\"nets\":[");
    for (var netIndex = 0; netIndex < nets.size(); netIndex++) {
      if (netIndex > 0) canonical.append(',');
      canonical.append('[');
      final var endpoints = nets.get(netIndex).endpoints();
      for (var endpointIndex = 0; endpointIndex < endpoints.size(); endpointIndex++) {
        if (endpointIndex > 0) canonical.append(',');
        final var endpoint = endpoints.get(endpointIndex);
        canonical
            .append("{\"componentId\":\"")
            .append(endpoint.componentId())
            .append("\",\"portId\":\"")
            .append(endpoint.portId())
            .append("\"}");
      }
      canonical.append(']');
    }
    canonical.append("]}");
    try {
      final var digest = MessageDigest.getInstance("SHA-256");
      return java.util.HexFormat.of()
          .formatHex(digest.digest(canonical.toString().getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is unavailable", exception);
    }
  }

  private static Set<WireEdge> pruneNonterminalSpurs(
      Set<WireEdge> edges, Set<GridNode> terminals) {
    final var adjacency = new HashMap<GridNode, Set<GridNode>>();
    for (final var edge : edges) {
      adjacency.computeIfAbsent(edge.first(), ignored -> new HashSet<>()).add(edge.second());
      adjacency.computeIfAbsent(edge.second(), ignored -> new HashSet<>()).add(edge.first());
    }
    final var pending = new ArrayDeque<GridNode>();
    for (final var entry : adjacency.entrySet()) {
      if (entry.getValue().size() <= 1 && !terminals.contains(entry.getKey())) {
        pending.push(entry.getKey());
      }
    }
    while (!pending.isEmpty()) {
      final var node = pending.pop();
      final var neighbors = adjacency.remove(node);
      if (neighbors == null) continue;
      for (final var neighbor : neighbors) {
        final var adjacent = adjacency.get(neighbor);
        if (adjacent == null) continue;
        adjacent.remove(node);
        if (adjacent.size() <= 1 && !terminals.contains(neighbor)) pending.push(neighbor);
      }
    }
    final var result = new HashSet<WireEdge>();
    for (final var entry : adjacency.entrySet()) {
      for (final var neighbor : entry.getValue()) {
        result.add(WireEdge.of(entry.getKey(), neighbor));
      }
    }
    return Set.copyOf(result);
  }

  private static Set<WireEdge> terminalTreeEdges(
      Set<WireEdge> edges, GridNode root, Set<GridNode> terminals) {
    final var adjacency = new HashMap<GridNode, Set<GridNode>>();
    for (final var edge : edges) {
      adjacency.computeIfAbsent(edge.first(), ignored -> new HashSet<>()).add(edge.second());
      adjacency.computeIfAbsent(edge.second(), ignored -> new HashSet<>()).add(edge.first());
    }
    final var parents = new HashMap<GridNode, GridNode>();
    final var visited = new HashSet<GridNode>();
    final var pending = new ArrayDeque<GridNode>();
    visited.add(root);
    pending.add(root);
    while (!pending.isEmpty()) {
      final var node = pending.removeFirst();
      final var neighbors = new ArrayList<>(adjacency.getOrDefault(node, Set.of()));
      neighbors.sort(GridNode::compareTo);
      for (final var neighbor : neighbors) {
        if (!visited.add(neighbor)) continue;
        parents.put(neighbor, node);
        pending.addLast(neighbor);
      }
    }

    final var result = new HashSet<WireEdge>();
    final var sortedTerminals = new ArrayList<>(terminals);
    sortedTerminals.sort(GridNode::compareTo);
    for (final var terminal : sortedTerminals) {
      if (terminal.equals(root)) continue;
      if (!visited.contains(terminal)) return pruneNonterminalSpurs(edges, terminals);
      var node = terminal;
      while (!node.equals(root)) {
        final var parent = parents.get(node);
        if (parent == null) return pruneNonterminalSpurs(edges, terminals);
        result.add(WireEdge.of(node, parent));
        node = parent;
      }
    }
    return Set.copyOf(result);
  }

  private static void checkCancelled() {
    if (Thread.currentThread().isInterrupted()) {
      throw new IllegalStateException("Circuit topology recognition was cancelled");
    }
  }

  private record RawPort(String id, VisionIr.PortDirection direction, GridNode node) {}

  private record PortGeometry(GridNode anchor, List<RawPort> ports, String gateSize) {}

  private record Port(
      String componentId, String id, VisionIr.PortDirection direction, GridNode node) {}

  private record Component(
      String id,
      OnnxComponentDetector.Detection detection,
      GridNode anchor,
      List<Port> ports,
      String gateSize) {}
}
