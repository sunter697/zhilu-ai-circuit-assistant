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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class TaskExecutionGuardTest {
  @Test
  void humanWaitingDoesNotConsumeExecutionBudget() {
    final var clock = new AtomicLong(100);
    final var guard = new TaskExecutionGuard(Duration.ofNanos(10), clock::get);
    clock.addAndGet(3);
    guard.pause();
    clock.addAndGet(1000);
    guard.check();
    assertEquals(7, guard.remainingNanos());

    guard.resume();
    clock.addAndGet(2);
    assertEquals(5, guard.remainingNanos());
    guard.pause();
    clock.addAndGet(1000);
    guard.resume();
    clock.addAndGet(5);
    assertEquals(0, guard.remainingNanos());
    assertThrows(TaskExecutionGuard.BudgetExceededException.class, guard::check);
  }

  @Test
  void repeatedPauseAndResumeCannotReplenishBudget() {
    final var clock = new AtomicLong(100);
    final var guard = new TaskExecutionGuard(Duration.ofNanos(10), clock::get);
    clock.addAndGet(4);
    guard.pause();
    clock.addAndGet(1000);
    guard.pause();
    assertEquals(6, guard.remainingNanos());

    guard.resume();
    clock.addAndGet(2);
    guard.resume();
    assertEquals(4, guard.remainingNanos());
    clock.addAndGet(4);
    guard.pause();
    clock.addAndGet(1000);
    guard.resume();
    assertEquals(0, guard.remainingNanos());
    assertThrows(TaskExecutionGuard.BudgetExceededException.class, guard::check);
  }

  @Test
  void pausingAnExhaustedTaskDoesNotHideExhaustion() {
    final var clock = new AtomicLong(100);
    final var guard = new TaskExecutionGuard(Duration.ofNanos(10), clock::get);
    clock.addAndGet(11);
    guard.pause();
    clock.addAndGet(1000);
    assertEquals(0, guard.remainingNanos());
    assertThrows(TaskExecutionGuard.BudgetExceededException.class, guard::check);
  }

  @Test
  void cancellationRemainsEffectiveWhilePausedAndAfterResume() {
    final var clock = new AtomicLong(100);
    final var guard = new TaskExecutionGuard(Duration.ofNanos(10), clock::get);
    guard.pause();
    guard.cancel();
    assertThrows(CancellationException.class, guard::check);
    guard.resume();
    assertThrows(CancellationException.class, guard::check);
    assertEquals(10, guard.remainingNanos());
  }

  @Test
  void interruptedWaitingThreadStillCancels() {
    final var clock = new AtomicLong(100);
    final var guard = new TaskExecutionGuard(Duration.ofNanos(10), clock::get);
    guard.pause();
    Thread.currentThread().interrupt();
    try {
      assertThrows(CancellationException.class, guard::check);
    } finally {
      Thread.interrupted();
    }
  }

  @Test
  void simultaneousPauseCallsChargeElapsedTimeOnlyOnce() throws Exception {
    final var clock = new AtomicLong(100);
    final var guard = new TaskExecutionGuard(Duration.ofNanos(10), clock::get);
    clock.addAndGet(3);
    final var ready = new CountDownLatch(2);
    final var start = new CountDownLatch(1);
    final var finished = new CountDownLatch(2);
    final Runnable pause =
        () -> {
          ready.countDown();
          try {
            start.await();
            guard.pause();
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          } finally {
            finished.countDown();
          }
        };
    final var first = new Thread(pause);
    final var second = new Thread(pause);
    first.start();
    second.start();
    try {
      assertTrue(ready.await(5, TimeUnit.SECONDS));
      start.countDown();
      assertTrue(finished.await(5, TimeUnit.SECONDS));
      clock.addAndGet(1000);
      assertEquals(7, guard.remainingNanos());
    } finally {
      start.countDown();
      first.join(5000);
      second.join(5000);
    }
  }
}
