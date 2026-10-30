/*
 * Logisim-evolution - digital logic design tool and simulator
 * Copyright by the Logisim-evolution developers
 *
 * https://github.com/logisim-evolution/
 *
 * This is free software released under GNU GPLv3 license
 */

package com.cburch.logisim.gui.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.cburch.logisim.ai.agent.AgentController;
import com.cburch.logisim.ai.agent.AgentPermissionManager;
import com.cburch.logisim.ai.agent.AgentState;
import com.cburch.logisim.ai.agent.AgentTaskListener;
import com.cburch.logisim.ai.agent.TaskBudget;
import com.cburch.logisim.ai.i18n.AiStrings;
import com.cburch.logisim.ai.model.ModelConfig;
import com.cburch.logisim.ai.model.ModelToolCall;
import com.cburch.logisim.ai.model.ModelTurn;
import com.cburch.logisim.ai.tools.StandardCircuitTools;
import com.cburch.logisim.ai.transaction.AgentApplyManager;
import com.cburch.logisim.ai.util.SimpleJson;
import com.cburch.logisim.ai.util.SwingExecutor;
import com.cburch.logisim.ai.validation.TaskSpec;
import com.cburch.logisim.ai.validation.ValidationStatus;
import com.cburch.logisim.ai.validation.VerificationEvidence;
import com.cburch.logisim.ai.workspace.WorkspaceManager;
import com.cburch.logisim.file.Loader;
import com.cburch.logisim.file.LogisimFile;
import com.cburch.logisim.proj.Project;
import com.cburch.logisim.util.LocaleManager;
import java.awt.Component;
import java.awt.Container;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JLabel;
import javax.swing.JPasswordField;
import javax.swing.JTabbedPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.JToggleButton;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AiAssistantPanelTest {
  @TempDir Path tempDir;

  @Test
  void checkboxAutomaticallyContinuesRealTaskAfterTextConfirmation() throws Exception {
    final var loader = new Loader(null);
    final var file = LogisimFile.createNew(loader, null);
    file.addLibrary(loader.loadLibrary("#Wiring"));
    final var project = new Project(file);
    project.getCurrentCircuit().setProject(project);
    final var permissions = new AgentPermissionManager();
    final var rounds = new AtomicInteger();
    final var entered = new CountDownLatch(1);
    final var release = new CountDownLatch(1);
    final var controller =
        new AgentController(
            new WorkspaceManager(tempDir),
            permissions,
            TaskBudget.defaults(),
            (config, messages, tools) ->
                switch (rounds.getAndIncrement()) {
                  case 0 ->
                      toolTurn(
                          toolCall(
                              "propose_specification",
                              Map.of(
                                  "kind",
                                  "BOOLEAN_EXPRESSIONS",
                                  "inputs",
                                  List.of(Map.of("name", "A", "width", 1)),
                                  "outputs",
                                  List.of(Map.of("name", "Y", "width", 1)),
                                  "expressions",
                                  Map.of("Y", "A"))));
                  case 1 -> {
                    entered.countDown();
                    assertTrue(release.await(5, TimeUnit.SECONDS));
                    yield toolTurn(
                        toolCall(
                            "add_component",
                            Map.of(
                                "type",
                                "Pin",
                                "x",
                                20,
                                "y",
                                50,
                                "attributes",
                                Map.of("label", "A"))),
                        toolCall(
                            "add_component",
                            Map.of(
                                "type",
                                "Pin",
                                "x",
                                200,
                                "y",
                                50,
                                "attributes",
                                Map.of("label", "Y", "type", "output"))));
                  }
                  case 2 ->
                      toolTurn(
                          toolCall(
                              "connect",
                              Map.of(
                                  "fromComponentId",
                                  "cmp_001",
                                  "fromPort",
                                  "p0",
                                  "toComponentId",
                                  "cmp_002",
                                  "toPort",
                                  "p0")));
                  default ->
                      new ModelTurn(
                          "完成", List.of(), "stop", Map.of("role", "assistant", "content", "完成"));
                },
            StandardCircuitTools.createRegistry());
    final var panel =
        SwingExecutor.call(() -> new AiAssistantPanel(project, permissions, controller));
    final var workerField = AiAssistantPanel.class.getDeclaredField("worker");
    workerField.setAccessible(true);
    final var worker = (ExecutorService) workerField.get(panel);
    try {
      SwingExecutor.run(
          () -> {
            modelField(panel).setText("offline");
            sendText(panel, "创建 Y=A 的组合电路");
          });
      worker.submit(() -> {}).get(5, TimeUnit.SECONDS);
      SwingExecutor.run(() -> sendText(panel, "没问题，就这样设计即可"));
      worker.submit(() -> {}).get(5, TimeUnit.SECONDS);
      SwingExecutor.run(() -> {});
      assertTrue(controller.isWaitingForEditPermission());
      assertEquals(1, rounds.get());
      SwingExecutor.run(
          () -> {
            final var checkbox = editPermission(panel);
            checkbox.doClick(0);
            assertFalse(findButton(panel, "send").isEnabled());
            assertTrue(findButton(panel, "cancel").isEnabled());
          });
      assertTrue(entered.await(5, TimeUnit.SECONDS));
      release.countDown();
      worker.submit(() -> {}).get(10, TimeUnit.SECONDS);
      SwingExecutor.run(
          () -> {
            assertTrue(findButton(panel, "apply").isEnabled());
            assertFalse(findButton(panel, "send").isEnabled());
          });
      assertEquals(AgentState.REVIEW, controller.getState());
      assertEquals(4, rounds.get());
      try (var directories = java.nio.file.Files.list(tempDir)) {
        assertEquals(1, directories.count());
      }
    } finally {
      release.countDown();
      SwingExecutor.run(panel::close);
      assertTrue(worker.awaitTermination(10, TimeUnit.SECONDS));
      WorkspaceManager.closeProject(project);
    }
  }

  private static void sendText(AiAssistantPanel panel, String text) {
    descendants(panel).stream()
        .filter(c -> c instanceof JTextArea area && area.isEditable())
        .map(c -> (JTextArea) c)
        .findFirst()
        .orElseThrow()
        .setText(text);
    findButton(panel, "send").doClick(0);
  }

  private static JButton findButton(AiAssistantPanel panel, String name) {
    return (JButton) findNamedComponent(panel, name);
  }

  private static Component findNamedComponent(Container container, String name) {
    return descendants(container).stream()
        .filter(component -> name.equals(component.getName()))
        .findFirst()
        .orElseThrow();
  }

  private static JTextField modelField(AiAssistantPanel panel) {
    return (JTextField) findNamedComponent(panel.modelSettingsPanel(), "model");
  }

  private static JCheckBox editPermission(AiAssistantPanel panel) {
    return (JCheckBox) findNamedComponent(panel.permissionSettingsPanel(), "allowEdits");
  }

  private static ModelToolCall toolCall(String name, Map<String, Object> args) {
    return new ModelToolCall(java.util.UUID.randomUUID().toString(), name, args);
  }

  private static ModelTurn toolTurn(ModelToolCall... calls) {
    final var raw = new ArrayList<Map<String, Object>>();
    for (var call : calls)
      raw.add(
          Map.of(
              "id",
              call.id(),
              "type",
              "function",
              "function",
              Map.of("name", call.name(), "arguments", SimpleJson.stringify(call.arguments()))));
    return new ModelTurn(
        "",
        List.of(calls),
        "tool_calls",
        Map.of("role", "assistant", "content", "", "tool_calls", raw));
  }

  @Test
  void mainPanelProvidesEntrypointsWithoutShowingConfigurationOrEditCheckbox() throws Exception {
    try (var fixture = new Fixture(AgentState.REVIEW)) {
      SwingExecutor.run(
          () -> {
            final var hiddenNames = List.of("endpoint", "model", "apiKey", "allowEdits");
            assertTrue(
                descendants(fixture.panel).stream()
                    .noneMatch(
                        component ->
                            component.getName() != null
                                && hiddenNames.contains(component.getName())));
            assertTrue(fixture.button("settings").isEnabled());
            assertTrue(fixture.button("permissions").isEnabled());
            assertTrue(
                findNamedComponent(fixture.panel.modelSettingsPanel(), "endpoint")
                    instanceof JTextField);
            assertTrue(
                findNamedComponent(fixture.panel.modelSettingsPanel(), "apiKey")
                    instanceof JPasswordField);
            assertTrue(editPermission(fixture.panel).isSelected());
          });
    }
  }

  @Test
  void typedProposalKeepsConfirmationInsideItsCardAndRetainsIdentityAcrossLanguageChange()
      throws Exception {
    final var originalLocale = SwingExecutor.call(LocaleManager::getLocale);
    try (var fixture = new Fixture(AgentState.REVIEW)) {
      final var specification =
          TaskSpec.fromArguments(
              Map.of(
                  "kind", "BOOLEAN_EXPRESSIONS",
                  "inputs",
                      List.of(Map.of("name", "A", "width", 1), Map.of("name", "B", "width", 1)),
                  "outputs", List.of(Map.of("name", "Y", "width", 1)),
                  "expressions", Map.of("Y", "~(A XOR B)")));
      final var digest = specification.digest();
      when(fixture.controller.pendingSpecification()).thenReturn(specification);
      SwingExecutor.run(
          () -> {
            LocaleManager.setLocale(Locale.SIMPLIFIED_CHINESE);
            modelField(fixture.panel).setText("offline");
            sendText(fixture.panel, "设计同或电路");
          });
      fixture.awaitIdle();
      SwingExecutor.run(
          () -> {
            fixture.listener.get().specificationProposed(specification, specification.describe());
            final var card =
                (SpecificationCard) findNamedComponent(fixture.panel, "specificationCard");
            final var confirm = fixture.button("confirmSpec");
            assertTrue(javax.swing.SwingUtilities.isDescendingFrom(confirm, card));
            assertTrue(confirm.isEnabled());
            assertTrue(fixture.button("reviseSpec").isEnabled());
            assertFalse(findNamedComponent(fixture.panel, "taskActions").isVisible());
            final var function = (JTextArea) findNamedComponent(card, "specificationFunction-0");
            function.select(0, 3);
            LocaleManager.setLocale(Locale.ENGLISH);
            fixture.listener.get().specificationProposed(specification, specification.describe());
            assertSame(card, findNamedComponent(fixture.panel, "specificationCard"));
            assertSame(confirm, fixture.button("confirmSpec"));
            assertSame(function, findNamedComponent(card, "specificationFunction-0"));
            assertEquals(3, function.getSelectionEnd());
            assertEquals(digest, card.specification().digest());
            assertEquals(
                1,
                descendants(fixture.panel).stream()
                    .filter(c -> "specificationCard".equals(c.getName()))
                    .count());
            fixture.button("reviseSpec").doClick(0);
          });
      verify(fixture.controller, never()).confirmSpecification();
      SwingExecutor.run(() -> fixture.button("confirmSpec").doClick(0));
      fixture.awaitIdle();
      SwingExecutor.run(
          () -> {
            final var card =
                (SpecificationCard) findNamedComponent(fixture.panel, "specificationCard");
            assertFalse(
                javax.swing.SwingUtilities.isDescendingFrom(fixture.button("confirmSpec"), card));
            assertTrue(fixture.button("apply").isEnabled());
          });
      verify(fixture.controller).confirmSpecification();
    } finally {
      SwingExecutor.run(() -> LocaleManager.setLocale(originalLocale));
    }
  }

  @Test
  void replacementSpecificationDisablesOldCardUntilMatchingPresentationArrives() throws Exception {
    try (var fixture = new Fixture(AgentState.REVIEW)) {
      final var xor =
          new TaskSpec(
              TaskSpec.Kind.BOOLEAN_EXPRESSIONS,
              List.of(new TaskSpec.SignalSpec("A", 1), new TaskSpec.SignalSpec("B", 1)),
              List.of(new TaskSpec.SignalSpec("Y", 1)),
              Map.of("Y", "A XOR B"));
      final var xnor =
          new TaskSpec(xor.kind(), xor.inputs(), xor.outputs(), Map.of("Y", "~(A XOR B)"));
      final var pending = new AtomicReference<>(xor);
      when(fixture.controller.pendingSpecification()).thenAnswer(invocation -> pending.get());
      SwingExecutor.run(
          () -> {
            modelField(fixture.panel).setText("offline");
            sendText(fixture.panel, "设计异或电路");
          });
      fixture.awaitIdle();
      SwingExecutor.run(
          () -> {
            // The first proposal also stays unconfirmable until its typed view is delivered.
            assertFalse(fixture.button("confirmSpec").isEnabled());
            assertFalse(fixture.button("confirmSpec").isVisible());
            fixture.listener.get().specificationProposed(xor, xor.describe());
            final var oldCard =
                (SpecificationCard) findNamedComponent(fixture.panel, "specificationCard");
            assertTrue(fixture.button("confirmSpec").isEnabled());
            pending.set(xnor);
            // Production delivers this waiting-state callback before the new typed card callback.
            fixture.listener.get().stateChanged(AgentState.WAITING_FOR_USER, "Revised proposal");
            final var confirm = fixture.button("confirmSpec");
            assertFalse(confirm.isEnabled());
            assertFalse(confirm.isVisible());
            assertFalse(javax.swing.SwingUtilities.isDescendingFrom(confirm, oldCard));
            confirm.doClick(0);
            fixture.listener.get().specificationProposed(xnor, xnor.describe());
            assertTrue(fixture.button("confirmSpec").isEnabled());
            assertTrue(fixture.button("confirmSpec").isVisible());
            assertFalse(javax.swing.SwingUtilities.isDescendingFrom(confirm, oldCard));
          });
      verify(fixture.controller, never()).confirmDisplayedSpecification(any(TaskSpec.class));
      SwingExecutor.run(() -> fixture.button("confirmSpec").doClick(0));
      fixture.awaitIdle();
      verify(fixture.controller).confirmDisplayedSpecification(xnor);
    }
  }

  @Test
  void invalidModelSettingsCanBeCancelledAndValidSettingsRemainSaved() throws Exception {
    try (var fixture = new Fixture(AgentState.REVIEW)) {
      SwingExecutor.run(
          () -> {
            final var panel = fixture.panel;
            final var endpoint =
                (JTextField) findNamedComponent(panel.modelSettingsPanel(), "endpoint");
            final var apiKey =
                (JPasswordField) findNamedComponent(panel.modelSettingsPanel(), "apiKey");
            endpoint.setText("https://old.example.invalid/v1/chat/completions");
            modelField(panel).setText("old-model");
            apiKey.setText("old-test-key");
            panel.beginModelSettingsEdit();
            endpoint.setText("relative-endpoint");
            modelField(panel).setText("");
            apiKey.setText("unsaved-test-key");
            assertFalse(panel.saveModelSettings());
            panel.cancelModelSettings();
            assertEquals("https://old.example.invalid/v1/chat/completions", endpoint.getText());
            assertEquals("old-model", modelField(panel).getText());
            assertEquals("old-test-key", new String(apiKey.getPassword()));
            panel.beginModelSettingsEdit();
            endpoint.setText("https://new.example.invalid/v1/chat/completions");
            modelField(panel).setText("new-model");
            apiKey.setText("saved-test-key");
            assertTrue(panel.saveModelSettings());
            panel.cancelModelSettings();
            assertEquals("https://new.example.invalid/v1/chat/completions", endpoint.getText());
            assertEquals("new-model", modelField(panel).getText());
            assertEquals("saved-test-key", new String(apiKey.getPassword()));
            assertFalse(transcript(panel).contains("test-key"));
          });
    }
  }

  @Test
  void chatMessagesOmitRolePrefixesWhileToolDiagnosticsRemainAvailable() throws Exception {
    try (var fixture = new Fixture(AgentState.REVIEW)) {
      fixture.confirmAndReview();
      fixture.listener.get().assistantMessage("The identity circuit is complete.");
      fixture.listener.get().toolActivity("connect", Map.of("ok", true, "wireCount", 3));
      SwingExecutor.run(
          () -> {
            final var text = transcript(fixture.panel);
            assertTrue(text.contains("Create an identity circuit"));
            assertTrue(text.contains("The identity circuit is complete."));
            assertFalse(text.contains(AiStrings.S.get("aiRoleYou") + ":\n"));
            assertFalse(text.contains(AiStrings.S.get("aiRoleAgent") + ":\n"));
            assertTrue(text.contains(AiStrings.S.get("aiRoleTool", "connect") + ":\n"));
            assertTrue(text.contains("ok=true"));
            assertTrue(text.contains("wireCount=3"));
            final var userMessage = (Container) findNamedComponent(fixture.panel, "message-user");
            final var assistantMessage =
                (Container) findNamedComponent(fixture.panel, "message-assistant");
            for (var message : List.of(userMessage, assistantMessage)) {
              assertTrue(
                  descendants(message).stream()
                      .noneMatch(component -> "messageTitle".equals(component.getName())));
              final var messageText = (JTextArea) findNamedComponent(message, "messageText");
              assertFalse(messageText.isEditable());
            }
          });
    }
  }

  @Test
  void toolDetailsCollapseAndRetainTheirOpenStateAcrossLanguageRefresh() throws Exception {
    final var originalLocale = SwingExecutor.call(LocaleManager::getLocale);
    try {
      SwingExecutor.run(() -> LocaleManager.setLocale(Locale.ENGLISH));
      try (var fixture = new Fixture(AgentState.REVIEW)) {
        fixture.confirmAndReview();
        fixture.listener.get().toolActivity("connect", Map.of("ok", true, "wireCount", 3));
        SwingExecutor.run(
            () -> {
              final var row = toolRow(fixture.panel);
              final var toggle =
                  descendants(row).stream()
                      .filter(component -> component instanceof JToggleButton)
                      .map(component -> (JToggleButton) component)
                      .findFirst()
                      .orElseThrow();
              final var body = (JTextArea) findNamedComponent(row, "messageText");
              assertFalse(toggle.isSelected());
              assertFalse(body.isVisible());
              toggle.doClick(0);
              assertTrue(toggle.isSelected());
              assertTrue(body.isVisible());
              fixture.listener.get().assistantMessage("The circuit is ready for review.");
              LocaleManager.setLocale(Locale.SIMPLIFIED_CHINESE);
              assertSame(row, toolRow(fixture.panel));
              assertTrue(toggle.isSelected());
              assertTrue(body.isVisible());
              assertEquals(AiStrings.S.get("aiRoleTool", "connect"), toggle.getText());
              toggle.doClick(0);
              assertFalse(toggle.isSelected());
              assertFalse(body.isVisible());
              assertTrue(transcript(fixture.panel).contains("wireCount=3"));
              assertTrue(transcript(fixture.panel).contains("验收:\n"));
            });
      }
    } finally {
      SwingExecutor.run(() -> LocaleManager.setLocale(originalLocale));
    }
  }

  private static Container toolRow(AiAssistantPanel panel) {
    return descendants(panel).stream()
        .filter(component -> component instanceof Container)
        .map(component -> (Container) component)
        .filter(component -> "message-debug".equals(component.getName()))
        .filter(
            row ->
                descendants(row).stream()
                    .anyMatch(
                        component ->
                            component instanceof JToggleButton toggle
                                && toggle.getText().contains("connect")))
        .findFirst()
        .orElseThrow();
  }

  @Test
  void revokingPermissionImmediatelyDisablesApplyWithoutDiscardingReview() throws Exception {
    try (var fixture = new Fixture(AgentState.REVIEW)) {
      fixture.confirmAndReview();
      SwingExecutor.run(
          () -> {
            final var checkbox = editPermission(fixture.panel);
            checkbox.doClick(0);
            assertFalse(fixture.button("apply").isEnabled());
            assertTrue(fixture.button("discard").isEnabled());
            assertTrue(fixture.button("reviewDetails").isEnabled());
            assertEquals(
                AiStrings.S.get("aiPermissionReadOnly"),
                ((JLabel) findNamedComponent(fixture.panel, "permissionState")).getText());
            fixture.button("apply").doClick(0);
          });
      fixture.awaitIdle();
      verify(fixture.controller, never()).applyReviewedTask();
      assertEquals(AgentState.REVIEW, fixture.controller.getState());
      SwingExecutor.run(
          () -> {
            editPermission(fixture.panel).doClick(0);
            assertTrue(fixture.button("apply").isEnabled());
            assertEquals(
                AiStrings.S.get("aiPermissionEditing"),
                ((JLabel) findNamedComponent(fixture.panel, "permissionState")).getText());
          });
      fixture.awaitIdle();
    }
  }

  @Test
  void reviewInspectorKeepsItsSnapshotAndUpdatesPermissionWithoutApplying() throws Exception {
    final var originalLocale = SwingExecutor.call(LocaleManager::getLocale);
    try (var fixture = new Fixture(AgentState.REVIEW)) {
      final var spec =
          new TaskSpec(
              TaskSpec.Kind.BOOLEAN_EXPRESSIONS,
              List.of(new TaskSpec.SignalSpec("A", 1)),
              List.of(new TaskSpec.SignalSpec("Y", 1)),
              Map.of("Y", "A"));
      final var details =
          Map.<String, Object>of(
              "taskId", "offline-task",
              "candidateId", "offline-reviewed-candidate",
              "candidateDigest", "circuit-digest",
              "baselineDigest", "baseline-digest",
              "specification", spec.toMap(),
              "verification",
                  new VerificationEvidence(
                          ValidationStatus.PASS,
                          "All 2 exhaustive rows matched the confirmed specification.",
                          List.of("Logisim simulation completed"),
                          List.of(),
                          List.of(),
                          spec.digest(),
                          "circuit-digest")
                      .toMap(),
              "diff", Map.of("added", List.of(), "removed", List.of(), "changed", false));
      when(fixture.controller.reviewDetails()).thenReturn(details);
      fixture.confirmAndReview();
      SwingExecutor.run(
          () -> {
            LocaleManager.setLocale(Locale.SIMPLIFIED_CHINESE);
            final var view = fixture.panel.reviewDetailsPanel();
            assertSame(view, fixture.panel.reviewDetailsPanel());
            final var tabs = (JTabbedPane) findNamedComponent(view, "reviewTabs");
            final var toggle = (JToggleButton) findNamedComponent(view, "reviewAdvancedToggle");
            final var raw = (JTextArea) findNamedComponent(view, "reviewRawJson");
            assertFalse(toggle.isSelected());
            toggle.doClick(0);
            raw.select(0, Math.min(12, raw.getText().length()));
            tabs.setSelectedIndex(1);
            LocaleManager.setLocale(Locale.ENGLISH);
            assertEquals(1, tabs.getSelectedIndex());
            assertTrue(toggle.isSelected());
            assertEquals(12, raw.getSelectionEnd());
            assertEquals(
                SimpleJson.parseObject(SimpleJson.stringify(details)),
                SimpleJson.parseObject(view.rawJson()));
            editPermission(fixture.panel).doClick(0);
            assertFalse(fixture.button("apply").isEnabled());
            assertSame(view, fixture.panel.reviewDetailsPanel());
            final var cue = findNamedComponent(view, "reviewEligibility");
            assertEquals(AiStrings.S.get("aiReviewCannotApply"), ((JTextArea) cue).getText());
            assertEquals(AgentState.REVIEW, fixture.controller.getState());
          });
      fixture.awaitIdle();
      verify(fixture.controller, never()).applyReviewedTask();
    } finally {
      SwingExecutor.run(() -> LocaleManager.setLocale(originalLocale));
    }
  }

  @Test
  void languageSwitchUpdatesLabelsAndTranscriptWithoutChangingReviewPermissions() throws Exception {
    final var originalLocale = SwingExecutor.call(LocaleManager::getLocale);
    try {
      SwingExecutor.run(() -> LocaleManager.setLocale(Locale.ENGLISH));
      try (var fixture = new Fixture(AgentState.REVIEW)) {
        fixture.confirmAndReview();
        fixture.listener.get().toolActivity("connect", Map.of("ok", true, "wireCount", 3));
        SwingExecutor.run(
            () -> {
              assertEquals("Send", fixture.button("send").getText());
              assertEquals("Apply", fixture.button("apply").getText());
              assertEquals(AiStrings.S.get("aiSettings"), fixture.button("settings").getText());
              assertEquals(
                  AiStrings.S.get("aiPermissions"), fixture.button("permissions").getText());
              assertEquals(
                  AiStrings.S.get("aiPermissionEditing"),
                  ((JLabel) findNamedComponent(fixture.panel, "permissionState")).getText());
              assertTrue(transcript(fixture.panel).contains("Tool connect:\n"));
              assertTrue(transcript(fixture.panel).contains("ok=true"));
              final var before = actionStates(fixture.panel);
              final var checkbox = editPermission(fixture.panel);
              assertTrue(checkbox.isSelected());
              LocaleManager.setLocale(Locale.SIMPLIFIED_CHINESE);
              assertEquals("发送", fixture.button("send").getText());
              assertEquals("应用", fixture.button("apply").getText());
              assertEquals("确认规格", fixture.button("confirmSpec").getText());
              assertEquals("允许本次会话编辑", checkbox.getText());
              assertEquals(AiStrings.S.get("aiSettings"), fixture.button("settings").getText());
              assertEquals(
                  AiStrings.S.get("aiPermissions"), fixture.button("permissions").getText());
              assertEquals(
                  AiStrings.S.get("aiPermissionEditing"),
                  ((JLabel) findNamedComponent(fixture.panel, "permissionState")).getText());
              assertTrue(checkbox.isSelected());
              assertEquals(before, actionStates(fixture.panel));
              assertEquals(AgentState.REVIEW, fixture.controller.getState());
              assertTrue(transcript(fixture.panel).contains("验收:\n"));
              assertTrue(transcript(fixture.panel).contains("工具 connect:\n"));
              assertTrue(transcript(fixture.panel).contains("ok=true"));
              assertTrue(transcript(fixture.panel).contains("wireCount=3"));
              final var status = (JLabel) findNamedComponent(fixture.panel, "status");
              assertEquals(AiStrings.state(AgentState.REVIEW), status.getText());
              assertEquals(AiStrings.displayMessage("Ready"), status.getToolTipText());
              final var detail = (JTextArea) findNamedComponent(fixture.panel, "statusDetail");
              assertEquals(AiStrings.displayMessage("Ready"), detail.getText());
              assertFalse(detail.isEditable());
              LocaleManager.setLocale(Locale.ENGLISH);
              assertEquals("Review details", fixture.button("reviewDetails").getText());
              assertEquals(before, actionStates(fixture.panel));
              assertTrue(transcript(fixture.panel).contains("Verification:\n"));
            });
      }
    } finally {
      SwingExecutor.run(() -> LocaleManager.setLocale(originalLocale));
    }
  }

  @Test
  void closingPanelRemovesLocaleListenerAndStopsLanguageUpdates() throws Exception {
    final var originalLocale = SwingExecutor.call(LocaleManager::getLocale);
    try {
      SwingExecutor.run(() -> LocaleManager.setLocale(Locale.ENGLISH));
      try (var fixture = new Fixture(AgentState.REVIEW)) {
        final var originalPermissionText =
            SwingExecutor.call(
                () -> ((JLabel) findNamedComponent(fixture.panel, "permissionState")).getText());
        SwingExecutor.run(fixture.panel::close);
        assertTrue(fixture.worker.awaitTermination(5, TimeUnit.SECONDS));
        SwingExecutor.run(
            () -> {
              LocaleManager.setLocale(Locale.SIMPLIFIED_CHINESE);
              assertEquals("Send", fixture.button("send").getText());
              assertEquals("Apply", fixture.button("apply").getText());
              assertEquals("Settings", fixture.button("settings").getText());
              assertEquals(
                  originalPermissionText,
                  ((JLabel) findNamedComponent(fixture.panel, "permissionState")).getText());
            });
        verify(fixture.controller).close();
      }
    } finally {
      SwingExecutor.run(() -> LocaleManager.setLocale(originalLocale));
    }
  }

  private static Map<String, Boolean> actionStates(AiAssistantPanel panel) {
    final var values = new java.util.LinkedHashMap<String, Boolean>();
    for (var component : descendants(panel)) {
      if (component instanceof JButton button) values.put(button.getName(), button.isEnabled());
    }
    return values;
  }

  private static String transcript(AiAssistantPanel panel) {
    return ((ChatTranscriptView) findNamedComponent(panel, "transcript")).plainText();
  }

  @Test
  void changingEditCheckboxNotifiesTheWaitingController() throws Exception {
    try (var fixture = new Fixture(AgentState.REVIEW)) {
      SwingExecutor.run(
          () -> {
            final var checkbox = editPermission(fixture.panel);
            checkbox.doClick(0);
            checkbox.doClick(0);
          });
      fixture.awaitIdle();
      verify(fixture.controller, times(2)).editPermissionChanged();
    }
  }

  @Test
  void terminalApplyResultsLeaveSendAvailableAfterTheResultCallback() throws Exception {
    for (final var terminal :
        List.of(AgentState.FAILED_BUDGET, AgentState.CANCELLED, AgentState.FAILED_INTERNAL)) {
      try (var fixture = new Fixture(terminal)) {
        fixture.confirmAndReview();
        fixture.startApply();
        fixture.releaseApply.countDown();
        fixture.awaitIdle();
        SwingExecutor.run(
            () -> {
              assertTrue(fixture.button("send").isEnabled(), terminal.name());
              assertTrue(fixture.button("cleanup").isEnabled(), terminal.name());
              assertFalse(fixture.button("apply").isEnabled(), terminal.name());
              assertFalse(fixture.button("discard").isEnabled(), terminal.name());
              assertFalse(fixture.button("cancel").isEnabled(), terminal.name());
              assertFalse(fixture.button("confirmSpec").isEnabled(), terminal.name());
            });
      }
    }
  }

  @Test
  void applyingDisablesDuplicateActionsAndCancelRemainsUsable() throws Exception {
    try (var fixture = new Fixture(AgentState.CANCELLED)) {
      fixture.confirmAndReview();
      fixture.startApply();
      SwingExecutor.run(
          () -> {
            assertFalse(fixture.button("send").isEnabled());
            assertFalse(fixture.button("apply").isEnabled());
            assertFalse(fixture.button("discard").isEnabled());
            assertTrue(fixture.button("cancel").isEnabled());
            fixture.button("cancel").doClick(0);
          });
      verify(fixture.controller).cancel();
      fixture.releaseApply.countDown();
      fixture.awaitIdle();
      SwingExecutor.run(() -> assertTrue(fixture.button("send").isEnabled()));
    }
  }

  @Test
  void recoverableApplyFailureReturnsToReviewWithRetryAndDiscard() throws Exception {
    try (var fixture = new Fixture(AgentState.REVIEW)) {
      fixture.confirmAndReview();
      fixture.startApply();
      fixture.releaseApply.countDown();
      fixture.awaitIdle();
      SwingExecutor.run(
          () -> {
            assertTrue(fixture.button("apply").isEnabled());
            assertTrue(fixture.button("discard").isEnabled());
            assertTrue(fixture.button("reviewDetails").isEnabled());
            assertFalse(fixture.button("send").isEnabled());
            assertFalse(fixture.button("cancel").isEnabled());
          });
    }
  }

  private static final class Fixture implements AutoCloseable {
    final AgentController controller = mock(AgentController.class);
    final AtomicReference<AgentState> state = new AtomicReference<>(AgentState.READY);
    final AtomicReference<AgentTaskListener> listener = new AtomicReference<>();
    final CountDownLatch enteredApply = new CountDownLatch(1);
    final CountDownLatch releaseApply = new CountDownLatch(1);
    final AiAssistantPanel panel;
    final ExecutorService worker;

    Fixture(AgentState applyOutcome) throws Exception {
      when(controller.getState()).thenAnswer(invocation -> state.get());
      when(controller.hasPendingSpecification())
          .thenAnswer(invocation -> state.get() == AgentState.WAITING_FOR_USER);
      when(controller.reviewDetails()).thenReturn(Map.of("candidateId", "offline-gui-test"));
      doAnswer(
              invocation -> {
                listener.set(invocation.getArgument(3));
                transition(AgentState.WAITING_FOR_USER);
                return null;
              })
          .when(controller)
          .startTask(any(), anyString(), any(ModelConfig.class), any(AgentTaskListener.class));
      doAnswer(
              invocation -> {
                transition(AgentState.PLANNING);
                state.set(AgentState.REVIEW);
                listener
                    .get()
                    .reviewReady(
                        "offline-task",
                        "Ready",
                        new VerificationEvidence(
                            ValidationStatus.PASS,
                            "Confirmed specification passed",
                            List.of(),
                            List.of(),
                            List.of(),
                            "specification-digest",
                            "circuit-digest"),
                        Map.of(),
                        true,
                        Path.of("offline-task"));
                listener.get().stateChanged(AgentState.REVIEW, "Ready");
                return null;
              })
          .when(controller)
          .confirmSpecification();
      doAnswer(
              invocation -> {
                controller.confirmSpecification();
                return null;
              })
          .when(controller)
          .confirmDisplayedSpecification(any(TaskSpec.class));
      when(controller.applyReviewedTask())
          .thenAnswer(
              invocation -> {
                transition(AgentState.APPLYING);
                enteredApply.countDown();
                assertTrue(releaseApply.await(5, TimeUnit.SECONDS));
                transition(applyOutcome);
                return new AgentApplyManager.ApplyResult(
                    false,
                    false,
                    "Offline apply outcome",
                    applyOutcome == AgentState.FAILED_INTERNAL);
              });
      final var permissions = new AgentPermissionManager();
      permissions.setEditsAllowed(true);
      panel = SwingExecutor.call(() -> new AiAssistantPanel(null, permissions, controller));
      final var workerField = AiAssistantPanel.class.getDeclaredField("worker");
      workerField.setAccessible(true);
      worker = (ExecutorService) workerField.get(panel);
    }

    void confirmAndReview() throws Exception {
      SwingExecutor.run(
          () -> {
            modelField(panel).setText("offline");
            sendText(panel, "Create an identity circuit");
          });
      awaitIdle();
      SwingExecutor.run(
          () -> {
            assertTrue(button("confirmSpec").isEnabled());
            button("confirmSpec").doClick(0);
          });
      awaitIdle();
      SwingExecutor.run(() -> assertTrue(button("apply").isEnabled()));
    }

    void startApply() throws Exception {
      SwingExecutor.run(() -> button("apply").doClick(0));
      assertTrue(enteredApply.await(5, TimeUnit.SECONDS));
      SwingExecutor.run(() -> {});
    }

    void awaitIdle() throws Exception {
      worker.submit(() -> {}).get(5, TimeUnit.SECONDS);
      SwingExecutor.run(() -> {});
    }

    JButton button(String name) {
      return findButton(panel, name);
    }

    private void transition(AgentState next) {
      state.set(next);
      listener.get().stateChanged(next, "Offline UI test");
    }

    @Override
    public void close() throws Exception {
      releaseApply.countDown();
      SwingExecutor.run(panel::close);
      assertTrue(worker.awaitTermination(5, TimeUnit.SECONDS));
      SwingExecutor.run(() -> {});
    }
  }

  private static List<Component> descendants(Container container) {
    final var components = new ArrayList<Component>();
    for (var component : container.getComponents()) {
      components.add(component);
      if (component instanceof Container child) components.addAll(descendants(child));
    }
    return components;
  }
}
