/*
 * Logisim-evolution - digital logic design tool and simulator
 * Copyright by the Logisim-evolution developers
 *
 * https://github.com/logisim-evolution/
 *
 * This is free software released under GNU GPLv3 license
 */

package com.cburch.logisim.ai.agent;

import static com.cburch.logisim.ai.i18n.AiStrings.S;

import com.cburch.logisim.ai.i18n.AiStrings;
import com.cburch.logisim.ai.logging.AgentTaskLogger;
import com.cburch.logisim.ai.model.ModelClient;
import com.cburch.logisim.ai.model.ModelConfig;
import com.cburch.logisim.ai.model.OpenAiCompatibleClient;
import com.cburch.logisim.ai.tools.CircuitDiff;
import com.cburch.logisim.ai.tools.StandardCircuitTools;
import com.cburch.logisim.ai.tools.ToolExecutionContext;
import com.cburch.logisim.ai.tools.ToolRegistry;
import com.cburch.logisim.ai.tools.ToolResult;
import com.cburch.logisim.ai.transaction.AgentApplyManager;
import com.cburch.logisim.ai.transaction.CircuitDigest;
import com.cburch.logisim.ai.transaction.VerifiedCandidate;
import com.cburch.logisim.ai.util.SimpleJson;
import com.cburch.logisim.ai.validation.TaskSpec;
import com.cburch.logisim.ai.validation.ValidationStatus;
import com.cburch.logisim.ai.workspace.AgentWorkspace;
import com.cburch.logisim.ai.workspace.WorkspaceManager;
import com.cburch.logisim.proj.Project;
import com.cburch.logisim.util.LocaleManager;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongSupplier;
import java.util.regex.Pattern;

/** Codex-style bounded Plan/Act/Observe loop specialized for Logisim circuits. */
public final class AgentController implements AutoCloseable {
  private static final String SYSTEM_PROMPT =
      """
You are Logisim AI Circuit Agent. Work only through the provided circuit tools.
The host supplies the current application UI locale with every request. All user-visible prose must use that locale, even when the user's input or earlier messages use a different language. Tool names, identifiers, JSON keys and protocol markers remain unchanged. Host task state is authoritative about permission and specification confirmation; never claim confirmation based only on your own text.
The structured circuit context is authoritative. Never invent component ids, ports, tool results, or test results.
For a critical logical ambiguity that changes circuit behavior, do not guess: reply with NEED_USER: followed by one concise clarification question.
If the user requests a circuit modification while session edit permission is false, do not keep attempting write tools. Reply with NEED_USER: asking the user to enable "Allow edits this session".
Non-semantic layout details may be chosen autonomously.
      Place input pins to the left of arithmetic/gate components and output pins to the right, with separate routing space. Use add_component attributes to set labels, widths and pin direction in one call before wiring.
Prefer structural edits when they preserve the user's layout. Use replace_logic_expression only when semantic rebuilding is appropriate and note that layout may change.
When changing a component type in place, use replace_component first. If it rejects because a connected port moves, inspect the circuit, remove only the short terminal wire segment at each moved port, retry replace_component, and reconnect the affected ports with connect. Never redraw the whole source net from the driving component; connect continues from the nearest existing point on that net.
Before any modification, propose_specification with explicit inputs, outputs and Boolean expressions or unsigned multiplication semantics. The user must confirm that specification before any write is allowed.
Explain circuit functions in user-facing prose using signal names and widths, not raw specification JSON. After propose_specification, the host presents the confirmation and controls; avoid repeating that contract or its button instructions. Machine JSON remains in tool calls and host task state.
For multiplication, clarify bit width and signedness. This version supports unsigned products with full 2N output bits, as one bus or slices in low-to-high order.
Two 4-bit operands produce a full 8-bit product, not a truncated 16-bit product.
Once confirmed, the functional specification is immutable for this task. You may edit layout and implementation, but never change the accepted behavior.
After modification, use verify_specification. The host also verifies before Review. run_truth_table and model-provided verify_truth_table are diagnostic observations and cannot authorize Apply.
If specification verification fails, inspect counterexamples and repair the workspace. At most three repair attempts are permitted by the host.
If verification is unsupported or incomplete, explain the limitation. The host will mark the result INCONCLUSIVE and will not apply it by default.
Do not request shell access, arbitrary files, Java execution, or secrets. API credentials are not part of circuit context.
Keep user-visible reasoning concise: report actions, evidence, assumptions and unresolved limitations, not private chain-of-thought.
""";

