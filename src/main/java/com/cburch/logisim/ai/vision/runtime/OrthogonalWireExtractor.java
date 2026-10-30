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
import java.awt.image.BufferedImage;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/** Extracts native Logisim black and blue orthogonal wires onto the ten-pixel grid. */
public final class OrthogonalWireExtractor {
  public record GridNode(int x, int y) implements Comparable<GridNode> {
    @Override
    public int compareTo(GridNode other) {
      final var xComparison = Integer.compare(x, other.x);
      return xComparison != 0 ? xComparison : Integer.compare(y, other.y);
    }
  }

  public record GridProfile(
      double spacing, double offsetX, double offsetY, boolean nativeV04) {
    public GridProfile {
      if (!Double.isFinite(spacing) || spacing <= 0.0) {
        throw new IllegalArgumentException("Grid spacing must be positive and finite");
      }
      if (!Double.isFinite(offsetX) || !Double.isFinite(offsetY)) {
        throw new IllegalArgumentException("Grid offsets must be finite");
      }
    }

    public double scale() {
      return spacing / 10.0;
    }

    public VisionIr.Point imagePoint(GridNode node) {
      return new VisionIr.Point(offsetX + node.x * spacing, offsetY + node.y * spacing);
    }

    public GridNode nearestNode(double imageX, double imageY) {
      return new GridNode(nearest(imageX, offsetX), nearest(imageY, offsetY));
    }

    private int nearest(double value, double offset) {
      return (int) Math.floor((value - offset) / spacing + 0.5 - 1.0e-6);
    }
  }

  public record WireEdge(GridNode first, GridNode second) {
    static WireEdge of(GridNode first, GridNode second) {
      return first.compareTo(second) <= 0
          ? new WireEdge(first, second)
          : new WireEdge(second, first);
    }
  }

  public record WireRegion(String id, Set<GridNode> nodes, Set<WireEdge> edges) {
    public WireRegion {
      nodes = Set.copyOf(nodes);
      edges = Set.copyOf(edges);
    }
  }

  public record WireExtraction(
      GridProfile profile, List<WireRegion> regions, Map<GridNode, String> nodeRegions) {
    public WireExtraction {
      regions = List.copyOf(regions);
      nodeRegions = Map.copyOf(nodeRegions);
    }
  }

  private static final GridProfile NATIVE_PROFILE = new GridProfile(10.0, 0.0, 0.0, true);
  private final double edgeCoverage;

  public OrthogonalWireExtractor() {
    this(0.55);
  }

  public OrthogonalWireExtractor(double edgeCoverage) {
    if (!(edgeCoverage > 0.0 && edgeCoverage <= 1.0)) {
      throw new IllegalArgumentException("edgeCoverage must be in (0, 1]");
    }
    this.edgeCoverage = edgeCoverage;
  }

  public WireExtraction extract(
      BufferedImage image, List<OnnxComponentDetector.Detection> detections) {
    return extract(image, detections, nativeProfile(detections));
  }

  static GridProfile nativeProfile(List<OnnxComponentDetector.Detection> detections) {
    final var scales = new ArrayList<Double>();
    for (final var detection : detections) {
      if (detection.kind() != VisionIr.ComponentKind.INPUT_PIN
          && detection.kind() != VisionIr.ComponentKind.OUTPUT_PIN) continue;
      scales.add((detection.box().x1() - detection.box().x0()) / 20.0);
      scales.add((detection.box().y1() - detection.box().y0()) / 20.0);
    }
    if (scales.isEmpty()) return NATIVE_PROFILE;
    scales.sort(Double::compareTo);
    final double scale;
    final var middle = scales.size() / 2;
    if (scales.size() % 2 == 0) {
      scale = (scales.get(middle - 1) + scales.get(middle)) / 2.0;
    } else {
      scale = scales.get(middle);
    }
    if (!Double.isFinite(scale) || scale < 0.5 || scale > 2.5) {
      throw new IllegalArgumentException("Unsupported native Logisim scale: " + scale);
    }
    final var rawSpacing = 10.0 * scale;
    final var anchors = new ArrayList<Double>();
    for (final var detection : detections) {
      if (detection.kind() != VisionIr.ComponentKind.INPUT_PIN
          && detection.kind() != VisionIr.ComponentKind.OUTPUT_PIN) continue;
      anchors.add(detection.box().x1());
      anchors.add((detection.box().y0() + detection.box().y1()) / 2.0);
    }
    var spacing = rawSpacing;
    if (!anchors.isEmpty()) {
      final var lower = Math.max(5.0, rawSpacing * 0.9);
      final var upper = Math.min(25.0, rawSpacing * 1.1);
      var bestScore = Double.POSITIVE_INFINITY;
      for (var index = 0; index <= 800; index++) {
        final var candidate = lower + (upper - lower) * index / 800.0;
        var alignment = 0.0;
        for (final var value : anchors) {
          final var logical = value / candidate;
          final var error = logical - Math.rint(logical);
          alignment += error * error;
        }
        alignment /= anchors.size();
        final var relativeSizeError = (candidate - rawSpacing) / rawSpacing;
        final var score = alignment + 0.1 * relativeSizeError * relativeSizeError;
        if (score < bestScore) {
          bestScore = score;
          spacing = candidate;
        }
      }
    }
    return new GridProfile(spacing, 0.0, 0.0, true);
  }

