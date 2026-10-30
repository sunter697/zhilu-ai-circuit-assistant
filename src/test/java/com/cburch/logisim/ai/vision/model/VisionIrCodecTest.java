/*
 * Logisim-evolution - digital logic design tool and simulator
 * Copyright by the Logisim-evolution developers
 *
 * https://github.com/logisim-evolution/
 *
 * This is free software released under GNU GPLv3 license
 */

package com.cburch.logisim.ai.vision.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import org.junit.jupiter.api.Test;

class VisionIrCodecTest {
  @Test
  void fixtureRoundTripsAndValidates() throws IOException {
    final var ir = fixture("and.json");
    assertTrue(VisionIrValidator.validate(ir).isEmpty());
    assertEquals(ir, VisionIrCodec.fromJson(VisionIrCodec.toJson(ir)));
  }

  @Test
  void rejectsUnknownProtocolVersion() throws IOException {
    final var ir = fixture("and.json");
    final var incompatible =
        new VisionIr(
            "vision-ir/1.0",
            ir.source(),
            ir.profile(),
            ir.components(),
            ir.nets(),
            ir.unresolved(),
            ir.semanticHash());
    final var error =
        assertThrows(
            IllegalArgumentException.class,
            () -> VisionIrValidator.validateOrThrow(incompatible));
    assertTrue(error.getMessage().contains("Unsupported schemaVersion"));
  }

  @Test
  void rejectsUnknownAndMultiplyAssignedPorts() throws IOException {
    final var ir = fixture("and.json");
    final var nets = new ArrayList<>(ir.nets());
    final var first = nets.getFirst();
    nets.set(
        0,
        new VisionIr.DetectedNet(
            first.id(),
            java.util.List.of(
                new VisionIr.NetEndpoint("pin_a", "missing"),
                new VisionIr.NetEndpoint("gate_and", "in0")),
            first.segments(),
            first.junctions(),
            first.confidence()));
    final var last = nets.getLast();
    nets.set(
        nets.size() - 1,
        new VisionIr.DetectedNet(
            last.id(),
            java.util.List.of(
                new VisionIr.NetEndpoint("gate_and", "in0"),
                new VisionIr.NetEndpoint("pin_y", "in")),
            last.segments(),
            last.junctions(),
            last.confidence()));
    final var invalid =
        new VisionIr(
            ir.schemaVersion(),
            ir.source(),
            ir.profile(),
            ir.components(),
            nets,
            ir.unresolved(),
            ir.semanticHash());
    final var errors = VisionIrValidator.validate(invalid);
    assertTrue(errors.stream().anyMatch(message -> message.contains("unknown port")));
    assertTrue(errors.stream().anyMatch(message -> message.contains("already assigned")));
  }

  private static VisionIr fixture(String name) throws IOException {
    try (var stream =
        VisionIrCodecTest.class.getResourceAsStream(
            "/com/cburch/logisim/ai/vision/" + name)) {
      if (stream == null) throw new IOException("Missing test fixture: " + name);
      return VisionIrCodec.fromJson(new String(stream.readAllBytes(), StandardCharsets.UTF_8));
    }
  }
}
