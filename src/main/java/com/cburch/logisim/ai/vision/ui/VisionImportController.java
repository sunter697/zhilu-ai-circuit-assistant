/*
 * Logisim-evolution - digital logic design tool and simulator
 * Copyright by the Logisim-evolution developers
 *
 * https://github.com/logisim-evolution/
 *
 * This is free software released under GNU GPLv3 license
 */

package com.cburch.logisim.ai.vision.ui;

import com.cburch.logisim.ai.vision.client.VisionService;
import com.cburch.logisim.ai.vision.client.VisionServices;
import com.cburch.logisim.ai.vision.importer.CircuitImportBuilder;
import com.cburch.logisim.ai.vision.importer.CircuitImportPlan;
import com.cburch.logisim.ai.vision.importer.CircuitImportPlanner;
import com.cburch.logisim.ai.vision.model.VisionIr;
import com.cburch.logisim.ai.vision.model.VisionIrValidator;
import com.cburch.logisim.file.LogisimFile;
import com.cburch.logisim.proj.Project;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** Prepares a recognition result without mutation, then applies it only after UI confirmation. */
public final class VisionImportController implements AutoCloseable {
  /** A null field means that the recognized value should be left unchanged. */
  public record ComponentCorrection(VisionIr.ComponentKind kind, String label) {}

  public record PreparedImport(
      Path sourceImage,
      VisionIr recognition,
      CircuitImportPlan plan,
      int componentCount,
      int netCount,
      int warningCount,
      int lowConfidenceCount,
      int correctionCount,
      String detectorMode,
      LogisimFile targetFile) {}

  private static final double LOW_CONFIDENCE_THRESHOLD = 0.75;
  private static final Set<VisionIr.ComponentKind> COMPATIBLE_GATE_KINDS =
      Set.of(
          VisionIr.ComponentKind.AND,
          VisionIr.ComponentKind.OR,
          VisionIr.ComponentKind.NAND,
          VisionIr.ComponentKind.NOR,
          VisionIr.ComponentKind.XOR,
          VisionIr.ComponentKind.XNOR);

  private final VisionService service;
  private final CircuitImportPlanner planner;
  private final CircuitImportBuilder builder;

  public VisionImportController() {
    this(VisionServices.createDefault(), new CircuitImportPlanner(), new CircuitImportBuilder());
  }

  VisionImportController(
      VisionService service, CircuitImportPlanner planner, CircuitImportBuilder builder) {
    this.service = service;
    this.planner = planner;
    this.builder = builder;
  }

  public PreparedImport prepare(Project project, Path image) {
    if (project == null || project.getLogisimFile() == null) {
      throw new IllegalArgumentException("A Logisim project is required");
    }
    final var health = service.health();
    if (!"ok".equals(health.status())) {
      throw new IllegalStateException("The vision service is not ready");
    }
    if (!VisionIr.VERSION.equals(health.protocolVersion())) {
      throw new IllegalStateException(
          "Vision protocol mismatch: expected "
              + VisionIr.VERSION
              + ", found "
              + health.protocolVersion());
    }

    final var ir = service.recognize(image);
    VisionIrValidator.validateOrThrow(ir);
    rejectUnresolvedErrors(ir);
    final var file = project.getLogisimFile();
    final var plan = planner.plan(ir, file, nextCircuitName(file));
    return new PreparedImport(
        image.toAbsolutePath().normalize(),
        ir,
        plan,
        ir.components().size(),
        ir.nets().size(),
        warningCount(ir),
        lowConfidenceCount(ir),
        0,
        health.detectorMode(),
        file);
  }

