/*
 * Logisim-evolution - digital logic design tool and simulator
 * Copyright by the Logisim-evolution developers
 *
 * https://github.com/logisim-evolution/
 *
 * This is free software released under GNU GPLv3 license
 */

package com.cburch.logisim.ai.context;

import com.cburch.logisim.circuit.Wire;
import com.cburch.logisim.comp.Component;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/** Assigns stable task-local references to live component and wire objects. */
public final class ComponentRefRegistry {
  private final IdentityHashMap<Component, String> componentIds = new IdentityHashMap<>();
  private final IdentityHashMap<Wire, String> wireIds = new IdentityHashMap<>();
  private int nextComponent = 1;
  private int nextWire = 1;

  public String idFor(Component component) {
    return componentIds.computeIfAbsent(
        component, key -> String.format("cmp_%03d", nextComponent++));
  }

  public String idFor(Wire wire) {
    return wireIds.computeIfAbsent(wire, key -> String.format("wire_%03d", nextWire++));
  }

  public Map<String, Component> currentComponents(Iterable<? extends Component> current) {
    final var result = new LinkedHashMap<String, Component>();
    for (final var component : current) result.put(idFor(component), component);
    return result;
  }

  public Map<String, Wire> currentWires(Iterable<Wire> current) {
    final var result = new LinkedHashMap<String, Wire>();
    for (final var wire : current) result.put(idFor(wire), wire);
    return result;
  }
}