  private final WorkspaceManager workspaceManager;
  private final AgentPermissionManager permissions;
  private final TaskBudget budget;
  private final ModelClient modelClient;
  private final ToolRegistry tools;
  private final LongSupplier monotonicClock;
  private final AgentApplyManager applyManager = new AgentApplyManager();
  private final AtomicBoolean cancelRequested = new AtomicBoolean(false);
  private final SessionConversation conversation = new SessionConversation();
  private static final Pattern NEED_USER = Pattern.compile("(?m)^\\h*NEED_USER\\h*:");
  private ActiveTask active;
  private boolean closed;
  private AgentState preparationState = AgentState.READY;

  public AgentController(AgentPermissionManager permissions) {
    this(
        new WorkspaceManager(),
        permissions,
        TaskBudget.defaults(),
        new OpenAiCompatibleClient(),
        StandardCircuitTools.createRegistry());
  }

  public AgentController(
      WorkspaceManager workspaceManager,
      AgentPermissionManager permissions,
      TaskBudget budget,
      ModelClient modelClient,
      ToolRegistry tools) {
    this(workspaceManager, permissions, budget, modelClient, tools, System::nanoTime);
  }

  AgentController(
      WorkspaceManager workspaceManager,
      AgentPermissionManager permissions,
      TaskBudget budget,
      ModelClient modelClient,
      ToolRegistry tools,
      LongSupplier monotonicClock) {
    this.workspaceManager = workspaceManager;
    this.permissions = permissions;
    this.budget = budget;
    this.modelClient = modelClient;
    this.tools = tools;
    this.monotonicClock = monotonicClock;
  }

  public synchronized AgentState getState() {
    return active == null ? preparationState : active.state;
  }

  public synchronized boolean hasPendingTask() {
    return active != null
        && (active.state == AgentState.WAITING_FOR_USER || active.state == AgentState.REVIEW);
  }

  public synchronized boolean hasPendingSpecification() {
    return active != null && active.context.pendingSpecification() != null;
  }

  /** Immutable pending contract for matching a displayed card to the current host proposal. */
  public synchronized TaskSpec pendingSpecification() {
    return active == null ? null : active.context.pendingSpecification();
  }

  public synchronized boolean isWaitingForEditPermission() {
    return active != null
        && active.state == AgentState.WAITING_FOR_USER
        && active.waitReason == WaitReason.EDIT_PERMISSION;
  }

  public void startTask(
      Project project, String request, ModelConfig config, AgentTaskListener listener) {
    if (project == null || project.getCurrentCircuit() == null)
      throw new IllegalArgumentException(S.get("aiControllerOpenCircuit"));
    if (request == null || request.isBlank())
      throw new IllegalArgumentException(S.get("aiControllerEmptyRequest"));
    synchronized (this) {
      if (closed) throw new IllegalStateException(S.get("aiControllerClosed"));
      if (active != null
          && active.state != AgentState.COMPLETED
          && active.state != AgentState.DISCARDED
          && active.state != AgentState.CANCELLED
          && !active.state.name().startsWith("FAILED")) {
        throw new IllegalStateException(S.get("aiControllerActiveTask"));
      }
      cancelRequested.set(false);
      active = null;
    }
    transition(
        listener,
        AgentState.CHECKING_PERMISSION,
        permissions.isEditsAllowed()
            ? S.get("aiControllerPermissionEnabled")
            : S.get("aiControllerPermissionReadonly"));
    transition(listener, AgentState.PREPARING_WORKSPACE, S.get("aiControllerCreatingWorkspace"));
    try {
      final var workspace = workspaceManager.create(project, project.getCurrentCircuit());
      try {
        final var logger = new AgentTaskLogger(workspace.taskId(), workspace.taskDirectory());
        final var context =
            new ToolExecutionContext(workspace, workspaceManager, permissions, budget, logger);
        final var messages = new ArrayList<Map<String, Object>>();
        conversation.scope(project, workspace.sourceFile(), workspace.sourceCircuit());
        messages.add(message("system", SYSTEM_PROMPT));
        messages.add(
            message(
                "user",
                initialUserMessage(request, context, permissions.isEditsAllowed())
                    + "\n\n"
                    + conversation.render()));
        conversation.addUser(request);
        final var task =
            new ActiveTask(
                workspace,
                context,
                logger,
                messages,
                request,
                config,
                listener,
                budget,
                monotonicClock);
        context.setExecutionGuard(task.guard::check);
        synchronized (this) {
          if (closed) {
            task.workspace.close();
            task.operations.shutdownNow();
            throw new CancellationException("AI controller was closed during workspace creation");
          }
          active = task;
          if (cancelRequested.get()) task.guard.cancel();
        }
        logger.event(
            "TASK_STARTED",
            AgentState.PREPARING_WORKSPACE,
            Map.of(
                "request", request,
                "circuit", workspace.circuit().getName(),
                "baselineDigest", workspace.sourceDigest(),
                "baselineProjectDigest", workspace.sourceProjectDigest(),
                "editPermission", permissions.isEditsAllowed()));
        runLoop(task);
      } catch (Exception e) {
        workspace.close();
        throw e;
      }
    } catch (Exception e) {
      failBeforeActive(listener, S.get("aiControllerPrepareFailed"), e);
    }
  }

