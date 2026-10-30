/*
 * Logisim-evolution - digital logic design tool and simulator
 * Copyright by the Logisim-evolution developers
 *
 * https://github.com/logisim-evolution/
 *
 * This is free software released under GNU GPLv3 license
 */

package com.cburch.logisim.ai.agent;

/** Default execution limits for one autonomous task. */
public record TaskBudget(
    int maxModelRounds, int maxRepairAttempts, int toolTimeoutSeconds, int taskTimeoutSeconds) {
  public static TaskBudget defaults() {
    return new TaskBudget(20, 3, 30, 300);
  }

  public TaskBudget {
    if (maxModelRounds < 1
        || maxRepairAttempts < 0
        || toolTimeoutSeconds < 1
        || taskTimeoutSeconds < 1) {
      throw new IllegalArgumentException("Task budget values are out of range");
    }
  }
}
