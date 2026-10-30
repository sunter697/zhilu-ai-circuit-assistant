/*
 * Logisim-evolution - digital logic design tool and simulator
 * Copyright by the Logisim-evolution developers
 *
 * https://github.com/logisim-evolution/
 *
 * This is free software released under GNU GPLv3 license
 */

package com.cburch.logisim.ai.vision.model;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Versioned, model-independent result of recognizing a circuit image. */
public record VisionIr(
    String schemaVersion,
    Source source,
    Profile profile,
    List<DetectedComponent> components,
    List<DetectedNet> nets,
    List<UnresolvedIssue> unresolved,
    String semanticHash) {
  public static final String VERSION = "vision-ir/0.1";

  public VisionIr {
    components = List.copyOf(components);
    nets = List.copyOf(nets);
    unresolved = List.copyOf(unresolved);
    semanticHash = semanticHash == null ? "" : semanticHash;
  }

  public enum ComponentKind {
    INPUT_PIN,
    OUTPUT_PIN,
    AND,
    OR,
    NOT,
    NAND,
    NOR,
    XOR,
    XNOR;

    public boolean isGate() {
      return this != INPUT_PIN && this != OUTPUT_PIN;
    }

    public int expectedInputCount(Map<String, Object> attributes) {
      if (this == INPUT_PIN || this == OUTPUT_PIN) return 0;
      if (this == NOT) return 1;
      final var value = attributes.get("inputs");
      return value instanceof Number number ? number.intValue() : 2;
    }
  }

  public enum Facing {
    EAST,
    WEST,
    NORTH,
    SOUTH
  }

  public enum PortDirection {
    INPUT,
    OUTPUT
  }

  public enum IssueSeverity {
    WARNING,
    ERROR
  }

  public record Source(String sha256, int width, int height) {}

  /** Image-to-circuit transform. At 100% zoom one image pixel is one circuit coordinate unit. */
  public record Profile(
      String theme,
      String gateShape,
      double gridPixels,
      double scale,
      double offsetX,
      double offsetY) {}

  public record Point(double x, double y) {}

  public record Box(double x0, double y0, double x1, double y1) {}

  public record DetectedPort(String id, PortDirection direction, Point point) {}

  public record DetectedComponent(
      String id,
      ComponentKind kind,
      Box bodyBox,
      Point anchor,
      Facing facing,
      Map<String, Object> attributes,
      List<DetectedPort> ports,
      double confidence) {
    public DetectedComponent {
      attributes =
          Collections.unmodifiableMap(
              new LinkedHashMap<>(attributes == null ? Map.of() : attributes));
      ports = List.copyOf(ports == null ? List.of() : ports);
    }
  }

  public record NetEndpoint(String componentId, String portId) {}

  public record Segment(Point start, Point end) {}

  public record DetectedNet(
      String id,
      List<NetEndpoint> endpoints,
      List<Segment> segments,
      List<Point> junctions,
      double confidence) {
    public DetectedNet {
      endpoints = List.copyOf(endpoints);
      segments = List.copyOf(segments);
      junctions = List.copyOf(junctions == null ? List.of() : junctions);
    }
  }

  public record UnresolvedIssue(
      String code, String message, IssueSeverity severity, String subjectId) {
    public UnresolvedIssue {
      subjectId = subjectId == null ? "" : subjectId;
    }
  }
}
