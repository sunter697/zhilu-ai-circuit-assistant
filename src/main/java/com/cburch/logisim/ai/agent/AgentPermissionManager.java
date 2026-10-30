/*
 * Logisim-evolution - digital logic design tool and simulator
 * Copyright by the Logisim-evolution developers
 *
 * https://github.com/logisim-evolution/
 *
 * This is free software released under GNU GPLv3 license
 */

package com.cburch.logisim.ai.agent;

import java.util.concurrent.atomic.AtomicBoolean;

/** Session-scoped permission gate for circuit writes. */
public final class AgentPermissionManager {
  private final AtomicBoolean editsAllowed = new AtomicBoolean(false);

  public boolean isEditsAllowed() {
    return editsAllowed.get();
  }

  public void setEditsAllowed(boolean allowed) {
    editsAllowed.set(allowed);
  }
}
