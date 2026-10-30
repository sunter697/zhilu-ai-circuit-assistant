/*
 * Logisim-evolution - digital logic design tool and simulator
 * Copyright by the Logisim-evolution developers
 *
 * https://github.com/logisim-evolution/
 *
 * This is free software released under GNU GPLv3 license
 */

package com.cburch.logisim.ai.vision.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class VisionServiceClientTest {
  @TempDir Path temporary;

  @Test
  void healthAndMultipartRecognitionUseTheLoopbackProtocol() throws Exception {
    final var receivedMultipart = new AtomicBoolean();
    final var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/health",
        exchange ->
            respond(
                exchange,
                200,
                """
                {"status":"ok","protocolVersion":"vision-ir/0.1",\
                "serviceVersion":"test","modelVersion":"test-model",\
                "detectorMode":"test-detector"}
                """));
    server.createContext(
        "/recognize",
        exchange -> {
          final var type = exchange.getRequestHeaders().getFirst("Content-Type");
          final var body = exchange.getRequestBody().readAllBytes();
          receivedMultipart.set(
              type != null
                  && type.startsWith("multipart/form-data; boundary=")
                  && new String(body, StandardCharsets.ISO_8859_1)
                      .contains("name=\"image\"; filename=\"circuit.png\""));
          respond(exchange, 200, fixture("and.json"));
        });
    server.start();
    try {
      final var endpoint = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/");
      final var client = new VisionServiceClient(endpoint);
      final var health = client.health();
      assertEquals("vision-ir/0.1", health.protocolVersion());
      assertEquals("test-detector", health.detectorMode());

      final var image = temporary.resolve("test.png");
      Files.write(image, new byte[] {(byte) 0x89, 'P', 'N', 'G'});
      final var ir = client.recognize(image);
      assertEquals(4, ir.components().size());
      assertEquals(3, ir.nets().size());
      assertTrue(receivedMultipart.get());
    } finally {
      server.stop(0);
    }
  }

  @Test
  void structuredServiceErrorIsPreserved() throws Exception {
    final var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/recognize",
        exchange ->
            respond(
                exchange,
                422,
                "{\"error\":{\"code\":\"UNSUPPORTED_IMAGE\",\"message\":\"PNG required\"}}"));
    server.start();
    try {
      final var endpoint = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/");
      final var client = new VisionServiceClient(endpoint);
      final var image = temporary.resolve("bad.png");
      Files.write(image, new byte[] {1});
      final var error = assertThrows(VisionServiceException.class, () -> client.recognize(image));
      assertEquals(VisionServiceException.Reason.REJECTED, error.reason());
      assertEquals("UNSUPPORTED_IMAGE", error.serviceCode());
      assertEquals("PNG required", error.getMessage());
    } finally {
      server.stop(0);
    }
  }

  @Test
  void nonLoopbackEndpointIsRejected() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new VisionServiceClient(URI.create("http://example.com:8765/")));
  }

  @Test
  void stoppedServiceIsReportedAsUnavailable() throws Exception {
    final var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    final var endpoint = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/");
    server.start();
    server.stop(0);

    final var error =
        assertThrows(VisionServiceException.class, () -> new VisionServiceClient(endpoint).health());
    assertEquals(VisionServiceException.Reason.SERVICE_UNAVAILABLE, error.reason());
  }

  @Test
  void slowRecognitionIsReportedAsTimeout() throws Exception {
    final var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/recognize",
        exchange -> {
          exchange.getRequestBody().readAllBytes();
          try {
            Thread.sleep(250);
          } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
          }
          respond(exchange, 200, fixture("and.json"));
        });
    server.start();
    try {
      final var endpoint = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/");
      final var client =
          new VisionServiceClient(
              endpoint,
              HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).build(),
              Duration.ofMillis(50));
      final var image = temporary.resolve("slow.png");
      Files.write(image, new byte[] {(byte) 0x89, 'P', 'N', 'G'});

      final var error =
          assertThrows(VisionServiceException.class, () -> client.recognize(image));
      assertEquals(VisionServiceException.Reason.TIMEOUT, error.reason());
    } finally {
      server.stop(0);
    }
  }

  private static void respond(HttpExchange exchange, int status, String json) throws IOException {
    final var encoded = json.getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().set("Content-Type", "application/json");
    exchange.sendResponseHeaders(status, encoded.length);
    exchange.getResponseBody().write(encoded);
    exchange.close();
  }

  private static String fixture(String name) throws IOException {
    try (var stream =
        VisionServiceClientTest.class.getResourceAsStream(
            "/com/cburch/logisim/ai/vision/" + name)) {
      if (stream == null) throw new IOException("Missing fixture: " + name);
      return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
    }
  }
}
