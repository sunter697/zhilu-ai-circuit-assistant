/*
 * Logisim-evolution - digital logic design tool and simulator
 * Copyright by the Logisim-evolution developers
 *
 * https://github.com/logisim-evolution/
 *
 * This is free software released under GNU GPLv3 license
 */

package com.cburch.logisim.ai.agent;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongSupplier;

/** One execution-time budget and cancellation boundary shared by all operations in a task. */
public final class TaskExecutionGuard {
  private final LongSupplier monotonicClock;
  private final AtomicBoolean cancelled = new AtomicBoolean();
  private long remainingBudgetNanos;
  private long activeSince;
  private boolean paused;

  public TaskExecutionGuard(Duration lifetime) {
    this(lifetime, System::nanoTime);
  }

  TaskExecutionGuard(Duration lifetime, LongSupplier monotonicClock) {
    this.monotonicClock = Objects.requireNonNull(monotonicClock);
    remainingBudgetNanos = Objects.requireNonNull(lifetime).toNanos();
    if (remainingBudgetNanos < 0) {
      throw new IllegalArgumentException("Task time budget must not be negative");
    }
    activeSince = monotonicClock.getAsLong();
  }

  public void cancel() {
    cancelled.set(true);
  }

  public boolean isCancelled() {
    return cancelled.get();
  }

  /** Stops charging execution time while the host is waiting for the user. */
  public synchronized void pause() {
    if (!paused) {
      remainingBudgetNanos = remainingWhileActive(monotonicClock.getAsLong());
      paused = true;
    }
  }

  /** Resumes charging time without restoring budget already used by the task. */
  public synchronized void resume() {
    if (paused) {
      activeSince = monotonicClock.getAsLong();
      paused = false;
    }
  }

  public synchronized long remainingNanos() {
    return paused ? remainingBudgetNanos : remainingWhileActive(monotonicClock.getAsLong());
  }

  private long remainingWhileActive(long now) {
    final var elapsed = now - activeSince;
    return elapsed >= remainingBudgetNanos ? 0 : remainingBudgetNanos - elapsed;
  }

  public void check() {
    if (cancelled.get() || Thread.currentThread().isInterrupted()) {
      throw new CancellationException("Task cancelled");
    }
    if (remainingNanos() == 0) {
      throw new BudgetExceededException("Task time budget exhausted");
    }
  }

  public static final class BudgetExceededException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public BudgetExceededException(String message) {
      super(message);
    }
  }
}
