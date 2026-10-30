/*
 * Logisim-evolution - digital logic design tool and simulator
 * Copyright by the Logisim-evolution developers
 *
 * https://github.com/logisim-evolution/
 *
 * This is free software released under GNU GPLv3 license
 */

package com.cburch.logisim.ai.vision.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.cburch.logisim.ai.vision.client.VisionService;
import com.cburch.logisim.ai.vision.importer.CircuitImportBuilder;
import com.cburch.logisim.ai.vision.importer.CircuitImportPlanner;
import com.cburch.logisim.ai.vision.model.VisionIr;
import com.cburch.logisim.ai.vision.model.VisionIrCodec;
import com.cburch.logisim.file.Loader;
import com.cburch.logisim.file.LogisimFile;
import com.cburch.logisim.gui.ai.VisionOverlayView;
import com.cburch.logisim.proj.Project;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class VisionImportControllerTest {
  @TempDir Path temporary;

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
  void prepareDoesNotMutateAndApplyAddsOneUndoableCircuit() throws IOException {
    final var recognized = fixture("and.json");
    final VisionService service =
        new VisionService() {
          @Override
          public Health health() {
            return new Health("ok", VisionIr.VERSION, "test", "test", "test-detector");
          }

          @Override
          public VisionIr recognize(Path image) {
            return recognized;
          }
        };
    final var controller =
        new VisionImportController(
            service, new CircuitImportPlanner(), new CircuitImportBuilder());

    try (var target = new Fixture()) {
      final var before = target.file.getCircuitCount();
      final var prepared = controller.prepare(target.project, Path.of("ignored.png"));
      assertEquals(before, target.file.getCircuitCount());
      assertNull(target.file.getCircuit("Vision_Imported"));
      assertEquals(4, prepared.componentCount());
      assertEquals(3, prepared.netCount());
      assertEquals(0, prepared.lowConfidenceCount());
      assertEquals(0, prepared.correctionCount());
      assertEquals(Path.of("ignored.png").toAbsolutePath().normalize(), prepared.sourceImage());
      assertSame(recognized, prepared.recognition());

      final var result = controller.apply(target.project, prepared);
      assertEquals(before + 1, target.file.getCircuitCount());
      assertSame(result.circuit(), target.file.getCircuit("Vision_Imported"));

      target.project.undoAction();
      assertNull(target.file.getCircuit("Vision_Imported"));
    }
  }

  @Test
  void protocolMismatchStopsBeforeRecognitionAndDoesNotMutate() throws IOException {
    final var recognizeCalled = new AtomicBoolean();
    final VisionService service =
        new VisionService() {
          @Override
          public Health health() {
            return new Health("ok", "vision-ir/9.9", "test", "test", "test");
          }

          @Override
          public VisionIr recognize(Path image) {
            recognizeCalled.set(true);
            return null;
          }
        };
    final var controller =
        new VisionImportController(
            service, new CircuitImportPlanner(), new CircuitImportBuilder());

    try (var target = new Fixture()) {
      final var before = target.file.getCircuitCount();
      final var error =
          assertThrows(
              IllegalStateException.class,
              () -> controller.prepare(target.project, Path.of("ignored.png")));
      assertTrue(error.getMessage().contains("protocol mismatch"));
      assertEquals(before, target.file.getCircuitCount());
      assertFalse(recognizeCalled.get());
    }
  }

  @Test
  void unresolvedRecognitionErrorDoesNotMutate() throws IOException {
    final var original = fixture("and.json");
    final var rejected =
        new VisionIr(
            original.schemaVersion(),
            original.source(),
            original.profile(),
            original.components(),
            original.nets(),
            List.of(
                new VisionIr.UnresolvedIssue(
                    "MULTIPLE_DRIVERS",
                    "Two outputs share one wire",
                    VisionIr.IssueSeverity.ERROR,
                    "wire_001")),
            original.semanticHash());
    final var controller = controllerFor(rejected);

    try (var target = new Fixture()) {
      final var before = target.file.getCircuitCount();
      final var error =
          assertThrows(
              IllegalArgumentException.class,
              () -> controller.prepare(target.project, Path.of("ignored.png")));
      assertTrue(error.getMessage().contains("MULTIPLE_DRIVERS"));
      assertEquals(before, target.file.getCircuitCount());
      assertNull(target.file.getCircuit("Vision_Imported"));
    }
  }

  @Test
  void lowConfidenceComponentsAndNetsAreCounted() throws IOException {
    final var original = fixture("and.json");
    final var components = new java.util.ArrayList<>(original.components());
    final var component = components.get(0);
    components.set(
        0,
        new VisionIr.DetectedComponent(
            component.id(),
            component.kind(),
            component.bodyBox(),
            component.anchor(),
            component.facing(),
            component.attributes(),
            component.ports(),
            0.6));
    final var nets = new java.util.ArrayList<>(original.nets());
    final var net = nets.get(0);
    nets.set(
        0,
        new VisionIr.DetectedNet(
            net.id(), net.endpoints(), net.segments(), net.junctions(), 0.7));
    final var recognized =
        new VisionIr(
            original.schemaVersion(),
            original.source(),
            original.profile(),
            components,
            nets,
            original.unresolved(),
            original.semanticHash());

    try (var target = new Fixture()) {
      final var prepared =
          controllerFor(recognized).prepare(target.project, Path.of("ignored.png"));
      assertEquals(2, prepared.lowConfidenceCount());
    }
  }

  @Test
  void safeCorrectionsAreReplannedAndRemainUndoable() throws IOException {
    final var recognized = fixture("and.json");
    final var controller = controllerFor(recognized);

    try (var target = new Fixture()) {
      final var before = target.file.getCircuitCount();
      final var prepared = controller.prepare(target.project, Path.of("ignored.png"));
      final var revised =
          controller.revise(
              target.project,
              prepared,
              Map.of(
                  "gate_and",
                  new VisionImportController.ComponentCorrection(
                      VisionIr.ComponentKind.XOR, null),
                  "pin_a",
                  new VisionImportController.ComponentCorrection(null, "IN_A")));

      assertEquals(before, target.file.getCircuitCount());
      assertEquals(2, revised.correctionCount());
      assertEquals("", revised.recognition().semanticHash());
      assertEquals(
          VisionIr.ComponentKind.XOR, revised.plan().component("gate_and").kind());
      assertEquals("XOR Gate", revised.plan().component("gate_and").factoryName());
      assertEquals("IN_A", revised.plan().component("pin_a").label());
      assertEquals(prepared.plan().targetCircuitName(), revised.plan().targetCircuitName());
      assertEquals(140, revised.plan().component("gate_and").port("in0").location().x());
      assertTrue(
          revised.plan().nets().stream()
              .filter(net -> net.id().equals("net_a"))
              .flatMap(net -> net.wires().stream())
              .anyMatch(wire -> wire.start().x() == 140 || wire.end().x() == 140));

      final var result = controller.apply(target.project, revised);
      assertSame(result.circuit(), target.file.getCircuit("Vision_Imported"));
      target.project.undoAction();
      assertNull(target.file.getCircuit("Vision_Imported"));
    }
  }

  @Test
  void unsafeCorrectionsAreRejectedWithoutMutation() throws IOException {
    final var recognized = fixture("and.json");
    final var controller = controllerFor(recognized);

    try (var target = new Fixture()) {
      final var before = target.file.getCircuitCount();
      final var prepared = controller.prepare(target.project, Path.of("ignored.png"));

      assertThrows(
          IllegalArgumentException.class,
          () ->
              controller.revise(
                  target.project,
                  prepared,
                  Map.of(
                      "pin_a",
                      new VisionImportController.ComponentCorrection(
                          VisionIr.ComponentKind.OUTPUT_PIN, null))));
      assertThrows(
          IllegalArgumentException.class,
          () ->
              controller.revise(
                  target.project,
                  prepared,
                  Map.of(
                      "gate_and",
                      new VisionImportController.ComponentCorrection(
                          VisionIr.ComponentKind.NOT, null))));
      assertThrows(
          IllegalArgumentException.class,
          () ->
              controller.revise(
                  target.project,
                  prepared,
                  Map.of(
                      "missing",
                      new VisionImportController.ComponentCorrection(
                          VisionIr.ComponentKind.OR, null))));
      assertThrows(
          IllegalArgumentException.class,
          () ->
              controller.revise(
                  target.project,
                  prepared,
                  Map.of(
                      "pin_a",
                      new VisionImportController.ComponentCorrection(null, "B"))));
      assertEquals(before, target.file.getCircuitCount());
      assertNull(target.file.getCircuit("Vision_Imported"));
    }
  }

  @Test
  void overlayLoadsSourceAndPaintsSelectableLayers() throws IOException {
    final var recognized = fixture("and.json");
    final var imagePath = temporary.resolve("circuit.png");
    ImageIO.write(
        new BufferedImage(400, 220, BufferedImage.TYPE_INT_RGB), "png", imagePath.toFile());
    final var view = new VisionOverlayView(imagePath, recognized);
    assertEquals(new java.awt.Dimension(400, 220), view.sourceSize());
    assertTrue(view.isShowingComponents());
    assertTrue(view.isShowingWires());
    assertTrue(view.isShowingJunctions());
    view.setShowComponents(false);
    view.setShowWires(false);
    view.setShowJunctions(false);
    assertFalse(view.isShowingComponents());
    assertFalse(view.isShowingWires());
    assertFalse(view.isShowingJunctions());

    view.setSize(640, 400);
    final var rendered = new BufferedImage(640, 400, BufferedImage.TYPE_INT_ARGB);
    final var graphics = rendered.createGraphics();
    view.paint(graphics);
    graphics.dispose();
  }

  @Test
  void overlayRejectsChangedSourceImage() throws IOException {
    final var recognized = fixture("and.json");
    final var wrongSize = temporary.resolve("changed-size.png");
    ImageIO.write(
        new BufferedImage(401, 220, BufferedImage.TYPE_INT_RGB), "png", wrongSize.toFile());
    assertThrows(IOException.class, () -> new VisionOverlayView(wrongSize, recognized));

    final var sameSize = temporary.resolve("changed-content.png");
    ImageIO.write(
        new BufferedImage(400, 220, BufferedImage.TYPE_INT_RGB), "png", sameSize.toFile());
    final var withExpectedHash =
        new VisionIr(
            recognized.schemaVersion(),
            new VisionIr.Source("0".repeat(64), 400, 220),
            recognized.profile(),
            recognized.components(),
            recognized.nets(),
            recognized.unresolved(),
            recognized.semanticHash());
    assertThrows(IOException.class, () -> new VisionOverlayView(sameSize, withExpectedHash));
  }

  @Test
  void closeReleasesCloseableVisionService() {
    final var closed = new AtomicBoolean();
    final class CloseableService implements VisionService, AutoCloseable {
      @Override
      public Health health() {
        return new Health("ok", VisionIr.VERSION, "test", "test", "test");
      }

      @Override
      public VisionIr recognize(Path image) {
        throw new UnsupportedOperationException();
      }

      @Override
      public void close() {
        closed.set(true);
      }
    }
    final var controller =
        new VisionImportController(
            new CloseableService(), new CircuitImportPlanner(), new CircuitImportBuilder());

    controller.close();

    assertTrue(closed.get());
  }

  private static VisionImportController controllerFor(VisionIr recognized) {
    final VisionService service =
        new VisionService() {
          @Override
          public Health health() {
            return new Health("ok", VisionIr.VERSION, "test", "test", "test-detector");
          }

          @Override
          public VisionIr recognize(Path image) {
            return recognized;
          }
        };
    return new VisionImportController(
        service, new CircuitImportPlanner(), new CircuitImportBuilder());
  }

  private static VisionIr fixture(String name) throws IOException {
    try (var stream =
        VisionImportControllerTest.class.getResourceAsStream(
            "/com/cburch/logisim/ai/vision/" + name)) {
      if (stream == null) throw new IOException("Missing fixture: " + name);
      return VisionIrCodec.fromJson(new String(stream.readAllBytes(), StandardCharsets.UTF_8));
    }
  }
}
