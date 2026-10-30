/*
 * Logisim-evolution - digital logic design tool and simulator
 * Copyright by the Logisim-evolution developers
 *
 * https://github.com/logisim-evolution/
 *
 * This is free software released under GNU GPLv3 license
 */

package com.cburch.logisim.ai.tools;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.cburch.logisim.circuit.Circuit;
import com.cburch.logisim.file.Loader;
import com.cburch.logisim.file.LogisimFile;
import org.junit.jupiter.api.Test;

class FactoryResolverTest {
  @Test
  void exactNamesPreservePunctuationAndUnicode() {
    final var file = LogisimFile.createNew(new Loader(null), null);
    try {
      final var underscored = new Circuit("Foo_Bar", file, null);
      final var plain = new Circuit("FooBar", file, null);
      final var chineseA = new Circuit("甲", file, null);
      final var chineseB = new Circuit("乙", file, null);
      file.addCircuit(underscored);
      file.addCircuit(plain);
      file.addCircuit(chineseA);
      file.addCircuit(chineseB);
      assertSame(underscored.getSubcircuitFactory(), FactoryResolver.resolve(file, "Foo_Bar"));
      assertSame(plain.getSubcircuitFactory(), FactoryResolver.resolve(file, "FooBar"));
      assertSame(chineseA.getSubcircuitFactory(), FactoryResolver.resolve(file, "甲"));
      assertSame(chineseB.getSubcircuitFactory(), FactoryResolver.resolve(file, "乙"));
      assertThrows(IllegalArgumentException.class, () -> FactoryResolver.resolve(file, "丙"));
    } finally {
      file.stopAutosaveThread(false);
    }
  }

  @Test
  void exactNameWinsButAmbiguousNormalizedNameIsRejected() {
    final var loader = new Loader(null);
    final var file = LogisimFile.createNew(loader, null);
    try {
      final var first = new Circuit("Choice", file, null);
      final var second = new Circuit("CHOICE", file, null);
      file.addCircuit(first);
      file.addCircuit(second);
      assertSame(first.getSubcircuitFactory(), FactoryResolver.resolve(file, "Choice"));
      assertThrows(IllegalArgumentException.class, () -> FactoryResolver.resolve(file, "choice"));
      file.addLibrary(loader.loadLibrary("#Gates"));
      assertSame(FactoryResolver.resolve(file, "AND Gate"), FactoryResolver.resolve(file, "and"));
    } finally {
      file.stopAutosaveThread(false);
    }
  }
}
