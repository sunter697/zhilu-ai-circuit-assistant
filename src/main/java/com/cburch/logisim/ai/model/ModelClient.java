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

public interface ModelClient {
  ModelTurn complete(
      ModelConfig config,
      List<Map<String, Object>> messages,
      List<Map<String, Object>> toolDefinitions)
      throws Exception;
}
