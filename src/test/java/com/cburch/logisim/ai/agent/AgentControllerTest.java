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

import com.cburch.logisim.ai.model.ModelClient;
import com.cburch.logisim.ai.model.ModelConfig;
import com.cburch.logisim.ai.model.ModelToolCall;
import com.cburch.logisim.ai.model.ModelTurn;
import com.cburch.logisim.ai.tools.CircuitDiff;
import com.cburch.logisim.ai.tools.StandardCircuitTools;
import com.cburch.logisim.ai.transaction.CircuitDigest;
import com.cburch.logisim.ai.util.SimpleJson;
import com.cburch.logisim.ai.validation.ValidationStatus;
import com.cburch.logisim.ai.validation.VerificationEvidence;
import com.cburch.logisim.ai.workspace.WorkspaceManager;
import com.cburch.logisim.file.Loader;
import com.cburch.logisim.file.LogisimFile;
import com.cburch.logisim.proj.Project;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AgentControllerTest {
  private static final ModelConfig CONFIG =
      new ModelConfig(URI.create("https://example.invalid"), "offline", "", Duration.ofSeconds(5));

  @TempDir Path tempDir;

  @Test
  void textConfirmationThenEnablingEditsContinuesTheSameTaskToVerifiedReview() throws Exception {
    final var permissions = new AgentPermissionManager();
    final var modelRounds = new AtomicInteger();
    final var request = "给我设计一个异或电路 Y=A XOR B";
    final ModelClient model =
        (config, messages, tools) ->
            switch (modelRounds.getAndIncrement()) {
              case 0 -> calls(xorProposal());
              case 1 -> {
                final var state = hostState(messages);
                assertEquals(request, state.get("originalRequest"));
                assertEquals(true, state.get("editPermission"));
                assertEquals("CONFIRMED", state.get("specificationStatus"));
                assertFalse(((Map<?, ?>) state.get("confirmedSpecification")).isEmpty());
                assertTrue(
                    messages.stream()
                        .anyMatch(m -> String.valueOf(m.get("content")).contains("没问题，就这样设计即可")));
                yield calls(
                    add("Pin", 20, 30, Map.of("label", "A")),
                    add("Pin", 20, 130, Map.of("label", "B")),
                    add("Pin", 300, 80, Map.of("label", "Y", "type", "output")),
                    add("XOR Gate", 180, 80, Map.of()));
              }
              case 2 ->
                  calls(
                      connect("cmp_001", "p0", "cmp_004", "p1"),
                      connect("cmp_002", "p0", "cmp_004", "p2"),
                      connect("cmp_004", "p0", "cmp_003", "p0"));
              default -> reply("已完成");
            };
    final var source = source();
    final var listener = new ReviewListener();
    try (var controller =
        new AgentController(
            new WorkspaceManager(tempDir),
            permissions,
            TaskBudget.defaults(),
            model,
            StandardCircuitTools.createRegistry())) {
      controller.startTask(source, request, CONFIG, listener);
      controller.continueTask("没问题，就这样设计即可");
      assertEquals(AgentState.WAITING_FOR_USER, controller.getState());
      assertFalse(controller.hasPendingSpecification());
      assertEquals(1, modelRounds.get(), "Waiting for permission should not spend a model round");
      permissions.setEditsAllowed(true);
      controller.editPermissionChanged();
      assertEquals(AgentState.REVIEW, controller.getState(), listener.failures.toString());
      assertTrue(listener.evidence.isTrustedPass());
      assertEquals(4, modelRounds.get());
      try (var directories = java.nio.file.Files.list(tempDir)) {
        assertEquals(1, directories.count(), "Permission changes must not create a new task");
      }
      assertTrue(source.getCurrentCircuit().getNonWires().isEmpty());
    } finally {
      close(source);
    }
  }

  @Test
  void permissionNotificationPreservesPendingProposalAndDoesNotGrantPermission() {
    final var permissions = new AgentPermissionManager();
    final var calls = new AtomicInteger();
    final var source = source();
    try (var controller =
        new AgentController(
            new WorkspaceManager(tempDir),
            permissions,
            TaskBudget.defaults(),
            (config, messages, tools) -> {
              calls.incrementAndGet();
              return calls(xorProposal());
            },
            StandardCircuitTools.createRegistry())) {
      controller.startTask(source, "Design XOR", CONFIG, new AgentTaskListener() {});
      controller.continueTask("开启了");
      assertFalse(permissions.isEditsAllowed());
      assertTrue(controller.hasPendingSpecification());
      assertEquals(1, calls.get());
      permissions.setEditsAllowed(true);
      controller.continueTask("开启了");
      assertTrue(controller.hasPendingSpecification());
      assertEquals(1, calls.get());
      assertEquals(AgentState.WAITING_FOR_USER, controller.getState());
    } finally {
      close(source);
    }
  }

  @Test
  void needUserAtEndOfReplyKeepsOriginalTaskAndMessageHistory() {
    final var modelRounds = new AtomicInteger();
    final var source = source();
    try (var controller =
        controller(
            (config, messages, tools) -> {
              if (modelRounds.getAndIncrement() == 0) return reply("我会继续设计。\nNEED_USER: 请确认位宽");
              assertEquals("Build a 4-bit multiplier", hostState(messages).get("originalRequest"));
              assertTrue(
                  messages.stream()
                      .anyMatch(m -> String.valueOf(m.get("content")).contains("请确认位宽")));
              return calls(xorProposal());
            },
            10)) {
      controller.startTask(source, "Build a 4-bit multiplier", CONFIG, new AgentTaskListener() {});
      assertEquals(AgentState.WAITING_FOR_USER, controller.getState());
      controller.continueTask("4 位无符号");
      assertTrue(controller.hasPendingSpecification());
      assertEquals(2, modelRounds.get());
    } finally {
      close(source);
    }
  }

  @Test
  void correctionRequiresNewConfirmationAndModelClaimsCannotAuthorizeWrites() {
    final var modelRounds = new AtomicInteger();
    final var failures = new AtomicInteger();
    final var source = source();
    try (var controller =
        controller(
            (config, messages, tools) -> {
              if (modelRounds.getAndIncrement() == 0) return calls(xorProposal());
              assertEquals("NOT_CONFIRMED", hostState(messages).get("specificationStatus"));
              return calls(add("Pin", 20, 30, Map.of("label", "A")));
            },
            2)) {
      controller.startTask(
          source,
          "Design XOR",
          CONFIG,
          new AgentTaskListener() {
            public void toolActivity(String tool, Map<String, Object> result) {
              if (Boolean.FALSE.equals(result.get("ok"))) failures.incrementAndGet();
            }
          });
      controller.continueTask("同意，但输出改为 Z");
      assertEquals(1, failures.get());
      assertFalse(controller.hasPendingSpecification());
      assertEquals(AgentState.FAILED_BUDGET, controller.getState());
    } finally {
      close(source);
    }
  }

  @Test
  void confirmedDesignWithoutWritesCannotBeReportedAsReadOnlyCompletion() {
    final var modelRounds = new AtomicInteger();
    final var source = source();
    try (var controller =
        controller(
            (config, messages, tools) ->
                modelRounds.getAndIncrement() == 0 ? calls(xorProposal()) : reply("准备好了"),
            10)) {
      controller.startTask(source, "Design XOR", CONFIG, new AgentTaskListener() {});
      controller.confirmSpecification();
      assertEquals(AgentState.WAITING_FOR_USER, controller.getState());
      assertTrue(controller.hasPendingTask());
    } finally {
      close(source);
    }
  }

  @Test
  void waitingDoesNotSpendBudgetButActualExecutionStillDoes() {
    final var clock = new AtomicLong();
    final var modelRounds = new AtomicInteger();
    final var permissions = new AgentPermissionManager();
    final var source = source();
    try (var controller =
        new AgentController(
            new WorkspaceManager(tempDir),
            permissions,
            new TaskBudget(10, 3, 30, 1),
            (config, messages, tools) -> {
              if (modelRounds.incrementAndGet() == 3) clock.addAndGet(TimeUnit.SECONDS.toNanos(2));
              return reply("NEED_USER: 输入位宽？");
            },
            StandardCircuitTools.createRegistry(),
            clock::get)) {
      controller.startTask(source, "Design", CONFIG, new AgentTaskListener() {});
      clock.addAndGet(TimeUnit.MINUTES.toNanos(24));
      controller.continueTask("4 位");
      assertEquals(AgentState.WAITING_FOR_USER, controller.getState());
      assertEquals(2, modelRounds.get());
      controller.continueTask("无符号");
      assertEquals(AgentState.FAILED_BUDGET, controller.getState());
    } finally {
      close(source);
    }
  }

  @Test
  void completedTaskRetainsBoundedConversationForNextTask() {
    final var modelRounds = new AtomicInteger();
    final var source = source();
    try (var controller =
        controller(
            (config, messages, tools) -> {
              if (modelRounds.getAndIncrement() > 0) {
                final var history = String.valueOf(messages.get(1).get("content"));
                assertTrue(history.contains("4位乘法器"));
                assertTrue(history.contains("使用内置 Multiplier"));
                assertEquals("那就按刚才的方案继续", hostState(messages).get("originalRequest"));
                assertEquals("NOT_CONFIRMED", hostState(messages).get("specificationStatus"));
              }
              return reply("使用内置 Multiplier");
            },
            10)) {
      controller.startTask(source, "如何设计4位乘法器？", CONFIG, new AgentTaskListener() {});
      assertEquals(AgentState.COMPLETED, controller.getState());
      controller.startTask(source, "那就按刚才的方案继续", CONFIG, new AgentTaskListener() {});
      assertEquals(2, modelRounds.get());
    } finally {
      close(source);
    }
  }

  private static ModelToolCall xorProposal() {
    return call(
        "propose_specification",
        Map.of(
            "kind",
            "BOOLEAN_EXPRESSIONS",
            "inputs",
            List.of(Map.of("name", "A", "width", 1), Map.of("name", "B", "width", 1)),
            "outputs",
            List.of(Map.of("name", "Y", "width", 1)),
            "expressions",
            Map.of("Y", "A ^ B")));
  }

  @Test
  void confirmationQuestionsDoNotAuthorizeWritesOrLoseTheProposal() {
    final var modelRounds = new AtomicInteger();
    final var source = source();
    try (var controller =
        controller(
            (config, messages, tools) -> {
              if (modelRounds.getAndIncrement() == 0) return calls(xorProposal());
              assertEquals(
                  "AWAITING_USER_CONFIRMATION", hostState(messages).get("specificationStatus"));
              return reply("这是待确认的异或规格");
            },
            10)) {
      controller.startTask(source, "Design XOR", CONFIG, new AgentTaskListener() {});
      for (var question : List.of("确认？", "可以?", "同意吗")) {
        controller.continueTask(question);
        assertEquals(AgentState.WAITING_FOR_USER, controller.getState());
        assertTrue(controller.hasPendingSpecification());
      }
      assertEquals(4, modelRounds.get());
    } finally {
      close(source);
    }
  }

  private static Map<String, Object> hostState(List<Map<String, Object>> messages) {
    final var state =
        messages.stream()
            .map(m -> String.valueOf(m.get("content")))
            .filter(text -> text.startsWith("Current host task state (authoritative;"))
            .findFirst()
            .orElseThrow();
    final var first = state.indexOf('{');
    final var last = state.lastIndexOf('}');
    return SimpleJson.parseObject(state.substring(first, last + 1));
  }

  @Test
  void cancellationDiscardsLateFinalResponse() throws Exception {
    final var entered = new CountDownLatch(1);
    final var release = new CountDownLatch(1);
    final ModelClient model =
        (config, messages, tools) -> {
          entered.countDown();
          release.await(5, TimeUnit.SECONDS);
          return reply("Done");
        };
    final var source = source();
    try (var controller = controller(model, 10)) {
      final var worker =
          new Thread(
              () -> controller.startTask(source, "Explain", CONFIG, new AgentTaskListener() {}));
      worker.start();
      assertTrue(entered.await(10, TimeUnit.SECONDS));
      controller.cancel();
      release.countDown();
      worker.join(5000);
      assertFalse(worker.isAlive());
      assertEquals(AgentState.CANCELLED, controller.getState());
    } finally {
      release.countDown();
      close(source);
    }
  }

  @Test
  void clarificationConsumesModelRoundBudget() {
    final var calls = new AtomicInteger();
    final var source = source();
    try (var controller =
        controller(
            (config, messages, tools) -> {
              calls.incrementAndGet();
              return reply("NEED_USER: Specify width");
            },
            2)) {
      controller.startTask(source, "Ambiguous", CONFIG, new AgentTaskListener() {});
      controller.continueTask("First clarification");
      controller.continueTask("Second clarification");
      assertEquals(2, calls.get());
      assertEquals(AgentState.FAILED_BUDGET, controller.getState());
    } finally {
      close(source);
    }
  }

  @Test
  void closingWaitingTaskReleasesIt() {
    final var source = source();
    final var controller = controller((config, messages, tools) -> reply("NEED_USER: Width?"), 10);
    try {
      controller.startTask(source, "Ambiguous", CONFIG, new AgentTaskListener() {});
      assertTrue(controller.hasPendingTask());
      controller.close();
      assertEquals(AgentState.CANCELLED, controller.getState());
      assertFalse(controller.hasPendingTask());
    } finally {
      controller.close();
      close(source);
    }
  }

  @Test
  void unknownToolReturnsRecoverableToolResult() {
    final var calls = new AtomicInteger();
    final var failures = new AtomicInteger();
    final var source = source();
    try (var controller =
        controller(
            (config, messages, tools) ->
                calls.getAndIncrement() == 0
                    ? calls(call("unknown_tool", Map.of()))
                    : reply("Recovered"),
            10)) {
      controller.startTask(
          source,
          "Explain",
          CONFIG,
          new AgentTaskListener() {
            @Override
            public void toolActivity(String tool, Map<String, Object> result) {
              if (Boolean.FALSE.equals(result.get("ok"))) failures.incrementAndGet();
            }
          });
      assertEquals(1, failures.get());
      assertEquals(AgentState.COMPLETED, controller.getState());
    } finally {
      close(source);
    }
  }

  @Test
  void modelTimeoutTerminatesTaskWithoutWaitingForLateResponse() {
    final var source = source();
    try (var controller =
        controller(
            (config, messages, tools) -> {
              Thread.sleep(5000);
              return reply("Late response");
            },
            10)) {
      final var shortConfig =
          new ModelConfig(CONFIG.endpoint(), "offline", "", Duration.ofMillis(100));
      controller.startTask(source, "Explain", shortConfig, new AgentTaskListener() {});
      assertEquals(AgentState.FAILED_BUDGET, controller.getState());
    } finally {
      close(source);
    }
  }

  @Test
  void hostVerifiesAndAppliesConfirmedXorWithUndoRedo() {
    exerciseCircuit(false);
  }

  @Test
  void hostDetectsWrongImplementationAndVerifiesRepair() {
    exerciseCircuit(true);
  }

  @Test
  void specificationAndReviewWaitingDoNotExpireVerifiedApply() {
    exerciseCircuit(false, true);
  }

  private void exerciseCircuit(boolean repair) {
    exerciseCircuit(repair, false);
  }

  private void exerciseCircuit(boolean repair, boolean waitPastBudget) {
    final var modelRounds = new AtomicInteger();
    final ModelClient model =
        (config, messages, tools) ->
            switch (modelRounds.getAndIncrement()) {
              case 0 ->
                  calls(
                      call(
                          "propose_specification",
                          Map.of(
                              "kind", "BOOLEAN_EXPRESSIONS",
                              "inputs",
                                  List.of(
                                      Map.of("name", "A", "width", 1),
                                      Map.of("name", "B", "width", 1)),
                              "outputs", List.of(Map.of("name", "Y", "width", 1)),
                              "expressions", Map.of("Y", "A ^ B"))));
              case 1 ->
                  calls(
                      add("Pin", 20, 30, Map.of("label", "A")),
                      add("Pin", 20, 130, Map.of("label", "B")),
                      add("Pin", 300, 80, Map.of("label", "Y", "type", "output")),
                      add(repair ? "AND Gate" : "XOR Gate", 180, 80, Map.of()));
              case 2 ->
                  calls(
                      connect("cmp_001", "p0", "cmp_004", "p1"),
                      connect("cmp_002", "p0", "cmp_004", "p2"),
                      connect("cmp_004", "p0", "cmp_003", "p0"));
              case 4 ->
                  calls(
                      call(
                          "replace_logic_expression",
                          Map.of("output", "Y", "expression", "A ^ B")));
              default -> reply("Circuit implementation is ready");
            };
    final var source = source();
    final var listener = new ReviewListener();
    final var baseline = CircuitDigest.sha256(source.getCurrentCircuit());
    final var baselineCanonical = CircuitDigest.canonical(source.getCurrentCircuit());
    final var clock = new AtomicLong();
    final var permissions = new AgentPermissionManager();
    permissions.setEditsAllowed(true);
    try (var controller =
        waitPastBudget
            ? new AgentController(
                new WorkspaceManager(tempDir),
                permissions,
                TaskBudget.defaults(),
                model,
                StandardCircuitTools.createRegistry(),
                clock::get)
            : controller(model, 10)) {
      controller.startTask(source, "Build Y=A XOR B", CONFIG, listener);
      assertEquals(AgentState.WAITING_FOR_USER, controller.getState());
      assertTrue(controller.hasPendingSpecification());
      assertEquals(baseline, CircuitDigest.sha256(source.getCurrentCircuit()));
      if (waitPastBudget) clock.addAndGet(TimeUnit.HOURS.toNanos(1));
      controller.confirmSpecification();
      assertEquals(AgentState.REVIEW, controller.getState(), listener.failures.toString());
      assertTrue(
          listener.applyAllowed,
          listener.evidence == null ? "No evidence" : listener.evidence.summary());
      assertEquals(ValidationStatus.PASS, listener.evidence.status());
      assertEquals(baseline, CircuitDigest.sha256(source.getCurrentCircuit()));
      if (waitPastBudget) clock.addAndGet(TimeUnit.HOURS.toNanos(1));
      final var result = controller.applyReviewedTask();
      assertTrue(result.applied(), result.message());
      final var applied = CircuitDigest.sha256(source.getCurrentCircuit());
      assertFalse(baseline.equals(applied));
      source.undoAction();
      assertEquals(
          baseline,
          CircuitDigest.sha256(source.getCurrentCircuit()),
          CircuitDiff.compareCanonical(
                  baselineCanonical, CircuitDigest.canonical(source.getCurrentCircuit()))
              .toString());
      source.redoAction();
      assertEquals(applied, CircuitDigest.sha256(source.getCurrentCircuit()));
      assertEquals(repair ? 6 : 4, modelRounds.get());
    } finally {
      close(source);
    }
  }

  private AgentController controller(ModelClient model, int rounds) {
    final var permissions = new AgentPermissionManager();
    permissions.setEditsAllowed(true);
    return new AgentController(
        new WorkspaceManager(tempDir),
        permissions,
        new TaskBudget(rounds, 3, 30, 300),
        model,
        StandardCircuitTools.createRegistry());
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

  private static ModelTurn reply(String text) {
    return new ModelTurn(text, List.of(), "stop", Map.of("role", "assistant", "content", text));
  }

  private static ModelToolCall call(String name, Map<String, Object> arguments) {
    return new ModelToolCall("call-" + java.util.UUID.randomUUID(), name, arguments);
  }

  private static ModelToolCall add(String type, int x, int y, Map<String, String> attrs) {
    return call("add_component", Map.of("type", type, "x", x, "y", y, "attributes", attrs));
  }

  private static ModelToolCall connect(String from, String fromPort, String to, String toPort) {
    return call(
        "connect",
        Map.of(
            "fromComponentId", from, "fromPort", fromPort, "toComponentId", to, "toPort", toPort));
  }

  private static ModelTurn calls(ModelToolCall... calls) {
    final var rawCalls = new ArrayList<Map<String, Object>>();
    for (var call : calls) {
      rawCalls.add(
          Map.of(
              "id",
              call.id(),
              "type",
              "function",
              "function",
              Map.of("name", call.name(), "arguments", SimpleJson.stringify(call.arguments()))));
    }
    return new ModelTurn(
        "",
        List.of(calls),
        "tool_calls",
        Map.of("role", "assistant", "content", "", "tool_calls", rawCalls));
  }

  private static final class ReviewListener implements AgentTaskListener {
    private boolean applyAllowed;
    private VerificationEvidence evidence;
    private final List<Map<String, Object>> failures = new ArrayList<>();

    @Override
    public void toolActivity(String tool, Map<String, Object> result) {
      if (Boolean.FALSE.equals(result.get("ok"))) failures.add(result);
    }

    @Override
    public void reviewReady(
        String taskId,
        String message,
        VerificationEvidence verification,
        Map<String, Object> diff,
        boolean allowed,
        Path taskDirectory) {
      applyAllowed = allowed;
      evidence = verification;
    }
  }
}
