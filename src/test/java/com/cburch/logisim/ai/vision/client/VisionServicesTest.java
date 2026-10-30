/*
 * Logisim-evolution - digital logic design tool and simulator
 * Copyright by the Logisim-evolution developers
 *
 * https://github.com/logisim-evolution/
 *
 * This is free software released under GNU GPLv3 license
 */

package com.cburch.logisim.ai.vision.client;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.cburch.logisim.ai.vision.runtime.EmbeddedVisionService;
import java.nio.file.Files;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;

@ResourceLock(Resources.SYSTEM_PROPERTIES)
class VisionServicesTest {
  @Test
  void locatesFrozenDevelopmentModel() {
    final var model = VisionServices.configuredModel();
    assertTrue(model != null && Files.isRegularFile(model));
  }

  @Test
  void explicitModeSelectsHttpOrEmbeddedWithoutStartingEitherRuntime() {
    final var previous = System.getProperty("logisim.vision.mode");
    try {
      System.setProperty("logisim.vision.mode", "http");
      assertInstanceOf(VisionServiceClient.class, VisionServices.createDefault());

      System.setProperty("logisim.vision.mode", "embedded");
      final var embedded =
          assertInstanceOf(EmbeddedVisionService.class, VisionServices.createDefault());
      embedded.close();
    } finally {
      if (previous == null) System.clearProperty("logisim.vision.mode");
      else System.setProperty("logisim.vision.mode", previous);
    }
  }
}
