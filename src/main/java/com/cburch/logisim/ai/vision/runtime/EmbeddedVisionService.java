/*
 * Logisim-evolution - digital logic design tool and simulator
 * Copyright by the Logisim-evolution developers
 *
 * https://github.com/logisim-evolution/
 *
 * This is free software released under GNU GPLv3 license
 */

package com.cburch.logisim.ai.vision.runtime;

import com.cburch.logisim.ai.vision.client.VisionService;
import com.cburch.logisim.ai.vision.client.VisionServiceException;
import com.cburch.logisim.ai.vision.model.VisionIr;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import javax.imageio.ImageIO;

/** In-process Java implementation of the native-v05 circuit-vision pipeline. */
public final class EmbeddedVisionService implements VisionService, AutoCloseable {
  public static final String MODEL_SHA256 =
      "1fce32b1b4f5a7f52754264be401d6d6a766eadd75c30305852d5761fa92318b";
  private static final long MAX_IMAGE_BYTES = 10L * 1024 * 1024;
  private static final int MAX_DIMENSION = 4096;
  private static final long MAX_PIXELS = 16L * 1024 * 1024;
  private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(30);

  private final Object initializationLock = new Object();
  private final Path model;
  private final NativeCircuitTopologyRecognizer topologyRecognizer;
  private final Duration operationTimeout;
  private final ExecutorService inferenceExecutor;
  private volatile OnnxComponentDetector detector;
  private volatile boolean closed;

  public EmbeddedVisionService(Path model) {
    this(model, new NativeCircuitTopologyRecognizer(), DEFAULT_TIMEOUT);
  }

  EmbeddedVisionService(
      Path model,
      NativeCircuitTopologyRecognizer topologyRecognizer,
      Duration operationTimeout) {
    if (model == null) throw new IllegalArgumentException("An ONNX model path is required");
    if (operationTimeout == null || operationTimeout.isZero() || operationTimeout.isNegative()) {
      throw new IllegalArgumentException("The embedded recognition timeout must be positive");
    }
    this.model = model.toAbsolutePath().normalize();
    this.topologyRecognizer = topologyRecognizer;
    this.operationTimeout = operationTimeout;
    inferenceExecutor =
        Executors.newSingleThreadExecutor(
            runnable -> {
              final var thread = new Thread(runnable, "logisim-vision-inference");
              thread.setDaemon(true);
              return thread;
            });
  }

  @Override
  public Health health() {
    return executeWithTimeout(
        () -> {
          final var activeDetector = detector();
          return new Health(
              "ok",
              VisionIr.VERSION,
              "embedded-java/0.1",
              "best.onnx:" + activeDetector.modelSha256().substring(0, 12),
              "java-onnx-native-v05");
        });
  }

  @Override
  public VisionIr recognize(Path imagePath) {
    return executeWithTimeout(() -> recognizeNow(imagePath));
  }

  private VisionIr recognizeNow(Path imagePath) {
    final var normalized = validateImagePath(imagePath);
    checkCancelled();
    final BufferedImage image;
    try {
      image = ImageIO.read(normalized.toFile());
    } catch (IOException exception) {
      throw new VisionServiceException(
          VisionServiceException.Reason.IO_ERROR,
          "The selected image could not be decoded",
          exception);
    }
    if (image == null) {
      throw new VisionServiceException(
          VisionServiceException.Reason.REJECTED, "The selected image format is not supported");
    }
    if (image.getWidth() > MAX_DIMENSION
        || image.getHeight() > MAX_DIMENSION
        || (long) image.getWidth() * image.getHeight() > MAX_PIXELS) {
      throw new VisionServiceException(
          VisionServiceException.Reason.REJECTED,
          "The selected image dimensions are too large");
    }
    checkCancelled();
    try {
      final var detections = detector().detect(image);
      checkCancelled();
      return topologyRecognizer.recognize(image, sha256(normalized), detections);
    } catch (VisionServiceException exception) {
      throw exception;
    } catch (RuntimeException exception) {
      if (Thread.currentThread().isInterrupted()) {
        throw new VisionServiceException(
            VisionServiceException.Reason.CANCELLED,
            "Image recognition was cancelled",
            exception);
      }
      throw new VisionServiceException(
          VisionServiceException.Reason.REJECTED,
          "Embedded image recognition failed: " + exception.getMessage(),
          exception);
    }
  }