  public WireExtraction extract(
      BufferedImage image,
      List<OnnxComponentDetector.Detection> detections,
      GridProfile profile) {
    checkCancelled();
    if (image == null || image.getWidth() <= 0 || image.getHeight() <= 0) {
      throw new IllegalArgumentException("A non-empty image is required");
    }
    if (!profile.nativeV04()) {
      throw new IllegalArgumentException("Only the native-v04 grid profile is supported in Java");
    }
    final var sourceMask = nativeWireMask(image);
    final var wireMask = copyMask(sourceMask);
    final var nativePorts = new ArrayList<NativePort>();
    for (var index = 0; index < detections.size(); index++) {
      checkCancelled();
      final var ports =
          clearComponentBodyAndRestorePorts(
              image, detections.get(index), profile, sourceMask, wireMask);
      for (final var port : ports) nativePorts.add(new NativePort(index, port));
    }
    restoreAlignedVerticalPorts(image, profile, sourceMask, wireMask, nativePorts);
    for (final var detection : detections) {
      isolateComponentBody(detection, profile, wireMask);
    }
    final var adjacency = gridGraph(wireMask, profile);
    final var regions = regions(adjacency);
    final var nodeRegions = new HashMap<GridNode, String>();
    for (final var region : regions) {
      for (final var node : region.nodes()) nodeRegions.put(node, region.id());
    }
    return new WireExtraction(profile, regions, nodeRegions);
  }

  static List<VisionIr.Segment> mergeSegments(Set<WireEdge> edges, GridProfile profile) {
    final var horizontal = new TreeMap<Integer, List<IntInterval>>();
    final var vertical = new TreeMap<Integer, List<IntInterval>>();
    for (final var edge : edges) {
      if (edge.first().y == edge.second().y) {
        horizontal
            .computeIfAbsent(edge.first().y, ignored -> new ArrayList<>())
            .add(new IntInterval(edge.first().x, edge.second().x));
      } else {
        vertical
            .computeIfAbsent(edge.first().x, ignored -> new ArrayList<>())
            .add(new IntInterval(edge.first().y, edge.second().y));
      }
    }
    final var result = new ArrayList<VisionIr.Segment>();
    for (final var entry : horizontal.entrySet()) {
      for (final var interval : mergeIntervals(entry.getValue())) {
        result.add(
            new VisionIr.Segment(
                profile.imagePoint(new GridNode(interval.start, entry.getKey())),
                profile.imagePoint(new GridNode(interval.end, entry.getKey()))));
      }
    }
    for (final var entry : vertical.entrySet()) {
      for (final var interval : mergeIntervals(entry.getValue())) {
        result.add(
            new VisionIr.Segment(
                profile.imagePoint(new GridNode(entry.getKey(), interval.start)),
                profile.imagePoint(new GridNode(entry.getKey(), interval.end))));
      }
    }
    return List.copyOf(result);
  }

  private static boolean[][] nativeWireMask(BufferedImage image) {
    final var mask = new boolean[image.getHeight()][image.getWidth()];
    for (var y = 0; y < image.getHeight(); y++) {
      checkCancelled();
      for (var x = 0; x < image.getWidth(); x++) {
        final var rgb = image.getRGB(x, y);
        final var red = (rgb >>> 16) & 0xff;
        final var green = (rgb >>> 8) & 0xff;
        final var blue = rgb & 0xff;
        final var maximum = Math.max(red, Math.max(green, blue));
        final var minimum = Math.min(red, Math.min(green, blue));
        final var blackWire = maximum <= 110 && maximum - minimum <= 12;
        final var blueWire = blue >= 80 && blue - red >= 40 && blue - green >= 40;
        mask[y][x] = blackWire || blueWire;
      }
    }
    return mask;
  }

