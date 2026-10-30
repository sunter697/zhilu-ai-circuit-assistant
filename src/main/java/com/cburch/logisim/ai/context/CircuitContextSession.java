/*
 * Logisim-evolution - digital logic design tool and simulator
 * Copyright by the Logisim-evolution developers
 *
 * https://github.com/logisim-evolution/
 *
 * This is free software released under GNU GPLv3 license
 */

package com.cburch.logisim.ai.context;

import com.cburch.logisim.circuit.Circuit;
import com.cburch.logisim.circuit.Wire;
import com.cburch.logisim.comp.Component;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Structured LLM-facing circuit context plus task-local object references. */
public final class CircuitContextSession {
  private final Circuit circuit;
  private final Map<String, Object> context;
  private final Map<String, Component> components;
  private final Map<String, Wire> wires;

  CircuitContextSession(
      Circuit circuit,
      Map<String, Object> context,
      Map<String, Component> components,
      Map<String, Wire> wires) {
    this.circuit = circuit;
    this.context = Collections.unmodifiableMap(new LinkedHashMap<>(context));
    this.components = Collections.unmodifiableMap(new LinkedHashMap<>(components));
    this.wires = Collections.unmodifiableMap(new LinkedHashMap<>(wires));
  }

  public Circuit circuit() {
    return circuit;
  }

  public Map<String, Object> context() {
    return context;
  }

  public Map<String, Component> components() {
    return components;
  }

  public Map<String, Wire> wires() {
    return wires;
  }

  public Component requireComponent(String id) {
    final var component = components.get(id);
    if (component == null) throw new IllegalArgumentException("Unknown component id: " + id);
    return component;
  }

  public Wire requireWire(String id) {
    final var wire = wires.get(id);
    if (wire == null) throw new IllegalArgumentException("Unknown wire id: " + id);
    return wire;
  }
}
