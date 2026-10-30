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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.cburch.logisim.ai.vision.model.VisionIr;
import com.cburch.logisim.ai.vision.model.VisionIrCodec;
import com.cburch.logisim.ai.vision.model.VisionIrValidator;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

class NativeCircuitTopologyRecognizerTest {
  private static final Path FIXTURES =
      Path.of("vision-service", "tests", "fixtures", "native-v04");
  private static final Path V05_EDGE_FIXTURES =
      Path.of(
          "src",
          "test",
          "resources",
          "com",
          "cburch",
          "logisim",
          "ai",
          "vision",
          "runtime",
          "v05-edge-cases");
  private static final Path MODEL = Path.of("vision-service", "models", "best.onnx");
  private static final String MODEL_SHA256 =
      "1fce32b1b4f5a7f52754264be401d6d6a766eadd75c30305852d5761fa92318b";
  private static final Map<String, String> PYTHON_SEMANTIC_HASHES =
      Map.of(
          "native_000014_v00_dcb2d8db12.json",
              "2ca7bf54aaff9b6e504d6c348fc7c423f04956d29f90e1896349e6ad63e15a39",
          "native_000014_v01_dcb2d8db12.json",
              "f1da2f76d5073041187a187c06b0e68b1227db5ba0f9682e98060e308cbf68a7",
          "native_000021_v00_e4182c95ef.json",
              "e0e50d4a5a6fb9476e87e1818e1f198094a9d4f788677e58fedee527ec1a5670",
          "native_000052_v00_e28eb59d08.json",
              "05e5ce6fb649b99b20be0d392f471092e65dc0dd1e525be10fc15ca7ca76d4f3");

  @Test
  void infersEveryLegalGateSizeFromScaleNormalizedBoxes() {
    assertEquals("NARROW", NativeCircuitTopologyRecognizer.multiInputGateSize(30));
    assertEquals("MEDIUM", NativeCircuitTopologyRecognizer.multiInputGateSize(50));
    assertEquals("WIDE", NativeCircuitTopologyRecognizer.multiInputGateSize(70));
    assertEquals("NARROW", NativeCircuitTopologyRecognizer.multiInputGateSize(31.5));
    assertEquals("WIDE", NativeCircuitTopologyRecognizer.multiInputGateSize(68.5));
    assertEquals("NARROW", NativeCircuitTopologyRecognizer.notGateSize(20));
    assertEquals("WIDE", NativeCircuitTopologyRecognizer.notGateSize(30));
  }

  @Test
  void infersNativeZoomFromFixedSizePinBoxes() {
    final var detections =
        List.of(
            new OnnxComponentDetector.Detection(
                VisionIr.ComponentKind.INPUT_PIN,
                new OnnxComponentDetector.PixelBox(15, 15, 45, 45),
                1.0),
            new OnnxComponentDetector.Detection(
                VisionIr.ComponentKind.OUTPUT_PIN,
                new OnnxComponentDetector.PixelBox(300, 30, 330, 60),
                1.0));
    final var profile = OrthogonalWireExtractor.nativeProfile(detections);
    assertEquals(15.0, profile.spacing(), 1.0e-9);
    assertEquals(1.5, profile.scale(), 1.0e-9);
  }

  @Test
  void refinesQuantizedHalfScaleFromPinAnchors() {
    final var detections =
        List.of(
            new OnnxComponentDetector.Detection(
                VisionIr.ComponentKind.INPUT_PIN,
                new OnnxComponentDetector.PixelBox(25, 29, 35, 40),
                1.0),
            new OnnxComponentDetector.Detection(
                VisionIr.ComponentKind.INPUT_PIN,
                new OnnxComponentDetector.PixelBox(24, 124, 35, 135),
                1.0),
            new OnnxComponentDetector.Detection(
                VisionIr.ComponentKind.OUTPUT_PIN,
                new OnnxComponentDetector.PixelBox(350, 150, 360, 160),
                1.0));

    final var profile = OrthogonalWireExtractor.nativeProfile(detections);

    assertEquals(5.0, profile.spacing(), 0.01);
    assertEquals(0.5, profile.scale(), 0.001);
  }

  @Test
  void blackWiresFanoutAndGridlessVariantsMatchFrozenPythonTruth() throws IOException {
    final var truthPaths =
        Files.list(FIXTURES)
            .filter(path -> path.getFileName().toString().endsWith(".json"))
            .sorted()
            .toList();
    assertEquals(4, truthPaths.size());

    final var recognizer = new NativeCircuitTopologyRecognizer();
    for (final var truthPath : truthPaths) {
      final var truth = VisionIrCodec.fromJson(Files.readString(truthPath));
      final var imagePath = replaceExtension(truthPath, ".png");
      final var image = ImageIO.read(imagePath.toFile());
      final var detections =
          truth.components().stream()
              .map(
                  component ->
                      new OnnxComponentDetector.Detection(
                          component.kind(),
                          new OnnxComponentDetector.PixelBox(
                              component.bodyBox().x0(),
                              component.bodyBox().y0(),
                              component.bodyBox().x1(),
                              component.bodyBox().y1()),
                          1.0))
              .toList();
      final var actual = recognizer.recognize(image, sha256(imagePath), detections);

      assertTrue(
          actual.unresolved().isEmpty(),
          () -> truthPath.getFileName() + ": " + actual.unresolved());
      assertTrue(
          VisionIrValidator.validate(actual).isEmpty(),
          () -> truthPath.getFileName() + ": " + VisionIrValidator.validate(actual));
      assertEquals(normalizedComponents(truth), normalizedComponents(actual), truthPath.toString());
      assertEquals(normalizedNets(truth), normalizedNets(actual), truthPath.toString());
      assertEquals(
          PYTHON_SEMANTIC_HASHES.get(truthPath.getFileName().toString()),
          actual.semanticHash(),
          truthPath.toString());
    }
  }

