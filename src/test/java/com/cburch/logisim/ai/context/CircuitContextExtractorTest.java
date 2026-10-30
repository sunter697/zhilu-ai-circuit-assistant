/*
 * Logisim-evolution - digital logic design tool and simulator
 * Copyright by the Logisim-evolution developers
 *
 * https://github.com/logisim-evolution/
 *
 * This is free software released under GNU GPLv3 license
 */

package com.cburch.logisim.ai.context;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.cburch.logisim.circuit.CircuitMutation;
import com.cburch.logisim.data.Location;
import com.cburch.logisim.file.Loader;
import com.cburch.logisim.file.LogisimFile;
import com.cburch.logisim.proj.Project;
import com.cburch.logisim.std.gates.GatesLibrary;
import com.cburch.logisim.tools.AddTool;
import org.junit.jupiter.api.Test;

class CircuitContextExtractorTest {
  @Test
  void taskLocalComponentIdsRemainStableAcrossRefreshes() {
    final var file = LogisimFile.createNew(new Loader(null), null);
    final var project = new Project(file);
    try {
      for (final var circuit : file.getCircuits()) circuit.setProject(project);
      final var circuit = project.getCurrentCircuit();
      final var andTool = (AddTool) new GatesLibrary().getTool("AND Gate");
      final var factory = andTool.getFactory();
      final var component =
          factory.createComponent(Location.create(100, 100, true), factory.createAttributeSet());
      final var mutation = new CircuitMutation(circuit);
      mutation.add(component);
      mutation.execute();

      final var extractor = new CircuitContextExtractor();
      final var first = extractor.extract(circuit);
      final var second = extractor.extract(circuit);
      final var firstId =
          first.components().entrySet().stream()
              .filter(entry -> entry.getValue() == component)
              .findFirst()
              .orElseThrow()
              .getKey();
      final var secondId =
          second.components().entrySet().stream()
              .filter(entry -> entry.getValue() == component)
              .findFirst()
              .orElseThrow()
              .getKey();
      assertEquals(firstId, secondId);
      assertTrue(firstId.startsWith("cmp_"));
    } finally {
      project.getSimulator().shutDown();
      file.stopAutosaveThread(false);
    }
  }
}
