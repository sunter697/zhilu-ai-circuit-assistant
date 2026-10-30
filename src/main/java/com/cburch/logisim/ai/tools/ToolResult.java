/*
 * Logisim-evolution - digital logic design tool and simulator
 * Copyright by the Logisim-evolution developers
 *
 * https://github.com/logisim-evolution/
 *
 * This is free software released under GNU GPLv3 license
 */

package com.cburch.logisim.ai.tools;

import java.util.LinkedHashMap;
import java.util.Map;

public record ToolResult(boolean ok, Map<String, Object> data, String error) {
  public static ToolResult success(Map<String, Object> data) {
    return new ToolResult(true, data == null ? Map.of() : data, null);
  }

  public static ToolResult failure(String error) {
    return new ToolResult(false, Map.of(), error == null ? "Tool failed" : error);
  }

  public Map<String, Object> toMap() {
    final var result = new LinkedHashMap<String, Object>();
    result.put("ok", ok);
    result.put("data", data);
    if (error != null) result.put("error", error);
    return result;
  }
}