  public void continueTask(String userMessage) {
    final ActiveTask task;
    synchronized (this) {
      task = active;
      if (task == null || task.state != AgentState.WAITING_FOR_USER) {
        throw new IllegalStateException(S.get("aiControllerNoClarification"));
      }
    }
    if (userMessage == null || userMessage.isBlank())
      throw new IllegalArgumentException(S.get("aiControllerClarificationEmpty"));
    if (task.context.pendingSpecification() != null && isSpecificationConfirmation(userMessage)) {
      confirmSpecification(userMessage);
      return;
    }
    if (!resumeAllowed(task)) return;
    conversation.addUser(userMessage);
    task.messages.add(message("user", userMessage));
    task.logger.event(
        "USER_CLARIFICATION", AgentState.WAITING_FOR_USER, Map.of("message", userMessage));
    if (task.context.pendingSpecification() != null && isPermissionNotification(userMessage)) {
      waitForSpecification(task);
      return;
    }
    if (task.waitReason == WaitReason.EDIT_PERMISSION && isPermissionNotification(userMessage)) {
      if (!permissions.isEditsAllowed()) {
        waitForPermission(task);
        return;
      }
      runLoop(task);
      return;
    }
    if (!isQuestion(userMessage)) task.context.rejectPendingSpecification();
    task.messages.add(
        message("system", "Current session edit permission: " + permissions.isEditsAllowed()));
    runLoop(task);
  }

  /** Permission is changed immediately by the UI; only a blocked task is resumed here. */
  public void editPermissionChanged() {
    final ActiveTask task;
    synchronized (this) {
      task = active;
      if (task == null
          || task.running
          || task.state != AgentState.WAITING_FOR_USER
          || task.waitReason != WaitReason.EDIT_PERMISSION
          || !permissions.isEditsAllowed()) return;
    }
    if (resumeAllowed(task)) runLoop(task);
  }

  public synchronized void cancel() {
    cancelRequested.set(true);
    if (active != null) active.guard.cancel();
  }

  public void finishCancellation() {
    final ActiveTask task;
    synchronized (this) {
      task = active;
    }
    if (task != null && task.guard.isCancelled() && !task.running && !task.workspace.isClosed())
      cancelTask(task);
  }

  public synchronized Map<String, Object> reviewDetails() {
    if (active == null || active.state != AgentState.REVIEW) return Map.of();
    return active.reviewDetails;
  }

  /** This entry point is invoked by the user's explicit specification confirmation in the UI. */
  public void confirmSpecification() {
    confirmSpecification(null, null);
  }

  /** A button can only confirm the exact contract shown when that button was invoked. */
  public void confirmDisplayedSpecification(TaskSpec expectedSpecification) {
    if (expectedSpecification == null) {
      throw new IllegalStateException(S.get("aiControllerNoSpecConfirmation"));
    }
    confirmSpecification(null, expectedSpecification);
  }

  private void confirmSpecification(String userMessage) {
    confirmSpecification(userMessage, null);
  }

  private void confirmSpecification(String userMessage, TaskSpec expectedSpecification) {
    final ActiveTask task;
    synchronized (this) {
      task = active;
      if (task == null
          || task.state != AgentState.WAITING_FOR_USER
          || task.context.pendingSpecification() == null
          || (expectedSpecification != null
              && !expectedSpecification.equals(task.context.pendingSpecification()))) {
        throw new IllegalStateException(S.get("aiControllerNoSpecConfirmation"));
      }
    }
    if (!resumeAllowed(task)) return;
    synchronized (this) {
      // A stale click must not consume a new proposal if state changed while resuming the guard.
      if (expectedSpecification != null
          && (active != task
              || task.state != AgentState.WAITING_FOR_USER
              || !expectedSpecification.equals(task.context.pendingSpecification()))) {
        throw new IllegalStateException(S.get("aiControllerNoSpecConfirmation"));
      }
      if (userMessage != null) {
        conversation.addUser(userMessage);
        task.messages.add(message("user", userMessage));
      }
      task.context.acceptSpecification();
    }
    task.messages.add(
        message(
            "user",
            "I confirm this functional specification: "
                + SimpleJson.stringify(task.context.confirmedSpecification().toMap())));
    assistantMessage(task, S.get("aiControllerSpecConfirmed"));
    if (!permissions.isEditsAllowed()) {
      waitForPermission(task);
      return;
    }
    runLoop(task);
  }

