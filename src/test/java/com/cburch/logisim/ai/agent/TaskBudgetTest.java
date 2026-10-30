/*
 * Logisim-evolution - digital logic design tool and simulator
 * Copyright by the Logisim-evolution developers
 *
 * https://github.com/logisim-evolution/
 *
 * This is free software released under GNU GPLv3 license
 */

package com.cburch.logisim.ai.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class TaskBudgetTest {
  @Test
  void defaultsMatchAgentPolicy() {
    final var budget = TaskBudget.defaults();
    assertEquals(20, budget.maxModelRounds());
    assertEquals(3, budget.maxRepairAttempts());
    assertEquals(30, budget.toolTimeoutSeconds());
    assertEquals(300, budget.taskTimeoutSeconds());
  }

  @Test
  void rejectsInvalidValues() {
    assertThrows(IllegalArgumentException.class, () -> new TaskBudget(0, 3, 30, 300));
  }
}
