/*
 * Logisim-evolution - digital logic design tool and simulator
 * Copyright by the Logisim-evolution developers
 *
 * https://github.com/logisim-evolution/
 *
 * This is free software released under GNU GPLv3 license
 */

package com.cburch.logisim.ai.context;

import com.cburch.logisim.ai.transaction.CircuitDigest;
import com.cburch.logisim.analyze.model.AnalyzerModel;
import com.cburch.logisim.circuit.Analyze;
import com.cburch.logisim.circuit.Circuit;
import com.cburch.logisim.circuit.Wire;
import com.cburch.logisim.comp.Component;
import com.cburch.logisim.comp.EndData;
import com.cburch.logisim.instance.Instance;
import com.cburch.logisim.instance.StdAttr;
import com.cburch.logisim.std.wiring.Pin;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Converts the live Logisim graph into a bounded, explicit structure for an LLM. */
public final class CircuitContextExtractor {
  private final ComponentRefRegistry refs = new ComponentRefRegistry();

  public CircuitContextSession extract(Circuit circuit) {
    if (circuit == null) throw new IllegalArgumentException("Circuit is required");
    final var componentMap = new LinkedHashMap<String, Component>();
    final var wireMap = new LinkedHashMap<String, Wire>();
    final var components = new ArrayList<>(circuit.getNonWires());
    components.sort(
        Comparator.comparingInt((Component c) -> c.getLocation().getY())
            .thenComparingInt(c -> c.getLocation().getX())
            .thenComparing(c -> c.getFactory() == null ? "" : c.getFactory().getName()));

    final var componentRows = new ArrayList<Map<String, Object>>();
    final var ioRows = new ArrayList<Map<String, Object>>();
    for (final var component : components) {
      final var id = refs.idFor(component);
      componentMap.put(id, component);
      final var row = componentRow(id, component);
      componentRows.add(row);
      if (component.getFactory() == Pin.FACTORY) ioRows.add(ioRow(id, component));
    }

    final var wires = new ArrayList<>(circuit.getWires());
    wires.sort(
        Comparator.comparingInt((Wire w) -> w.getEnd0().getY())
            .thenComparingInt(w -> w.getEnd0().getX())
            .thenComparingInt(w -> w.getEnd1().getY())
            .thenComparingInt(w -> w.getEnd1().getX()));
    final var wireRows = new ArrayList<Map<String, Object>>();
    for (final var wire : wires) {
      final var id = refs.idFor(wire);
      wireMap.put(id, wire);
      final var row = new LinkedHashMap<String, Object>();
      row.put("id", id);
      row.put("from", List.of(wire.getEnd0().getX(), wire.getEnd0().getY()));
      row.put("to", List.of(wire.getEnd1().getX(), wire.getEnd1().getY()));
      wireRows.add(row);
    }

    final var root = new LinkedHashMap<String, Object>();
    root.put("contextVersion", "logisim-ai-context/0.1");
    root.put("circuit", circuit.getName());
    root.put("structuralDigest", CircuitDigest.sha256(circuit));
    root.put("customAppearance", !circuit.getAppearance().isDefaultAppearance());
    root.put(
        "bounds",
        Map.of(
            "x", circuit.getBounds().getX(),
            "y", circuit.getBounds().getY(),
            "width", circuit.getBounds().getWidth(),
            "height", circuit.getBounds().getHeight()));
    root.put("io", ioRows);
    root.put("components", componentRows);
    root.put("wires", wireRows);
    root.put("booleanAnalysis", analyzeBooleanLogic(circuit));
    root.put(
        "completeness",
        Map.of(
            "structure",
            "COMPLETE",
            "booleanAnalysis",
            ((Map<?, ?>) root.get("booleanAnalysis")).get("status")));
    return new CircuitContextSession(circuit, root, componentMap, wireMap);
  }

  private static Map<String, Object> componentRow(String id, Component component) {
    final var row = new LinkedHashMap<String, Object>();
    row.put("id", id);
    row.put(
        "type", component.getFactory() == null ? "<unknown>" : component.getFactory().getName());
    row.put(
        "displayName",
        component.getFactory() == null ? "<unknown>" : component.getFactory().getDisplayName());
    row.put("location", List.of(component.getLocation().getX(), component.getLocation().getY()));
    final var attrs = new LinkedHashMap<String, Object>();
    final var attrSet = component.getAttributeSet();
    if (attrSet != null && attrSet.getAttributes() != null) {
      for (final var attr : attrSet.getAttributes()) {
        if (attrSet.isToSave(attr))
          attrs.put(attr.getName(), CircuitDigest.attributeValue(attrSet, attr));
      }
    }
    row.put("attributes", attrs);
    final var ports = new ArrayList<Map<String, Object>>();
    for (var p = 0; p < component.getEnds().size(); p++) {
      final EndData end = component.getEnd(p);
      final var port = new LinkedHashMap<String, Object>();
      port.put("id", "p" + p);
      port.put("index", p);
      port.put("location", List.of(end.getLocation().getX(), end.getLocation().getY()));
      port.put("width", end.getWidth().getWidth());
      port.put(
          "direction",
          end.isInput() && end.isOutput() ? "inout" : end.isInput() ? "input" : "output");
      ports.add(port);
    }
    row.put("ports", ports);
    return row;
  }

  private static Map<String, Object> ioRow(String id, Component component) {
    final var row = new LinkedHashMap<String, Object>();
    row.put("componentId", id);
    final var instance = Instance.getInstanceFor(component);
    if (instance == null) {
      row.put("direction", "unknown");
      return row;
    }
    row.put("label", safeLabel(instance));
    row.put("width", instance.getAttributeValue(StdAttr.WIDTH).getWidth());
    row.put("direction", Pin.FACTORY.isInputPin(instance) ? "input" : "output");
    return row;
  }

  private static String safeLabel(Instance instance) {
    try {
      final var label = instance.getAttributeValue(StdAttr.LABEL);
      return label == null ? "" : label;
    } catch (RuntimeException e) {
      return "";
    }
  }

  private static Map<String, Object> analyzeBooleanLogic(Circuit circuit) {
    final var result = new LinkedHashMap<String, Object>();
    try {
      final var model = new AnalyzerModel();
      Analyze.computeExpression(model, circuit, Analyze.getPinLabels(circuit));
      final var expressions = new LinkedHashMap<String, Object>();
      for (final var output : model.getOutputs().bits) {
        expressions.put(output, model.getOutputExpressions().getExpressionString(output));
      }
      result.put("status", "SUPPORTED");
      result.put("inputs", new ArrayList<>(model.getInputs().bits));
      result.put("outputs", new ArrayList<>(model.getOutputs().bits));
      result.put("expressions", expressions);
    } catch (Exception e) {
      result.put("status", "PARTIAL_OR_UNSUPPORTED");
      result.put("reason", e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
    }
    return result;
  }
}