  private static boolean[][] copyMask(boolean[][] source) {
    final var result = new boolean[source.length][];
    for (var y = 0; y < source.length; y++) result[y] = source[y].clone();
    return result;
  }

  private List<IntPoint> clearComponentBodyAndRestorePorts(
      BufferedImage image,
      OnnxComponentDetector.Detection detection,
      GridProfile profile,
      boolean[][] source,
      boolean[][] target) {
    final var box = detection.box();
    final var x0 = (int) Math.rint(box.x0());
    final var y0 = (int) Math.rint(box.y0());
    final var x1 = (int) Math.rint(box.x1());
    final var y1 = (int) Math.rint(box.y1());
    fillFalse(target, x0, y0, x1 + 1, y1 + 1);

    final var ports = nativeComponentPorts(detection, profile);
    final var radius = Math.max(1, (int) Math.round(profile.spacing() * 0.16));
    final var reach = (int) Math.round(profile.spacing());
    final int[] directions;
    if (detection.kind() == VisionIr.ComponentKind.INPUT_PIN) {
      directions = new int[] {1};
    } else if (detection.kind() == VisionIr.ComponentKind.OUTPUT_PIN) {
      directions = new int[] {-1};
    } else if (detection.kind() == VisionIr.ComponentKind.NOT) {
      directions = new int[] {-1, 1};
    } else {
      directions = new int[] {-1, -1, 1};
    }
    for (var index = 0; index < ports.size(); index++) {
      final var port = ports.get(index);
      final var direction = directions[index];
      final var outwardReach =
          detection.kind() == VisionIr.ComponentKind.OUTPUT_PIN ? 4 * reach : reach;
      final var left = direction < 0 ? port.x - outwardReach : port.x - radius;
      final var right = direction < 0 ? port.x + radius + 1 : port.x + outwardReach + 1;
      restore(
          source,
          target,
          left,
          port.y - radius,
          right,
          port.y + radius + 1);
    }
    if (detection.kind() != VisionIr.ComponentKind.INPUT_PIN
        && detection.kind() != VisionIr.ComponentKind.OUTPUT_PIN
        && detection.kind() != VisionIr.ComponentKind.NOT) {
      for (var index = 0; index < 2; index++) {
        final var port = ports.get(index);
        final var direction = index == 0 ? -1 : 1;
        final var outsideY = port.y + direction * reach * 2;
        if (verticalCoverage(source, port.y, outsideY, port.x, radius) < edgeCoverage) continue;
        restore(
            source,
            target,
            port.x - radius,
            Math.min(port.y, outsideY) - radius,
            port.x + radius + 1,
            Math.max(port.y, outsideY) + radius + 1);
      }
    }
    return ports;
  }

  private static void isolateComponentBody(
      OnnxComponentDetector.Detection detection, GridProfile profile, boolean[][] target) {
    if (detection.kind() == VisionIr.ComponentKind.INPUT_PIN
        || detection.kind() == VisionIr.ComponentKind.OUTPUT_PIN) return;
    final var ports = nativeComponentPorts(detection, profile);
    final var radius = Math.max(1, (int) Math.round(profile.spacing() * 0.16));
    if (detection.kind() == VisionIr.ComponentKind.NOT) {
      final var input = ports.get(0);
      final var output = ports.get(1);
      fillFalse(
          target,
          input.x + radius + 1,
          input.y - radius,
          output.x - radius,
          input.y + radius + 1);
      return;
    }
    final var topInput = ports.get(0);
    final var bottomInput = ports.get(1);
    fillFalse(
        target,
        topInput.x - radius,
        topInput.y + radius + 1,
        topInput.x + radius + 1,
        bottomInput.y - radius);
  }

