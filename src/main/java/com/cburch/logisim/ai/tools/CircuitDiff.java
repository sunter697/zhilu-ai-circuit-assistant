/*
 * Logisim-evolution - digital logic design tool and simulator
 * Copyright by the Logisim-evolution developers
 *
 * https://github.com/logisim-evolution/
 *
 * This is free software released under GNU GPLv3 license
 */

package com.cburch.logisim.ai.tools;

import com.cburch.logisim.ai.transaction.CircuitDigest;
import com.cburch.logisim.circuit.Circuit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;

public final class CircuitDiff {
  private CircuitDiff() {}

  public static Map<String, Object> compare(Circuit baseline, Circuit candidate) {
    return compareCanonical(CircuitDigest.canonical(baseline), CircuitDigest.canonical(candidate));
  }

  public static Map<String, Object> compareCanonical(
      String baselineCanonical, String candidateCanonical) {
    final var before = new LinkedHashSet<>(baselineCanonical.lines().toList());
    final var after = new LinkedHashSet<>(candidateCanonical.lines().toList());
    final var removed = new ArrayList<String>();
    final var added = new ArrayList<String>();
    for (final var line : before) if (!after.contains(line)) removed.add(line);
    for (final var line : after) if (!before.contains(line)) added.add(line);
    final var result = new LinkedHashMap<String, Object>();
    result.put("baselineDigest", CircuitDigest.sha256Text(baselineCanonical));
    result.put("candidateDigest", CircuitDigest.sha256Text(candidateCanonical));
    result.put("removed", removed);
    result.put("added", added);
    result.put("changed", !removed.isEmpty() || !added.isEmpty());
    return result;
  }
}
