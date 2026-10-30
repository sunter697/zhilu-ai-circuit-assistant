/*
 * Logisim-evolution - digital logic design tool and simulator
 * Copyright by the Logisim-evolution developers
 *
 * https://github.com/logisim-evolution/
 *
 * This is free software released under GNU GPLv3 license
 */

package com.cburch.logisim.ai.vision.model;

import com.cburch.logisim.ai.util.SimpleJson;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Dependency-free JSON codec for the circuit-vision protocol. */
public final class VisionIrCodec {
  private VisionIrCodec() {}

  public static VisionIr fromJson(String json) {
    return fromMap(SimpleJson.parseObject(json));
  }

  public static VisionIr fromMap(Map<String, Object> root) {
    final var sourceMap = object(required(root, "source", "$"), "$.source");
    final var profileMap = object(required(root, "profile", "$"), "$.profile");
    final var components = new ArrayList<VisionIr.DetectedComponent>();
    final var componentValues = array(required(root, "components", "$"), "$.components");
    for (var i = 0; i < componentValues.size(); i++) {
      components.add(component(object(componentValues.get(i), "$.components[" + i + "]"), i));
    }
    final var nets = new ArrayList<VisionIr.DetectedNet>();
    final var netValues = array(required(root, "nets", "$"), "$.nets");
    for (var i = 0; i < netValues.size(); i++) {
      nets.add(net(object(netValues.get(i), "$.nets[" + i + "]"), i));
    }
    final var unresolved = new ArrayList<VisionIr.UnresolvedIssue>();
    final var issueValues = optionalArray(root.get("unresolved"), "$.unresolved");
    for (var i = 0; i < issueValues.size(); i++) {
      unresolved.add(issue(object(issueValues.get(i), "$.unresolved[" + i + "]"), i));
    }
    return new VisionIr(
        string(root, "schemaVersion", "$"),
        new VisionIr.Source(
            string(sourceMap, "sha256", "$.source"),
            integer(sourceMap, "width", "$.source"),
            integer(sourceMap, "height", "$.source")),
        new VisionIr.Profile(
            string(profileMap, "theme", "$.profile"),
            string(profileMap, "gateShape", "$.profile"),
            number(profileMap, "gridPixels", "$.profile"),
            number(profileMap, "scale", "$.profile"),
            number(profileMap, "offsetX", "$.profile"),
            number(profileMap, "offsetY", "$.profile")),
        components,
        nets,
        unresolved,
        optionalString(root.get("semanticHash"), "$.semanticHash"));
  }

  public static String toJson(VisionIr ir) {
    return SimpleJson.stringify(toMap(ir));
  }

  public static Map<String, Object> toMap(VisionIr ir) {
    final var root = new LinkedHashMap<String, Object>();
    root.put("schemaVersion", ir.schemaVersion());
    root.put(
        "source",
        orderedMap(
            "sha256",
            ir.source().sha256(),
            "width",
            ir.source().width(),
            "height",
            ir.source().height()));
    root.put(
        "profile",
        orderedMap(
            "theme",
            ir.profile().theme(),
            "gateShape",
            ir.profile().gateShape(),
            "gridPixels",
            ir.profile().gridPixels(),
            "scale",
            ir.profile().scale(),
            "offsetX",
            ir.profile().offsetX(),
            "offsetY",
            ir.profile().offsetY()));
    root.put("components", ir.components().stream().map(VisionIrCodec::componentMap).toList());
    root.put("nets", ir.nets().stream().map(VisionIrCodec::netMap).toList());
    root.put("unresolved", ir.unresolved().stream().map(VisionIrCodec::issueMap).toList());
    root.put("semanticHash", ir.semanticHash());
    return root;
  }