  private static List<IntPoint> nativeComponentPorts(
      OnnxComponentDetector.Detection detection, GridProfile profile) {
    final var box = detection.box();
    final var centerY = (box.y0() + box.y1()) / 2.0;
    final var anchor = profile.nearestNode(box.x1(), centerY);
    final var output = imagePoint(profile, anchor);
    if (detection.kind() == VisionIr.ComponentKind.INPUT_PIN
        || detection.kind() == VisionIr.ComponentKind.OUTPUT_PIN) {
      return List.of(output);
    }

    final var inputX = profile.nearestNode(box.x0(), centerY).x();
    if (detection.kind() == VisionIr.ComponentKind.NOT) {
      return List.of(imagePoint(profile, new GridNode(inputX, anchor.y())), output);
    }

    final var gateSize =
        NativeCircuitTopologyRecognizer.multiInputGateSize(
            (box.y1() - box.y0()) / profile.scale());
    final var inputSteps = gateSize.equals("NARROW") ? 1 : 2;
    return List.of(
        imagePoint(profile, new GridNode(inputX, anchor.y() - inputSteps)),
        imagePoint(profile, new GridNode(inputX, anchor.y() + inputSteps)),
        output);
  }

  private static IntPoint imagePoint(GridProfile profile, GridNode node) {
    final var point = profile.imagePoint(node);
    return new IntPoint((int) Math.round(point.x()), (int) Math.round(point.y()));
  }

  private void restoreAlignedVerticalPorts(
      BufferedImage image,
      GridProfile profile,
      boolean[][] source,
      boolean[][] target,
      List<NativePort> ports) {
    final var radius = Math.max(1, (int) Math.round(profile.spacing() * 0.16));
    final var minimumLength = (int) Math.round(profile.spacing());
    for (var firstIndex = 0; firstIndex < ports.size(); firstIndex++) {
      final var first = ports.get(firstIndex);
      for (var secondIndex = firstIndex + 1; secondIndex < ports.size(); secondIndex++) {
        final var second = ports.get(secondIndex);
        if (first.componentIndex == second.componentIndex
            || first.point.x != second.point.x
            || Math.abs(first.point.y - second.point.y) < minimumLength) continue;
        if (verticalCoverage(
                source, first.point.y, second.point.y, first.point.x, radius)
            < edgeCoverage) continue;
        restore(
            source,
            target,
            first.point.x - radius,
            Math.min(first.point.y, second.point.y) - radius,
            first.point.x + radius + 1,
            Math.max(first.point.y, second.point.y) + radius + 1);
      }
    }
  }

  private static void fillFalse(boolean[][] mask, int x0, int y0, int x1, int y1) {
    final var left = Math.max(0, x0);
    final var top = Math.max(0, y0);
    final var right = Math.min(mask[0].length, x1);
    final var bottom = Math.min(mask.length, y1);
    for (var y = top; y < bottom; y++) {
      for (var x = left; x < right; x++) mask[y][x] = false;
    }
  }

  private static void restore(
      boolean[][] source, boolean[][] target, int x0, int y0, int x1, int y1) {
    final var left = Math.max(0, x0);
    final var top = Math.max(0, y0);
    final var right = Math.min(source[0].length, x1);
    final var bottom = Math.min(source.length, y1);
    for (var y = top; y < bottom; y++) {
      for (var x = left; x < right; x++) target[y][x] = source[y][x];
    }
  }

  private Map<GridNode, Set<GridNode>> gridGraph(boolean[][] mask, GridProfile profile) {
    final var height = mask.length;
    final var width = mask[0].length;
    final var minX = (int) Math.ceil(-profile.offsetX() / profile.spacing());
    final var maxX =
        (int) Math.floor((width - 1 - profile.offsetX()) / profile.spacing());
    final var minY = (int) Math.ceil(-profile.offsetY() / profile.spacing());
    final var maxY =
        (int) Math.floor((height - 1 - profile.offsetY()) / profile.spacing());
    final var radius = Math.max(1, (int) Math.round(profile.spacing() * 0.16));
    final Map<GridNode, Set<GridNode>> adjacency = new LinkedHashMap<>();

    for (var yIndex = minY; yIndex <= maxY; yIndex++) {
      checkCancelled();
      final var imageY = profile.offsetY() + yIndex * profile.spacing();
      for (var xIndex = minX; xIndex < maxX; xIndex++) {
        final var imageX0 = profile.offsetX() + xIndex * profile.spacing();
        if (horizontalCoverage(mask, imageX0, imageX0 + profile.spacing(), imageY, radius)
            >= edgeCoverage) {
          connect(adjacency, new GridNode(xIndex, yIndex), new GridNode(xIndex + 1, yIndex));
        }
      }
    }
    for (var xIndex = minX; xIndex <= maxX; xIndex++) {
      checkCancelled();
      final var imageX = profile.offsetX() + xIndex * profile.spacing();
      for (var yIndex = minY; yIndex < maxY; yIndex++) {
        final var imageY0 = profile.offsetY() + yIndex * profile.spacing();
        if (verticalCoverage(mask, imageY0, imageY0 + profile.spacing(), imageX, radius)
            >= edgeCoverage) {
          connect(adjacency, new GridNode(xIndex, yIndex), new GridNode(xIndex, yIndex + 1));
        }
      }
    }
    return adjacency;
  }

