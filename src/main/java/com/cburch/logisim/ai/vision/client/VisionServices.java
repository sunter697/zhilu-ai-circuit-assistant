/*
 * Logisim-evolution - digital logic design tool and simulator
 * Copyright by the Logisim-evolution developers
 *
 * https://github.com/logisim-evolution/
 *
 * This is free software released under GNU GPLv3 license
 */

package com.cburch.logisim.ai.vision.client;

import com.cburch.logisim.ai.vision.runtime.EmbeddedVisionService;
import com.cburch.logisim.ai.vision.runtime.OnnxComponentDetector;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;

/** Selects the embedded recognizer when safe, retaining the Python HTTP compatibility route. */
public final class VisionServices {
  private static final Object BUNDLED_MODEL_LOCK = new Object();
  private static volatile Path bundledModel;

  private VisionServices() {}

  public static VisionService createDefault() {
    final var mode = configuredMode();
    if (mode.equals("http")) return new VisionServiceClient();
    if (mode.equals("auto")
        && !OnnxComponentDetector.runtimeCompatibilityProblem().isEmpty()) {
      return new VisionServiceClient();
    }
    final var model = configuredModel();
    if (mode.equals("embedded")) {
      if (model == null) {
        throw new IllegalStateException("The embedded vision model could not be found");
      }
      return new EmbeddedVisionService(model);
    }
    if (!mode.equals("auto")) {
      throw new IllegalArgumentException("Unsupported vision mode: " + mode);
    }
    if (model != null) {
      return new EmbeddedVisionService(model);
    }
    return new VisionServiceClient();
  }

  static Path configuredModel() {
    final var property = System.getProperty("logisim.vision.model");
    final var environment = System.getenv("LOGISIM_VISION_MODEL");
    final var configured =
        property != null && !property.isBlank()
            ? property
            : environment != null && !environment.isBlank() ? environment : null;
    if (configured != null) {
      final var candidate = Path.of(configured).toAbsolutePath().normalize();
      return Files.isRegularFile(candidate) ? candidate : null;
    }
    final var development =
        Path.of("vision-service", "models", "best.onnx").toAbsolutePath().normalize();
    if (Files.isRegularFile(development)) return development;
    try {
      final var location =
          Path.of(
                  VisionServices.class
                      .getProtectionDomain()
                      .getCodeSource()
                      .getLocation()
                      .toURI())
              .toAbsolutePath()
              .normalize();
      final var directory = Files.isDirectory(location) ? location : location.getParent();
      if (directory != null) {
        final var adjacent = directory.resolve("models").resolve("best.onnx");
        if (Files.isRegularFile(adjacent)) return adjacent;
      }
    } catch (Exception ignored) {
      // The development and explicit paths remain authoritative.
    }
    return extractBundledModel();
  }

  private static Path extractBundledModel() {
    var current = bundledModel;
    if (current != null && Files.isRegularFile(current)) return current;
    synchronized (BUNDLED_MODEL_LOCK) {
      current = bundledModel;
      if (current != null && Files.isRegularFile(current)) return current;
      try (var input =
          VisionServices.class.getResourceAsStream(
              "/com/cburch/logisim/ai/vision/models/best.onnx")) {
        if (input == null) return null;
        final var extracted = Files.createTempFile("logisim-vision-model-", ".onnx");
        Files.copy(input, extracted, StandardCopyOption.REPLACE_EXISTING);
        extracted.toFile().deleteOnExit();
        bundledModel = extracted;
        return extracted;
      } catch (java.io.IOException exception) {
        throw new IllegalStateException("The bundled vision model could not be extracted", exception);
      }
    }
  }

  private static String configuredMode() {
    final var property = System.getProperty("logisim.vision.mode");
    final var environment = System.getenv("LOGISIM_VISION_MODE");
    final var value =
        property != null && !property.isBlank()
            ? property
            : environment != null && !environment.isBlank() ? environment : "auto";
    return value.strip().toLowerCase(Locale.ROOT);
  }
}
