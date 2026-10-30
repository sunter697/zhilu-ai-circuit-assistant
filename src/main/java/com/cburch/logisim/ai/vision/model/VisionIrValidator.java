/*
 * Logisim-evolution - digital logic design tool and simulator
 * Copyright by the Logisim-evolution developers
 *
 * https://github.com/logisim-evolution/
 *
 * This is free software released under GNU GPLv3 license
 */

package com.cburch.logisim.ai.vision.model;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Validates untrusted recognition output before it reaches circuit construction code. */
public final class VisionIrValidator {
  private VisionIrValidator() {}

  public static List<String> validate(VisionIr ir) {
    final var errors = new ArrayList<String>();
    if (ir == null) return List.of("Vision IR is required");
    if (!VisionIr.VERSION.equals(ir.schemaVersion())) {
      errors.add("Unsupported schemaVersion: " + ir.schemaVersion());
    }
    validateSource(ir.source(), errors);
    validateProfile(ir.profile(), errors);

    final var components = new LinkedHashMap<String, VisionIr.DetectedComponent>();
    for (var i = 0; i < ir.components().size(); i++) {
      final var component = ir.components().get(i);
      final var path = "components[" + i + "]";
      if (component.id() == null || component.id().isBlank()) {
        errors.add(path + ".id is required");
      } else if (components.putIfAbsent(component.id(), component) != null) {
        errors.add(path + ".id is duplicated: " + component.id());
      }
      if (component.kind() == null) errors.add(path + ".kind is required");
      validateBox(component.bodyBox(), path + ".bodyBox", ir.source(), errors);
      validatePoint(component.anchor(), path + ".anchor", errors);
      if (component.facing() == null) errors.add(path + ".facing is required");
      validateConfidence(component.confidence(), path + ".confidence", errors);
      if (component.kind() != null) {
        validateAttributes(component, path, errors);
        validatePorts(component, path, errors);
      }
    }
    if (components.isEmpty()) errors.add("At least one component is required");

    final var netIds = new HashSet<String>();
    final var usedPorts = new HashMap<String, String>();
    for (var i = 0; i < ir.nets().size(); i++) {
      validateNet(ir.nets().get(i), i, components, netIds, usedPorts, errors);
    }
    if (ir.nets().isEmpty()) errors.add("At least one net is required");
    for (var i = 0; i < ir.unresolved().size(); i++) {
      final var issue = ir.unresolved().get(i);
      final var path = "unresolved[" + i + "]";
      if (issue.code() == null || issue.code().isBlank()) errors.add(path + ".code is required");
      if (issue.message() == null || issue.message().isBlank()) {
        errors.add(path + ".message is required");
      }
      if (issue.severity() == null) errors.add(path + ".severity is required");
    }
    return List.copyOf(errors);
  }

  public static void validateOrThrow(VisionIr ir) {
    final var errors = validate(ir);
    if (!errors.isEmpty()) {
      throw new IllegalArgumentException("Invalid Vision IR: " + String.join("; ", errors));
    }
  }

  public static Set<String> expectedPortIds(VisionIr.DetectedComponent component) {
    final var result = new HashSet<String>();
    switch (component.kind()) {
      case INPUT_PIN -> result.add("out");
      case OUTPUT_PIN -> result.add("in");
      case NOT -> {
        result.add("out");
        result.add("in0");
      }
      default -> {
        result.add("out");
        final var inputs = component.kind().expectedInputCount(component.attributes());
        for (var i = 0; i < inputs; i++) result.add("in" + i);
      }
    }
    return Set.copyOf(result);
  }

  private static void validateSource(VisionIr.Source source, List<String> errors) {
    if (source == null) {
      errors.add("source is required");
      return;
    }
    if (source.sha256() == null || source.sha256().isBlank()) {
      errors.add("source.sha256 is required");
    }
    if (source.width() <= 0) errors.add("source.width must be positive");
    if (source.height() <= 0) errors.add("source.height must be positive");
  }

