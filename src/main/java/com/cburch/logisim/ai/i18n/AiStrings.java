/*
 * Logisim-evolution - digital logic design tool and simulator
 * Copyright by the Logisim-evolution developers
 *
 * https://github.com/logisim-evolution/
 *
 * This is free software released under GNU GPLv3 license
 */

package com.cburch.logisim.ai.i18n;

import com.cburch.logisim.ai.agent.AgentState;
import com.cburch.logisim.ai.validation.ValidationStatus;
import com.cburch.logisim.util.LocaleManager;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.ResourceBundle;
import java.util.regex.Pattern;

/** Uses Logisim's active locale; machine payloads and diagnostic source text remain unchanged. */
public final class AiStrings {
  private static final String BUNDLE = "resources/logisim/strings/ai/ai";
  private static final Map<String, RenderedMessage> rendered = new LinkedHashMap<>();
  public static final LocaleManager S = new AiLocaleManager();

  private static final Map<String, String> FIXED = fixedMessages();
  private static final List<MessagePattern> PATTERNS =
      List.of(
          pattern(
              "All (\\d+) exhaustive rows matched the confirmed specification\\.",
              "aiVerificationPass"),
          pattern(
              "(\\d+) output mismatches \\(unknown/error values also fail\\)\\.",
              "aiVerificationMismatch"),
          pattern("All (\\d+) exhaustive truth-table rows matched\\.", "aiDiagnosticPass"),
          pattern(
              "(\\d+) output mismatch\\(es\\) were found in exhaustive truth-table verification\\.",
              "aiVerificationMismatch"),
          pattern("Truth table (\\d+)/(\\d+) rows matched", "aiDiagnosticRowsMatched"),
          pattern(
              "Circuit interface differs from the confirmed specification\\. Inputs: (.*); outputs:"
                  + " (.*)",
              "aiInterfaceMismatch"),
          pattern(
              "Unsupported component for combinational acceptance: (.*)", "aiUnsupportedComponent"),
          pattern(
              "Acceptance requires 1\\.\\.(\\d+) input bits and an output; found (\\d+) input bits"
                  + " and (\\d+) outputs\\.",
              "aiInputLimit"),
          pattern("Model API returned HTTP (\\d+)", "aiModelHttpError"),
          pattern(
              "AI workspaces do not yet support external or custom libraries: (.*)",
              "aiWorkspaceNoLibrary"),
          pattern(
              "AI workspaces do not yet support custom subcircuit appearances: (.*)",
              "aiWorkspaceNoAppearance"),
          pattern(
              "AI workspaces do not yet support FPGA board mappings: (.*)",
              "aiWorkspaceNoBoardMap"),
          pattern("Duplicate library identity: (.*)", "aiDuplicateLibrary"),
          pattern("Duplicate circuit identity: (.*)", "aiDuplicateCircuit"),
          pattern("Apply preflight failed: (.*)", "aiApplyPreflightFailed"),
          pattern(
              "Formal apply failed and the original circuit was restored: (.*)", "aiApplyRestored"),
          pattern(
              "Formal apply failed and automatic restoration also failed\\. Save a copy before"
                  + " continuing\\. Apply error: (.*); restore error: (.*)",
              "aiApplyRecoveryFailed"),
          pattern(
              "Preflight specification verification failed: (.*)",
              "aiPreflightVerificationFailed"));

  private AiStrings() {}

  public static String state(AgentState state) {
    return S.get("aiState_" + state.name());
  }

  public static String validationStatus(ValidationStatus status) {
    return S.get("aiValidation_" + status.name());
  }

  /**
   * Localizes known host messages without changing the original evidence or arbitrary model text.
   */
  public static String displayMessage(String message) {
    if (message == null || message.isBlank()) return message == null ? "" : message;
    final RenderedMessage previous;
    synchronized (rendered) {
      previous = rendered.get(message);
    }
    if (previous != null) {
      if (previous.parts() != null) {
        return compose(
            previous.parts().stream().map(AiStrings::displayMessage).toArray(String[]::new));
      }
      final var args = previous.arguments().clone();
      if (containsReason(previous.key())) {
        for (var i = 0; i < args.length; i++) {
          if (args[i] instanceof String text) args[i] = displayMessage(text);
        }
      }
      return S.get(previous.key(), args);
    }
    final var fixed = FIXED.get(message);
    if (fixed != null) return S.get(fixed);
    for (var entry : PATTERNS) {
      final var match = entry.pattern().matcher(message);
      if (match.matches()) {
        final var arguments = new Object[match.groupCount()];
        for (var i = 0; i < arguments.length; i++) {
          final var text = match.group(i + 1);
          arguments[i] = containsReason(entry.key()) ? displayMessage(text) : text;
        }
        return S.get(entry.key(), arguments);
      }
    }
    return message;
  }

  /** Retains each host display fragment so a multi-line confirmation can change language too. */
  public static String compose(String... parts) {
    final var fragments = Arrays.stream(parts).filter(p -> p != null && !p.isEmpty()).toList();
    if (fragments.size() == 1) return fragments.getFirst();
    final var text = String.join("\n", fragments);
    remember(text, new RenderedMessage(null, null, fragments));
    return text;
  }

  /**
   * Only entire explicit replies confirm a concrete pending contract; history grants no authority.
   */
  public static boolean isConfirmationReply(String text) {
    if (text == null || isQuestionReply(text)) return false;
    final var normalized = normalizeReply(text);
    for (var locale : List.of(Locale.ENGLISH, Locale.CHINESE, LocaleManager.getLocale())) {
      final var replies =
          ResourceBundle.getBundle(
                  BUNDLE,
                  locale,
                  ResourceBundle.Control.getNoFallbackControl(
                      ResourceBundle.Control.FORMAT_PROPERTIES))
              .getString("aiConfirmReplies");
      if (Arrays.stream(replies.split(";"))
          .map(AiStrings::normalizeReply)
          .anyMatch(normalized::equalsIgnoreCase)) return true;
    }
    return false;
  }

