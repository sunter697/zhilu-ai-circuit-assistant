/*
 * Logisim-evolution - digital logic design tool and simulator
 * Copyright by the Logisim-evolution developers
 *
 * https://github.com/logisim-evolution/
 *
 * This is free software released under GNU GPLv3 license
 */

package com.cburch.logisim.ai.vision.importer;

import com.cburch.logisim.ai.tools.FactoryResolver;
import com.cburch.logisim.ai.vision.model.VisionIr;
import com.cburch.logisim.comp.Component;
import com.cburch.logisim.data.Attribute;
import com.cburch.logisim.data.AttributeSet;
import com.cburch.logisim.data.BitWidth;
import com.cburch.logisim.data.Direction;
import com.cburch.logisim.data.Location;
import com.cburch.logisim.file.LogisimFile;
import com.cburch.logisim.instance.StdAttr;
import com.cburch.logisim.std.gates.GateAttributes;
import com.cburch.logisim.std.wiring.Pin;

/** Creates standard Logisim components from validated vision plan entries. */
final class VisionComponentFactory {
  private VisionComponentFactory() {}

  static Component create(LogisimFile file, CircuitImportPlan.PlannedComponent planned) {
    final var factory = FactoryResolver.resolve(file, planned.factoryName());
    final var attributes = factory.createAttributeSet();
    setIfPresent(attributes, StdAttr.FACING, direction(planned.facing()));
    setIfPresent(attributes, StdAttr.WIDTH, BitWidth.create(planned.bitWidth()));
    setIfPresent(attributes, StdAttr.LABEL, planned.label());
    if (attributes.containsAttribute(GateAttributes.ATTR_INPUTS)) {
      attributes.setValue(GateAttributes.ATTR_INPUTS, planned.inputCount());
    }
    if (planned.kind().isGate()) {
      setGateSize(attributes, planned);
    }
    if (attributes.containsAttribute(Pin.ATTR_TYPE)) {
      attributes.setValue(
          Pin.ATTR_TYPE,
          planned.kind() == VisionIr.ComponentKind.OUTPUT_PIN ? Pin.OUTPUT : Pin.INPUT);
    }
    return factory.createComponent(
        Location.create(planned.anchor().x(), planned.anchor().y(), true), attributes);
  }

  static Component createTunnel(LogisimFile file, CircuitImportPlan.PlannedTunnel planned) {
    final var factory = FactoryResolver.resolve(file, "Tunnel");
    final var attributes = factory.createAttributeSet();
    setIfPresent(attributes, StdAttr.FACING, direction(planned.facing()));
    setIfPresent(attributes, StdAttr.WIDTH, BitWidth.create(planned.bitWidth()));
    setIfPresent(attributes, StdAttr.LABEL, planned.label());
    return factory.createComponent(
        Location.create(planned.location().x(), planned.location().y(), true), attributes);
  }

  private static void setGateSize(
      AttributeSet attributes, CircuitImportPlan.PlannedComponent planned) {
    final var sizeAttribute = attributes.getAttribute("size");
    if (sizeAttribute == null) {
      throw new IllegalArgumentException(
          "Gate factory has no size attribute: " + planned.factoryName());
    }
    final String serializedSize;
    if (planned.kind() == VisionIr.ComponentKind.NOT) {
      serializedSize =
          switch (planned.gateSize()) {
            case NARROW -> "20";
            case WIDE -> "30";
            case MEDIUM -> throw new IllegalArgumentException("NOT does not support MEDIUM size");
          };
    } else {
      serializedSize =
          switch (planned.gateSize()) {
            case NARROW -> "30";
            case MEDIUM -> "50";
            case WIDE -> "70";
          };
    }
    setParsed(attributes, sizeAttribute, serializedSize);
  }

  private static <T> void setParsed(
      AttributeSet attributes, Attribute<T> attribute, String serializedValue) {
    attributes.setValue(attribute, attribute.parse(serializedValue));
  }

  private static Direction direction(VisionIr.Facing facing) {
    return switch (facing) {
      case EAST -> Direction.EAST;
      case WEST -> Direction.WEST;
      case NORTH -> Direction.NORTH;
      case SOUTH -> Direction.SOUTH;
    };
  }

  private static <T> void setIfPresent(
      AttributeSet attributes, com.cburch.logisim.data.Attribute<T> attribute, T value) {
    if (attributes.containsAttribute(attribute)) attributes.setValue(attribute, value);
  }
}
