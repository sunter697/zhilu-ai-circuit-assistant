/*
 * Logisim-evolution - digital logic design tool and simulator
 * Copyright by the Logisim-evolution developers
 *
 * https://github.com/logisim-evolution/
 *
 * This is free software released under GNU GPLv3 license
 */

package com.cburch.logisim.ai.util;

import java.lang.reflect.InvocationTargetException;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.SwingUtilities;

/** Executes circuit/UI mutations on Swing's event dispatch thread. */
public final class SwingExecutor {
  private SwingExecutor() {}

  public static void run(Runnable action) {
    call(
        () -> {
          action.run();
          return null;
        });
  }

  public static <T> T call(Callable<T> action) {
    if (SwingUtilities.isEventDispatchThread()) {
      try {
        return action.call();
      } catch (RuntimeException e) {
        throw e;
      } catch (Exception e) {
        throw new RuntimeException(e);
      }
    }
    final var value = new AtomicReference<T>();
    final var error = new AtomicReference<Throwable>();
    try {
      SwingUtilities.invokeAndWait(
          () -> {
            try {
              value.set(action.call());
            } catch (Throwable t) {
              error.set(t);
            }
          });
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new RuntimeException("Interrupted while waiting for the Swing event thread", e);
    } catch (InvocationTargetException e) {
      throw new RuntimeException("Swing event thread failed", e.getCause());
    }
    final var problem = error.get();
    if (problem instanceof RuntimeException runtime) throw runtime;
    if (problem != null) throw new RuntimeException(problem);
    return value.get();
  }
}
