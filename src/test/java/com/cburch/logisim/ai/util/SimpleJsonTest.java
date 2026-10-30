/*
 * Logisim-evolution - digital logic design tool and simulator
 * Copyright by the Logisim-evolution developers
 *
 * https://github.com/logisim-evolution/
 *
 * This is free software released under GNU GPLv3 license
 */

package com.cburch.logisim.ai.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SimpleJsonTest {
  @Test
  void roundTripsNestedValues() {
    final var original =
        Map.of("text", "a\nb\"c", "number", 7, "values", List.of(true, false, "x"));
    final var parsed = SimpleJson.parseObject(SimpleJson.stringify(original));
    assertEquals("a\nb\"c", parsed.get("text"));
    assertEquals(7L, parsed.get("number"));
    assertEquals(List.of(true, false, "x"), parsed.get("values"));
  }

  @Test
  void rejectsTrailingData() {
    final var error =
        assertThrows(IllegalArgumentException.class, () -> SimpleJson.parse("{} garbage"));
    assertTrue(error.getMessage().contains("trailing"));
  }
}
