/*
 * Logisim-evolution - digital logic design tool and simulator
 * Copyright by the Logisim-evolution developers
 *
 * https://github.com/logisim-evolution/
 *
 * This is free software released under GNU GPLv3 license
 */

package com.cburch.logisim.ai.vision.importer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.cburch.logisim.ai.validation.ValidationEngine;
import com.cburch.logisim.ai.vision.model.VisionIr;
import com.cburch.logisim.ai.vision.model.VisionIrCodec;
import com.cburch.logisim.circuit.CircuitMutation;
import com.cburch.logisim.file.Loader;
import com.cburch.logisim.file.LogisimFile;
import com.cburch.logisim.proj.Project;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CircuitImportBuilderTest {
  private static final class Fixture implements AutoCloseable {
    final Loader loader = new Loader(null);
    final LogisimFile file = LogisimFile.createNew(loader, null);
    final Project project;

    Fixture() {
      file.addLibrary(loader.loadLibrary("#Wiring"));
      file.addLibrary(loader.loadLibrary("#Gates"));
      project = new Project(file);
      file.getCircuits().forEach(circuit -> circuit.setProject(project));
    }

    @Override
    public void close() {
      project.getSimulator().shutDown();
      file.stopAutosaveThread(false);
    }
  }

  @Test
  void andFixtureBuildsSimulatesAndUndoesAsOneProjectAction() throws IOException {
    try (var fixture = new Fixture()) {
      final var plan =
          new CircuitImportPlanner().plan(fixture("and.json"), fixture.file, "Imported_AND");
      final var result = new CircuitImportBuilder().buildAndAdd(fixture.project, plan);

      assertSame(result.circuit(), fixture.file.getCircuit("Imported_AND"));
      assertTrue(new StructuralImportVerifier().verify(result.circuit(), plan).valid());
      assertEquals(4, result.circuit().getNonWires().size());
      assertEquals(3, result.circuit().getWires().size());
      assertTruthTable(
          result.circuit(),
          fixture.project,
          List.of(
              List.of("0", "0", "0"),
              List.of("0", "1", "0"),
              List.of("1", "0", "0"),
              List.of("1", "1", "1")));

      fixture.project.undoAction();
      assertNull(fixture.file.getCircuit("Imported_AND"));
      fixture.project.redoAction();
      assertSame(result.circuit(), fixture.file.getCircuit("Imported_AND"));
    }
  }

  @Test
  void andNotFixtureBuildsAndSimulates() throws IOException {
    try (var fixture = new Fixture()) {
      final var plan =
          new CircuitImportPlanner().plan(fixture("and-not.json"), fixture.file, "Imported_NAND");
      final var result = new CircuitImportBuilder().buildDetached(fixture.project, plan);
      assertTruthTable(
          result.circuit(),
          fixture.project,
          List.of(
              List.of("0", "0", "1"),
              List.of("0", "1", "1"),
              List.of("1", "0", "1"),
              List.of("1", "1", "0")));
      assertNull(fixture.file.getCircuit("Imported_NAND"));
    }
  }

  @Test
  void narrowGateSizeFromVisionIrControlsRealPortGeometry() throws IOException {
    try (var fixture = new Fixture()) {
      final var original = fixture("and.json");
      final var components = new ArrayList<VisionIr.DetectedComponent>();
      for (final var component : original.components()) {
        if (component.kind() != VisionIr.ComponentKind.AND) {
          components.add(component);
          continue;
        }
        final var attributes = new LinkedHashMap<>(component.attributes());
        attributes.put("size", "NARROW");
        components.add(
            new VisionIr.DetectedComponent(
                component.id(),
                component.kind(),
                component.bodyBox(),
                component.anchor(),
                component.facing(),
                attributes,
                component.ports(),
                component.confidence()));
      }
      final var nets = new ArrayList<VisionIr.DetectedNet>();
      for (final var net : original.nets()) {
        final var segments =
            switch (net.id()) {
              case "net_a" ->
                  List.of(segment(100, 80, 140, 80), segment(140, 80, 140, 90),
                      segment(140, 90, 170, 90));
              case "net_b" ->
                  List.of(segment(100, 120, 140, 120), segment(140, 120, 140, 110),
                      segment(140, 110, 170, 110));
              default -> net.segments();
            };
        nets.add(
            new VisionIr.DetectedNet(
                net.id(), net.endpoints(), segments, net.junctions(), net.confidence()));
      }
      final var narrowIr =
          new VisionIr(
              original.schemaVersion(),
              original.source(),
              original.profile(),
              components,
              nets,
              original.unresolved(),
              original.semanticHash());

      final var plan =
          new CircuitImportPlanner().plan(narrowIr, fixture.file, "Imported_Narrow_AND");
      final var gate = plan.component("gate_and");
      assertEquals(CircuitImportPlan.GateSize.NARROW, gate.gateSize());
      assertEquals(new CircuitImportPlan.GridPoint(170, 90), gate.port("in0").location());
      assertEquals(new CircuitImportPlan.GridPoint(170, 110), gate.port("in1").location());

      final var result = new CircuitImportBuilder().buildDetached(fixture.project, plan);
      assertTrue(new StructuralImportVerifier().verify(result.circuit(), plan).valid());
      assertTruthTable(
          result.circuit(),
          fixture.project,
          List.of(
              List.of("0", "0", "0"),
              List.of("0", "1", "0"),
              List.of("1", "0", "0"),
              List.of("1", "1", "1")));
    }
  }

  @Test
  void multilevelFixtureBuildsWithoutChangingProject() throws IOException {
    try (var fixture = new Fixture()) {
      final var before = fixture.file.getCircuitCount();
      final var plan =
          new CircuitImportPlanner()
              .plan(fixture("multilevel-and-or.json"), fixture.file, "Imported_Multilevel");
      final var result = new CircuitImportBuilder().buildDetached(fixture.project, plan);
      assertEquals(8, result.circuit().getNonWires().size());
      assertEquals(11, result.circuit().getWires().size());
      assertEquals(before, fixture.file.getCircuitCount());
      assertTrue(new StructuralImportVerifier().verify(result.circuit(), plan).valid());
      assertMultilevelTruthTable(result.circuit(), fixture.project);
    }
  }

  @Test
  void separateMidSegmentCrossingsKeepTheirWiresAndSimulate() throws IOException {
    try (var fixture = new Fixture()) {
      final var original = fixture("and.json");
      final var crossedNets = new ArrayList<VisionIr.DetectedNet>();
      for (final var net : original.nets()) {
        if (!net.id().equals("net_b")) {
          crossedNets.add(net);
          continue;
        }
        crossedNets.add(
            new VisionIr.DetectedNet(
                net.id(),
                net.endpoints(),
                List.of(
                    segment(100, 120, 120, 120),
                    segment(120, 60, 120, 120),
                    segment(120, 60, 130, 60),
                    segment(130, 60, 130, 120),
                    segment(130, 120, 150, 120)),
                net.junctions(),
                net.confidence()));
      }
      final var crossed =
          new VisionIr(
              original.schemaVersion(),
              original.source(),
              original.profile(),
              original.components(),
              crossedNets,
              original.unresolved(),
              original.semanticHash());

      final var plan =
          new CircuitImportPlanner().plan(crossed, fixture.file, "Imported_Crossing_AND");
      assertTrue(plan.routingTunnels().isEmpty());
      final var result = new CircuitImportBuilder().buildDetached(fixture.project, plan);

      assertTrue(new StructuralImportVerifier().verify(result.circuit(), plan).valid());
      assertTruthTable(
          result.circuit(),
          fixture.project,
          List.of(
              List.of("0", "0", "0"),
              List.of("0", "1", "0"),
              List.of("1", "0", "0"),
              List.of("1", "1", "1")));
    }
  }

  @Test
  void endpointContactTunnelsOnlyOneConflictingNet() throws IOException {
    try (var fixture = new Fixture()) {
      final var original = fixture("and.json");
      final var contactedNets = new ArrayList<VisionIr.DetectedNet>();
      for (final var net : original.nets()) {
        if (!net.id().equals("net_b")) {
          contactedNets.add(net);
          continue;
        }
        contactedNets.add(
            new VisionIr.DetectedNet(
                net.id(),
                net.endpoints(),
                List.of(
                    segment(100, 120, 120, 120),
                    segment(120, 80, 120, 120),
                    segment(120, 120, 150, 120)),
                net.junctions(),
                net.confidence()));
      }
      final var contacted =
          new VisionIr(
              original.schemaVersion(),
              original.source(),
              original.profile(),
              original.components(),
              contactedNets,
              original.unresolved(),
              original.semanticHash());

      final var plan =
          new CircuitImportPlanner().plan(contacted, fixture.file, "Imported_Tunneled_AND");
      assertEquals(2, plan.routingTunnels().size());
      assertEquals(
          List.of("net_a"),
          plan.routingTunnels().stream().map(CircuitImportPlan.PlannedTunnel::netId).distinct().toList());

      final var result = new CircuitImportBuilder().buildDetached(fixture.project, plan);
      assertTrue(new StructuralImportVerifier().verify(result.circuit(), plan).valid());
      assertTruthTable(
          result.circuit(),
          fixture.project,
          List.of(
              List.of("0", "0", "0"),
              List.of("0", "1", "0"),
              List.of("1", "0", "0"),
              List.of("1", "1", "1")));
    }
  }

  @Test
  void disconnectedGeometryIsRejectedBeforeProjectMutation() throws IOException {
    try (var fixture = new Fixture()) {
      final var ir = fixture("and.json");
      final var nets = new java.util.ArrayList<>(ir.nets());
      final var net = nets.getFirst();
      nets.set(
          0,
          new VisionIr.DetectedNet(
              net.id(),
              net.endpoints(),
              List.of(
                  new VisionIr.Segment(
                      new VisionIr.Point(100, 80), new VisionIr.Point(140, 80))),
              net.junctions(),
              net.confidence()));
      final var invalid =
          new VisionIr(
              ir.schemaVersion(),
              ir.source(),
              ir.profile(),
              ir.components(),
              nets,
              ir.unresolved(),
              ir.semanticHash());
      final var before = fixture.file.getCircuitCount();
      assertThrows(
          IllegalArgumentException.class,
          () -> new CircuitImportPlanner().plan(invalid, fixture.file, "Rejected"));
      assertEquals(before, fixture.file.getCircuitCount());
      assertNull(fixture.file.getCircuit("Rejected"));
    }
  }

  @Test
  void verifierDetectsPostBuildWireRemoval() throws IOException {
    try (var fixture = new Fixture()) {
      final var plan =
          new CircuitImportPlanner().plan(fixture("and.json"), fixture.file, "Tampered");
      final var result = new CircuitImportBuilder().buildDetached(fixture.project, plan);
      final var mutation = new CircuitMutation(result.circuit());
      mutation.remove(result.circuit().getWires().iterator().next());
      mutation.execute();
      final var verification = new StructuralImportVerifier().verify(result.circuit(), plan);
      assertFalse(verification.valid());
      assertTrue(verification.errors().stream().anyMatch(error -> error.contains("Wire count")));
    }
  }

  @SuppressWarnings("unchecked")
  private static void assertMultilevelTruthTable(
      com.cburch.logisim.circuit.Circuit circuit, Project project) {
    final Map<String, Object> table = new ValidationEngine().truthTable(project, circuit, 32);
    assertEquals("OK", table.get("status"), String.valueOf(table.get("reason")));
    assertEquals(List.of("A", "B", "C", "D", "Y"), table.get("columns"));
    final var rows = (List<List<String>>) table.get("rows");
    assertEquals(16, rows.size());
    for (final var row : rows) {
      final var expected =
          ("1".equals(row.get(0)) && "1".equals(row.get(1)))
                  || ("1".equals(row.get(2)) && "1".equals(row.get(3)))
              ? "1"
              : "0";
      assertEquals(expected, row.get(4), "Unexpected multilevel output for row " + row);
    }
  }

  @SuppressWarnings("unchecked")
  private static void assertTruthTable(
      com.cburch.logisim.circuit.Circuit circuit,
      Project project,
      List<List<String>> expectedRows) {
    final Map<String, Object> table = new ValidationEngine().truthTable(project, circuit, 32);
    assertEquals("OK", table.get("status"), String.valueOf(table.get("reason")));
    assertEquals(List.of("A", "B", "Y"), table.get("columns"));
    assertEquals(expectedRows, (List<List<String>>) table.get("rows"));
  }

  private static VisionIr fixture(String name) throws IOException {
    try (var stream =
        CircuitImportBuilderTest.class.getResourceAsStream(
            "/com/cburch/logisim/ai/vision/" + name)) {
      if (stream == null) throw new IOException("Missing test fixture: " + name);
      return VisionIrCodec.fromJson(new String(stream.readAllBytes(), StandardCharsets.UTF_8));
    }
  }

  private static VisionIr.Segment segment(int x0, int y0, int x1, int y1) {
    return new VisionIr.Segment(new VisionIr.Point(x0, y0), new VisionIr.Point(x1, y1));
  }
}
