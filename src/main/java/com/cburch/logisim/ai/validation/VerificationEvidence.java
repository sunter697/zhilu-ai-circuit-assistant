/*
 * Logisim-evolution - digital logic design tool and simulator
 * Copyright by the Logisim-evolution developers
 *
 * https://github.com/logisim-evolution/
 *
 * This is free software released under GNU GPLv3 license
 */

package com.cburch.logisim.ai.validation;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record VerificationEvidence(
    ValidationStatus status,
    String summary,
    List<String> passedChecks,
    List<String> warnings,
    List<Map<String, Object>> failures,
    String specificationDigest,
    String circuitDigest) {

  public VerificationEvidence {
    passedChecks = List.copyOf(passedChecks);
    warnings = List.copyOf(warnings);
    failures = failures.stream().map(Map::copyOf).toList();
    specificationDigest = specificationDigest == null ? "" : specificationDigest;
    circuitDigest = circuitDigest == null ? "" : circuitDigest;
  }

  public VerificationEvidence(
      ValidationStatus status,
      String summary,
      List<String> passedChecks,
      List<String> warnings,
      List<Map<String, Object>> failures) {
    this(status, summary, passedChecks, warnings, failures, "", "");
  }

  public boolean isTrustedPass() {
    return status == ValidationStatus.PASS
        && !specificationDigest.isEmpty()
        && !circuitDigest.isEmpty();
  }

  public static VerificationEvidence notRun() {
    return new VerificationEvidence(
        ValidationStatus.NOT_RUN, "Validation has not run", List.of(), List.of(), List.of());
  }

  public Map<String, Object> toMap() {
    final var map = new LinkedHashMap<String, Object>();
    map.put("status", status.name());
    map.put("summary", summary);
    map.put("passedChecks", passedChecks);
    map.put("warnings", warnings);
    map.put("failures", failures);
    map.put("specificationDigest", specificationDigest);
    map.put("circuitDigest", circuitDigest);
    return map;
  }
}