  public String cleanupRetainedTaskArtifacts() {
    final ActiveTask task;
    synchronized (this) {
      task = active;
      if (task == null
          || !(task.state == AgentState.COMPLETED
              || task.state == AgentState.DISCARDED
              || task.state == AgentState.CANCELLED
              || task.state.name().startsWith("FAILED"))) {
        throw new IllegalStateException(S.get("aiControllerNoArtifacts"));
      }
    }
    final var directory = task.workspace.taskDirectory();
    try {
      workspaceManager.deleteRetainedWorkspace(task.workspace);
      task.logger.event(
          "WORKSPACE_DELETED",
          task.state,
          Map.of("workspace", task.workspace.workspaceFile().toString()));
    } catch (IOException e) {
      throw new IllegalStateException(
          S.get("aiControllerCleanupFailed", AiStrings.displayMessage(safeMessage(e))), e);
    }
    return S.get("aiControllerCleanupDone", directory);
  }

  public AgentApplyManager.ApplyResult applyReviewedTask() {
    final ActiveTask task;
    synchronized (this) {
      task = active;
      if (task == null || task.state != AgentState.REVIEW)
        throw new IllegalStateException(S.get("aiControllerNoReviewedChange"));
      if (!task.context.hasTrustedPass() || task.candidate == null) {
        throw new IllegalStateException(S.get("aiControllerUnverifiedApply"));
      }
      if (!resumeAllowed(task)) {
        return new AgentApplyManager.ApplyResult(false, false, S.get("aiControllerApplyExpired"));
      }
      task.state = AgentState.APPLYING;
    }
    task.listener.stateChanged(AgentState.APPLYING, S.get("aiControllerApplying"));
    task.logger.event("APPLY_STARTED", AgentState.APPLYING, Map.of());
    final AgentApplyManager.ApplyResult result;
    try {
      result =
          applyManager.apply(
              task.workspace,
              task.candidate,
              permissions,
              task.guard::check,
              Duration.ofSeconds(budget.toolTimeoutSeconds()));
      // Once commit succeeds, report the committed result even if cancellation arrives later.
      if (!result.applied() && !result.recoveryFailed()) task.guard.check();
    } catch (CancellationException e) {
      cancelTask(task);
      return new AgentApplyManager.ApplyResult(false, false, S.get("aiControllerApplyCancelled"));
    } catch (TaskExecutionGuard.BudgetExceededException e) {
      failTask(task, AgentState.FAILED_BUDGET, e.getMessage(), e);
      return new AgentApplyManager.ApplyResult(
          false, false, AiStrings.displayMessage(e.getMessage()));
    }
    if (result.applied()) {
      task.state = AgentState.COMPLETED;
      task.logger.event("TASK_COMPLETED", task.state, Map.of("message", result.message()));
      finalizeReport(task, "APPLIED");
      closeTaskWorkspace(task);
      task.listener.stateChanged(task.state, AiStrings.displayMessage(result.message()));
    } else if (result.recoveryFailed()) {
      failTask(task, AgentState.FAILED_INTERNAL, result.message(), null);
    } else {
      task.state = AgentState.REVIEW;
      task.guard.pause();
      task.logger.event(
          result.conflict() ? "APPLY_CONFLICT" : "APPLY_FAILED",
          task.state,
          Map.of("message", result.message()));
      task.listener.stateChanged(task.state, AiStrings.displayMessage(result.message()));
    }
    return new AgentApplyManager.ApplyResult(
        result.applied(),
        result.conflict(),
        AiStrings.displayMessage(result.message()),
        result.recoveryFailed());
  }

  public void discardReviewedTask() {
    final ActiveTask task;
    synchronized (this) {
      task = active;
      if (task == null
          || (task.state != AgentState.REVIEW && task.state != AgentState.WAITING_FOR_USER)) {
        throw new IllegalStateException(S.get("aiControllerNoDiscardableTask"));
      }
      task.state = AgentState.DISCARDED;
    }
    try {
      workspaceManager.persist(task.workspace);
    } catch (IOException e) {
      task.logger.event("WORKSPACE_PERSIST_FAILED", task.state, Map.of("error", safeMessage(e)));
    }
    task.logger.event(
        "TASK_DISCARDED",
        task.state,
        Map.of("workspace", task.workspace.workspaceFile().toString()));
    finalizeReport(task, "DISCARDED_WORKSPACE_RETAINED");
    closeTaskWorkspace(task);
    task.listener.stateChanged(
        task.state, S.get("aiControllerRetained", task.workspace.taskDirectory()));
  }

