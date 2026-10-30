/*
 * Logisim-evolution - digital logic design tool and simulator
 * Copyright by the Logisim-evolution developers
 *
 * https://github.com/logisim-evolution/
 *
 * This is free software released under GNU GPLv3 license
 */

package com.cburch.logisim.ai.vision.runtime;

import ai.onnxruntime.OnnxJavaType;
import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;
import ai.onnxruntime.TensorInfo;
import com.cburch.logisim.ai.vision.model.VisionIr;
import java.awt.Color;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.FloatBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.imageio.ImageIO;

/** CPU-only Java ONNX Runtime adapter for the frozen YOLO component detector. */
public final class OnnxComponentDetector implements AutoCloseable {
  public record PixelBox(double x0, double y0, double x1, double y1) {
    double area() {
      return Math.max(0.0, x1 - x0) * Math.max(0.0, y1 - y0);
    }
  }

  public record Detection(VisionIr.ComponentKind kind, PixelBox box, double confidence) {}

  private static final int INPUT_SIZE = 800;
  private static final double CONFIDENCE_THRESHOLD = 0.35;
  private static final double NMS_IOU_THRESHOLD = 0.7;
  private static final long MAX_MODEL_BYTES = 64L * 1024 * 1024;
  private static final List<VisionIr.ComponentKind> LEGACY_CLASSES =
      List.of(
          VisionIr.ComponentKind.INPUT_PIN,
          VisionIr.ComponentKind.OUTPUT_PIN,
          VisionIr.ComponentKind.AND,
          VisionIr.ComponentKind.OR,
          VisionIr.ComponentKind.NOT,
          VisionIr.ComponentKind.NAND,
          VisionIr.ComponentKind.NOR);
  private static final List<VisionIr.ComponentKind> FULL_CLASSES =
      List.of(
          VisionIr.ComponentKind.INPUT_PIN,
          VisionIr.ComponentKind.OUTPUT_PIN,
          VisionIr.ComponentKind.AND,
          VisionIr.ComponentKind.OR,
          VisionIr.ComponentKind.NOT,
          VisionIr.ComponentKind.NAND,
          VisionIr.ComponentKind.NOR,
          VisionIr.ComponentKind.XOR,
          VisionIr.ComponentKind.XNOR);

  private final OrtEnvironment environment;
  private final OrtSession session;
  private final String inputName;
  private final List<VisionIr.ComponentKind> classNames;
  private final String modelSha256;

