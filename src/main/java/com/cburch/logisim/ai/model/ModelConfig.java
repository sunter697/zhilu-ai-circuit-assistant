/*
 * Logisim-evolution - digital logic design tool and simulator
 * Copyright by the Logisim-evolution developers
 *
 * https://github.com/logisim-evolution/
 *
 * This is free software released under GNU GPLv3 license
 */

package com.cburch.logisim.ai.model;

import java.net.URI;
import java.time.Duration;

/** Session-only cloud model configuration. API keys are never persisted by the AI subsystem. */
public record ModelConfig(URI endpoint, String model, String apiKey, Duration timeout) {
  public ModelConfig {
    if (endpoint == null || !endpoint.isAbsolute())
      throw new IllegalArgumentException("A valid absolute API endpoint is required");
    if (model == null || model.isBlank())
      throw new IllegalArgumentException("Model name is required");
    if (apiKey == null) apiKey = "";
    if (timeout == null) timeout = Duration.ofSeconds(60);
    if (timeout.isZero() || timeout.isNegative()) {
      throw new IllegalArgumentException("Model timeout must be positive");
    }
  }
}
