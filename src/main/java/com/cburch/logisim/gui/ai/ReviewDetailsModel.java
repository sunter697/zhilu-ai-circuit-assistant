/*
 * Logisim-evolution - digital logic design tool and simulator
 * Copyright by the Logisim-evolution developers
 *
 * https://github.com/logisim-evolution/
 *
 * This is free software released under GNU GPLv3 license
 */

package com.cburch.logisim.gui.ai;

import com.cburch.logisim.ai.util.SimpleJson;
import com.cburch.logisim.ai.validation.TaskSpec;
import com.cburch.logisim.ai.validation.ValidationStatus;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Detached presentation snapshot; display parsing never changes the acceptance evidence. */
final class ReviewDetailsModel {
  enum ObjectKind {
    COMPONENT,
    WIRE,
    CIRCUIT,
    OTHER
  }

  record ChangeRecord(
      boolean added,
      ObjectKind kind,
      String factoryName,
      List<?> identity,
      String x,
      String y,
      String endX,
      String endY,
      Map<String, Object> attributes,
      String text,
      boolean parsed) {}

  private final Map<String, Object> snapshot;
  private final TaskSpec specification;
  private final List<ChangeRecord> changes;

  ReviewDetailsModel(Map<String, Object> details) {
    snapshot =
        object(
            freeze(SimpleJson.parse(SimpleJson.stringify(details == null ? Map.of() : details))));
    TaskSpec parsed = null;
    try {
      parsed = TaskSpec.fromArguments(object(snapshot.get("specification")));
    } catch (RuntimeException ignored) {
      // An incomplete review remains inspectable; it must not become a valid specification.
    }
    specification = parsed;
    final var records = new ArrayList<ChangeRecord>();
    final var diff = object(snapshot.get("diff"));
    for (final boolean added : List.of(false, true)) {
      for (final var item : list(diff.get(added ? "added" : "removed"))) {
        records.add(parseRecord(added, item));
      }
    }
    changes = List.copyOf(records);
  }

  Map<String, Object> snapshot() {
    return snapshot;
  }

  TaskSpec specification() {
    return specification;
  }

  Map<String, Object> verification() {
    return object(snapshot.get("verification"));
  }

  ValidationStatus status() {
    try {
      return ValidationStatus.valueOf(text(verification().get("status")));
    } catch (IllegalArgumentException ignored) {
      return ValidationStatus.NOT_RUN;
    }
  }

  boolean trustedPass() {
    final var evidence = verification();
    return status() == ValidationStatus.PASS
        && specification != null
        && evidence.get("passedChecks") instanceof List<?>
        && evidence.get("warnings") instanceof List<?>
        && evidence.get("failures") instanceof List<?>
        && !text(evidence.get("summary")).isBlank()
        && list(evidence.get("failures")).isEmpty()
        && specification.digest().equals(text(evidence.get("specificationDigest")))
        && !text(snapshot.get("candidateDigest")).isBlank()
        && text(snapshot.get("candidateDigest")).equals(text(evidence.get("circuitDigest")));
  }

  List<ChangeRecord> changes() {
    return changes;
  }

  long count(boolean added, ObjectKind kind) {
    return changes.stream()
        .filter(record -> record.added() == added && record.kind() == kind)
        .count();
  }

  String rawJson() {
    final var result = new StringBuilder();
    pretty(result, snapshot, 0);
    return result.toString();
  }