  public static boolean isQuestionReply(String text) {
    if (text == null) return false;
    final var normalized = normalizeReply(text);
    return text.contains("?")
        || text.contains("？")
        || text.contains("¿")
        || text.contains("؟")
        || text.contains(";")
        || (LocaleManager.getLocale().getLanguage().equals("el") && text.strip().endsWith(";"))
        || normalized.endsWith("吗")
        || normalized.endsWith("呢");
  }

  private static String normalizeReply(String text) {
    return text.toLowerCase(Locale.ROOT).replaceAll("[\\s\\p{P}]+", "");
  }

  private static boolean containsReason(String key) {
    return List.of(
            "aiApplyPreflightFailed",
            "aiApplyRestored",
            "aiApplyRecoveryFailed",
            "aiPreflightVerificationFailed",
            "aiControllerExecutionFailed",
            "aiControllerCleanupFailed",
            "aiControllerPermissionStatus")
        .contains(key);
  }

  public static String disposition(String disposition) {
    return switch (disposition) {
      case "APPLIED" -> S.get("aiApplySuccess");
      case "READ_ONLY_COMPLETED" -> state(AgentState.COMPLETED);
      case "DISCARDED_WORKSPACE_RETAINED" -> state(AgentState.DISCARDED);
      case "CANCELLED_WORKSPACE_RETAINED" -> state(AgentState.CANCELLED);
      case "AWAITING_REVIEW" -> state(AgentState.REVIEW);
      default -> {
        try {
          yield state(AgentState.valueOf(disposition));
        } catch (IllegalArgumentException e) {
          yield disposition;
        }
      }
    };
  }

  private static MessagePattern pattern(String expression, String key) {
    return new MessagePattern(Pattern.compile(expression, Pattern.DOTALL), key);
  }

  private static Map<String, String> fixedMessages() {
    final var map = new LinkedHashMap<String, String>();
    map.put("Task time budget exhausted", "aiTaskBudgetExhausted");
    map.put("Operation execution budget exhausted", "aiOperationBudgetExhausted");
    map.put("Apply preflight budget exhausted", "aiOperationBudgetExhausted");
    map.put("Task cancelled", "aiTaskCancelled");
    map.put("A valid absolute API endpoint is required", "aiEndpointInvalid");
    map.put("Model name is required", "aiModelRequired");
    map.put("Model timeout must be positive", "aiModelTimeoutInvalid");
    map.put("Model API returned no choices", "aiModelEmptyResponse");
    map.put("Validation has not run", "aiValidationNotRun");
    map.put("Logisim simulation completed", "aiSimulationCompleted");
    map.put("Only the first 32 mismatches are shown", "aiMismatchLimit");
    map.put("No user-confirmed validation plan is available", "aiVerificationNoPlan");
    map.put("Circuit changed during verification; evidence was discarded", "aiEvidenceStale");
    map.put("No Logisim project is open", "aiNoProject");
    map.put("AI workspaces do not yet support HDL/VHDL content", "aiWorkspaceNoHdl");
    map.put(
        "Workspace reload changed the project; AI editing was refused", "aiWorkspaceReloadChanged");
    map.put("Could not prepare isolated workspace", "aiWorkspacePrepareFailed");
    map.put("The reviewed workspace was applied as one undoable Logisim action.", "aiApplySuccess");
    map.put("Apply requires a frozen, specification-verified candidate.", "aiApplyUnavailable");
    map.put("A verified candidate and task workspace are required.", "aiApplyUnavailable");
    map.put("The result is not fully verified; apply is disabled by default", "aiApplyUnavailable");
    map.put("No reviewed AI change is ready to apply", "aiApplyUnavailable");
    map.put("Apply preflight must run outside Swing EDT.", "aiApplyWrongThread");
    map.put("Edit permission has been revoked for this session.", "aiApplyPermissionRevoked");
    map.put("The candidate does not belong to this active workspace.", "aiApplyConflict");
    map.put("The formal project or active circuit changed.", "aiApplyConflict");
    map.put("The workspace changed after Review; its evidence is stale.", "aiApplyConflict");
    map.put("The formal project changed; user edits will not be overwritten.", "aiApplyConflict");
    return Map.copyOf(map);
  }

  private record MessagePattern(Pattern pattern, String key) {}

  private record RenderedMessage(String key, Object[] arguments, List<String> parts) {
    RenderedMessage(String key, Object[] arguments) {
      this(key, arguments, null);
    }
  }

  private static void remember(String text, RenderedMessage template) {
    synchronized (rendered) {
      rendered.put(text, template);
      if (rendered.size() > 1024) rendered.remove(rendered.keySet().iterator().next());
    }
  }

  /** Remembers display templates so an already visible host detail can follow a locale change. */
  private static final class AiLocaleManager extends LocaleManager {
    AiLocaleManager() {
      super("resources/logisim", "ai");
    }

    @Override
    public String get(String key) {
      return remember(super.get(key), key, new Object[0]);
    }

    @Override
    public String get(String key, Object... args) {
      return remember(String.format(super.get(key), args), key, args.clone());
    }

    private static String remember(String value, String key, Object[] args) {
      AiStrings.remember(value, new RenderedMessage(key, args));
      return value;
    }
  }
}
