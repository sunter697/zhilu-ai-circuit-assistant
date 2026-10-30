/*
 * Logisim-evolution - digital logic design tool and simulator
 * Copyright by the Logisim-evolution developers
 *
 * https://github.com/logisim-evolution/
 *
 * This is free software released under GNU GPLv3 license
 */

package com.cburch.logisim.ai.transaction;

import com.cburch.logisim.ai.util.SimpleJson;
import com.cburch.logisim.circuit.Circuit;
import com.cburch.logisim.circuit.Wire;
import com.cburch.logisim.comp.Component;
import com.cburch.logisim.data.Attribute;
import com.cburch.logisim.data.AttributeSet;
import com.cburch.logisim.file.LogisimFile;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.TreeMap;

/** Deterministic structural fingerprint used to detect concurrent circuit edits. */
public final class CircuitDigest {
  private CircuitDigest() {}

  public static String sha256(Circuit circuit) {
    return sha256Text(canonical(circuit));
  }

  /**
   * Conservative project-level structural digest. Any circuit change anywhere in the open project
   * invalidates an Agent workspace review, preventing validation against stale subcircuits.
   */
  public static String projectSha256(LogisimFile file) {
    if (file == null) throw new IllegalArgumentException("Logisim file is required");
    final var sections = new ArrayList<String>();
    final var main = file.getMainCircuit();
    sections.add("main=" + SimpleJson.stringify(main == null ? "" : main.getName()));
    final var libraries =
        file.getLibraries().stream()
            .map(library -> library.getName() + ":" + library.getClass().getName())
            .sorted()
            .toList();
    sections.add("libraries=" + SimpleJson.stringify(libraries));
    sections.add(
        "options=" + SimpleJson.stringify(attributes(file.getOptions().getAttributeSet())));
    for (final var circuit : file.getCircuits()) {
      sections.add(canonical(circuit, file));
    }
    sections.subList(1, sections.size()).sort(Comparator.naturalOrder());
    return sha256Text(String.join("\n---circuit---\n", sections));
  }

  public static String canonical(Circuit circuit) {
    return canonical(
        circuit, circuit.getProject() == null ? null : circuit.getProject().getLogisimFile());
  }

  private static String canonical(Circuit circuit, LogisimFile file) {
    final var lines = new ArrayList<String>();
    lines.add("circuit=" + SimpleJson.stringify(circuit.getName()));
    lines.add(
        "circuitAttributes=" + SimpleJson.stringify(attributes(circuit.getStaticAttributes())));
    for (final var component : circuit.getNonWires()) {
      lines.add(componentLine(component, file));
    }
    for (final var wire : circuit.getWires()) {
      lines.add(wireLine(circuit, wire));
    }
    lines.subList(1, lines.size()).sort(Comparator.naturalOrder());
    return String.join("\n", lines);
  }

  private static String componentLine(Component component, LogisimFile file) {
    final var factory = component.getFactory();
    Object identity =
        factory == null ? "<none>" : List.of(factory.getClass().getName(), factory.getName());
    if (file != null && factory != null) {
      try {
        identity = FactoryIdentity.capture(file, factory).canonicalParts();
      } catch (IllegalArgumentException ignored) {
        // Read-only context can describe unsupported factories. Workspace creation rejects them.
      }
    }
    return "component|"
        + SimpleJson.stringify(
            List.of(
                identity,
                component.getLocation().getX(),
                component.getLocation().getY(),
                attributes(component.getAttributeSet())));
  }

  private static TreeMap<String, String> attributes(AttributeSet attrs) {
    final var values = new TreeMap<String, String>();
    if (attrs != null && attrs.getAttributes() != null) {
      for (final var attr : attrs.getAttributes()) {
        if (attrs.isToSave(attr)) values.put(attr.getName(), attributeValue(attrs, attr));
      }
    }
    return values;
  }

  private static String wireLine(Circuit circuit, Wire wire) {
    final var a = wire.getEnd0();
    final var b = wire.getEnd1();
    final var position = circuit.getWireBusWidthPos(wire);
    return "wire|"
        + a.getX()
        + ','
        + a.getY()
        + '|'
        + b.getX()
        + ','
        + b.getY()
        + '|'
        + Wire.BUS_WIDTH_POS_ATTR.toStandardString(
            position == null ? Wire.BUS_WIDTH_POS_NONE : position);
  }

  @SuppressWarnings({"rawtypes", "unchecked"})
  public static String attributeValue(AttributeSet attrs, Attribute<?> attr) {
    try {
      final Object value = attrs.getValue((Attribute) attr);
      if (value == null) return "";
      return ((Attribute) attr).toStandardString(value);
    } catch (RuntimeException e) {
      return String.valueOf(attrs.getValue((Attribute) attr));
    }
  }

  public static String sha256Text(String text) {
    try {
      final var digest =
          MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
      final var out = new StringBuilder(digest.length * 2);
      for (final byte value : digest) out.append(String.format("%02x", value));
      return out.toString();
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is unavailable", e);
    }
  }
}