  public OnnxComponentDetector(Path model, String expectedSha256) {
    final var normalized = validateModel(model);
    modelSha256 = sha256(normalized);
    if (expectedSha256 != null
        && !expectedSha256.isBlank()
        && !modelSha256.equalsIgnoreCase(expectedSha256.strip())) {
      throw new IllegalArgumentException(
          "ONNX model SHA-256 mismatch: expected "
              + expectedSha256
              + ", found "
              + modelSha256);
    }
    final var compatibilityProblem = runtimeCompatibilityProblem();
    if (!compatibilityProblem.isEmpty()) {
      throw new IllegalStateException(compatibilityProblem);
    }
    try {
      environment = OrtEnvironment.getEnvironment();
      try (var options = new OrtSession.SessionOptions()) {
        options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT);
        session = environment.createSession(normalized.toString(), options);
      }
      if (session.getInputNames().size() != 1 || session.getOutputNames().size() != 1) {
        throw new IllegalArgumentException("The detector must have exactly one input and one output");
      }
      inputName = session.getInputNames().iterator().next();
      classNames = validateContract(session);
    } catch (OrtException exception) {
      throw new IllegalArgumentException("The ONNX detector could not be opened", exception);
    }
  }

  public String modelSha256() {
    return modelSha256;
  }

  public List<VisionIr.ComponentKind> classNames() {
    return classNames;
  }

  /** Returns an explanation instead of loading native code when the JVM is known to be unsafe. */
  public static String runtimeCompatibilityProblem() {
    if (!System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("windows")) {
      return "";
    }
    if (Boolean.parseBoolean(System.getenv("LOGISIM_VISION_ALLOW_ORACLE_ONNX_RUNTIME"))) {
      return "";
    }
    final var vendor = System.getProperty("java.vendor", "");
    if (!vendor.toLowerCase(java.util.Locale.ROOT).contains("oracle")) return "";
    return "The Oracle JDK bundles an MSVC runtime that is incompatible with ONNX Runtime "
        + "on Windows. Use a current Microsoft OpenJDK or Eclipse Temurin JDK, or package a "
        + "verified current MSVC runtime.";
  }

  public List<Detection> detect(Path imagePath) {
    if (Thread.currentThread().isInterrupted()) {
      throw new IllegalStateException("ONNX component detection was cancelled");
    }
    final BufferedImage image;
    try {
      image = ImageIO.read(imagePath.toFile());
    } catch (IOException exception) {
      throw new IllegalArgumentException("The circuit image could not be read", exception);
    }
    if (image == null) throw new IllegalArgumentException("The circuit image is not readable");
    return detect(image);
  }

  public List<Detection> detect(BufferedImage image) {
    if (image == null || image.getWidth() <= 0 || image.getHeight() <= 0) {
      throw new IllegalArgumentException("A non-empty circuit image is required");
    }
    if (Thread.currentThread().isInterrupted()) {
      throw new IllegalStateException("ONNX component detection was cancelled");
    }
    final var prepared = letterbox(image);
    try (var tensor =
            OnnxTensor.createTensor(
                environment,
                FloatBuffer.wrap(prepared.channelFirstPixels()),
                new long[] {1, 3, INPUT_SIZE, INPUT_SIZE});
        var result = session.run(Map.of(inputName, tensor))) {
      if (Thread.currentThread().isInterrupted()) {
        throw new IllegalStateException("ONNX component detection was cancelled");
      }
      final var value = result.get(0).getValue();
      if (!(value instanceof float[][][] output)) {
        throw new IllegalArgumentException("The ONNX detector returned an unsupported value");
      }
      return decode(output, image.getWidth(), image.getHeight(), prepared);
    } catch (OrtException exception) {
      throw new IllegalStateException("ONNX component detection failed", exception);
    }
  }

  @Override
  public void close() {
    try {
      session.close();
    } catch (OrtException exception) {
      throw new IllegalStateException("The ONNX detector could not be closed", exception);
    }
  }

  private List<Detection> decode(
      float[][][] output, int imageWidth, int imageHeight, PreparedImage prepared) {
    if (output.length != 1
        || output[0].length != 4 + classNames.size()
        || output[0][0].length == 0) {
      throw new IllegalArgumentException("The ONNX detector output shape changed unexpectedly");
    }
    final var candidates = new ArrayList<Detection>();
    for (var prediction = 0; prediction < output[0][0].length; prediction++) {
      var bestClass = 0;
      var bestScore = output[0][4][prediction];
      for (var classIndex = 1; classIndex < classNames.size(); classIndex++) {
        final var score = output[0][4 + classIndex][prediction];
        if (score > bestScore) {
          bestClass = classIndex;
          bestScore = score;
        }
      }
      if (bestScore < CONFIDENCE_THRESHOLD) continue;
      final var centerX = output[0][0][prediction];
      final var centerY = output[0][1][prediction];
      final var width = output[0][2][prediction];
      final var height = output[0][3][prediction];
      candidates.add(
          new Detection(
              classNames.get(bestClass),
              new PixelBox(
                  clip((centerX - width / 2.0 - prepared.left()) / prepared.ratio(), imageWidth),
                  clip((centerY - height / 2.0 - prepared.top()) / prepared.ratio(), imageHeight),
                  clip((centerX + width / 2.0 - prepared.left()) / prepared.ratio(), imageWidth),
                  clip((centerY + height / 2.0 - prepared.top()) / prepared.ratio(), imageHeight)),
              bestScore));
    }
    final var kept = classAwareNms(candidates);
    kept.removeIf(detection -> detection.box().x0() >= detection.box().x1()
        || detection.box().y0() >= detection.box().y1());
    kept.sort(
        Comparator.comparingDouble((Detection detection) -> detection.box().x0())
            .thenComparingDouble(detection -> detection.box().y0()));
    return List.copyOf(kept);
  }

  private static List<Detection> classAwareNms(List<Detection> candidates) {
    final var byClass = new HashMap<VisionIr.ComponentKind, List<Detection>>();
    for (final var candidate : candidates) {
      byClass.computeIfAbsent(candidate.kind(), ignored -> new ArrayList<>()).add(candidate);
    }
    final var kept = new ArrayList<Detection>();
    for (final var sameClass : byClass.values()) {
      sameClass.sort(Comparator.comparingDouble(Detection::confidence).reversed());
      while (!sameClass.isEmpty()) {
        final var selected = sameClass.remove(0);
        kept.add(selected);
        sameClass.removeIf(
            candidate -> intersectionOverUnion(selected.box(), candidate.box()) > NMS_IOU_THRESHOLD);
      }
    }
    return kept;
  }

  private static double intersectionOverUnion(PixelBox first, PixelBox second) {
    final var x0 = Math.max(first.x0(), second.x0());
    final var y0 = Math.max(first.y0(), second.y0());
    final var x1 = Math.min(first.x1(), second.x1());
    final var y1 = Math.min(first.y1(), second.y1());
    final var intersection = Math.max(0.0, x1 - x0) * Math.max(0.0, y1 - y0);
    return intersection / Math.max(first.area() + second.area() - intersection, 1.0e-9);
  }

  private static PreparedImage letterbox(BufferedImage source) {
    final var ratio =
        Math.min((double) INPUT_SIZE / source.getWidth(), (double) INPUT_SIZE / source.getHeight());
    final var width = (int) Math.rint(source.getWidth() * ratio);
    final var height = (int) Math.rint(source.getHeight() * ratio);
    final var left = (int) Math.rint((INPUT_SIZE - width) / 2.0 - 0.1);
    final var top = (int) Math.rint((INPUT_SIZE - height) / 2.0 - 0.1);
    final var canvas = new BufferedImage(INPUT_SIZE, INPUT_SIZE, BufferedImage.TYPE_INT_RGB);
    final var graphics = canvas.createGraphics();
    graphics.setColor(Color.WHITE);
    graphics.fillRect(0, 0, INPUT_SIZE, INPUT_SIZE);
    graphics.setRenderingHint(
        RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
    graphics.drawImage(source, left, top, width, height, null);
    graphics.dispose();

    final var rgb = canvas.getRGB(0, 0, INPUT_SIZE, INPUT_SIZE, null, 0, INPUT_SIZE);
    final var plane = INPUT_SIZE * INPUT_SIZE;
    final var pixels = new float[plane * 3];
    for (var index = 0; index < plane; index++) {
      pixels[index] = ((rgb[index] >>> 16) & 0xff) / 255.0f;
      pixels[plane + index] = ((rgb[index] >>> 8) & 0xff) / 255.0f;
      pixels[2 * plane + index] = (rgb[index] & 0xff) / 255.0f;
    }
    return new PreparedImage(pixels, ratio, left, top);
  }

  private static List<VisionIr.ComponentKind> validateContract(OrtSession session)
      throws OrtException {
    final var input = (TensorInfo) session.getInputInfo().values().iterator().next().getInfo();
    final var output = (TensorInfo) session.getOutputInfo().values().iterator().next().getInfo();
    final var inputShape = input.getShape();
    final var outputShape = output.getShape();
    if (input.type != OnnxJavaType.FLOAT
        || inputShape.length != 4
        || inputShape[0] != 1
        || inputShape[1] != 3
        || inputShape[2] != INPUT_SIZE
        || inputShape[3] != INPUT_SIZE) {
      throw new IllegalArgumentException("Expected ONNX input float[1,3,800,800]");
    }
    if (output.type != OnnxJavaType.FLOAT || outputShape.length != 3 || outputShape[0] != 1) {
      throw new IllegalArgumentException("Expected a three-dimensional float detector output");
    }
    final var classCount = Math.toIntExact(outputShape[1] - 4);
    final List<VisionIr.ComponentKind> classes;
    if (classCount == FULL_CLASSES.size()) {
      classes = FULL_CLASSES;
    } else if (classCount == LEGACY_CLASSES.size()) {
      classes = LEGACY_CLASSES;
    } else {
      throw new IllegalArgumentException("Unsupported ONNX detector class count: " + classCount);
    }
    if (outputShape[2] <= 0) {
      throw new IllegalArgumentException("The ONNX detector has no prediction rows");
    }
    return classes;
  }

  private static Path validateModel(Path model) {
    if (model == null) throw new IllegalArgumentException("An ONNX model path is required");
    final var normalized = model.toAbsolutePath().normalize();
    try {
      if (!Files.isRegularFile(normalized)) {
        throw new IllegalArgumentException("The ONNX model does not exist: " + normalized);
      }
      final var size = Files.size(normalized);
      if (size <= 0 || size > MAX_MODEL_BYTES) {
        throw new IllegalArgumentException("The ONNX model size is invalid: " + size);
      }
    } catch (IOException exception) {
      throw new IllegalArgumentException("The ONNX model could not be inspected", exception);
    }
    return normalized;
  }

  private static String sha256(Path path) {
    try {
      final var digest = MessageDigest.getInstance("SHA-256");
      try (var input = Files.newInputStream(path)) {
        final var buffer = new byte[64 * 1024];
        for (var count = input.read(buffer); count >= 0; count = input.read(buffer)) {
          digest.update(buffer, 0, count);
        }
      }
      return java.util.HexFormat.of().formatHex(digest.digest());
    } catch (IOException | NoSuchAlgorithmException exception) {
      throw new IllegalArgumentException("The ONNX model could not be hashed", exception);
    }
  }

  private static double clip(double value, int upperBound) {
    return Math.max(0.0, Math.min(value, upperBound));
  }

  private record PreparedImage(float[] channelFirstPixels, double ratio, int left, int top) {}
}
