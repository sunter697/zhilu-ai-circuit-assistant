/*
 * Logisim-evolution - digital logic design tool and simulator
 * Copyright by the Logisim-evolution developers
 *
 * https://github.com/logisim-evolution/
 *
 * This is free software released under GNU GPLv3 license
 */

package com.cburch.logisim.ai.tools;

import java.util.Map;

public interface CircuitTool {
  String name();

  String description();

  Map<String, Object> inputSchema();

  boolean writesCircuit();

  ToolResult execute(Map<String, Object> arguments, ToolExecutionContext context) throws Exception;
}
