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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.cburch.logisim.ai.model.ModelClient;
import com.cburch.logisim.ai.model.ModelConfig;
import com.cburch.logisim.ai.model.ModelToolCall;
import com.cburch.logisim.ai.model.ModelTurn;
import com.cburch.logisim.ai.tools.StandardCircuitTools;
import com.cburch.logisim.ai.util.SimpleJson;
import com.cburch.logisim.ai.validation.TaskSpec;
import com.cburch.logisim.ai.workspace.WorkspaceManager;
import com.cburch.logisim.file.Loader;
import com.cburch.logisim.file.LogisimFile;
import com.cburch.logisim.proj.Project;
import com.cburch.logisim.util.LocaleManager;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AgentControllerLocaleTest {
  private static final ModelConfig CONFIG =
      new ModelConfig(URI.create("https://example.invalid"), "offline", "", Duration.ofSeconds(5));

  @TempDir Path tempDir;
  private Locale originalLocale;

  @BeforeEach
  void rememberLocale() {
    originalLocale = LocaleManager.getLocale();
  }

  @AfterEach
  void restoreLocale() {
    LocaleManager.setLocale(originalLocale);
  }

  @Test
  void modelRequestsUseCurrentSoftwareLocaleDespiteOppositeInputLanguageAndRefreshAfterSwitch() {
    LocaleManager.setLocale(Locale.SIMPLIFIED_CHINESE);
    final var source = source();
    final var rounds = new AtomicInteger();
    final var listener = new RecordingListener();
    final ModelClient model =
        (config, messages, tools) -> {
          final var directive = String.valueOf(messages.getLast().get("content"));
          assertEquals("system", messages.getLast().get("role"));
          assertTrue(directive.contains("regardless of the user's input language"));
          assertTrue(directive.contains("NEED_USER:"));
          if (rounds.getAndIncrement() == 0) {
            assertEquals("zh", LocaleManager.getLocale().getLanguage());
            assertTrue(
                directive.contains(
                    LocaleManager.getLocale().toLanguageTag()
                        + " ("
                        + LocaleManager.getLocale().getDisplayName(Locale.ENGLISH)
                        + ")"));
            assertTrue(
                messages.stream()
                    .anyMatch(
                        m -> String.valueOf(m.get("content")).contains("Explain a multiplier")));
            return reply("NEED_USER: 请补充位宽");
          }
          assertTrue(directive.contains("en (English)"));
          assertFalse(directive.contains("zh-CN"));
          assertTrue(
              messages.stream().anyMatch(m -> "四位即可".equals(String.valueOf(m.get("content")))));
          return reply("A four-bit multiplier has an eight-bit product.");
        };
    try (var controller = controller(new AgentPermissionManager(), model)) {
      controller.startTask(source, "Explain a multiplier", CONFIG, listener);
      assertEquals(AgentState.WAITING_FOR_USER, controller.getState());
      assertTrue(listener.details.getLast().startsWith("等待补充说明"));
      LocaleManager.setLocale(Locale.ENGLISH);
      controller.continueTask("四位即可");
      assertEquals(AgentState.COMPLETED, controller.getState());
      assertEquals("Read-only task completed", listener.details.getLast());
      assertEquals("A four-bit multiplier has an eight-bit product.", listener.messages.getLast());
      assertEquals(2, rounds.get());
    } finally {
      close(source);
    }
  }

  @Test
  void hostConfirmationAndPermissionMessagesUseUiLocaleWithoutExposingContractJson() {
    for (final var locale : List.of(Locale.SIMPLIFIED_CHINESE, Locale.ENGLISH)) {
      LocaleManager.setLocale(locale);
      final var chinese = locale.getLanguage().equals("zh");
      final var source = source();
      final var listener = new RecordingListener();
      final var rounds = new AtomicInteger();
      try (var controller =
          controller(
              new AgentPermissionManager(),
              (config, messages, tools) -> {
                rounds.incrementAndGet();
                return proposal();
              })) {
        controller.startTask(source, chinese ? "Design XOR" : "设计异或门", CONFIG, listener);
        assertTrue(controller.hasPendingSpecification());
        assertTrue(
            listener
                .messages
                .getLast()
                .startsWith(
                    chinese ? "编辑前，请确认以下功能规格：" : "Please confirm the function before editing:"));
        assertTrue(listener.messages.getLast().contains("Y = A⊻B"));
        assertFalse(listener.messages.getLast().contains("\"kind\""));
        assertFalse(listener.messages.getLast().contains("\"expressions\""));
        controller.continueTask(chinese ? "confirm" : "没问题，就这样设计即可");
        assertFalse(controller.hasPendingSpecification());
        assertTrue(controller.isWaitingForEditPermission());
        assertEquals(1, rounds.get(), "Host confirmation should not spend a model round");
        assertTrue(
            listener.messages.stream()
                .anyMatch(
                    m ->
                        m.startsWith(
                            chinese
                                ? "规格已确认，将按上述功能搭建并验收。"
                                : "Specification confirmed. I will build and verify against this"
                                    + " function.")));
        assertTrue(
            listener
                .messages
                .getLast()
                .contains(chinese ? "允许本次会话编辑" : "Allow edits this session"));
      } finally {
        close(source);
      }
    }
  }

  @Test
  void switchingLocaleBeforeConfirmationRefreshesHostMessagesWithoutRestartingTask() {
    LocaleManager.setLocale(Locale.ENGLISH);
    final var source = source();
    final var listener = new RecordingListener();
    final var rounds = new AtomicInteger();
    try (var controller =
        controller(
            new AgentPermissionManager(),
            (config, messages, tools) -> {
              rounds.incrementAndGet();
              return proposal();
            })) {
      controller.startTask(source, "设计异或门", CONFIG, listener);
      assertTrue(listener.details.getLast().startsWith("Waiting for specification confirmation"));
      LocaleManager.setLocale(Locale.SIMPLIFIED_CHINESE);
      controller.continueTask("确认");
      assertEquals(1, rounds.get());
      assertTrue(controller.isWaitingForEditPermission());
      assertTrue(listener.details.getLast().startsWith("等待开启编辑权限"));
      assertTrue(listener.messages.getLast().startsWith("当前任务与规格已保留"));
      LocaleManager.setLocale(Locale.ENGLISH);
      controller.cancel();
      controller.finishCancellation();
      assertEquals("Task cancelled; work copy retained", listener.details.getLast());
    } finally {
      close(source);
    }
  }

  @Test
  void translatedConfirmationIsAcceptedInTheCurrentUiLanguage() {
    LocaleManager.setLocale(Locale.FRENCH);
    final var source = source();
    final var rounds = new AtomicInteger();
    try (var controller =
        controller(
            new AgentPermissionManager(),
            (config, messages, tools) -> {
              rounds.incrementAndGet();
              return proposal();
            })) {
      controller.startTask(source, "Build XOR", CONFIG, new RecordingListener());
      controller.continueTask("confirmer");
      assertFalse(controller.hasPendingSpecification());
      assertTrue(controller.isWaitingForEditPermission());
      assertEquals(1, rounds.get());
    } finally {
      close(source);
    }
  }

  @Test
  void localizedPermissionQuestionResumesAutomaticallyWhenEditsAreEnabled() {
    LocaleManager.setLocale(Locale.SIMPLIFIED_CHINESE);
    final var source = source();
    final var permissions = new AgentPermissionManager();
    final var rounds = new AtomicInteger();
    try (var controller =
        controller(
            permissions,
            (config, messages, tools) ->
                rounds.getAndIncrement() == 0 ? reply("NEED_USER: 请开启“允许本次会话编辑”。") : proposal())) {
      controller.startTask(source, "Build XOR", CONFIG, new RecordingListener());
      assertTrue(controller.isWaitingForEditPermission());
      permissions.setEditsAllowed(true);
      controller.editPermissionChanged();
      assertTrue(controller.hasPendingSpecification());
      assertEquals(2, rounds.get());
    } finally {
      close(source);
    }
  }

  @Test
  void staleDisplayedCardCannotConfirmARevisedPendingContract() {
    LocaleManager.setLocale(Locale.ENGLISH);
    final var source = source();
    final var rounds = new AtomicInteger();
    final var listener = new RecordingListener();
    try (var controller =
        controller(
            new AgentPermissionManager(),
            (config, messages, tools) ->
                rounds.getAndIncrement() == 0 ? proposal("A ^ B") : proposal("~(A ^ B)"))) {
      assertNull(controller.pendingSpecification());
      controller.startTask(source, "Design XOR", CONFIG, listener);
      final var originalCard = controller.pendingSpecification();
      controller.continueTask("Invert the function before I confirm it.");
      final var revisedCard = controller.pendingSpecification();
      assertNotEquals(originalCard.digest(), revisedCard.digest());
      final var messageCount = listener.messages.size();

      assertThrows(
          IllegalStateException.class,
          () -> controller.confirmDisplayedSpecification(originalCard));
      assertEquals(revisedCard, controller.pendingSpecification());
      assertTrue(controller.hasPendingSpecification());
      assertFalse(controller.isWaitingForEditPermission());
      assertEquals(AgentState.WAITING_FOR_USER, controller.getState());
      assertEquals(
          messageCount, listener.messages.size(), "Rejected card must not be acknowledged");
      assertEquals(2, rounds.get(), "Rejected card must not resume the model");
      assertTrue(source.getCurrentCircuit().getNonWires().isEmpty());
      assertTrue(source.getCurrentCircuit().getWires().isEmpty());

      controller.confirmDisplayedSpecification(revisedCard);
      assertNull(controller.pendingSpecification());
      assertTrue(controller.isWaitingForEditPermission());
      assertEquals(2, rounds.get());
    } finally {
      close(source);
    }
  }

  @Test
  void typedSpecificationCallbackKeepsTheExactContractAndConversationHistory() {
    LocaleManager.setLocale(Locale.ENGLISH);
    final var source = source();
    final var rounds = new AtomicInteger();
    final var specification = new AtomicReference<TaskSpec>();
    final var presentation = new AtomicReference<String>();
    final var assistantMessages = new ArrayList<String>();
    final var listener =
        new AgentTaskListener() {
          @Override
          public void specificationProposed(TaskSpec spec, String text) {
            specification.set(spec);
            presentation.set(text);
          }

          @Override
          public void assistantMessage(String text) {
            assistantMessages.add(text);
          }
        };
    try (var controller =
        controller(
            new AgentPermissionManager(),
            (config, messages, tools) -> {
              if (rounds.getAndIncrement() == 0) return proposal();
              final var history = String.valueOf(messages.get(1).get("content"));
              assertTrue(history.contains("ASSISTANT: " + presentation.get()));
              assertEquals(
                  history.indexOf(presentation.get()), history.lastIndexOf(presentation.get()));
              return reply("The prior contract remains a context hint, not authorization.");
            })) {
      controller.startTask(source, "Design XOR", CONFIG, listener);
      assertTrue(controller.hasPendingSpecification());
      assertEquals("A ^ B", specification.get().expressions().get("Y"));
      assertEquals(4, specification.get().exhaustiveRows());
      assertTrue(presentation.get().startsWith(specification.get().describe()));
      assertTrue(assistantMessages.isEmpty(), "Typed UI callback must not duplicate prose");
      controller.cancel();
      controller.finishCancellation();
      controller.startTask(source, "Explain the prior function", CONFIG, listener);
      assertEquals(AgentState.COMPLETED, controller.getState());
      assertEquals(2, rounds.get());
    } finally {
      close(source);
    }
  }

  @Test
  void specificationDescriptionChangesLanguageWithoutChangingItsAcceptanceDigest() {
    final var spec =
        TaskSpec.fromArguments(
            Map.of(
                "kind", "UNSIGNED_MULTIPLICATION",
                "inputs", List.of(Map.of("name", "A", "width", 4), Map.of("name", "B", "width", 4)),
                "outputs", List.of(Map.of("name", "P", "width", 8))));
    final var digest = spec.digest();
    final var contract = spec.toMap();
    LocaleManager.setLocale(Locale.ENGLISH);
    assertTrue(spec.describe().startsWith("Please confirm the function before editing:"));
    assertTrue(spec.describe().contains("unknown or error outputs fail."));
    LocaleManager.setLocale(Locale.SIMPLIFIED_CHINESE);
    assertTrue(spec.describe().startsWith("编辑前，请确认以下功能规格："));
    assertTrue(spec.describe().contains("未知或错误输出均视为失败"));
    assertEquals(digest, spec.digest());
    assertEquals(contract, spec.toMap());
  }

  private AgentController controller(AgentPermissionManager permissions, ModelClient model) {
    return new AgentController(
        new WorkspaceManager(tempDir),
        permissions,
        TaskBudget.defaults(),
        model,
        StandardCircuitTools.createRegistry());
  }

  private static ModelTurn proposal() {
    return proposal("A ^ B");
  }

  private static ModelTurn proposal(String expression) {
    final var arguments =
        Map.<String, Object>of(
            "kind", "BOOLEAN_EXPRESSIONS",
            "inputs", List.of(Map.of("name", "A", "width", 1), Map.of("name", "B", "width", 1)),
            "outputs", List.of(Map.of("name", "Y", "width", 1)),
            "expressions", Map.of("Y", expression));
    final var call = new ModelToolCall("proposal", "propose_specification", arguments);
    return new ModelTurn(
        "",
        List.of(call),
        "tool_calls",
        Map.of(
            "role", "assistant",
            "content", "",
            "tool_calls",
                List.of(
                    Map.of(
                        "id", call.id(),
                        "type", "function",
                        "function",
                            Map.of(
                                "name",
                                call.name(),
                                "arguments",
                                SimpleJson.stringify(arguments))))));
  }

  private static ModelTurn reply(String text) {
    return new ModelTurn(text, List.of(), "stop", Map.of("role", "assistant", "content", text));
  }

  private static Project source() {
    final var loader = new Loader(null);
    final var file = LogisimFile.createNew(loader, null);
    file.addLibrary(loader.loadLibrary("#Wiring"));
    file.addLibrary(loader.loadLibrary("#Gates"));
    final var project = new Project(file);
    file.getCircuits().forEach(circuit -> circuit.setProject(project));
    return project;
  }

  private static void close(Project project) {
    project.getSimulator().shutDown();
    project.getLogisimFile().stopAutosaveThread(false);
  }

  private static final class RecordingListener implements AgentTaskListener {
    private final List<String> messages = new ArrayList<>();
    private final List<String> details = new ArrayList<>();

    @Override
    public void assistantMessage(String text) {
      messages.add(text);
    }

    @Override
    public void stateChanged(AgentState state, String detail) {
      details.add(detail);
    }
  }
}
