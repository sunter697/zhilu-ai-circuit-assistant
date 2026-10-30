/*
 * Logisim-evolution - digital logic design tool and simulator
 * Copyright by the Logisim-evolution developers
 *
 * https://github.com/logisim-evolution/
 *
 * This is free software released under GNU GPLv3 license
 */

package com.cburch.logisim.ai.model;

import java.util.List;
import java.util.Map;

public record ModelTurn(
    String content,
    List<ModelToolCall> toolCalls,
    String finishReason,
    Map<String, Object> rawAssistantMessage) {
  public boolean hasToolCalls() {
    return toolCalls != null && !toolCalls.isEmpty();
  }
}