  private void runLoop(ActiveTask task) {
    task.running = true;
    try {
      while (task.rounds < budget.maxModelRounds()) {
        task.guard.check();
        transition(
            task,
            task.rounds == 0 ? AgentState.PLANNING : AgentState.EXECUTING,
            S.get("aiControllerDecisionRound", task.rounds + 1, budget.maxModelRounds()));
        task.rounds++;
        final var turn =
            bounded(
                task,
                () ->
                    modelClient.complete(
                        task.config, modelMessages(task), tools.openAiToolDefinitions()),
                task.config.timeout());
        task.guard.check();
        task.messages.add(turn.rawAssistantMessage());

        if (turn.hasToolCalls()) {
          if (turn.content() != null && !turn.content().isBlank()) {
            assistantMessage(task, turn.content());
          }
          for (final var call : turn.toolCalls()) {
            task.guard.check();
            ToolResult result;
            task.logger.event(
                "TOOL_CALL_STARTED",
                task.state,
                Map.of("tool", call.name(), "arguments", call.arguments()));
            final var toolStartedAt = System.nanoTime();
            try {
              final var tool = tools.require(call.name());
              final var toolState =
                  call.name().startsWith("verify_") || call.name().equals("run_truth_table")
                      ? AgentState.VALIDATING
                      : tool.writesCircuit()
                              && task.context.lastValidation().status() == ValidationStatus.FAIL
                          ? AgentState.REPAIRING
                          : AgentState.EXECUTING;
              transition(task, toolState, S.get("aiControllerRunningTool", call.name()));
              result =
                  bounded(
                      task,
                      () -> tool.execute(call.arguments(), task.context),
                      Duration.ofSeconds(budget.toolTimeoutSeconds()));
            } catch (CancellationException | TaskExecutionGuard.BudgetExceededException e) {
              throw e;
            } catch (Exception e) {
              result = ToolResult.failure(safeMessage(e));
            }
            task.guard.check();
            final var durationMs = Duration.ofNanos(System.nanoTime() - toolStartedAt).toMillis();
            task.logger.event(
                result.ok() ? "TOOL_CALL_SUCCEEDED" : "TOOL_CALL_FAILED",
                task.state,
                Map.of("tool", call.name(), "durationMs", durationMs, "result", result.toMap()));
            task.listener.toolActivity(call.name(), result.toMap());
            task.messages.add(toolMessage(call.id(), result));
          }
          if (task.context.pendingSpecification() != null) {
            task.specificationFlowStarted = true;
            waitForSpecification(task);
            return;
          }
          if (task.context.lastValidation().status() == ValidationStatus.FAIL
              && task.context.repairAttempts() >= budget.maxRepairAttempts()) {
            failTask(
                task, AgentState.FAILED_VALIDATION, S.get("aiControllerRepairExhausted"), null);
            return;
          }
          continue;
        }

        task.guard.check();
        final var finalMessage = turn.content() == null ? "" : turn.content().trim();
        task.finalMessage = finalMessage;
        final var clarification = NEED_USER.matcher(finalMessage);
        if (clarification.find()) {
          task.logger.event(
              "USER_CLARIFICATION_REQUIRED",
              AgentState.WAITING_FOR_USER,
              Map.of("question", finalMessage));
          final var reason =
              !permissions.isEditsAllowed()
                      && (task.specificationFlowStarted
                          || finalMessage.contains("Allow edits this session")
                          || finalMessage.contains(S.get("aiAllowEdits")))
                  ? WaitReason.EDIT_PERMISSION
                  : WaitReason.CLARIFICATION;
          waitForUser(
              task,
              reason,
              S.get("aiControllerWaitingClarification"),
              finalMessage.replaceFirst("(?m)^\\h*NEED_USER\\h*:", ""));
          return;
        }
        if (task.specificationFlowStarted && !task.context.modified()) {
          if (task.context.pendingSpecification() != null) {
            waitForSpecification(task);
          } else if (!permissions.isEditsAllowed()) {
            waitForPermission(task);
          } else {
            waitForUser(
                task,
                WaitReason.CLARIFICATION,
                S.get("aiControllerDesignIncompleteStatus"),
                finalMessage + "\n\n" + S.get("aiControllerDesignIncomplete"));
          }
          return;
        }
        if (task.context.modified()) {
          transition(task, AgentState.VALIDATING, S.get("aiControllerVerifying"));
          final var evidence =
              bounded(
                  task,
                  task.context::verifySpecification,
                  Duration.ofSeconds(budget.toolTimeoutSeconds()));
          task.guard.check();
          task.logger.event("HOST_VALIDATION", task.state, evidence.toMap());
          final var status = evidence.status();
          if (status == ValidationStatus.FAIL) {
            if (task.context.repairAttempts() >= budget.maxRepairAttempts()) {
              failTask(
                  task,
                  AgentState.FAILED_VALIDATION,
                  S.get("aiControllerRepairFailed", task.context.repairAttempts()),
                  null);
              return;
            }
            task.messages.add(
                message(
                    "system",
                    "Host specification verification FAILED. Repair the implementation without"
                        + " changing the confirmed specification. Counterexamples: "
                        + SimpleJson.stringify(evidence.toMap())));
            continue;
          }
          prepareReview(task);
          return;
        }

        assistantMessage(task, finalMessage);
        task.state = AgentState.COMPLETED;
        task.logger.event("TASK_COMPLETED", task.state, Map.of("readOnly", true));
        finalizeReport(task, "READ_ONLY_COMPLETED");
        closeTaskWorkspace(task);
        task.listener.stateChanged(task.state, S.get("aiControllerReadOnlyCompleted"));
        return;
      }
      failTask(task, AgentState.FAILED_BUDGET, S.get("aiControllerModelBudget"), null);
    } catch (CancellationException e) {
      cancelTask(task);
    } catch (TaskExecutionGuard.BudgetExceededException e) {
      failTask(task, AgentState.FAILED_BUDGET, safeMessage(e), e);
    } catch (Exception e) {
      failTask(
          task,
          AgentState.FAILED_INTERNAL,
          S.get("aiControllerExecutionFailed", AiStrings.displayMessage(safeMessage(e))),
          e);
    } finally {
      task.running = false;
    }
  }

