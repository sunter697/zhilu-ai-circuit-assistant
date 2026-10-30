/*
 * Logisim-evolution - digital logic design tool and simulator
 * Copyright by the Logisim-evolution developers
 *
 * https://github.com/logisim-evolution/
 *
 * This is free software released under GNU GPLv3 license
 */

package com.cburch.logisim.ai.transaction;

import com.cburch.logisim.circuit.Circuit;
import com.cburch.logisim.circuit.CircuitMutation;
import com.cburch.logisim.circuit.Wire;
import com.cburch.logisim.comp.ComponentFactory;
import com.cburch.logisim.data.Attribute;
import com.cburch.logisim.data.AttributeSet;
import com.cburch.logisim.data.Location;
import com.cburch.logisim.file.LogisimFile;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Project-independent blueprint for replacing one circuit's schematic contents. */
public final class CircuitSnapshot {
  public record ComponentData(
      FactoryIdentity identity, int x, int y, Map<String, String> attributes) {
    public ComponentData {
      attributes = Collections.unmodifiableMap(new LinkedHashMap<>(attributes));
    }
  }

  public record WireData(int x0, int y0, int x1, int y1, String busWidthPosition) {}

  private final String circuitName;
  private final List<ComponentData> components;
  private final List<WireData> wires;
  private final Map<String, String> circuitAttributes;

  private CircuitSnapshot(
      String circuitName,
      List<ComponentData> components,
      List<WireData> wires,
      Map<String, String> circuitAttributes) {
    this.circuitName = circuitName;
    this.components = List.copyOf(components);
    this.wires = List.copyOf(wires);
    this.circuitAttributes = Collections.unmodifiableMap(new LinkedHashMap<>(circuitAttributes));
  }

  public static CircuitSnapshot capture(Circuit circuit) {
    if (circuit.getProject() == null) {
      throw new IllegalArgumentException("Snapshot circuit must belong to a project");
    }
    return capture(circuit, circuit.getProject().getLogisimFile());
  }

  public static CircuitSnapshot capture(Circuit circuit, LogisimFile file) {
    final var components = new ArrayList<ComponentData>();
    for (final var component : circuit.getNonWires()) {
      final var factory = component.getFactory();
      if (factory == null)
        throw new IllegalArgumentException("Cannot snapshot a component without a factory");
      final var attrs = new LinkedHashMap<String, String>();
      final var set = component.getAttributeSet();
      if (set != null && set.getAttributes() != null) {
        for (final var attr : set.getAttributes()) {
          if (set.isToSave(attr))
            attrs.put(attr.getName(), CircuitDigest.attributeValue(set, attr));
        }
      }
      components.add(
          new ComponentData(
              FactoryIdentity.capture(file, factory),
              component.getLocation().getX(),
              component.getLocation().getY(),
              attrs));
    }
    final var wires = new ArrayList<WireData>();
    for (final var wire : circuit.getWires()) {
      wires.add(
          new WireData(
              wire.getEnd0().getX(),
              wire.getEnd0().getY(),
              wire.getEnd1().getX(),
              wire.getEnd1().getY(),
              Wire.BUS_WIDTH_POS_ATTR.toStandardString(circuit.getWireBusWidthPos(wire))));
    }
    final var circuitAttrs = new LinkedHashMap<String, String>();
    final var staticAttrs = circuit.getStaticAttributes();
    for (final var attr : staticAttrs.getAttributes()) {
      if (staticAttrs.isToSave(attr)) {
        circuitAttrs.put(attr.getName(), CircuitDigest.attributeValue(staticAttrs, attr));
      }
    }
    return new CircuitSnapshot(circuit.getName(), components, wires, circuitAttrs);
  }

  public String circuitName() {
    return circuitName;
  }

  public List<ComponentData> components() {
    return components;
  }

  public List<WireData> wires() {
    return wires;
  }

  /** Builds but does not execute a single replacement mutation. */
  public CircuitMutation buildMutation(Circuit target, LogisimFile targetFile) {
    if (!target.getName().equals(circuitName)) {
      throw new IllegalArgumentException(
          "Snapshot circuit " + circuitName + " does not match target " + target.getName());
    }
    final var mutation = new CircuitMutation(target);
    // Attribute changes must precede replacements. Logisim's mutation batching resets the
    // pending replacement map when an attribute change is encountered.
    addCircuitAttributes(mutation, target);
    mutation.clear();
    for (final var data : components) {
      final ComponentFactory factory = data.identity().resolve(targetFile);
      final var attrs = factory.createAttributeSet();
      applyAllAttributes(attrs, data.attributes());
      final var component =
          factory.createComponent(Location.create(data.x(), data.y(), true), attrs);
      mutation.add(component);
    }
    for (final var data : wires) {
      final var wire =
          Wire.create(
              Location.create(data.x0(), data.y0(), true),
              Location.create(data.x1(), data.y1(), true));
      mutation.add(wire);
      if (!data.busWidthPosition().equals("none")) {
        mutation.set(
            wire, Wire.BUS_WIDTH_POS_ATTR, Wire.BUS_WIDTH_POS_ATTR.parse(data.busWidthPosition()));
      }
    }
    return mutation;
  }

  void addCircuitAttributes(CircuitMutation mutation, Circuit target) {
    final var staticAttrs = target.getStaticAttributes();
    for (final var entry : circuitAttributes.entrySet()) {
      final var attr = find(staticAttrs, entry.getKey());
      if (attr == null) {
        throw new IllegalArgumentException("Cannot reproduce circuit attribute: " + entry.getKey());
      }
      if (entry.getValue().equals(CircuitDigest.attributeValue(staticAttrs, attr))) continue;
      mutation.setForCircuit(attr, attr.parse(entry.getValue()));
    }
  }

  private static void applyAllAttributes(AttributeSet attrs, Map<String, String> values) {
    final var pending = new LinkedHashMap<>(values);
    // Some Logisim attribute sets expose additional attributes after another attribute is set.
    for (var pass = 0; pass < 4 && !pending.isEmpty(); pass++) {
      var progress = false;
      final var iterator = pending.entrySet().iterator();
      while (iterator.hasNext()) {
        final var entry = iterator.next();
        final var attr = find(attrs, entry.getKey());
        if (attr == null) continue;
        try {
          setParsed(attrs, attr, entry.getValue());
          iterator.remove();
          progress = true;
        } catch (RuntimeException ignored) {
          // Retry after other dynamic attributes have been installed.
        }
      }
      if (!progress) break;
    }
    if (!pending.isEmpty()) {
      throw new IllegalArgumentException(
          "Cannot reproduce component attributes: " + pending.keySet());
    }
  }

  private static Attribute<?> find(AttributeSet attrs, String name) {
    if (attrs.getAttributes() == null) return null;
    for (final var attr : attrs.getAttributes()) {
      if (attr.getName().equals(name)) return attr;
    }
    return null;
  }

  @SuppressWarnings({"rawtypes", "unchecked"})
  private static void setParsed(AttributeSet attrs, Attribute<?> attr, String value) {
    attrs.setValue((Attribute) attr, ((Attribute) attr).parse(value));
  }
}
