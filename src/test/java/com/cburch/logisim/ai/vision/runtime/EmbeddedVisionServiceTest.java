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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.cburch.logisim.ai.vision.client.VisionServiceException;
import com.cburch.logisim.ai.vision.model.VisionIr;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class EmbeddedVisionServiceTest {
  private static final Path MODEL = Path.of("vision-service", "models", "best.onnx");
  private static final Path IMAGE =
      Path.of(
          "vision-service",
          "tests",
          "fixtures",
          "native-v04",
          "native_000052_v00_e28eb59d08.png");

  @Test
  void rejectsMissingImageBeforeLoadingNativeRuntime() {
    final var service = new EmbeddedVisionService(MODEL);
    final var error =
        assertThrows(
            VisionServiceException.class,
            () -> service.recognize(Path.of("missing-circuit-image.png")));
    assertEquals(VisionServiceException.Reason.IO_ERROR, error.reason());
    service.close();
  }

  @Test
  void enforcesAutomaticTimeoutBeforeNativeRuntimeIsRequired() {
    final var service =
        new EmbeddedVisionService(
            MODEL, new NativeCircuitTopologyRecognizer(), Duration.ofNanos(1));
    try {
      final var error =
          assertThrows(VisionServiceException.class, () -> service.recognize(IMAGE));
      assertEquals(VisionServiceException.Reason.TIMEOUT, error.reason());
    } finally {
      service.close();
    }
  }

  @Test
  void propagatesCallerCancellationBeforeNativeRuntimeIsRequired() {
    final var service = new EmbeddedVisionService(MODEL);
    try {
      Thread.currentThread().interrupt();
      final var error =
          assertThrows(VisionServiceException.class, () -> service.recognize(IMAGE));
      assertEquals(VisionServiceException.Reason.CANCELLED, error.reason());
    } finally {
      Thread.interrupted();
      service.close();
    }
  }

  @Test
  void recognizesNativeImageWithoutPythonProcess() {
    assumeTrue(Files.isRegularFile(MODEL));
    assumeTrue(Files.isRegularFile(IMAGE));
    assumeTrue(
        OnnxComponentDetector.runtimeCompatibilityProblem().isEmpty(),
        OnnxComponentDetector.runtimeCompatibilityProblem());
    try (var service = new EmbeddedVisionService(MODEL)) {
      final var health = service.health();
      assertEquals("ok", health.status());
      assertEquals(VisionIr.VERSION, health.protocolVersion());
      assertEquals("java-onnx-native-v05", health.detectorMode());

      final var result = service.recognize(IMAGE);
      assertEquals(7, result.components().size());
      assertEquals(6, result.nets().size());
      assertFalse(result.semanticHash().isBlank());
      assertEquals(0, result.unresolved().size());
    }
  }
}
