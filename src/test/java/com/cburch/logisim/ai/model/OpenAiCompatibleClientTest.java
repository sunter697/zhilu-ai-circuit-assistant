/*
 * Logisim-evolution - digital logic design tool and simulator
 * Copyright by the Logisim-evolution developers
 *
 * https://github.com/logisim-evolution/
 *
 * This is free software released under GNU GPLv3 license
 */

package com.cburch.logisim.ai.model;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

class OpenAiCompatibleClientTest {
  @Test
  @SuppressWarnings("unchecked")
  void providerErrorBodyCannotExposeEchoedCredential() throws Exception {
    final var client = mock(HttpClient.class);
    final HttpResponse<String> response = mock(HttpResponse.class);
    when(response.statusCode()).thenReturn(401);
    when(response.body()).thenReturn("Authorization: Bearer audit-fixture-secret");
    when(client.<String>send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
        .thenReturn(response);
    final var config =
        new ModelConfig(
            URI.create("https://example.invalid"),
            "offline",
            "audit-fixture-secret",
            Duration.ofSeconds(1));
    final var error =
        assertThrows(
            IllegalStateException.class,
            () -> new OpenAiCompatibleClient(client).complete(config, List.of(), List.of()));
    assertTrue(error.getMessage().contains("401"));
    assertFalse(error.getMessage().contains("audit-fixture-secret"));
    assertFalse(error.getMessage().contains("Authorization"));
  }
}
