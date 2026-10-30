/*
 * Logisim-evolution - digital logic design tool and simulator
 * Copyright by the Logisim-evolution developers
 *
 * https://github.com/logisim-evolution/
 *
 * This is free software released under GNU GPLv3 license
 */

package com.cburch.logisim.ai.vision.client;

import com.cburch.logisim.ai.util.SimpleJson;
import com.cburch.logisim.ai.vision.model.VisionIr;
import com.cburch.logisim.ai.vision.model.VisionIrCodec;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;

/** Multipart HTTP client for the loopback-only Python recognition service. */
public final class VisionServiceClient implements VisionService {
  public static final URI DEFAULT_ENDPOINT = URI.create("http://127.0.0.1:8765/");
  private static final int MAX_IMAGE_BYTES = 10 * 1024 * 1024;
  private static final int MAX_RESPONSE_CHARS = 4 * 1024 * 1024;

  private final URI endpoint;
  private final HttpClient httpClient;
  private final Duration requestTimeout;

  public VisionServiceClient() {
    this(configuredEndpoint());
  }

  public VisionServiceClient(URI endpoint) {
    this(
        endpoint,
        HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build(),
        Duration.ofSeconds(30));
  }

  VisionServiceClient(URI endpoint, HttpClient httpClient, Duration requestTimeout) {
    validateEndpoint(endpoint);
    this.endpoint = endpoint;
    this.httpClient = httpClient;
    this.requestTimeout = requestTimeout;
  }

  @Override
  public Health health() {
    final var request =
        HttpRequest.newBuilder(endpoint.resolve("/health"))
            .GET()
            .timeout(Duration.ofSeconds(3))
            .header("Accept", "application/json")
            .build();
    final var root = responseObject(send(request));
    return new Health(
        text(root, "status"),
        text(root, "protocolVersion"),
        text(root, "serviceVersion"),
        text(root, "modelVersion"),
        text(root, "detectorMode"));
  }

  @Override
  public VisionIr recognize(Path image) {
    if (image == null || !Files.isRegularFile(image)) {
      throw new VisionServiceException(
          VisionServiceException.Reason.IO_ERROR, "The selected image does not exist");
    }
    final byte[] imageBytes;
    try {
      final var size = Files.size(image);
      if (size <= 0 || size > MAX_IMAGE_BYTES) {
        throw new VisionServiceException(
            VisionServiceException.Reason.REJECTED,
            "The selected image must be between 1 byte and 10 MiB");
      }
      imageBytes = Files.readAllBytes(image);
    } catch (IOException exception) {
      throw new VisionServiceException(
          VisionServiceException.Reason.IO_ERROR,
          "The selected image could not be read",
          exception);
    }

    final var boundary = "----LogisimVision" + UUID.randomUUID().toString().replace("-", "");
    final var request =
        HttpRequest.newBuilder(endpoint.resolve("/recognize"))
            .timeout(requestTimeout)
            .header("Accept", "application/json")
            .header("Content-Type", "multipart/form-data; boundary=" + boundary)
            .POST(HttpRequest.BodyPublishers.ofByteArray(multipart(boundary, imageBytes)))
            .build();
    final var response = send(request);
    final var body = checkedBody(response);
    try {
      return VisionIrCodec.fromJson(body);
    } catch (RuntimeException exception) {
      throw new VisionServiceException(
          VisionServiceException.Reason.INVALID_RESPONSE,
          "The vision service returned invalid Vision IR",
          exception);
    }
  }

  private HttpResponse<String> send(HttpRequest request) {
    try {
      final var response =
          httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
      if (response.statusCode() < 200 || response.statusCode() >= 300) {
        throw serviceError(response);
      }
      return response;
    } catch (HttpTimeoutException exception) {
      throw new VisionServiceException(
          VisionServiceException.Reason.TIMEOUT,
          "The vision service did not respond before the timeout",
          exception);
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new VisionServiceException(
          VisionServiceException.Reason.CANCELLED, "Image recognition was cancelled", exception);
    } catch (IOException exception) {
      final var unavailable =
          exception instanceof ConnectException || exception.getCause() instanceof ConnectException;
      throw new VisionServiceException(
          unavailable
              ? VisionServiceException.Reason.SERVICE_UNAVAILABLE
              : VisionServiceException.Reason.IO_ERROR,
          unavailable
              ? "The local vision service is not running"
              : "Communication with the vision service failed",
          exception);
    }
  }

  private static VisionServiceException serviceError(HttpResponse<String> response) {
    var code = "HTTP_" + response.statusCode();
    var message = "Vision service returned HTTP " + response.statusCode();
    try {
      final var root = SimpleJson.parseObject(response.body());
      if (root.get("error") instanceof Map<?, ?> error) {
        if (error.get("code") != null) code = String.valueOf(error.get("code"));
        if (error.get("message") != null) message = String.valueOf(error.get("message"));
      }
    } catch (RuntimeException ignored) {
      // Keep the bounded HTTP fallback instead of exposing an arbitrary response body.
    }
    return new VisionServiceException(
        VisionServiceException.Reason.REJECTED, code, message, null);
  }

  private static byte[] multipart(String boundary, byte[] imageBytes) {
    try {
      final var output = new ByteArrayOutputStream(imageBytes.length + 512);
      output.write(("--" + boundary + "\r\n").getBytes(StandardCharsets.US_ASCII));
      output.write(
          "Content-Disposition: form-data; name=\"image\"; filename=\"circuit.png\"\r\n"
              .getBytes(StandardCharsets.US_ASCII));
      output.write("Content-Type: image/png\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
      output.write(imageBytes);
      output.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.US_ASCII));
      return output.toByteArray();
    } catch (IOException exception) {
      throw new IllegalStateException("Could not create the multipart request", exception);
    }
  }

  private static Map<String, Object> responseObject(HttpResponse<String> response) {
    final var body = checkedBody(response);
    try {
      return SimpleJson.parseObject(body);
    } catch (RuntimeException exception) {
      throw new VisionServiceException(
          VisionServiceException.Reason.INVALID_RESPONSE,
          "The vision service returned invalid JSON",
          exception);
    }
  }

  private static String checkedBody(HttpResponse<String> response) {
    final var body = response.body();
    if (body == null || body.isBlank() || body.length() > MAX_RESPONSE_CHARS) {
      throw new VisionServiceException(
          VisionServiceException.Reason.INVALID_RESPONSE,
          "The vision service returned an empty or oversized response");
    }
    return body;
  }

  private static String text(Map<String, Object> object, String key) {
    final var value = object.get(key);
    if (!(value instanceof String text) || text.isBlank()) {
      throw new VisionServiceException(
          VisionServiceException.Reason.INVALID_RESPONSE,
          "The vision service response is missing " + key);
    }
    return text;
  }

  private static URI configuredEndpoint() {
    final var property = System.getProperty("logisim.vision.endpoint");
    final var environment = System.getenv("LOGISIM_VISION_ENDPOINT");
    final var value =
        property != null && !property.isBlank()
            ? property
            : environment != null && !environment.isBlank()
                ? environment
                : DEFAULT_ENDPOINT.toString();
    return URI.create(value);
  }

  private static void validateEndpoint(URI endpoint) {
    if (endpoint == null
        || !"http".equalsIgnoreCase(endpoint.getScheme())
        || endpoint.getHost() == null
        || !(endpoint.getHost().equalsIgnoreCase("localhost")
            || endpoint.getHost().equals("127.0.0.1")
            || endpoint.getHost().equals("::1"))) {
      throw new IllegalArgumentException("The one-week vision endpoint must use loopback HTTP");
    }
  }
}
