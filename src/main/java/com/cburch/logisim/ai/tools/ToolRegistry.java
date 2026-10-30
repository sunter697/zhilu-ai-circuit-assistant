/*
 * Logisim-evolution - digital logic design tool and simulator
 * Copyright by the Logisim-evolution developers
 *
 * https://github.com/logisim-evolution/
 *
 * This is free software released under GNU GPLv3 license
 */

package com.cburch.logisim.ai.tools;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Explicit allow-list of operations the model may request. */
public final class ToolRegistry {
  private final Map<String, CircuitTool> tools = new LinkedHashMap<>();

  public ToolRegistry register(CircuitTool tool) {
    if (tool == null) throw new IllegalArgumentException("Tool is required");
    if (tools.putIfAbsent(tool.name(), tool) != null)
      throw new IllegalArgumentException("Duplicate tool: " + tool.name());
    return this;
  }

  public CircuitTool require(String name) {
    final var tool = tools.get(name);
    if (tool == null) throw new IllegalArgumentException("Unknown tool: " + name);
    return tool;
  }

  public List<CircuitTool> all() {
    return Collections.unmodifiableList(new ArrayList<>(tools.values()));
  }

  public List<Map<String, Object>> openAiToolDefinitions() {
    final var result = new ArrayList<Map<String, Object>>();
    for (final var tool : tools.values()) {
      result.add(
          Map.of(
              "type",
              "function",
              "function",
              Map.of(
                  "name", tool.name(),
                  "description", tool.description(),
                  "parameters", tool.inputSchema())));
    }
    return result;
  }
}
