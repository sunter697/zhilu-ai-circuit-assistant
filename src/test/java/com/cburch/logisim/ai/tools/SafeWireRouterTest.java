/*
 * Logisim-evolution - digital logic design tool and simulator
 * Copyright by the Logisim-evolution developers
 *
 * https://github.com/logisim-evolution/
 *
 * This is free software released under GNU GPLv3 license
 */

package com.cburch.logisim.ai.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.cburch.logisim.ai.transaction.CircuitDigest;
import com.cburch.logisim.ai.validation.ValidationEngine;
import com.cburch.logisim.ai.validation.ValidationStatus;
import com.cburch.logisim.circuit.Circuit;
import com.cburch.logisim.circuit.CircuitMutation;
import com.cburch.logisim.circuit.Wire;
import com.cburch.logisim.comp.Component;
import com.cburch.logisim.data.Attribute;
import com.cburch.logisim.data.Location;
import com.cburch.logisim.file.Loader;
import com.cburch.logisim.file.LogisimFile;
import com.cburch.logisim.proj.Project;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SafeWireRouterTest {
  private static final class Fixture implements AutoCloseable {
    private final LogisimFile file;
    private final Project project;
    private final Circuit circuit;

    private Fixture() {
      final var loader = new Loader(null);
      file = LogisimFile.createNew(loader, null);
      file.addLibrary(loader.loadLibrary("#Wiring"));
      file.addLibrary(loader.loadLibrary("#Arithmetic"));
      project = new Project(file);
      circuit = project.getCurrentCircuit();
      circuit.setProject(project);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private Component add(String type, int x, int y, Map<String, String> values) {
      final var factory = FactoryResolver.resolve(file, type);
      final var attrs = factory.createAttributeSet();
      for (final var value : values.entrySet()) {
        final Attribute attr =
            attrs.getAttributes().stream()
                .filter(a -> a.getName().equals(value.getKey()))
                .findFirst()
                .orElseThrow();
        attrs.setValue(attr, attr.parse(value.getValue()));
      }
      final var component = factory.createComponent(Location.create(x, y, true), attrs);
      final var mutation = new CircuitMutation(circuit);
      mutation.add(component);
      mutation.execute();
      return component;
    }

    private Component pin(String name, int x, int y, boolean output, int width) {
      return add(
          "Pin",
          x,
          y,
          Map.of(
              "label",
              name,
              "type",
              output ? "output" : "input",
              "width",
              Integer.toString(width)));
    }

    private List<Wire> connect(Component from, int fromPort, Component to, int toPort) {
      final var result = SafeWireRouter.route(circuit, from.getEnd(fromPort), to.getEnd(toPort));
      final var mutation = new CircuitMutation(circuit);
      for (final var wire : result) mutation.add(wire);
      mutation.execute();
      return result;
    }

    @Override
    public void close() {
      project.getSimulator().shutDown();
      file.stopAutosaveThread(false);
    }
  }

  @Test
  void trappedLoggedMultiplierPortIsRejectedWithoutJoiningOtherNets() {
    try (var f = new Fixture()) {
      final var multiplier =
          f.add("Multiplier", 100, 100, Map.of("width", "4", "mode", "unsigned"));
      final var a = f.pin("A", 200, 120, false, 4);
      final var b = f.pin("B", 300, 120, false, 4);
      final var low = f.pin("P2", 400, 120, true, 4);
      final var high = f.pin("P4", 500, 120, true, 4);
      f.connect(a, 0, multiplier, 0);
      f.connect(b, 0, multiplier, 1);
      final var before = CircuitDigest.canonical(f.circuit);
      assertThrows(IllegalArgumentException.class, () -> f.connect(multiplier, 2, low, 0));
      assertEquals(before, CircuitDigest.canonical(f.circuit));
    }
  }

  @Test
  void rebuiltMultiplierLayoutMatchesAll256Products() {
    try (var f = new Fixture()) {
      final var multiplier =
          f.add("Multiplier", 100, 100, Map.of("width", "4", "mode", "unsigned"));
      final var a = f.pin("A", 20, 50, false, 4);
      final var b = f.pin("B", 20, 150, false, 4);
      final var low = f.pin("P2", 300, 100, true, 4);
      final var high = f.pin("P4", 300, 160, true, 4);
      f.connect(a, 0, multiplier, 0);
      f.connect(b, 0, multiplier, 1);
      f.connect(multiplier, 2, low, 0);
      f.connect(multiplier, 4, high, 0);
      final var rows = new ArrayList<List<String>>();
      for (int left = 0; left < 16; left++) {
        for (int right = 0; right < 16; right++) {
          final var row = new ArrayList<String>();
          bits(row, left);
          bits(row, right);
          bits(row, left * right);
          bits(row, (left * right) >>> 4);
          rows.add(row);
        }
      }
      final var evidence =
          new ValidationEngine()
              .verifyTruthTable(
                  f.project,
                  f.circuit,
                  List.of("A[3]", "A[2]", "A[1]", "A[0]", "B[3]", "B[2]", "B[1]", "B[0]"),
                  List.of("P2[3]", "P2[2]", "P2[1]", "P2[0]", "P4[3]", "P4[2]", "P4[1]", "P4[0]"),
                  rows);
      assertEquals(ValidationStatus.PASS, evidence.status(), evidence.toMap().toString());
    }
  }

  @Test
  void routesAroundAnUnrelatedWireAndPortWithoutChangingTheirBehavior() {
    try (var f = new Fixture()) {
      final var a = f.pin("A", 20, 100, false, 1);
      final var b = f.pin("B", 100, 50, false, 1);
      final var x = f.pin("X", 200, 100, true, 1);
      final var y = f.pin("Y", 100, 150, true, 1);
      f.connect(b, 0, y, 0);
      f.connect(a, 0, x, 0);
      final var evidence =
          new ValidationEngine()
              .verifyTruthTable(
                  f.project,
                  f.circuit,
                  List.of("B", "A"),
                  List.of("X", "Y"),
                  List.of(
                      List.of("0", "0", "0", "0"),
                      List.of("0", "1", "1", "0"),
                      List.of("1", "0", "0", "1"),
                      List.of("1", "1", "1", "1")));
      assertEquals(ValidationStatus.PASS, evidence.status(), evidence.toMap().toString());
    }
  }

  @Test
  void secondDriverAndWidthMismatchAreRejectedWithoutMutation() {
    try (var f = new Fixture()) {
      final var a = f.pin("A", 20, 50, false, 1);
      final var b = f.pin("B", 20, 100, false, 1);
      final var x = f.pin("X", 200, 50, true, 1);
      final var wide = f.pin("Wide", 200, 200, true, 4);
      f.connect(a, 0, x, 0);
      final var before = CircuitDigest.canonical(f.circuit);
      assertThrows(IllegalArgumentException.class, () -> f.connect(b, 0, x, 0));
      assertThrows(IllegalArgumentException.class, () -> f.connect(a, 0, b, 0));
      assertThrows(IllegalArgumentException.class, () -> f.connect(a, 0, wide, 0));
      assertEquals(before, CircuitDigest.canonical(f.circuit));
    }
  }

  @Test
  void sameNetIsIdempotentAndSourceCanFanOut() {
    try (var f = new Fixture()) {
      final var a = f.pin("A", 20, 50, false, 1);
      final var x = f.pin("X", 200, 50, true, 1);
      final var y = f.pin("Y", 200, 100, true, 1);
      f.connect(a, 0, x, 0);
      final var before = CircuitDigest.canonical(f.circuit);
      assertTrue(f.connect(a, 0, x, 0).isEmpty());
      assertEquals(before, CircuitDigest.canonical(f.circuit));
      f.connect(a, 0, y, 0);
      final var evidence =
          new ValidationEngine()
              .verifyTruthTable(
                  f.project,
                  f.circuit,
                  List.of("A"),
                  List.of("X", "Y"),
                  List.of(List.of("0", "0", "0"), List.of("1", "1", "1")));
      assertEquals(ValidationStatus.PASS, evidence.status(), evidence.toMap().toString());
    }
  }

  @Test
  void reconnectContinuesFromNearestExistingNetEndpoint() {
    try (var f = new Fixture()) {
      final var source = f.pin("A", 20, 50, false, 1);
      final var destination = f.pin("Y", 110, 100, true, 1);
      final var corner = Location.create(100, 50, true);
      final var terminal = Location.create(100, 100, true);
      final var mutation = new CircuitMutation(f.circuit);
      mutation.add(Wire.create(source.getEnd(0).getLocation(), corner));
      mutation.add(Wire.create(corner, terminal));
      mutation.execute();

      final var routed =
          SafeWireRouter.route(f.circuit, source.getEnd(0), destination.getEnd(0));

      assertEquals(
          List.of(Wire.create(terminal, destination.getEnd(0).getLocation())), routed);
    }
  }

  @Test
  void enclosedDestinationAndBidirectionalNetsAreRejectedWithoutMutation() {
    try (var f = new Fixture()) {
      final var a = f.pin("A", 20, 50, false, 1);
      final var x = f.pin("X", 200, 100, true, 1);
      final var tunnel = f.add("Tunnel", 300, 200, Map.of());
      final var mutation = new CircuitMutation(f.circuit);
      final int[][] corners = {{160, 60}, {240, 60}, {240, 140}, {160, 140}};
      for (int i = 0; i < 4; i++) {
        final var next = corners[(i + 1) % 4];
        mutation.add(
            Wire.create(
                Location.create(corners[i][0], corners[i][1], true),
                Location.create(next[0], next[1], true)));
      }
      mutation.execute();
      final var before = CircuitDigest.canonical(f.circuit);
      assertThrows(IllegalArgumentException.class, () -> f.connect(a, 0, x, 0));
      assertThrows(IllegalArgumentException.class, () -> f.connect(a, 0, tunnel, 0));
      assertEquals(before, CircuitDigest.canonical(f.circuit));
    }
  }

  private static void bits(List<String> row, int number) {
    for (int bit = 3; bit >= 0; bit--) row.add(Integer.toString((number >>> bit) & 1));
  }
}
