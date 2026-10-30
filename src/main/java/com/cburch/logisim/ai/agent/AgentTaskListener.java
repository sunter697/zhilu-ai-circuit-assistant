/*
 * Logisim-evolution - digital logic design tool and simulator
 * Copyright by the Logisim-evolution developers
 *
 * https://github.com/logisim-evolution/
 *
 * This is free software released under GNU GPLv3 license
 */

package com.cburch.logisim.ai.agent;

import com.cburch.logisim.ai.validation.TaskSpec;
import com.cburch.logisim.ai.validation.VerificationEvidence;
import java.nio.file.Path;
import java.util.Map;

public interface AgentTaskListener {
  default void stateChanged(AgentState state, String detail) {}

  default void assistantMessage(String message) {}

  /** Typed display callback; text-only consumers retain the existing confirmation message. */
  default void specificationProposed(TaskSpec specification, String presentation) {
    assistantMessage(presentation);
  }

  default void toolActivity(String toolName, Map<String, Object> result) {}

  default void reviewReady(
      String taskId,
      String finalMessage,
      VerificationEvidence evidence,
      Map<String, Object> diff,
      boolean applyAllowed,
      Path taskDirectory) {}

  default void failed(String message, Throwable error) {}
}