  private static void validateProfile(VisionIr.Profile profile, List<String> errors) {
    if (profile == null) {
      errors.add("profile is required");
      return;
    }
    if (profile.theme() == null || profile.theme().isBlank()) {
      errors.add("profile.theme is required");
    }
    if (profile.gateShape() == null || profile.gateShape().isBlank()) {
      errors.add("profile.gateShape is required");
    }
    if (!positiveFinite(profile.gridPixels())) {
      errors.add("profile.gridPixels must be positive and finite");
    }
    if (!positiveFinite(profile.scale())) {
      errors.add("profile.scale must be positive and finite");
    }
    if (!Double.isFinite(profile.offsetX()) || !Double.isFinite(profile.offsetY())) {
      errors.add("profile offsets must be finite");
    }
  }

  private static void validateAttributes(
      VisionIr.DetectedComponent component, String path, List<String> errors) {
    final var width = integerAttribute(component.attributes(), "bitWidth", 1, path, errors);
    if (width < 1 || width > 64) errors.add(path + ".attributes.bitWidth must be 1..64");
    final var inputs =
        integerAttribute(
            component.attributes(),
            "inputs",
            component.kind() == VisionIr.ComponentKind.NOT ? 1 : 2,
            path,
            errors);
    if (component.kind().isGate()) {
      if (component.kind() == VisionIr.ComponentKind.NOT && inputs != 1) {
        errors.add(path + ".attributes.inputs must be 1 for NOT");
      } else if (component.kind() != VisionIr.ComponentKind.NOT && (inputs < 2 || inputs > 64)) {
        errors.add(path + ".attributes.inputs must be 2..64");
      }
    }
    final var label = component.attributes().get("label");
    if (label != null && !(label instanceof String)) {
      errors.add(path + ".attributes.label must be a string");
    }
    final var size = component.attributes().get("size");
    if (size != null) {
      if (!(size instanceof String value)) {
        errors.add(path + ".attributes.size must be a string");
      } else if (!component.kind().isGate()) {
        errors.add(path + ".attributes.size is only valid for gates");
      } else {
        final var normalized = value.toUpperCase(java.util.Locale.ROOT);
        final var allowed =
            component.kind() == VisionIr.ComponentKind.NOT
                ? Set.of("NARROW", "WIDE")
                : Set.of("NARROW", "MEDIUM", "WIDE");
        if (!allowed.contains(normalized)) {
          errors.add(path + ".attributes.size is not supported for " + component.kind());
        }
      }
    }
  }

  private static int integerAttribute(
      Map<String, Object> attributes,
      String name,
      int defaultValue,
      String path,
      List<String> errors) {
    final var value = attributes.get(name);
    if (value == null) return defaultValue;
    if (value instanceof Number number && Math.rint(number.doubleValue()) == number.doubleValue()) {
      return number.intValue();
    }
    errors.add(path + ".attributes." + name + " must be an integer");
    return defaultValue;
  }

  private static void validatePorts(
      VisionIr.DetectedComponent component, String path, List<String> errors) {
    final var expected = expectedPortIds(component);
    final var ids = new HashSet<String>();
    for (var i = 0; i < component.ports().size(); i++) {
      final var port = component.ports().get(i);
      final var portPath = path + ".ports[" + i + "]";
      if (port.id() == null || port.id().isBlank()) {
        errors.add(portPath + ".id is required");
      } else {
        if (!ids.add(port.id())) errors.add(portPath + ".id is duplicated: " + port.id());
        if (!expected.contains(port.id())) {
          errors.add(portPath + ".id is not valid for " + component.kind() + ": " + port.id());
        }
      }
      if (port.direction() == null) errors.add(portPath + ".direction is required");
      validatePoint(port.point(), portPath + ".point", errors);
    }
  }

