/*
 * Logisim-evolution - digital logic design tool and simulator
 * Copyright by the Logisim-evolution developers
 *
 * https://github.com/logisim-evolution/
 *
 * This is free software released under GNU GPLv3 license
 */

package com.cburch.logisim.ai.transaction;

import static org.junit.jupiter.api.Assertions.assertNotEquals;

import com.cburch.logisim.circuit.Circuit;
import com.cburch.logisim.circuit.CircuitMutation;
import com.cburch.logisim.data.Location;
import com.cburch.logisim.file.Loader;
import com.cburch.logisim.file.LogisimFile;
import com.cburch.logisim.proj.Project;
import com.cburch.logisim.std.gates.GatesLibrary;
import com.cburch.logisim.tools.AddTool;
import org.junit.jupiter.api.Test;

class CircuitDigestTest {
  @Test
  void projectDigestChangesWhenAnotherCircuitChanges() {
    final var file = LogisimFile.createNew(new Loader(null), null);
    final var project = new Project(file);
    try {
      for (final var circuit : file.getCircuits()) circuit.setProject(project);
      final var helper = new Circuit("helper", file, project);
      file.addCircuit(helper);
      final var before = CircuitDigest.projectSha256(file);

      final var andTool = (AddTool) new GatesLibrary().getTool("AND Gate");
      final var factory = andTool.getFactory();
      final var component =
          factory.createComponent(Location.create(100, 100, true), factory.createAttributeSet());
      final var mutation = new CircuitMutation(helper);
      mutation.add(component);
      mutation.execute();

      final var after = CircuitDigest.projectSha256(file);
      assertNotEquals(before, after);
    } finally {
      project.getSimulator().shutDown();
      file.stopAutosaveThread(false);
    }
  }
}