  @Override
  public void close() {
    synchronized (initializationLock) {
      if (closed) return;
      closed = true;
    }
    inferenceExecutor.shutdownNow();
    if (awaitTermination(2, TimeUnit.SECONDS)) {
      closeDetector();
      return;
    }
    final var cleanup =
        new Thread(
            () -> {
              while (!awaitTermination(1, TimeUnit.DAYS)) {
                // Wait until an in-flight native call actually returns before closing its session.
              }
              try {
                closeDetector();
              } catch (RuntimeException ignored) {
                // The application is already closing and this daemon must not keep it alive.
              }
            },
            "logisim-vision-cleanup");
    cleanup.setDaemon(true);
    cleanup.start();
  }

  private <T> T executeWithTimeout(Callable<T> action) {
    if (closed) throw new IllegalStateException("The embedded vision service is closed");
    final java.util.concurrent.Future<T> future;
    try {
      future = inferenceExecutor.submit(action);
    } catch (RejectedExecutionException exception) {
      throw new IllegalStateException("The embedded vision service is closed", exception);
    }
    try {
      return future.get(operationTimeout.toNanos(), TimeUnit.NANOSECONDS);
    } catch (TimeoutException exception) {
      future.cancel(true);
      throw new VisionServiceException(
          VisionServiceException.Reason.TIMEOUT,
          "Embedded image recognition exceeded " + operationTimeout.toSeconds() + " seconds",
          exception);
    } catch (InterruptedException exception) {
      future.cancel(true);
      Thread.currentThread().interrupt();
      throw new VisionServiceException(
          VisionServiceException.Reason.CANCELLED,
          "Image recognition was cancelled",
          exception);
    } catch (CancellationException exception) {
      throw new VisionServiceException(
          VisionServiceException.Reason.CANCELLED,
          "Image recognition was cancelled",
          exception);
    } catch (ExecutionException exception) {
      final var cause = exception.getCause();
      if (cause instanceof RuntimeException runtimeException) throw runtimeException;
      if (cause instanceof Error error) throw error;
      throw new IllegalStateException("Embedded image recognition failed", cause);
    }
  }

  private boolean awaitTermination(long timeout, TimeUnit unit) {
    try {
      return inferenceExecutor.awaitTermination(timeout, unit);
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      return false;
    }
  }

  private void closeDetector() {
    final OnnxComponentDetector activeDetector;
    synchronized (initializationLock) {
      activeDetector = detector;
      detector = null;
    }
    if (activeDetector != null) activeDetector.close();
  }

  private OnnxComponentDetector detector() {
    var current = detector;
    if (current != null) return current;
    synchronized (initializationLock) {
      if (closed) throw new IllegalStateException("The embedded vision service is closed");
      current = detector;
      if (current == null) {
        current = new OnnxComponentDetector(model, MODEL_SHA256);
        detector = current;
      }
      return current;
    }
  }

  private static Path validateImagePath(Path imagePath) {
    if (imagePath == null) {
      throw new VisionServiceException(
          VisionServiceException.Reason.IO_ERROR, "A circuit image is required");
    }
    final var normalized = imagePath.toAbsolutePath().normalize();
    try {
      if (!Files.isRegularFile(normalized)) {
        throw new VisionServiceException(
            VisionServiceException.Reason.IO_ERROR, "The selected image does not exist");
      }
      final var size = Files.size(normalized);
      if (size <= 0 || size > MAX_IMAGE_BYTES) {
        throw new VisionServiceException(
            VisionServiceException.Reason.REJECTED,
            "The selected image must be between 1 byte and 10 MiB");
      }
    } catch (IOException exception) {
      throw new VisionServiceException(
          VisionServiceException.Reason.IO_ERROR,
          "The selected image could not be inspected",
          exception);
    }
    return normalized;
  }

  private static void checkCancelled() {
    if (Thread.currentThread().isInterrupted()) {
      throw new VisionServiceException(
          VisionServiceException.Reason.CANCELLED, "Image recognition was cancelled");
    }
  }

  private static String sha256(Path path) {
    try {
      final var digest = MessageDigest.getInstance("SHA-256");
      try (var input = Files.newInputStream(path)) {
        final var buffer = new byte[64 * 1024];
        for (var count = input.read(buffer); count >= 0; count = input.read(buffer)) {
          checkCancelled();
          digest.update(buffer, 0, count);
        }
      }
      return HexFormat.of().formatHex(digest.digest());
    } catch (IOException exception) {
      throw new VisionServiceException(
          VisionServiceException.Reason.IO_ERROR,
          "The selected image could not be hashed",
          exception);
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is unavailable", exception);
    }
  }
}