  private static VisionIr.DetectedComponent component(Map<String, Object> map, int index) {
    final var path = "$.components[" + index + "]";
    final var ports = new ArrayList<VisionIr.DetectedPort>();
    final var portValues = optionalArray(map.get("ports"), path + ".ports");
    for (var i = 0; i < portValues.size(); i++) {
      final var portPath = path + ".ports[" + i + "]";
      final var port = object(portValues.get(i), portPath);
      ports.add(
          new VisionIr.DetectedPort(
              string(port, "id", portPath),
              enumValue(
                  VisionIr.PortDirection.class,
                  string(port, "direction", portPath),
                  portPath + ".direction"),
              point(required(port, "point", portPath), portPath + ".point")));
    }
    return new VisionIr.DetectedComponent(
        string(map, "id", path),
        enumValue(
            VisionIr.ComponentKind.class, string(map, "kind", path), path + ".kind"),
        box(required(map, "bodyBox", path), path + ".bodyBox"),
        point(required(map, "anchor", path), path + ".anchor"),
        enumValue(VisionIr.Facing.class, string(map, "facing", path), path + ".facing"),
        optionalObject(map.get("attributes"), path + ".attributes"),
        ports,
        number(map, "confidence", path));
  }

  private static VisionIr.DetectedNet net(Map<String, Object> map, int index) {
    final var path = "$.nets[" + index + "]";
    final var endpoints = new ArrayList<VisionIr.NetEndpoint>();
    final var endpointValues = array(required(map, "endpoints", path), path + ".endpoints");
    for (var i = 0; i < endpointValues.size(); i++) {
      final var endpointPath = path + ".endpoints[" + i + "]";
      final var endpoint = object(endpointValues.get(i), endpointPath);
      endpoints.add(
          new VisionIr.NetEndpoint(
              string(endpoint, "componentId", endpointPath),
              string(endpoint, "portId", endpointPath)));
    }
    final var segments = new ArrayList<VisionIr.Segment>();
    final var segmentValues = array(required(map, "segments", path), path + ".segments");
    for (var i = 0; i < segmentValues.size(); i++) {
      final var segmentPath = path + ".segments[" + i + "]";
      final var values = array(segmentValues.get(i), segmentPath);
      if (values.size() != 4) throw error(segmentPath, "must contain four numbers");
      segments.add(
          new VisionIr.Segment(
              new VisionIr.Point(number(values.get(0), segmentPath), number(values.get(1), segmentPath)),
              new VisionIr.Point(number(values.get(2), segmentPath), number(values.get(3), segmentPath))));
    }
    final var junctions = new ArrayList<VisionIr.Point>();
    final var junctionValues = optionalArray(map.get("junctions"), path + ".junctions");
    for (var i = 0; i < junctionValues.size(); i++) {
      junctions.add(point(junctionValues.get(i), path + ".junctions[" + i + "]"));
    }
    return new VisionIr.DetectedNet(
        string(map, "id", path),
        endpoints,
        segments,
        junctions,
        number(map, "confidence", path));
  }

  private static VisionIr.UnresolvedIssue issue(Map<String, Object> map, int index) {
    final var path = "$.unresolved[" + index + "]";
    return new VisionIr.UnresolvedIssue(
        string(map, "code", path),
        string(map, "message", path),
        enumValue(
            VisionIr.IssueSeverity.class,
            string(map, "severity", path),
            path + ".severity"),
        optionalString(map.get("subjectId"), path + ".subjectId"));
  }

  private static Map<String, Object> componentMap(VisionIr.DetectedComponent component) {
    final var map = new LinkedHashMap<String, Object>();
    map.put("id", component.id());
    map.put("kind", component.kind().name());
    map.put("bodyBox", boxList(component.bodyBox()));
    map.put("anchor", pointList(component.anchor()));
    map.put("facing", component.facing().name());
    map.put("attributes", component.attributes());
    map.put(
        "ports",
        component.ports().stream()
            .map(
                port ->
                    orderedMap(
                        "id",
                        port.id(),
                        "direction",
                        port.direction().name(),
                        "point",
                        pointList(port.point())))
            .toList());
    map.put("confidence", component.confidence());
    return map;
  }

