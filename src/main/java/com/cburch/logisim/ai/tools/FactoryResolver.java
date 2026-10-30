/*
 * Logisim-evolution - digital logic design tool and simulator
 * Copyright by the Logisim-evolution developers
 *
 * https://github.com/logisim-evolution/
 *
 * This is free software released under GNU GPLv3 license
 */

package com.cburch.logisim.ai.tools;

import com.cburch.logisim.comp.ComponentFactory;
import com.cburch.logisim.file.LogisimFile;
import com.cburch.logisim.tools.AddTool;
import com.cburch.logisim.tools.Library;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Resolves user/model-facing component names against the project's loaded libraries. */
public final class FactoryResolver {
  private FactoryResolver() {}

  public static ComponentFactory resolve(LogisimFile file, String requestedName) {
    if (requestedName == null || requestedName.isBlank()) {
      throw new IllegalArgumentException("Component type is required");
    }
    final var candidates = new ArrayList<Candidate>();
    collect(file, candidates, new HashSet<>());
    final var exact = matches(candidates, requestedName.trim(), false);
    if (!exact.isEmpty()) return unique(exact, requestedName);
    final var normalized = matches(candidates, normalize(alias(requestedName)), true);
    if (!normalized.isEmpty()) return unique(normalized, requestedName);
    throw new IllegalArgumentException("Unknown or unloaded component type: " + requestedName);
  }

  private record Candidate(ComponentFactory factory, List<String> names) {}

  private static void collect(Library library, List<Candidate> candidates, Set<Library> visited) {
    if (!visited.add(library)) return;
    for (final var tool : library.getTools()) {
      if (!(tool instanceof AddTool addTool)) continue;
      try {
        final var factory = addTool.getFactory(true);
        if (factory == null) continue;
        candidates.add(
            new Candidate(
                factory, List.of(factory.getName(), factory.getDisplayName(), addTool.getName())));
      } catch (RuntimeException ignored) {
        // A lazy custom-library tool may fail to load. Continue looking through safe tools.
      }
    }
    for (final var child : library.getLibraries()) {
      collect(child, candidates, visited);
    }
  }

  private static Set<ComponentFactory> matches(
      List<Candidate> candidates, String wanted, boolean normalized) {
    final Set<ComponentFactory> result = Collections.newSetFromMap(new IdentityHashMap<>());
    for (final var candidate : candidates) {
      for (final var name : candidate.names()) {
        if ((normalized ? normalize(name) : name).equals(wanted)) result.add(candidate.factory());
      }
    }
    return result;
  }

  private static ComponentFactory unique(Set<ComponentFactory> matches, String name) {
    if (matches.size() != 1) {
      throw new IllegalArgumentException(
          "Ambiguous component type: " + name + ". Use an exact, unique factory name.");
    }
    return matches.iterator().next();
  }

  private static String alias(String name) {
    return switch (normalize(name)) {
      case "and", "andgate" -> "AND Gate";
      case "or", "orgate" -> "OR Gate";
      case "xor", "xorgate" -> "XOR Gate";
      case "xnor", "xnorgate" -> "XNOR Gate";
      case "nand", "nandgate" -> "NAND Gate";
      case "nor", "norgate" -> "NOR Gate";
      case "not", "notgate", "inverter" -> "NOT Gate";
      case "buf", "buffer" -> "Buffer";
      default -> name;
    };
  }

  private static String normalize(String value) {
    return value == null
        ? ""
        : Normalizer.normalize(value, Normalizer.Form.NFKC)
            .trim()
            .toLowerCase(Locale.ROOT)
            .replaceAll("\\s+", " ");
  }
}
