/*
 * Logisim-evolution - digital logic design tool and simulator
 * Copyright by the Logisim-evolution developers
 *
 * https://github.com/logisim-evolution/
 *
 * This is free software released under GNU GPLv3 license
 */

package com.cburch.logisim.ai.logging;

import static com.cburch.logisim.ai.i18n.AiStrings.S;

import com.cburch.logisim.ai.agent.AgentState;
import com.cburch.logisim.ai.i18n.AiStrings;
import com.cburch.logisim.ai.util.SimpleJson;
import com.cburch.logisim.ai.validation.VerificationEvidence;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/** Append-only task event log plus a concise human-readable report. */
public final class AgentTaskLogger {
  private final String taskId;
  private final Path directory;
  private final Path eventFile;
  private final Path reportFile;

  public AgentTaskLogger(String taskId, Path directory) throws IOException {
    this.taskId = taskId;
    this.directory = directory;
    Files.createDirectories(directory);
    this.eventFile = directory.resolve("events.jsonl");
    this.reportFile = directory.resolve("report.md");
  }

  public Path eventFile() {
    return eventFile;
  }

  public Path reportFile() {
    return reportFile;
  }

  public synchronized void event(String type, AgentState state, Map<String, ?> data) {
    final var row = new LinkedHashMap<String, Object>();
    row.put("taskId", taskId);
    row.put("eventId", UUID.randomUUID().toString());
    row.put("timestamp", Instant.now().toString());
    row.put("type", type);
    row.put("state", state == null ? null : state.name());
    row.put("data", redact(data == null ? Map.of() : data));
    try {
      Files.writeString(
          eventFile,
          SimpleJson.stringify(row) + System.lineSeparator(),
          StandardCharsets.UTF_8,
          StandardOpenOption.CREATE,
          StandardOpenOption.APPEND);
    } catch (IOException e) {
      System.err.println("AI task log write failed: " + e.getMessage());
    }
  }

  public synchronized void writeReport(
      String originalRequest,
      String finalMessage,
      String baselineDigest,
      String workspaceDigest,
      VerificationEvidence evidence,
      Map<String, Object> diff,
      int repairAttempts,
      String disposition) {
    writeReport(
        originalRequest,
        finalMessage,
        baselineDigest,
        workspaceDigest,
        evidence,
        diff,
        repairAttempts,
        disposition,
        Map.of());
  }

  public synchronized void writeReport(
      String originalRequest,
      String finalMessage,
      String baselineDigest,
      String workspaceDigest,
      VerificationEvidence evidence,
      Map<String, Object> diff,
      int repairAttempts,
      String disposition,
      Map<String, Object> contract) {
    final var out = new StringBuilder();
    out.append("# ").append(S.get("aiReportTitle")).append("\n\n");
    out.append("- ").append(S.get("aiReportTaskId")).append(": `").append(taskId).append("`\n");
    out.append("- ")
        .append(S.get("aiReportBaselineDigest"))
        .append(": `")
        .append(baselineDigest)
        .append("`\n");
    out.append("- ")
        .append(S.get("aiReportWorkspaceDigest"))
        .append(": `")
        .append(workspaceDigest)
        .append("`\n");
    out.append("- ")
        .append(S.get("aiReportRepairAttempts"))
        .append(": ")
        .append(repairAttempts)
        .append("\n");
    out.append("- ")
        .append(S.get("aiReportDisposition"))
        .append(": ")
        .append(AiStrings.disposition(disposition))
        .append(" (`")
        .append(disposition)
        .append("`)\n\n");
    out.append("## ")
        .append(S.get("aiReportBinding"))
        .append("\n\n```json\n")
        .append(SimpleJson.stringify(redact(contract)))
        .append("\n```\n\n");
    out.append("## ")
        .append(S.get("aiReportUserRequest"))
        .append("\n\n")
        .append(originalRequest == null ? "" : originalRequest)
        .append("\n\n");
    out.append("## ")
        .append(S.get("aiReportAgentResult"))
        .append("\n\n")
        .append(AiStrings.displayMessage(finalMessage))
        .append("\n\n");
    out.append("## ").append(S.get("aiRoleDiff")).append("\n\n");
    if (diff == null || diff.isEmpty()) {
      out.append(S.get("aiReportNoDiff")).append("\n\n");
    } else {
      out.append("```json\n").append(SimpleJson.stringify(diff)).append("\n```\n\n");
    }
    out.append("## ").append(S.get("aiRoleVerification")).append("\n\n");
    if (evidence == null) {
      out.append(S.get("aiValidationNotRun")).append('\n');
    } else {
      out.append(S.get("aiReportStatus"))
          .append(": **")
          .append(AiStrings.validationStatus(evidence.status()))
          .append("** (`")
          .append(evidence.status())
          .append("`)\n\n");
      out.append(AiStrings.displayMessage(evidence.summary())).append("\n\n");
      for (final var check : evidence.passedChecks())
        out.append("- ✓ ").append(AiStrings.displayMessage(check)).append('\n');
      for (final var warning : evidence.warnings())
        out.append("- ⚠ ").append(AiStrings.displayMessage(warning)).append('\n');
      for (final var failure : evidence.failures())
        out.append("- ✗ `").append(failure).append("`\n");
    }
    out.append("\n## ").append(S.get("aiRoleArtifacts")).append("\n\n");
    out.append("- `baseline.circ`\n- `workspace.circ`\n- `metadata.json`\n- `events.jsonl`\n");
    try {
      Files.writeString(reportFile, out.toString(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      System.err.println("AI report write failed: " + e.getMessage());
    }
  }

  private static Object redact(Object value) {
    if (value instanceof Map<?, ?> map) {
      final var result = new LinkedHashMap<String, Object>();
      for (final var entry : map.entrySet()) {
        final var key = String.valueOf(entry.getKey());
        final var normalized = key.toLowerCase(Locale.ROOT);
        if (normalized.contains("apikey")
            || normalized.contains("api_key")
            || normalized.contains("authorization")
            || normalized.equals("token")) {
          result.put(key, "<redacted>");
        } else {
          result.put(key, redact(entry.getValue()));
        }
      }
      return result;
    }
    if (value instanceof Iterable<?> iterable) {
      final var list = new ArrayList<>();
      for (final var item : iterable) list.add(redact(item));
      return list;
    }
    return value;
  }
}
