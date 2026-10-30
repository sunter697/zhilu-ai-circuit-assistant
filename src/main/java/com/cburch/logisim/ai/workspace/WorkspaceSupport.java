/*
 * Logisim-evolution - digital logic design tool and simulator
 * Copyright by the Logisim-evolution developers
 *
 * https://github.com/logisim-evolution/
 *
 * This is free software released under GNU GPLv3 license
 */

package com.cburch.logisim.ai.workspace;

import com.cburch.logisim.ai.transaction.FactoryIdentity;
import com.cburch.logisim.file.LogisimFile;
import java.util.HashSet;

/** The isolation/apply boundary supported by v1.1, checked before serializing any project. */
public final class WorkspaceSupport {
  private WorkspaceSupport() {}

  public static void requireSupported(LogisimFile file) {
    if (file == null) throw new IllegalArgumentException("No Logisim project is open");
    if (!file.getVhdlContents().isEmpty()) {
      throw new IllegalArgumentException("AI workspaces do not yet support HDL/VHDL content");
    }
    final var names = new HashSet<String>();
    for (final var library : file.getLibraries()) {
      if (!file.getLoader().getBuiltin().getLibraries().contains(library)) {
        throw new IllegalArgumentException(
            "AI workspaces do not yet support external or custom libraries: " + library.getName());
      }
      if (!names.add(library.getName())) {
        throw new IllegalArgumentException("Duplicate library identity: " + library.getName());
      }
    }
    names.clear();
    for (final var circuit : file.getCircuits()) {
      if (!names.add(circuit.getName())) {
        throw new IllegalArgumentException("Duplicate circuit identity: " + circuit.getName());
      }
      if (!circuit.getAppearance().isDefaultAppearance()) {
        throw new IllegalArgumentException(
            "AI workspaces do not yet support custom subcircuit appearances: " + circuit.getName());
      }
      if (!circuit.getBoardMapNamestoSave().isEmpty()) {
        throw new IllegalArgumentException(
            "AI workspaces do not yet support FPGA board mappings: " + circuit.getName());
      }
      for (final var component : circuit.getNonWires()) {
        FactoryIdentity.capture(file, component.getFactory());
      }
    }
  }
}