  @Test
  void onnxToTopologyMatchesFrozenPythonTruth() throws IOException {
    assumeTrue(Files.isRegularFile(MODEL));
    assumeTrue(
        OnnxComponentDetector.runtimeCompatibilityProblem().isEmpty(),
        OnnxComponentDetector.runtimeCompatibilityProblem());
    final var truthPaths =
        Files.list(FIXTURES)
            .filter(path -> path.getFileName().toString().endsWith(".json"))
            .sorted()
            .toList();
    final var recognizer = new NativeCircuitTopologyRecognizer();
    try (var detector = new OnnxComponentDetector(MODEL, MODEL_SHA256)) {
      for (final var truthPath : truthPaths) {
        final var truth = VisionIrCodec.fromJson(Files.readString(truthPath));
        final var imagePath = replaceExtension(truthPath, ".png");
        final var image = ImageIO.read(imagePath.toFile());
        final var actual = recognizer.recognize(image, sha256(imagePath), detector.detect(imagePath));

        assertTrue(
            actual.unresolved().isEmpty(),
            () -> truthPath.getFileName() + ": " + actual.unresolved());
        assertEquals(
            normalizedComponents(truth), normalizedComponents(actual), truthPath.toString());
        assertEquals(normalizedNets(truth), normalizedNets(actual), truthPath.toString());
      }
    }
  }

  @Test
  void v05SizeAndVerticalPortEdgeCasesMatchTruth() throws IOException {
    assumeTrue(Files.isRegularFile(MODEL));
    assumeTrue(
        OnnxComponentDetector.runtimeCompatibilityProblem().isEmpty(),
        OnnxComponentDetector.runtimeCompatibilityProblem());
    final var truthPaths =
        Files.list(V05_EDGE_FIXTURES)
            .filter(path -> path.getFileName().toString().endsWith(".json"))
            .sorted()
            .toList();
    assertEquals(3, truthPaths.size());

    final var recognizer = new NativeCircuitTopologyRecognizer();
    try (var detector = new OnnxComponentDetector(MODEL, MODEL_SHA256)) {
      for (final var truthPath : truthPaths) {
        final var truth = VisionIrCodec.fromJson(Files.readString(truthPath));
        final var imagePath = replaceExtension(truthPath, ".png");
        final var image = ImageIO.read(imagePath.toFile());
        final var truthDetections =
            truth.components().stream()
                .map(
                    component ->
                        new OnnxComponentDetector.Detection(
                            component.kind(),
                            new OnnxComponentDetector.PixelBox(
                                component.bodyBox().x0(),
                                component.bodyBox().y0(),
                                component.bodyBox().x1(),
                                component.bodyBox().y1()),
                            1.0))
                .toList();

        assertMatchesTruth(
            truth,
            recognizer.recognize(image, sha256(imagePath), truthDetections),
            truthPath);
        assertMatchesTruth(
            truth,
            recognizer.recognize(image, sha256(imagePath), detector.detect(imagePath)),
            truthPath);
      }
    }
  }

  private static void assertMatchesTruth(VisionIr truth, VisionIr actual, Path truthPath) {
    assertTrue(
        actual.unresolved().isEmpty(),
        () -> truthPath.getFileName() + ": " + actual.unresolved());
    assertEquals(normalizedComponents(truth), normalizedComponents(actual), truthPath.toString());
    assertEquals(normalizedNets(truth), normalizedNets(actual), truthPath.toString());
  }

  private static Path replaceExtension(Path path, String extension) {
    final var name = path.getFileName().toString();
    return path.resolveSibling(name.substring(0, name.lastIndexOf('.')) + extension);
  }

  private static List<String> normalizedComponents(VisionIr ir) {
    final var result = new ArrayList<String>();
    for (final var component : ir.components()) {
      final var ports =
          component.ports().stream()
              .map(
                  port ->
                      port.id()
                          + ":"
                          + port.direction()
                          + ":"
                          + pointKey(port.point()))
              .sorted()
              .toList();
      result.add(component.kind() + "@" + pointKey(component.anchor()) + ports);
    }
    result.sort(String::compareTo);
    return result;
  }

  private static List<String> normalizedNets(VisionIr ir) {
    final Map<String, VisionIr.DetectedComponent> components =
        ir.components().stream()
            .collect(java.util.stream.Collectors.toMap(VisionIr.DetectedComponent::id, item -> item));
    final var result = new ArrayList<String>();
    for (final var net : ir.nets()) {
      final var endpoints =
          net.endpoints().stream()
              .map(
                  endpoint -> {
                    final var component = components.get(endpoint.componentId());
                    return component.kind()
                        + "@"
                        + pointKey(component.anchor())
                        + ":"
                        + endpoint.portId();
                  })
              .sorted()
              .toList();
      final var junctions = net.junctions().stream().map(NativeCircuitTopologyRecognizerTest::pointKey).sorted().toList();
      result.add(endpoints + "|" + junctions);
    }
    result.sort(Comparator.naturalOrder());
    return result;
  }

  private static String pointKey(VisionIr.Point point) {
    return Math.round(point.x()) + "," + Math.round(point.y());
  }

  private static String sha256(Path path) throws IOException {
    try {
      final var digest = MessageDigest.getInstance("SHA-256");
      digest.update(Files.readAllBytes(path));
      return HexFormat.of().formatHex(digest.digest());
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException(exception);
    }
  }
}