  private static <T> T bounded(ActiveTask task, Callable<T> operation, Duration limit)
      throws Exception {
    task.guard.check();
    final long deadline =
        System.nanoTime() + Math.min(task.guard.remainingNanos(), limit.toNanos());
    final Future<T> future = task.operations.submit(operation);
    try {
      while (true) {
        task.guard.check();
        final long remaining = deadline - System.nanoTime();
        if (remaining <= 0) {
          task.guard.cancel();
          throw new TaskExecutionGuard.BudgetExceededException(
              "Operation execution budget exhausted");
        }
        try {
          return future.get(
              Math.min(remaining, TimeUnit.MILLISECONDS.toNanos(100)), TimeUnit.NANOSECONDS);
        } catch (TimeoutException e) {
          // Poll the shared cancellation token while the operation is pending.
        } catch (ExecutionException e) {
          if (e.getCause() instanceof Exception cause) throw cause;
          throw new RuntimeException(e.getCause());
        }
      }
    } finally {
      if (!future.isDone()) future.cancel(true);
    }
  }

  private void prepareReview(ActiveTask task) {
    task.state = AgentState.REVIEW;
    final var evidence = task.context.lastValidation();
    final var diff =
        CircuitDiff.compareCanonical(
            task.workspace.baselineCanonical(), CircuitDigest.canonical(task.workspace.circuit()));
    task.candidate =
        task.context.hasTrustedPass()
            ? VerifiedCandidate.prepare(task.workspace, task.context.validationPlan(), evidence)
            : null;
    final var applyAllowed = task.candidate != null && permissions.isEditsAllowed();
    final var details = new LinkedHashMap<String, Object>();
    details.put("taskId", task.workspace.taskId());
    details.put(
        "specification",
        task.context.confirmedSpecification() == null
            ? Map.of()
            : task.context.confirmedSpecification().toMap());
    details.put("candidateId", task.candidate == null ? "" : task.candidate.id());
    details.put("candidateDigest", task.candidate == null ? "" : task.candidate.circuitDigest());
    details.put("baselineDigest", task.workspace.sourceDigest());
    details.put("verification", evidence.toMap());
    details.put("diff", diff);
    task.reviewDetails = Map.copyOf(details);
    task.guard.pause();
    task.logger.event(
        "REVIEW_REQUIRED",
        task.state,
        Map.of(
            "validation", evidence.toMap(),
            "diff", diff,
            "applyAllowed", applyAllowed));
    finalizeReport(task, "AWAITING_REVIEW");
    assistantMessage(task, task.finalMessage);
    task.listener.reviewReady(
        task.workspace.taskId(),
        task.finalMessage,
        evidence,
        diff,
        applyAllowed,
        task.workspace.taskDirectory());
    task.listener.stateChanged(
        task.state,
        applyAllowed ? S.get("aiControllerReviewReady") : S.get("aiControllerReviewDisabled"));
  }

  private void cancelTask(ActiveTask task) {
    task.state = AgentState.CANCELLED;
    try {
      workspaceManager.persist(task.workspace);
    } catch (IOException ignored) {
    }
    task.logger.event("TASK_CANCELLED", task.state, Map.of());
    finalizeReport(task, "CANCELLED_WORKSPACE_RETAINED");
    closeTaskWorkspace(task);
    task.listener.stateChanged(task.state, S.get("aiControllerCancelled"));
  }

  private boolean resumeAllowed(ActiveTask task) {
    try {
      task.guard.resume();
      task.guard.check();
      return true;
    } catch (CancellationException e) {
      cancelTask(task);
    } catch (TaskExecutionGuard.BudgetExceededException e) {
      failTask(task, AgentState.FAILED_BUDGET, e.getMessage(), e);
    }
    return false;
  }

