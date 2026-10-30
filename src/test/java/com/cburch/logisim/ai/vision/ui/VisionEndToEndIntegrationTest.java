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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.cburch.logisim.file.Loader;
import com.cburch.logisim.file.LogisimFile;
import com.cburch.logisim.proj.Project;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/** Opt-in release test that crosses Python HTTP, Vision IR, planning, and Java import. */
@EnabledIfEnvironmentVariable(named = "LOGISIM_VISION_INTEGRATION", matches = "1")
class VisionEndToEndIntegrationTest {
  private record RealImageCase(String fileName, int componentCount, int netCount) {}

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
  void frozenModelImportsThroughTheRealLoopbackServiceAndUndoesAsOneAction() {
    final var image =
        Path.of(
                "vision-service",
                "tests",
                "fixtures",
                "day6",
                "valid",
                "simple",
                "S01_AND.png")
            .toAbsolutePath();
    assertTrue(Files.isRegularFile(image), "The frozen S01 demo image must exist");

    try (var target = new Fixture()) {
      final var before = target.file.getCircuitCount();
      final var controller = new VisionImportController();
      final var prepared = controller.prepare(target.project, image);

      assertEquals(before, target.file.getCircuitCount());
      assertNull(target.file.getCircuit("Vision_Imported"));
      assertEquals(4, prepared.componentCount());
      assertEquals(3, prepared.netCount());
      assertEquals(0, prepared.warningCount());
      assertEquals(0, prepared.lowConfidenceCount());
      assertTrue(prepared.detectorMode().contains("onnx"));

      final var result = controller.apply(target.project, prepared);
      assertEquals(before + 1, target.file.getCircuitCount());
      assertEquals(4, result.components().size());
      assertNotNull(target.file.getCircuit("Vision_Imported"));

      target.project.undoAction();
      assertEquals(before, target.file.getCircuitCount());
      assertNull(target.file.getCircuit("Vision_Imported"));
    }
  }

  @Test
  void crossingHeavyRealImagesImportWithoutShortingSeparateNets() {
    final var cases =
        java.util.List.of(
            new RealImageCase("T18_TWO_OR_NAND.png", 8, 7),
            new RealImageCase("T19_TWO_NOT.png", 10, 9),
            new RealImageCase("T20_ALL_TYPES.png", 13, 12));

    for (final var imageCase : cases) {
      final var image =
          Path.of(
                  "vision-service",
                  "tests",
                  "fixtures",
                  "real-logisim",
                  imageCase.fileName())
              .toAbsolutePath();
      assertTrue(Files.isRegularFile(image), "The real Logisim image must exist: " + image);

      try (var target = new Fixture(); var controller = new VisionImportController()) {
        final var before = target.file.getCircuitCount();
        final var prepared = controller.prepare(target.project, image);

        assertEquals(imageCase.componentCount(), prepared.componentCount(), imageCase.fileName());
        assertEquals(imageCase.netCount(), prepared.netCount(), imageCase.fileName());
        assertEquals(0, prepared.warningCount(), imageCase.fileName());
        assertTrue(prepared.plan().routingTunnels().isEmpty(), imageCase.fileName());
        assertTrue(prepared.plan().wires().size() >= imageCase.netCount(), imageCase.fileName());

        final var result = controller.apply(target.project, prepared);
        assertEquals(before + 1, target.file.getCircuitCount(), imageCase.fileName());
        assertEquals(imageCase.componentCount(), result.components().size(), imageCase.fileName());
        assertNotNull(target.file.getCircuit("Vision_Imported"), imageCase.fileName());
      }
    }
  }
}
