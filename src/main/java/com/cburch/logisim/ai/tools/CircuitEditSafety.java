/*
 * Logisim-evolution - digital logic design tool and simulator
 * Copyright by the Logisim-evolution developers
 *
 * https://github.com/logisim-evolution/
 *
 * This is free software released under GNU GPLv3 license
 */

package com.cburch.logisim.ai.tools;

import com.cburch.logisim.circuit.Circuit;
import com.cburch.logisim.comp.Component;
import com.cburch.logisim.comp.EndData;
import com.cburch.logisim.data.Bounds;

/** Prevents implicit electrical connections when components or their geometry change. */
final class CircuitEditSafety {
  private CircuitEditSafety() {}

  static void requireSafe(Circuit circuit, Component candidate, Component previous) {
    if (previous != null
        && (bidirectional(previous) || bidirectional(candidate))
        && !equivalentAttributes(previous, candidate)) {
      throw new IllegalArgumentException(
          "Changing a bidirectional component can merge remote or bit-level nets. Tunnel/splitter"
              + " edits require a dedicated bit-level tool and are not supported yet.");
    }
    for (final var other : circuit.getNonWires()) {
      if (other != previous && overlaps(candidate.getBounds(), other.getBounds())) {
        throw new IllegalArgumentException("Component bodies overlap; choose a clear placement.");
      }
    }
    for (var index = 0; index < candidate.getEnds().size(); index++) {
      final var end = candidate.getEnd(index);
      final var old =
          previous != null && index < previous.getEnds().size() ? previous.getEnd(index) : null;
      if (!same(end, old) && connected(circuit, end, previous)) {
        throw new IllegalArgumentException(
            "A new or changed port would touch an existing net. "
                + "Use connect after placing the component in clear space.");
      }
    }
    if (previous != null) {
      for (var index = 0; index < previous.getEnds().size(); index++) {
        final var old = previous.getEnd(index);
        final var replacement = index < candidate.getEnds().size() ? candidate.getEnd(index) : null;
        if (!same(old, replacement) && connected(circuit, old, previous)) {
          throw new IllegalArgumentException(
              "A connected port would change location, width or direction. "
                  + "Disconnect affected ports before changing the component.");
        }
      }
    }
  }

  private static boolean bidirectional(Component component) {
    return component.getEnds().stream().anyMatch(end -> end.getType() == EndData.INPUT_OUTPUT);
  }

  private static boolean equivalentAttributes(Component a, Component b) {
    if (a.getFactory() != b.getFactory()) return false;
    final var left = a.getAttributeSet();
    final var right = b.getAttributeSet();
    if (!left.getAttributes().equals(right.getAttributes())) return false;
    for (var attribute : left.getAttributes()) {
      if (!com.cburch.logisim.ai.transaction.CircuitDigest.attributeValue(left, attribute)
          .equals(com.cburch.logisim.ai.transaction.CircuitDigest.attributeValue(right, attribute)))
        return false;
    }
    return true;
  }

  private static boolean same(EndData a, EndData b) {
    return a != null
        && b != null
        && a.getLocation().equals(b.getLocation())
        && a.getWidth().equals(b.getWidth())
        && a.getType() == b.getType();
  }

  private static boolean connected(Circuit circuit, EndData end, Component ignored) {
    if (circuit.getWires().stream().anyMatch(wire -> wire.contains(end.getLocation()))) return true;
    return circuit.getNonWires().stream()
        .filter(component -> component != ignored)
        .flatMap(component -> component.getEnds().stream())
        .anyMatch(other -> other.getLocation().equals(end.getLocation()));
  }

  private static boolean overlaps(Bounds a, Bounds b) {
    return Math.max(a.getX(), b.getX())
            < Math.min((long) a.getX() + a.getWidth(), (long) b.getX() + b.getWidth())
        && Math.max(a.getY(), b.getY())
            < Math.min((long) a.getY() + a.getHeight(), (long) b.getY() + b.getHeight());
  }
}