  private static Map<String, Object> netMap(VisionIr.DetectedNet net) {
    final var map = new LinkedHashMap<String, Object>();
    map.put("id", net.id());
    map.put(
        "endpoints",
        net.endpoints().stream()
            .map(
                endpoint ->
                    orderedMap(
                        "componentId",
                        endpoint.componentId(),
                        "portId",
                        endpoint.portId()))
            .toList());
    map.put(
        "segments",
        net.segments().stream()
            .map(
                segment ->
                    List.of(
                        segment.start().x(),
                        segment.start().y(),
                        segment.end().x(),
                        segment.end().y()))
            .toList());
    map.put("junctions", net.junctions().stream().map(VisionIrCodec::pointList).toList());
    map.put("confidence", net.confidence());
    return map;
  }

  private static Map<String, Object> issueMap(VisionIr.UnresolvedIssue issue) {
    return orderedMap(
        "code",
        issue.code(),
        "message",
        issue.message(),
        "severity",
        issue.severity().name(),
        "subjectId",
        issue.subjectId());
  }

  private static List<Double> pointList(VisionIr.Point point) {
    return List.of(point.x(), point.y());
  }

  private static List<Double> boxList(VisionIr.Box box) {
    return List.of(box.x0(), box.y0(), box.x1(), box.y1());
  }

  private static VisionIr.Point point(Object value, String path) {
    final var values = array(value, path);
    if (values.size() != 2) throw error(path, "must contain two numbers");
    return new VisionIr.Point(number(values.get(0), path), number(values.get(1), path));
  }

  private static VisionIr.Box box(Object value, String path) {
    final var values = array(value, path);
    if (values.size() != 4) throw error(path, "must contain four numbers");
    return new VisionIr.Box(
        number(values.get(0), path),
        number(values.get(1), path),
        number(values.get(2), path),
        number(values.get(3), path));
  }

  private static Object required(Map<String, Object> map, String key, String path) {
    if (!map.containsKey(key) || map.get(key) == null) {
      throw error(path + "." + key, "is required");
    }
    return map.get(key);
  }

  private static String string(Map<String, Object> map, String key, String path) {
    final var value = required(map, key, path);
    if (value instanceof String text) return text;
    throw error(path + "." + key, "must be a string");
  }

  private static String optionalString(Object value, String path) {
    if (value == null) return "";
    if (value instanceof String text) return text;
    throw error(path, "must be a string");
  }

  private static int integer(Map<String, Object> map, String key, String path) {
    final var value = required(map, key, path);
    if (value instanceof Number number && Math.rint(number.doubleValue()) == number.doubleValue()) {
      try {
        return Math.toIntExact(number.longValue());
      } catch (ArithmeticException e) {
        throw error(path + "." + key, "is outside the integer range");
      }
    }
    throw error(path + "." + key, "must be an integer");
  }

  private static double number(Map<String, Object> map, String key, String path) {
    return number(required(map, key, path), path + "." + key);
  }

  private static double number(Object value, String path) {
    if (value instanceof Number number) return number.doubleValue();
    throw error(path, "must be a number");
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> object(Object value, String path) {
    if (value instanceof Map<?, ?> map) return (Map<String, Object>) map;
    throw error(path, "must be an object");
  }

  private static Map<String, Object> optionalObject(Object value, String path) {
    return value == null ? Map.of() : object(value, path);
  }

  @SuppressWarnings("unchecked")
  private static List<Object> array(Object value, String path) {
    if (value instanceof List<?> list) return (List<Object>) list;
    throw error(path, "must be an array");
  }

  private static List<Object> optionalArray(Object value, String path) {
    return value == null ? List.of() : array(value, path);
  }

  private static <E extends Enum<E>> E enumValue(
      Class<E> type, String value, String path) {
    try {
      return Enum.valueOf(type, value);
    } catch (IllegalArgumentException e) {
      throw error(path, "contains an unsupported value: " + value);
    }
  }

  private static Map<String, Object> orderedMap(Object... values) {
    final var map = new LinkedHashMap<String, Object>();
    for (var i = 0; i < values.length; i += 2) {
      map.put((String) values[i], values[i + 1]);
    }
    return map;
  }

  private static IllegalArgumentException error(String path, String message) {
    return new IllegalArgumentException(path + " " + message);
  }
}