  private void failTask(ActiveTask task, AgentState state, String message, Throwable error) {
    task.state = state;
    task.finalMessage = message;
    try {
      workspaceManager.persist(task.workspace);
    } catch (IOException ignored) {
    }
    task.logger.event("TASK_FAILED", state, Map.of("message", message));
    finalizeReport(task, state.name());
    closeTaskWorkspace(task);
    task.listener.failed(AiStrings.displayMessage(message), error);
    task.listener.stateChanged(state, AiStrings.displayMessage(message));
  }

  private void failBeforeActive(AgentTaskListener listener, String message, Throwable error) {
    synchronized (this) {
      if (active == null) preparationState = AgentState.FAILED_INTERNAL;
    }
    listener.failed(message + ": " + AiStrings.displayMessage(safeMessage(error)), error);
    listener.stateChanged(AgentState.FAILED_INTERNAL, message);
  }

  private void finalizeReport(ActiveTask task, String disposition) {
    task.logger.writeReport(
        task.originalRequest,
        task.finalMessage,
        task.workspace.sourceDigest(),
        CircuitDigest.sha256(task.workspace.circuit()),
        task.context.lastValidation(),
        CircuitDiff.compareCanonical(
            task.workspace.baselineCanonical(), CircuitDigest.canonical(task.workspace.circuit())),
        task.context.repairAttempts(),
        disposition,
        Map.of(
            "agentVersion",
            "1.1.6",
            "modelRounds",
            task.rounds,
            "uiLocale",
            LocaleManager.getLocale().toLanguageTag(),
            "specification",
            task.context.confirmedSpecification() == null
                ? Map.of()
                : task.context.confirmedSpecification().toMap(),
            "trustedValidation",
            task.context.hasTrustedPass()));
  }

  private static void closeTaskWorkspace(ActiveTask task) {
    task.workspace.close();
    task.operations.shutdownNow();
  }

  @Override
  public void close() {
    final ActiveTask task;
    synchronized (this) {
      closed = true;
      cancel();
      task = active;
    }
    if (task != null && !task.running && !task.workspace.isClosed()) cancelTask(task);
  }

  private void transition(AgentTaskListener listener, AgentState state, String detail) {
    synchronized (this) {
      preparationState = state;
    }
    listener.stateChanged(state, detail);
  }

  private void transition(ActiveTask task, AgentState state, String detail) {
    task.state = state;
    task.logger.event("STATE_CHANGED", state, Map.of("detail", detail));
    task.listener.stateChanged(state, detail);
  }

  private static Map<String, Object> message(String role, String content) {
    final var result = new LinkedHashMap<String, Object>();
    result.put("role", role);
    result.put("content", content);
    return result;
  }

  private static Map<String, Object> toolMessage(String toolCallId, ToolResult result) {
    final var message = new LinkedHashMap<String, Object>();
    message.put("role", "tool");
    message.put("tool_call_id", toolCallId);
    message.put("content", SimpleJson.stringify(result.toMap()));
    return message;
  }

  private static String initialUserMessage(
      String request, ToolExecutionContext context, boolean editPermission) {
    return "User task:\n"
        + request
        + "\n\nSession edit permission: "
        + editPermission
        + "\n\nCurrent circuit context:\n"
        + SimpleJson.stringify(context.circuitContext().context());
  }

  private List<Map<String, Object>> modelMessages(ActiveTask task) {
    final var state = new LinkedHashMap<String, Object>();
    state.put("originalRequest", task.originalRequest);
    state.put("editPermission", permissions.isEditsAllowed());
    state.put("workspaceVersion", task.context.workspaceVersion());
    state.put("verification", task.context.lastValidation().toMap());
    state.put(
        "specificationStatus",
        task.context.confirmedSpecification() != null
            ? "CONFIRMED"
            : task.context.pendingSpecification() != null
                ? "AWAITING_USER_CONFIRMATION"
                : "NOT_CONFIRMED");
    state.put(
        "confirmedSpecification",
        task.context.confirmedSpecification() == null
            ? Map.of()
            : task.context.confirmedSpecification().toMap());
    state.put(
        "pendingSpecification",
        task.context.pendingSpecification() == null
            ? Map.of()
            : task.context.pendingSpecification().toMap());
    final var messages = new ArrayList<>(task.messages);
    messages.add(
        message(
            "system",
            "Current host task state (authoritative; overrides old permission or confirmation"
                + " claims):\n"
                + SimpleJson.stringify(state)
                + "\n"
                + "Continue the original task using the user's follow-ups. An empty circuit"
                + " describes the starting state, not an absent task. Only CONFIRMED authorizes"
                + " writes, with editPermission=true. If no contract is confirmed, propose one;"
                + " never claim it is already confirmed."));
    final var locale = LocaleManager.getLocale();
    messages.add(
        message(
            "system",
            "Current application UI locale: "
                + locale.toLanguageTag()
                + " ("
                + locale.getDisplayName(java.util.Locale.ENGLISH)
                + "). All user-visible replies, including clarification questions and final"
                + " result summaries, MUST use this application locale regardless of the user's"
                + " input language, prior assistant replies, or earlier locale instructions."
                + " Keep tool names, JSON keys, signal/component identifiers, formulas, and the"
                + " NEED_USER: protocol marker unchanged. This directive applies to the current"
                + " response and supersedes language preferences in previous messages."));
    return List.copyOf(messages);
  }