  private static void connect(
      Map<GridNode, Set<GridNode>> adjacency, GridNode first, GridNode second) {
    adjacency.computeIfAbsent(first, ignored -> new LinkedHashSet<>()).add(second);
    adjacency.computeIfAbsent(second, ignored -> new LinkedHashSet<>()).add(first);
  }

  private static double horizontalCoverage(
      boolean[][] mask, double x0, double x1, double imageY, int radius) {
    final var left = Math.max(0, (int) Math.floor(x0));
    final var right = Math.min(mask[0].length - 1, (int) Math.ceil(x1));
    final var center = (int) Math.round(imageY);
    var maximum = 0.0;
    for (var y = Math.max(0, center - radius);
        y < Math.min(mask.length, center + radius + 1);
        y++) {
      var count = 0;
      for (var x = left; x <= right; x++) if (mask[y][x]) count++;
      maximum = Math.max(maximum, (double) count / (right - left + 1));
    }
    return maximum;
  }

  private static double verticalCoverage(
      boolean[][] mask, double y0, double y1, double imageX, int radius) {
    final var top = Math.max(0, (int) Math.floor(y0));
    final var bottom = Math.min(mask.length - 1, (int) Math.ceil(y1));
    final var center = (int) Math.round(imageX);
    var maximum = 0.0;
    for (var x = Math.max(0, center - radius);
        x < Math.min(mask[0].length, center + radius + 1);
        x++) {
      var count = 0;
      for (var y = top; y <= bottom; y++) if (mask[y][x]) count++;
      maximum = Math.max(maximum, (double) count / (bottom - top + 1));
    }
    return maximum;
  }

  private static List<WireRegion> regions(Map<GridNode, Set<GridNode>> adjacency) {
    final var seen = new HashSet<GridNode>();
    final var raw = new ArrayList<RawRegion>();
    for (final var start : adjacency.keySet()) {
      checkCancelled();
      if (!seen.add(start)) continue;
      final var pending = new ArrayDeque<GridNode>();
      pending.push(start);
      final var nodes = new HashSet<GridNode>();
      final var edges = new HashSet<WireEdge>();
      while (!pending.isEmpty()) {
        final var node = pending.pop();
        nodes.add(node);
        for (final var neighbor : adjacency.get(node)) {
          edges.add(WireEdge.of(node, neighbor));
          if (seen.add(neighbor)) pending.push(neighbor);
        }
      }
      raw.add(new RawRegion(nodes, edges));
    }
    raw.sort(Comparator.comparing(region -> region.nodes.stream().min(GridNode::compareTo).orElseThrow()));
    final var result = new ArrayList<WireRegion>();
    for (var index = 0; index < raw.size(); index++) {
      final var region = raw.get(index);
      result.add(
          new WireRegion(
              "wire_%03d".formatted(index + 1), region.nodes, region.edges));
    }
    return List.copyOf(result);
  }

  private static List<IntInterval> mergeIntervals(List<IntInterval> intervals) {
    final var ordered = new ArrayList<IntInterval>();
    for (final var interval : intervals) {
      ordered.add(
          new IntInterval(
              Math.min(interval.start, interval.end), Math.max(interval.start, interval.end)));
    }
    ordered.sort(Comparator.comparingInt(IntInterval::start).thenComparingInt(IntInterval::end));
    final var merged = new ArrayList<IntInterval>();
    for (final var interval : ordered) {
      if (merged.isEmpty() || interval.start > merged.get(merged.size() - 1).end) {
        merged.add(interval);
      } else {
        final var previous = merged.remove(merged.size() - 1);
        merged.add(new IntInterval(previous.start, Math.max(previous.end, interval.end)));
      }
    }
    return List.copyOf(merged);
  }

  private static void checkCancelled() {
    if (Thread.currentThread().isInterrupted()) {
      throw new IllegalStateException("Wire extraction was cancelled");
    }
  }

  private record IntPoint(int x, int y) {}

  private record NativePort(int componentIndex, IntPoint point) {}

  private record IntInterval(int start, int end) {}

  private record RawRegion(Set<GridNode> nodes, Set<WireEdge> edges) {}
}