  /** Applies safe semantic corrections and creates a fresh, fully validated import plan. */
  public PreparedImport revise(
      Project project, PreparedImport prepared, Map<String, ComponentCorrection> corrections) {
    if (project == null || prepared == null || corrections == null) {
      throw new IllegalArgumentException("A project, prepared import, and corrections are required");
    }
    if (project.getLogisimFile() != prepared.targetFile()) {
      throw new IllegalStateException("The target project changed after image recognition");
    }

    final var knownIds = new HashSet<String>();
    prepared.recognition().components().forEach(component -> knownIds.add(component.id()));
    for (final var id : corrections.keySet()) {
      if (!knownIds.contains(id)) {
        throw new IllegalArgumentException("Unknown recognized component: " + id);
      }
      if (corrections.get(id) == null) {
        throw new IllegalArgumentException("Correction must not be null: " + id);
      }
    }

    final var revisedComponents = new java.util.ArrayList<VisionIr.DetectedComponent>();
    var correctionCount = 0;
    for (final var component : prepared.recognition().components()) {
      final var correction = corrections.get(component.id());
      if (correction == null) {
        revisedComponents.add(component);
        continue;
      }

      final var revisedKind = correction.kind() == null ? component.kind() : correction.kind();
      validateKindCorrection(component, revisedKind);
      final var attributes = new LinkedHashMap<>(component.attributes());
      if (revisedKind != component.kind()) correctionCount++;

      if (correction.label() != null) {
        if (component.kind() != VisionIr.ComponentKind.INPUT_PIN
            && component.kind() != VisionIr.ComponentKind.OUTPUT_PIN) {
          throw new IllegalArgumentException(
              "Only input and output pin labels may be corrected: " + component.id());
        }
        final var revisedLabel = validateLabel(correction.label(), component.id());
        final var originalLabel = String.valueOf(attributes.getOrDefault("label", ""));
        if (!revisedLabel.equals(originalLabel)) {
          attributes.put("label", revisedLabel);
          correctionCount++;
        }
      }

      revisedComponents.add(
          new VisionIr.DetectedComponent(
              component.id(),
              revisedKind,
              component.bodyBox(),
              component.anchor(),
              component.facing(),
              attributes,
              component.ports(),
              component.confidence()));
    }
    if (correctionCount == 0) return prepared;

    final var original = prepared.recognition();
    final var revised =
        new VisionIr(
            original.schemaVersion(),
            original.source(),
            original.profile(),
            revisedComponents,
            original.nets(),
            original.unresolved(),
            "");
    VisionIrValidator.validateOrThrow(revised);
    rejectUnresolvedErrors(revised);
    final var plan =
        planner.planRevised(
            revised,
            prepared.targetFile(),
            prepared.plan().targetCircuitName(),
            prepared.plan());
    return new PreparedImport(
        prepared.sourceImage(),
        revised,
        plan,
        revised.components().size(),
        revised.nets().size(),
        warningCount(revised),
        lowConfidenceCount(revised),
        correctionCount,
        prepared.detectorMode(),
        prepared.targetFile());
  }

  public CircuitImportBuilder.Result apply(Project project, PreparedImport prepared) {
    if (project == null || prepared == null) {
      throw new IllegalArgumentException("A project and prepared import are required");
    }
    if (project.getLogisimFile() != prepared.targetFile()) {
      throw new IllegalStateException("The target project changed after image recognition");
    }
    return builder.buildAndAdd(project, prepared.plan());
  }

  @Override
  public void close() {
    if (!(service instanceof AutoCloseable closeable)) return;
    try {
      closeable.close();
    } catch (RuntimeException exception) {
      throw exception;
    } catch (Exception exception) {
      throw new IllegalStateException("The vision service could not be closed", exception);
    }
  }

  private static void validateKindCorrection(
      VisionIr.DetectedComponent component, VisionIr.ComponentKind revisedKind) {
    if (revisedKind == component.kind()) return;
    if (!COMPATIBLE_GATE_KINDS.contains(component.kind())
        || !COMPATIBLE_GATE_KINDS.contains(revisedKind)
        || component.kind().expectedInputCount(component.attributes())
            != revisedKind.expectedInputCount(component.attributes())) {
      throw new IllegalArgumentException(
          "Unsafe component type correction: "
              + component.id()
              + " "
              + component.kind()
              + " -> "
              + revisedKind);
    }
  }

  private static String validateLabel(String label, String componentId) {
    final var value = label.strip();
    if (value.isEmpty() || value.length() > 64 || value.chars().anyMatch(Character::isISOControl)) {
      throw new IllegalArgumentException(
          "Pin label must contain 1 to 64 printable characters: " + componentId);
    }
    return value;
  }

  private static void rejectUnresolvedErrors(VisionIr ir) {
    final var errors =
        ir.unresolved().stream()
            .filter(issue -> issue.severity() == VisionIr.IssueSeverity.ERROR)
            .toList();
    if (errors.isEmpty()) return;
    final var summary =
        errors.stream()
            .limit(8)
            .map(issue -> issue.code() + ": " + issue.message())
            .collect(Collectors.joining("\n"));
    throw new IllegalArgumentException("Recognition contains unresolved errors:\n" + summary);
  }

  private static int warningCount(VisionIr ir) {
    return (int)
        ir.unresolved().stream()
            .filter(issue -> issue.severity() == VisionIr.IssueSeverity.WARNING)
            .count();
  }

  private static int lowConfidenceCount(VisionIr ir) {
    return (int)
            ir.components().stream()
                .filter(component -> component.confidence() < LOW_CONFIDENCE_THRESHOLD)
                .count()
        + (int)
            ir.nets().stream()
                .filter(net -> net.confidence() < LOW_CONFIDENCE_THRESHOLD)
                .count();
  }

  private static String nextCircuitName(LogisimFile file) {
    final var base = "Vision_Imported";
    if (file.getCircuit(base) == null) return base;
    for (var index = 2; index < 10_000; index++) {
      final var candidate = base + "_" + index;
      if (file.getCircuit(candidate) == null) return candidate;
    }
    throw new IllegalStateException("No available imported-circuit name");
  }
}
