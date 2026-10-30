/*
 * Logisim-evolution - digital logic design tool and simulator
 * Copyright by the Logisim-evolution developers
 *
 * https://github.com/logisim-evolution/
 *
 * This is free software released under GNU GPLv3 license
 */

package com.cburch.logisim.ai.transaction;

import com.cburch.logisim.circuit.SubcircuitFactory;
import com.cburch.logisim.comp.ComponentFactory;
import com.cburch.logisim.file.LogisimFile;
import com.cburch.logisim.tools.AddTool;
import com.cburch.logisim.tools.Library;
import java.util.ArrayList;
import java.util.List;

/** Exact identity used by snapshots; model aliases and display names are never used here. */
public record FactoryIdentity(List<String> libraryPath, String factoryName, boolean subcircuit) {
  public FactoryIdentity {
    libraryPath = List.copyOf(libraryPath);
    if (factoryName == null || factoryName.isBlank()) {
      throw new IllegalArgumentException("A factory identity requires an exact name");
    }
  }

  public static FactoryIdentity capture(LogisimFile file, ComponentFactory factory) {
    if (factory instanceof SubcircuitFactory sub && file.contains(sub.getSubcircuit())) {
      return new FactoryIdentity(List.of(), sub.getSubcircuit().getName(), true);
    }
    final var matches = new ArrayList<FactoryIdentity>();
    for (final var library : file.getLibraries()) {
      locate(library, List.of(library.getName()), factory, matches);
    }
    if (matches.size() != 1) {
      throw new IllegalArgumentException(
          "Component factory must belong to exactly one loaded library: " + factory.getName());
    }
    return matches.getFirst();
  }

  private static void locate(
      Library library, List<String> path, ComponentFactory wanted, List<FactoryIdentity> matches) {
    for (final var tool : library.getTools()) {
      if (tool instanceof AddTool add && add.getFactory() == wanted) {
        matches.add(new FactoryIdentity(path, wanted.getName(), false));
      }
    }
    for (final var child : library.getLibraries()) {
      final var childPath = new ArrayList<>(path);
      childPath.add(child.getName());
      locate(child, childPath, wanted, matches);
    }
  }

  public ComponentFactory resolve(LogisimFile file) {
    if (subcircuit) {
      return file.getCircuits().stream()
          .filter(circuit -> circuit.getName().equals(factoryName))
          .findFirst()
          .orElseThrow(() -> new IllegalArgumentException("Missing subcircuit: " + factoryName))
          .getSubcircuitFactory();
    }
    Library library = file;
    for (final var name : libraryPath) {
      final var matches =
          library.getLibraries().stream().filter(child -> child.getName().equals(name)).toList();
      if (matches.size() != 1) {
        throw new IllegalArgumentException("Missing or ambiguous snapshot library: " + libraryPath);
      }
      library = matches.getFirst();
    }
    final var factories = new ArrayList<ComponentFactory>();
    for (final var tool : library.getTools()) {
      if (tool instanceof AddTool add && add.getFactory().getName().equals(factoryName)) {
        factories.add(add.getFactory());
      }
    }
    if (factories.size() != 1) {
      throw new IllegalArgumentException("Missing or ambiguous snapshot factory: " + factoryName);
    }
    return factories.getFirst();
  }

  public List<Object> canonicalParts() {
    return List.of(subcircuit ? "project-subcircuit" : "library", libraryPath, factoryName);
  }
}
