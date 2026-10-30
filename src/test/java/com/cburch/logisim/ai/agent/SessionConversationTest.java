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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.cburch.logisim.circuit.Circuit;
import com.cburch.logisim.file.Loader;
import com.cburch.logisim.file.LogisimFile;
import com.cburch.logisim.proj.Project;
import org.junit.jupiter.api.Test;

class SessionConversationTest {
  @Test
  void retainsOriginalUserAndAssistantTextWithoutToolOrConfigurationInput() {
    final var history = new SessionConversation();
    history.addUser("  给我设计一个4位乘法器\n输入 A、B。");
    history.addAssistant("请确认无符号 4×4 位，完整输出 8 位。");
    final var rendered = history.render();
    assertTrue(rendered.contains("USER:   给我设计一个4位乘法器\n输入 A、B。"));
    assertTrue(rendered.contains("ASSISTANT: 请确认无符号 4×4 位，完整输出 8 位。"));
    assertFalse(rendered.contains("call-"));
    assertFalse(rendered.contains("apiKey"));
    assertFalse(rendered.contains("tool_call"));
    assertTrue(rendered.contains("intent hints only"));
    assertTrue(rendered.contains("current host-confirmed specification"));
    assertTrue(rendered.contains("Do not reuse old component IDs"));
    assertTrue(rendered.contains("past confirmations or edit permissions"));
  }

  @Test
  void evictsOldestMessagesAfterTwelveEntries() {
    final var history = new SessionConversation();
    for (var i = 0; i < 13; i++) {
      history.addUser("message-" + i + "-end");
    }
    final var rendered = history.render();
    assertFalse(rendered.contains("message-0-end"));
    assertTrue(rendered.contains("message-1-end"));
    assertTrue(rendered.contains("message-12-end"));
    assertEquals(12, rendered.lines().filter(line -> line.startsWith("USER: ")).count());
  }

  @Test
  void truncatesIndividualMessagesAndEvictsToTotalCharacterBudget() {
    final var history = new SessionConversation();
    history.addUser("x".repeat(2000) + "discarded-suffix");
    assertTrue(history.render().contains("x".repeat(2000)));
    assertFalse(history.render().contains("discarded-suffix"));
    for (var i = 1; i <= 6; i++) {
      history.addAssistant("message-" + i + ":" + "y".repeat(1990));
    }
    final var rendered = history.render();
    assertFalse(rendered.contains("x".repeat(2000)));
    assertTrue(rendered.contains("message-1:"));
    assertTrue(rendered.contains("message-6:"));
    assertEquals(6, rendered.lines().filter(line -> line.startsWith("ASSISTANT: ")).count());
  }

  @Test
  void doesNotSplitSupplementaryUnicodeAtTruncationBoundary() {
    final var history = new SessionConversation();
    history.addUser("x".repeat(1999) + "😀" + "ignored");
    assertTrue(history.render().contains("USER: " + "x".repeat(1999) + "\n"));
    assertFalse(history.render().contains("😀"));
    assertFalse(history.render().contains("ignored"));
  }

  @Test
  void ignoresNullOrBlankTextAndCanExplicitlyClearHistory() {
    final var history = new SessionConversation();
    history.addUser(null);
    history.addAssistant(" \n\t");
    assertEquals("", history.render());
    history.addUser("Remember multiplication");
    history.clear();
    assertEquals("", history.render());
  }

  @Test
  void retainsSameObjectScopeAndClearsOnProjectFileOrCircuitIdentityChange() {
    final var file = LogisimFile.createNew(new Loader(null), null);
    final var secondFile = LogisimFile.createNew(new Loader(null), null);
    final var project = new Project(file);
    final var secondProject = new Project(file);
    try {
      final var circuit = file.getMainCircuit();
      final var sameNameCircuit = new Circuit(circuit.getName(), file, project);
      final var history = new SessionConversation();
      history.scope(project, file, circuit);
      history.addUser("first");
      history.scope(project, file, circuit);
      assertTrue(history.render().contains("first"));

      history.scope(secondProject, file, circuit);
      assertEquals("", history.render());
      history.addUser("second");
      history.scope(secondProject, secondFile, circuit);
      assertEquals("", history.render());
      history.addUser("third");
      history.scope(secondProject, secondFile, sameNameCircuit);
      assertEquals("", history.render());
    } finally {
      project.getSimulator().shutDown();
      secondProject.getSimulator().shutDown();
      file.stopAutosaveThread(false);
      secondFile.stopAutosaveThread(false);
    }
  }
}