  private static void validateNet(
      VisionIr.DetectedNet net,
      int index,
      Map<String, VisionIr.DetectedComponent> components,
      Set<String> netIds,
      Map<String, String> usedPorts,
      List<String> errors) {
    final var path = "nets[" + index + "]";
    if (net.id() == null || net.id().isBlank()) {
      errors.add(path + ".id is required");
    } else if (!netIds.add(net.id())) {
      errors.add(path + ".id is duplicated: " + net.id());
    }
    if (net.endpoints().size() < 2) errors.add(path + " must contain at least two endpoints");
    final var localEndpoints = new HashSet<String>();
    for (var i = 0; i < net.endpoints().size(); i++) {
      final var endpoint = net.endpoints().get(i);
      final var endpointPath = path + ".endpoints[" + i + "]";
      final var component = components.get(endpoint.componentId());
      if (component == null) {
        errors.add(endpointPath + " references an unknown component: " + endpoint.componentId());
        continue;
      }
      if (!expectedPortIds(component).contains(endpoint.portId())) {
        errors.add(
            endpointPath
                + " references an unknown port on "
                + endpoint.componentId()
                + ": "
                + endpoint.portId());
        continue;
      }
      final var key = endpoint.componentId() + ":" + endpoint.portId();
      if (!localEndpoints.add(key)) errors.add(endpointPath + " is duplicated: " + key);
      final var previous = usedPorts.putIfAbsent(key, net.id());
      if (previous != null && !previous.equals(net.id())) {
        errors.add(endpointPath + " is already assigned to net " + previous);
      }
    }
    if (net.segments().isEmpty()) errors.add(path + " must contain at least one segment");
    for (var i = 0; i < net.segments().size(); i++) {
      final var segment = net.segments().get(i);
      final var segmentPath = path + ".segments[" + i + "]";
      validatePoint(segment.start(), segmentPath + ".start", errors);
      validatePoint(segment.end(), segmentPath + ".end", errors);
      if (segment.start() != null
          && segment.end() != null
          && segment.start().x() != segment.end().x()
          && segment.start().y() != segment.end().y()) {
        errors.add(segmentPath + " must be horizontal or vertical");
      }
      if (segment.start() != null && segment.start().equals(segment.end())) {
        errors.add(segmentPath + " must not have zero length");
      }
    }
    for (var i = 0; i < net.junctions().size(); i++) {
      validatePoint(net.junctions().get(i), path + ".junctions[" + i + "]", errors);
    }
    validateConfidence(net.confidence(), path + ".confidence", errors);
  }

  private static void validateBox(
      VisionIr.Box box, String path, VisionIr.Source source, List<String> errors) {
    if (box == null) {
      errors.add(path + " is required");
      return;
    }
    if (!Double.isFinite(box.x0())
        || !Double.isFinite(box.y0())
        || !Double.isFinite(box.x1())
        || !Double.isFinite(box.y1())) {
      errors.add(path + " must contain finite coordinates");
      return;
    }
    if (box.x0() >= box.x1() || box.y0() >= box.y1()) {
      errors.add(path + " must have positive width and height");
    }
    if (source != null
        && (box.x0() < 0
            || box.y0() < 0
            || box.x1() > source.width()
            || box.y1() > source.height())) {
      errors.add(path + " must be inside the source image");
    }
  }

  private static void validatePoint(VisionIr.Point point, String path, List<String> errors) {
    if (point == null) {
      errors.add(path + " is required");
    } else if (!Double.isFinite(point.x()) || !Double.isFinite(point.y())) {
      errors.add(path + " must contain finite coordinates");
    }
  }

  private static void validateConfidence(double confidence, String path, List<String> errors) {
    if (!Double.isFinite(confidence) || confidence < 0 || confidence > 1) {
      errors.add(path + " must be between 0 and 1");
    }
  }

  private static boolean positiveFinite(double value) {
    return Double.isFinite(value) && value > 0;
  }
}