  private static ChangeRecord parseRecord(boolean added, Object value) {
    final var raw = value instanceof String string ? string : SimpleJson.stringify(value);
    try {
      if (raw.startsWith("component|")) {
        final var payload = list(freeze(SimpleJson.parse(raw.substring(10))));
        if (payload.size() != 4
            || !(payload.get(0) instanceof List<?> identity)
            || !coordinate(payload.get(1))
            || !coordinate(payload.get(2))
            || !(payload.get(3) instanceof Map<?, ?>)) throw new IllegalArgumentException();
        final String factory;
        if (identity.size() == 3
            && identity.get(0) instanceof String namespace
            && (namespace.equals("library") || namespace.equals("project-subcircuit"))
            && identity.get(1) instanceof List<?> path
            && path.stream().allMatch(item -> item instanceof String)
            && identity.get(2) instanceof String name) {
          factory = name;
        } else if (identity.size() == 2
            && identity.get(0) instanceof String
            && identity.get(1) instanceof String name) {
          factory = name;
        } else throw new IllegalArgumentException();
        return new ChangeRecord(
            added,
            ObjectKind.COMPONENT,
            factory,
            identity,
            text(payload.get(1)),
            text(payload.get(2)),
            "",
            "",
            object(payload.get(3)),
            "",
            true);
      }
      if (raw.startsWith("wire|")) {
        final var parts = raw.split("\\|", -1);
        if (parts.length != 4) throw new IllegalArgumentException();
        final var start = point(parts[1]);
        final var end = point(parts[2]);
        return new ChangeRecord(
            added,
            ObjectKind.WIRE,
            "",
            List.of(),
            start[0],
            start[1],
            end[0],
            end[1],
            Map.of(),
            parts[3],
            true);
      }
      if (raw.startsWith("circuit=")) {
        final var name = SimpleJson.parse(raw.substring(8));
        if (!(name instanceof String)) throw new IllegalArgumentException();
        return new ChangeRecord(
            added, ObjectKind.CIRCUIT, "", List.of(), "", "", "", "", Map.of(), text(name), true);
      }
      if (raw.startsWith("circuitAttributes=")) {
        final var attrs = freeze(SimpleJson.parse(raw.substring(18)));
        if (!(attrs instanceof Map<?, ?>)) throw new IllegalArgumentException();
        return new ChangeRecord(
            added, ObjectKind.CIRCUIT, "", List.of(), "", "", "", "", object(attrs), "", true);
      }
    } catch (RuntimeException ignored) {
      // Preserve unsupported or malformed data verbatim instead of inventing coordinates or types.
    }
    return new ChangeRecord(
        added, ObjectKind.OTHER, "", List.of(), "", "", "", "", Map.of(), raw, false);
  }

  private static String[] point(String text) {
    final var parts = text.split(",", -1);
    if (parts.length != 2) throw new IllegalArgumentException();
    Integer.parseInt(parts[0]);
    Integer.parseInt(parts[1]);
    return parts;
  }

  private static boolean coordinate(Object value) {
    return value instanceof Number number && number.doubleValue() == number.intValue();
  }

  @SuppressWarnings("unchecked")
  static Map<String, Object> object(Object value) {
    return value instanceof Map<?, ?> ? (Map<String, Object>) value : Map.of();
  }

  static List<?> list(Object value) {
    return value instanceof List<?> items ? items : List.of();
  }

  static String text(Object value) {
    return value == null ? "" : String.valueOf(value);
  }

  private static Object freeze(Object value) {
    if (value instanceof Map<?, ?> source) {
      final var map = new LinkedHashMap<String, Object>();
      source.forEach((key, item) -> map.put(String.valueOf(key), freeze(item)));
      return Collections.unmodifiableMap(map);
    }
    if (value instanceof List<?> items) {
      return Collections.unmodifiableList(items.stream().map(ReviewDetailsModel::freeze).toList());
    }
    return value;
  }

  private static void pretty(StringBuilder out, Object value, int depth) {
    if (value instanceof Map<?, ?> map && !map.isEmpty()) {
      out.append("{\n");
      var remaining = map.size();
      for (final var entry : map.entrySet()) {
        out.append("  ".repeat(depth + 1))
            .append(SimpleJson.stringify(entry.getKey()))
            .append(": ");
        pretty(out, entry.getValue(), depth + 1);
        out.append(--remaining == 0 ? "\n" : ",\n");
      }
      out.append("  ".repeat(depth)).append('}');
    } else if (value instanceof List<?> list && !list.isEmpty()) {
      out.append("[\n");
      for (var index = 0; index < list.size(); index++) {
        out.append("  ".repeat(depth + 1));
        pretty(out, list.get(index), depth + 1);
        out.append(index == list.size() - 1 ? "\n" : ",\n");
      }
      out.append("  ".repeat(depth)).append(']');
    } else out.append(SimpleJson.stringify(value));
  }
}
