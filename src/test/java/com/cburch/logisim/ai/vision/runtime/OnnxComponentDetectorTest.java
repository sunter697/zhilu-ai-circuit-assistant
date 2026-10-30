/*
 * Logisim-evolution - digital logic design tool and simulator
 * Copyright by the Logisim-evolution developers
 *
 * https://github.com/logisim-evolution/
 *
 * This is free software released under GNU GPLv3 license
 */

package com.cburch.logisim.ai.vision.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.cburch.logisim.ai.vision.model.VisionIr;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class OnnxComponentDetectorTest {
  private static final Path MODEL = Path.of("vision-service", "models", "best.onnx");
  private static final String MODEL_SHA256 =
      "1fce32b1b4f5a7f52754264be401d6d6a766eadd75c30305852d5761fa92318b";

  @Test
  void validatesFrozenModelContractAndChecksum() {
    assumeTrue(Files.isRegularFile(MODEL));
    assumeTrue(
        OnnxComponentDetector.runtimeCompatibilityProblem().isEmpty(),
        OnnxComponentDetector.runtimeCompatibilityProblem());
    try (var detector = new OnnxComponentDetector(MODEL, MODEL_SHA256)) {
      assertEquals(MODEL_SHA256, detector.modelSha256());
      assertEquals(9, detector.classNames().size());
      assertEquals(VisionIr.ComponentKind.INPUT_PIN, detector.classNames().get(0));
      assertEquals(VisionIr.ComponentKind.XNOR, detector.classNames().get(8));
    }
  }

  @Test
  void rejectsMissingOrUnexpectedModelBeforeInference() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new OnnxComponentDetector(Path.of("missing-model.onnx"), MODEL_SHA256));
    assumeTrue(Files.isRegularFile(MODEL));
    final var error =
        assertThrows(
            IllegalArgumentException.class,
            () -> new OnnxComponentDetector(MODEL, "0".repeat(64)));
    assertTrue(error.getMessage().contains("SHA-256 mismatch"));
  }

  @Test
  void matchesPythonDetectorOnFrozenImage() {
    assumeTrue(Files.isRegularFile(MODEL));
    assumeTrue(
        OnnxComponentDetector.runtimeCompatibilityProblem().isEmpty(),
        OnnxComponentDetector.runtimeCompatibilityProblem());
    final var image =
        Path.of(
            "vision-service",
            "tests",
            "fixtures",
            "day6",
            "valid",
            "simple",
            "S01_AND.png");
    assumeTrue(Files.isRegularFile(image));

    try (var detector = new OnnxComponentDetector(MODEL, MODEL_SHA256)) {
      final var detections = detector.detect(image);
      assertEquals(
          List.of(
              VisionIr.ComponentKind.INPUT_PIN,
              VisionIr.ComponentKind.INPUT_PIN,
              VisionIr.ComponentKind.AND),
          detections.stream().map(OnnxComponentDetector.Detection::kind).toList());
      assertBox(detections.get(0).box(), 49, 428, 85, 450);
      assertBox(detections.get(1).box(), 51, 48, 86, 70);
      assertBox(detections.get(2).box(), 350, 225, 400, 275);
      assertTrue(Math.abs(detections.get(2).confidence() - 0.987015) < 0.001);
    }
  }

  private static void assertBox(
      OnnxComponentDetector.PixelBox box, int x0, int y0, int x1, int y1) {
    assertEquals(x0, Math.round(box.x0()));
    assertEquals(y0, Math.round(box.y0()));
    assertEquals(x1, Math.round(box.x1()));
    assertEquals(y1, Math.round(box.y1()));
  }
}
