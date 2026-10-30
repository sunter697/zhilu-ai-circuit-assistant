/*
 * Logisim-evolution - digital logic design tool and simulator
 * Copyright by the Logisim-evolution developers
 *
 * https://github.com/logisim-evolution/
 *
 * This is free software released under GNU GPLv3 license
 */

package com.cburch.logisim.ai.validation;

import com.cburch.logisim.ai.util.SimpleJson;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Immutable functional contract. A proposal becomes authoritative only after user confirmation. */
public record TaskSpec(
    Kind kind, List<SignalSpec> inputs, List<SignalSpec> outputs, Map<String, String> expressions) {
  public enum Kind {
    BOOLEAN_EXPRESSIONS,
    UNSIGNED_MULTIPLICATION
  }

  public record SignalSpec(String name, int width) {
    public SignalSpec {
      if (name == null || !name.matches("[A-Za-z_][A-Za-z0-9_]*")) {
        throw new IllegalArgumentException("Signal names must be nonempty ASCII identifiers");
      }
      if (width < 1 || width > 32) throw new IllegalArgumentException("Signal width must be 1..32");
    }

    public String bitName(int bit) {
      return width == 1 ? name : name + "[" + bit + "]";
    }

    public Map<String, Object> toMap() {
      return Map.of("name", name, "width", width);
    }
  }

  public TaskSpec {
    if (kind == null) throw new IllegalArgumentException("Specification kind is required");
    inputs = List.copyOf(inputs);
    outputs = List.copyOf(outputs);
    expressions = Collections.unmodifiableMap(new LinkedHashMap<>(expressions));
    if (inputs.isEmpty() || outputs.isEmpty()) {
      throw new IllegalArgumentException("At least one input and one output are required");
    }
    final var names = new HashSet<String>();
    for (final var signals : List.of(inputs, outputs)) {
      for (final var signal : signals) {
        if (!names.add(signal.name()))
          throw new IllegalArgumentException("Duplicate signal: " + signal.name());
      }
    }
    if (inputs.stream().mapToInt(SignalSpec::width).sum() > ValidationPlan.MAX_INPUT_BITS) {
      throw new IllegalArgumentException("Exhaustive acceptance is limited to 10 input bits");
    }
    if (outputs.stream().mapToInt(SignalSpec::width).sum() > 256) {
      throw new IllegalArgumentException("At most 256 output bits are supported");
    }
    if (kind == Kind.UNSIGNED_MULTIPLICATION) {
      if (inputs.size() != 2 || inputs.get(0).width() != inputs.get(1).width()) {
        throw new IllegalArgumentException(
            "Unsigned multiplication requires two equal-width operands");
      }
      if (outputs.stream().mapToInt(SignalSpec::width).sum() != 2 * inputs.get(0).width()) {
        throw new IllegalArgumentException(
            "Product outputs must cover the full 2N bits, low slice first");
      }
      if (!expressions.isEmpty())
        throw new IllegalArgumentException("Multiplication does not accept expressions");
    } else if (!expressions.keySet().equals(new HashSet<>(bits(outputs)))) {
      throw new IllegalArgumentException(
          "An expression is required for every output bit, with no extra outputs");
    }
    for (final var expression : expressions.values()) {
      if (expression == null || expression.isBlank() || expression.length() > 4096) {
        throw new IllegalArgumentException("Expressions must contain 1..4096 characters");
      }
    }
  }

  public static TaskSpec fromArguments(Map<String, Object> arguments) {
    if (arguments.containsKey("rows"))
      throw new IllegalArgumentException("Model-supplied rows cannot define acceptance");
    final var kind = Kind.valueOf(String.valueOf(arguments.get("kind")).toUpperCase(Locale.ROOT));
    final var expressions = new LinkedHashMap<String, String>();
    if (arguments.get("expressions") instanceof Map<?, ?> values) {
      for (final var entry : values.entrySet()) {
        if (!(entry.getKey() instanceof String key)
            || !(entry.getValue() instanceof String value)) {
          throw new IllegalArgumentException("Expressions must map output bit names to strings");
        }
        expressions.put(key, value);
      }
    } else if (arguments.containsKey("expressions")) {
      throw new IllegalArgumentException("Expressions must be an object");
    }
    final var result =
        new TaskSpec(
            kind, signals(arguments.get("inputs")), signals(arguments.get("outputs")), expressions);
    new ValidationPlan(result); // Reject an invalid expression before presenting a confirmation.
    return result;
  }

  private static List<SignalSpec> signals(Object value) {
    if (!(value instanceof List<?> list))
      throw new IllegalArgumentException("Signals must be an array");
    final var result = new ArrayList<SignalSpec>();
    for (final var item : list) {
      if (!(item instanceof Map<?, ?> signal)
          || !(signal.get("name") instanceof String name)
          || !(signal.get("width") instanceof Number width)
          || width.doubleValue() != width.intValue()) {
        throw new IllegalArgumentException("Each signal requires name and integral width");
      }
      result.add(new SignalSpec(name, width.intValue()));
    }
    return result;
  }

  static List<String> bits(List<SignalSpec> signals) {
    final var result = new ArrayList<String>();
    for (final var signal : signals) {
      for (var bit = signal.width() - 1; bit >= 0; bit--) result.add(signal.bitName(bit));
    }
    return List.copyOf(result);
  }

  public Map<String, Object> toMap() {
    final var result = new LinkedHashMap<String, Object>();
    result.put("kind", kind.name());
    result.put("inputs", inputs.stream().map(SignalSpec::toMap).toList());
    result.put("outputs", outputs.stream().map(SignalSpec::toMap).toList());
    if (kind == Kind.UNSIGNED_MULTIPLICATION)
      result.put("outputSliceOrder", "least-significant-first");
    result.put("expressions", expressions);
    return result;
  }

  public String describe() {
    return SpecPresentation.describe(this);
  }

  /** Localized display lines derived with the same expression parser as acceptance. */
  public List<String> functionDescription() {
    return SpecPresentation.functionDescription(this);
  }

  /** Number of binary input combinations covered by exhaustive acceptance. */
  public int exhaustiveRows() {
    return 1 << inputs.stream().mapToInt(SignalSpec::width).sum();
  }

  public String digest() {
    try {
      // Normalize expression order; input/output order has explicit semantic meaning.
      final var canonical = new LinkedHashMap<>(toMap());
      canonical.put("expressions", new java.util.TreeMap<>(expressions));
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256")
                  .digest(SimpleJson.stringify(canonical).getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