  private void assistantMessage(ActiveTask task, String text) {
    conversation.addAssistant(text);
    task.listener.assistantMessage(text);
  }

  private void waitForSpecification(ActiveTask task) {
    final var specification = task.context.pendingSpecification();
    final var presentation =
        AiStrings.compose(
            specification.describe(),
            S.get("aiControllerSpecConfirmHelp"),
            S.get(
                "aiControllerPermissionStatus",
                permissions.isEditsAllowed()
                    ? S.get("aiControllerPermissionOn")
                    : S.get("aiControllerPermissionRequired")));
    waitForUserState(task, WaitReason.SPECIFICATION, S.get("aiControllerWaitingSpec"));
    conversation.addAssistant(presentation);
    task.listener.specificationProposed(specification, presentation);
  }

  private void waitForPermission(ActiveTask task) {
    waitForUser(
        task,
        WaitReason.EDIT_PERMISSION,
        S.get("aiControllerWaitingPermission"),
        AiStrings.compose(
            S.get("aiControllerPermissionHelp"),
            task.context.confirmedSpecification() == null
                ? S.get("aiControllerSpecUnconfirmed")
                : ""));
  }

  private void waitForUser(ActiveTask task, WaitReason reason, String detail, String text) {
    waitForUserState(task, reason, detail);
    assistantMessage(task, text);
  }

  private void waitForUserState(ActiveTask task, WaitReason reason, String detail) {
    task.waitReason = reason;
    task.guard.pause();
    transition(task, AgentState.WAITING_FOR_USER, detail);
    task.logger.event("HOST_WAITING_FOR_USER", task.state, Map.of("reason", reason.name()));
  }

  private static boolean isSpecificationConfirmation(String text) {
    return AiStrings.isConfirmationReply(text);
  }

  private static boolean isPermissionNotification(String text) {
    return List.of("开启了", "已开启", "已经开启", "已开启编辑权限", "开启了编辑权限", "enabled", "editsenabled")
        .contains(normalizedReply(text));
  }

  private static boolean isQuestion(String text) {
    return AiStrings.isQuestionReply(text);
  }

  private static String normalizedReply(String text) {
    return text.toLowerCase(java.util.Locale.ROOT).replaceAll("[\\s\\p{Punct}，。！？、；：]+", "");
  }

  private enum WaitReason {
    SPECIFICATION,
    EDIT_PERMISSION,
    CLARIFICATION
  }

  private static String safeMessage(Throwable error) {
    return error == null
        ? ""
        : error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
  }

  private static final class ActiveTask {
    final AgentWorkspace workspace;
    final ToolExecutionContext context;
    final AgentTaskLogger logger;
    final List<Map<String, Object>> messages;
    final String originalRequest;
    final ModelConfig config;
    final AgentTaskListener listener;
    final TaskExecutionGuard guard;
    final ExecutorService operations;
    volatile AgentState state = AgentState.PREPARING_WORKSPACE;
    volatile boolean running;
    VerifiedCandidate candidate;
    Map<String, Object> reviewDetails = Map.of();
    int rounds;
    boolean specificationFlowStarted;
    WaitReason waitReason = WaitReason.CLARIFICATION;
    String finalMessage = "";

    ActiveTask(
        AgentWorkspace workspace,
        ToolExecutionContext context,
        AgentTaskLogger logger,
        List<Map<String, Object>> messages,
        String originalRequest,
        ModelConfig config,
        AgentTaskListener listener,
        TaskBudget budget,
        LongSupplier monotonicClock) {
      this.workspace = workspace;
      this.context = context;
      this.logger = logger;
      this.messages = messages;
      this.originalRequest = originalRequest;
      this.config = config;
      this.listener = listener;
      this.guard =
          new TaskExecutionGuard(Duration.ofSeconds(budget.taskTimeoutSeconds()), monotonicClock);
      this.operations =
          Executors.newSingleThreadExecutor(
              runnable -> {
                final var thread = new Thread(runnable, "Logisim-AI-Operation");
                thread.setDaemon(true);
                return thread;
              });
    }
  }
}
