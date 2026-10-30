/*
 * Logisim-evolution - digital logic design tool and simulator
 * Copyright by the Logisim-evolution developers
 *
 * https://github.com/logisim-evolution/
 *
 * This is free software released under GNU GPLv3 license
 */

package com.cburch.logisim.ai.agent;

/** Stable task states exposed to the UI and task log. */
public enum AgentState {
  READY,
  CHECKING_PERMISSION,
  PLANNING,
  WAITING_FOR_USER,
  PREPARING_WORKSPACE,
  EXECUTING,
  VALIDATING,
  REPAIRING,
  REVIEW,
  APPLYING,
  COMPLETED,
  FAILED_VALIDATION,
  FAILED_EXECUTION,
  FAILED_BUDGET,
  FAILED_INTERNAL,
  CANCELLED,
  DISCARDED
}
