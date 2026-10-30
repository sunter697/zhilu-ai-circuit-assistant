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
import com.cburch.logisim.ai.util.SwingExecutor;
import com.cburch.logisim.ai.validation.TaskSpec;
import com.cburch.logisim.analyze.model.AnalyzerModel;
import com.cburch.logisim.analyze.model.Parser;
import com.cburch.logisim.circuit.Analyze;
import com.cburch.logisim.circuit.CircuitMutation;
import com.cburch.logisim.comp.Component;
import com.cburch.logisim.comp.ComponentFactory;
import com.cburch.logisim.data.Attribute;
import com.cburch.logisim.data.AttributeSet;
import com.cburch.logisim.data.Location;
import com.cburch.logisim.std.gates.CircuitBuilder;
import com.cburch.logisim.std.wiring.Pin;
import com.cburch.logisim.util.StringUtil;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** The first-party tool surface exposed to the model. */
public final class StandardCircuitTools {
  private StandardCircuitTools() {}

  @FunctionalInterface
  private interface ToolExecutor {
    ToolResult execute(Map<String, Object> args, ToolExecutionContext context) throws Exception;
  }

  private record FunctionalTool(
      String name,
      String description,
      Map<String, Object> inputSchema,
      boolean writesCircuit,
      ToolExecutor executor)
      implements CircuitTool {
    @Override
    public ToolResult execute(Map<String, Object> arguments, ToolExecutionContext context)
        throws Exception {
      return executor.execute(arguments == null ? Map.of() : arguments, context);
    }
  }

  public static ToolRegistry createRegistry() {
    final var registry = new ToolRegistry();
    registry.register(
        tool(
            "propose_specification",
            "Propose the exact acceptance specification for user confirmation before any writes."
                + " Never generate expected truth-table rows. After proposing, wait for the user to"
                + " confirm. Unsigned multiplication outputs are ordered from low-order bits to"
                + " high-order bits.",
            schema(
                Map.of(
                    "kind",
                        Map.of(
                            "type",
                            "string",
                            "enum",
                            List.of("BOOLEAN_EXPRESSIONS", "UNSIGNED_MULTIPLICATION")),
                    "inputs", signalsProp("Input signals with exact names and bit widths"),
                    "outputs",
                        signalsProp(
                            "Output signals with exact names and bit widths; multiplication slices"
                                + " are low to high"),
                    "expressions",
                        objectProp(
                            "For BOOLEAN_EXPRESSIONS only: output bit name to Boolean expression")),
                List.of("kind", "inputs", "outputs")),
            false,
            (args, ctx) -> {
              final var specification = TaskSpec.fromArguments(args);
              ctx.proposeSpecification(specification);
              return ToolResult.success(
                  Map.of(
                      "status",
                      "AWAITING_USER_CONFIRMATION",
                      "specification",
                      specification.toMap()));
            }));
    registry.register(
        tool(
            "verify_specification",
            "Verify the current workspace against the user-confirmed specification using a"
                + " host-generated oracle. This is the authoritative acceptance check; supplied"
                + " model truth tables cannot authorize Apply.",
            schema(Map.of(), List.of()),
            false,
            (args, ctx) -> ToolResult.success(ctx.verifySpecification().toMap())));
    registry.register(
        tool(
            "inspect_circuit",
            "Return the current structured circuit context. Component ids are task-local and should"
                + " be used for later edits.",
            schema(Map.of(), List.of()),
            false,
            (args, ctx) -> ToolResult.success(ctx.refreshContext().context())));

    registry.register(
        tool(
            "get_diff",
            "Compare the current workspace circuit with the immutable baseline captured when the"
                + " task started.",
            schema(Map.of(), List.of()),
            false,
            (args, ctx) ->
                ToolResult.success(
                    CircuitDiff.compareCanonical(
                        ctx.workspace().baselineCanonical(),
                        CircuitDigest.canonical(ctx.workspace().circuit())))));

    registry.register(
        tool(
            "add_component",
            "Add a component using a loaded Logisim component type. Omit x/y for automatic"
                + " placement. Attributes are Logisim standard string values.",
            schema(
                Map.of(
                    "type",
                        stringProp(
                            "Component factory name such as AND Gate, XNOR Gate, Pin, Adder"),
                    "x", integerProp("Optional x coordinate"),
                    "y", integerProp("Optional y coordinate"),
                    "attributes",
                        objectProp("Optional attribute-name to standard-string value map")),
                List.of("type")),
            true,
            StandardCircuitTools::addComponent));

    registry.register(
        tool(
            "remove_component",
            "Remove one component by its task-local component id.",
            schema(
                Map.of("componentId", stringProp("Component id from inspect_circuit")),
                List.of("componentId")),
            true,
            StandardCircuitTools::removeComponent));

    registry.register(
        tool(
            "replace_component",
            "Replace one component with another type while preserving location and compatible"
                + " attributes. Ports at identical locations keep their nets. If a connected port"
                + " moves, the tool rejects without changes; remove only that port's terminal wire,"
                + " retry replacement, then reconnect it.",
            schema(
                Map.of(
                    "componentId", stringProp("Component id from inspect_circuit"),
                    "type", stringProp("Replacement component factory name"),
                    "attributes", objectProp("Optional replacement attribute overrides")),
                List.of("componentId", "type")),
            true,
            StandardCircuitTools::replaceComponent));

    registry.register(
        tool(
            "set_attribute",
            "Set one component attribute using its Logisim attribute name and standard string"
                + " value.",
            schema(
                Map.of(
                    "componentId", stringProp("Component id from inspect_circuit"),
                    "attribute", stringProp("Attribute name"),
                    "value", stringProp("Standard string representation of the new value")),
                List.of("componentId", "attribute", "value")),
            true,
            StandardCircuitTools::setAttribute));

    registry.register(
        tool(
            "connect",
            "Safely connect an output port to an input port using bounded net-aware routing."
                + " Rejects conflicting drivers, widths, unsupported bidirectional nets, or blocked"
                + " routes without changing wires. Routing continues from the nearest explicit"
                + " point already on the source net, so do not redraw or remove the rest of that"
                + " net. Use component ids and pN port ids from inspect_circuit.",
            schema(
                Map.of(
                    "fromComponentId", stringProp("Source component id"),
                    "fromPort", stringProp("Source port id such as p0"),
                    "toComponentId", stringProp("Destination component id"),
                    "toPort", stringProp("Destination port id such as p1")),
                List.of("fromComponentId", "fromPort", "toComponentId", "toPort")),
            true,
            StandardCircuitTools::connect));

    registry.register(
        tool(
            "remove_wire",
            "Remove one wire segment by its task-local wire id.",
            schema(Map.of("wireId", stringProp("Wire id from inspect_circuit")), List.of("wireId")),
            true,
            StandardCircuitTools::removeWire));

    registry.register(
        tool(
            "analyze_boolean_logic",
            "Ask Logisim's Analyze engine for Boolean expressions. Unsupported components are"
                + " reported explicitly.",
            schema(Map.of(), List.of()),
            false,
            (args, ctx) -> {
              final var context = ctx.refreshContext().context();
              @SuppressWarnings("unchecked")
              final var analysis = (Map<String, Object>) context.get("booleanAnalysis");
              return ToolResult.success(analysis);
            }));

    registry.register(
        tool(
            "replace_logic_expression",
            "Semantic rebuild: replace one output Boolean expression and rebuild the combinational"
                + " circuit with Logisim CircuitBuilder. This can change layout.",
            schema(
                Map.of(
                    "output", stringProp("Existing output bit name"),
                    "expression", stringProp("Boolean expression using existing input names"),
                    "twoInputGatesOnly", booleanProp("Use only two-input gates"),
                    "nandOnly", booleanProp("Prefer NAND implementation where supported")),
                List.of("output", "expression")),
            true,
            StandardCircuitTools::replaceLogicExpression));

    registry.register(
        tool(
            "run_truth_table",
            "Run Logisim's real combinational simulation and return a bounded truth table. This is"
                + " evidence, not an expected oracle.",
            schema(
                Map.of(
                    "maxRows",
                    integerProp("Maximum rows to include in the returned payload; default 256")),
                List.of()),
            false,
            (args, ctx) ->
                ToolResult.success(
                    ctx.validationEngine()
                        .truthTable(
                            ctx.workspace().project(),
                            ctx.workspace().circuit(),
                            intArg(args, "maxRows", 256)))));

    registry.register(
        tool(
            "verify_truth_table",
            "Diagnostic comparison against a supplied truth table. Cannot authorize Apply or"
                + " replace the confirmed specification. Use verify_specification for authoritative"
                + " acceptance. Rows use Logisim binary count order.",
            schema(
                Map.of(
                    "inputs", stringArrayProp("Input bit column names in exact Logisim order"),
                    "outputs", stringArrayProp("Output bit column names in exact Logisim order"),
                    "rows",
                        arrayProp(
                            "Exhaustive rows; each row contains input values followed by expected"
                                + " output values")),
                List.of("inputs", "outputs", "rows")),
            false,
            StandardCircuitTools::verifyTruthTable));
    return registry;
  }

  private static CircuitTool tool(
      String name,
      String description,
      Map<String, Object> schema,
      boolean writes,
      ToolExecutor executor) {
    return new FunctionalTool(name, description, schema, writes, executor);
  }

  private static ToolResult addComponent(Map<String, Object> args, ToolExecutionContext ctx)
      throws Exception {
    ctx.checkWriteAllowed();
    final var factory =
        FactoryResolver.resolve(
            ctx.workspace().project().getLogisimFile(), stringArg(args, "type"));
    final var attrs = factory.createAttributeSet();
    applyAttributes(attrs, mapArg(args, "attributes"));
    final var location = requestedLocation(args, ctx, factory, attrs);
    final var component = factory.createComponent(location, attrs);
    SwingExecutor.run(
        () -> {
          ctx.checkWriteAllowed();
          CircuitEditSafety.requireSafe(ctx.workspace().circuit(), component, null);
          ctx.beforeWrite();
          final var mutation = new CircuitMutation(ctx.workspace().circuit());
          mutation.add(component);
          ctx.workspace()
              .project()
              .doAction(mutation.toAction(StringUtil.constantGetter("AI: add component")));
        });
    ctx.afterWrite();
    final var id = idFor(ctx, component);
    return ToolResult.success(
        Map.of(
            "componentId",
            id,
            "type",
            factory.getName(),
            "location",
            List.of(location.getX(), location.getY())));
  }

  private static ToolResult removeComponent(Map<String, Object> args, ToolExecutionContext ctx)
      throws Exception {
    ctx.beforeWrite();
    final var component = ctx.circuitContext().requireComponent(stringArg(args, "componentId"));
    SwingExecutor.run(
        () -> {
          ctx.checkWriteAllowed();
          final var mutation = new CircuitMutation(ctx.workspace().circuit());
          mutation.remove(component);
          ctx.workspace()
              .project()
              .doAction(mutation.toAction(StringUtil.constantGetter("AI: remove component")));
        });
    ctx.afterWrite();
    return ToolResult.success(Map.of("removed", stringArg(args, "componentId")));
  }

  private static ToolResult replaceComponent(Map<String, Object> args, ToolExecutionContext ctx)
      throws Exception {
    ctx.checkWriteAllowed();
    final var oldComponent = ctx.circuitContext().requireComponent(stringArg(args, "componentId"));
    final var factory =
        FactoryResolver.resolve(
            ctx.workspace().project().getLogisimFile(), stringArg(args, "type"));
    final var attrs = factory.createAttributeSet();
    copyCompatibleAttributes(oldComponent.getAttributeSet(), attrs);
    applyAttributes(attrs, mapArg(args, "attributes"));
    final var replacement = factory.createComponent(oldComponent.getLocation(), attrs);
    SwingExecutor.run(
        () -> {
          ctx.checkWriteAllowed();
          CircuitEditSafety.requireSafe(ctx.workspace().circuit(), replacement, oldComponent);
          ctx.beforeWrite();
          final var mutation = new CircuitMutation(ctx.workspace().circuit());
          mutation.replace(oldComponent, replacement);
          ctx.workspace()
              .project()
              .doAction(mutation.toAction(StringUtil.constantGetter("AI: replace component")));
        });
    ctx.afterWrite();
    return ToolResult.success(
        Map.of(
            "oldComponentId", stringArg(args, "componentId"),
            "newComponentId", idFor(ctx, replacement),
            "type", factory.getName()));
  }

  private static ToolResult setAttribute(Map<String, Object> args, ToolExecutionContext ctx)
      throws Exception {
    ctx.checkWriteAllowed();
    final var component = ctx.circuitContext().requireComponent(stringArg(args, "componentId"));
    final var attrSet = component.getAttributeSet();
    final var attr = findAttribute(attrSet, stringArg(args, "attribute"));
    if (attr == null)
      throw new IllegalArgumentException(
          "Component does not expose attribute " + stringArg(args, "attribute"));
    final var parsed = parseAttribute(attr, stringArg(args, "value"));
    SwingExecutor.run(
        () -> {
          ctx.checkWriteAllowed();
          final var trialAttrs = (AttributeSet) component.getAttributeSet().clone();
          final var trialAttr = findAttribute(trialAttrs, attr.getName());
          setParsed(trialAttrs, trialAttr, stringArg(args, "value"));
          final var trial =
              component.getFactory().createComponent(component.getLocation(), trialAttrs);
          CircuitEditSafety.requireSafe(ctx.workspace().circuit(), trial, component);
          ctx.beforeWrite();
          final var mutation = new CircuitMutation(ctx.workspace().circuit());
          mutation.set(component, attr, parsed);
          ctx.workspace()
              .project()
              .doAction(mutation.toAction(StringUtil.constantGetter("AI: set attribute")));
        });
    ctx.afterWrite();
    return ToolResult.success(
        Map.of(
            "componentId",
            stringArg(args, "componentId"),
            "attribute",
            attr.getName(),
            "value",
            stringArg(args, "value")));
  }

  private static ToolResult connect(Map<String, Object> args, ToolExecutionContext ctx)
      throws Exception {
    return SwingExecutor.call(
        () -> {
          ctx.checkWriteAllowed();
          final var current = ctx.refreshContext();
          final var from = current.requireComponent(stringArg(args, "fromComponentId"));
          final var to = current.requireComponent(stringArg(args, "toComponentId"));
          final var fromIndex = portIndex(stringArg(args, "fromPort"), from.getEnds().size());
          final var toIndex = portIndex(stringArg(args, "toPort"), to.getEnds().size());
          final var fromEnd = from.getEnd(fromIndex);
          final var toEnd = to.getEnd(toIndex);
          final var wires = SafeWireRouter.route(ctx.workspace().circuit(), fromEnd, toEnd);
          if (wires.isEmpty())
            return ToolResult.success(Map.of("alreadyConnected", true, "wireSegmentsAdded", 0));
          ctx.beforeWrite();
          ctx.checkWriteAllowed();
          final var mutation = new CircuitMutation(ctx.workspace().circuit());
          for (final var wire : wires) mutation.add(wire);
          ctx.workspace()
              .project()
              .doAction(mutation.toAction(StringUtil.constantGetter("AI: connect components")));
          ctx.afterWrite();
          return ToolResult.success(
              Map.of(
                  "from", List.of(fromEnd.getLocation().getX(), fromEnd.getLocation().getY()),
                  "to", List.of(toEnd.getLocation().getX(), toEnd.getLocation().getY()),
                  "wireSegmentsAdded", wires.size()));
        });
  }

  private static ToolResult removeWire(Map<String, Object> args, ToolExecutionContext ctx)
      throws Exception {
    ctx.checkWriteAllowed();
    final var wire = ctx.circuitContext().requireWire(stringArg(args, "wireId"));
    SwingExecutor.run(
        () -> {
          ctx.checkWriteAllowed();
          ctx.beforeWrite();
          final var mutation = new CircuitMutation(ctx.workspace().circuit());
          mutation.remove(wire);
          ctx.workspace()
              .project()
              .doAction(mutation.toAction(StringUtil.constantGetter("AI: remove wire")));
        });
    ctx.afterWrite();
    return ToolResult.success(Map.of("removed", stringArg(args, "wireId")));
  }

  private static ToolResult replaceLogicExpression(
      Map<String, Object> args, ToolExecutionContext ctx) throws Exception {
    ctx.beforeWrite();
    final var circuit = ctx.workspace().circuit();
    final var model = new AnalyzerModel();
    Analyze.computeExpression(model, circuit, Analyze.getPinLabels(circuit));
    final var output = stringArg(args, "output");
    if (!model.getOutputs().bits.contains(output))
      throw new IllegalArgumentException("Unknown output bit: " + output);
    final var expressionText = stringArg(args, "expression");
    final var expression = Parser.parse(expressionText, model);
    model.getOutputExpressions().setExpression(output, expression, expressionText);
    final var twoInputs = booleanArg(args, "twoInputGatesOnly", false);
    final var nandOnly = booleanArg(args, "nandOnly", false);
    final var mutation = CircuitBuilder.build(circuit, model, twoInputs, nandOnly);
    SwingExecutor.run(
        () -> {
          ctx.checkWriteAllowed();
          ctx.workspace()
              .project()
              .doAction(mutation.toAction(StringUtil.constantGetter("AI: rebuild Boolean logic")));
        });
    ctx.afterWrite();
    return ToolResult.success(
        Map.of(
            "output",
            output,
            "expression",
            expressionText,
            "strategy",
            "SEMANTIC_REBUILD",
            "layoutMayChange",
            true));
  }

  private static ToolResult verifyTruthTable(Map<String, Object> args, ToolExecutionContext ctx) {
    final var inputs = stringList(args.get("inputs"));
    final var outputs = stringList(args.get("outputs"));
    final var rows = rows(args.get("rows"));
    final var evidence =
        ctx.validationEngine()
            .verifyTruthTable(
                ctx.workspace().project(), ctx.workspace().circuit(), inputs, outputs, rows);
    final var result = new LinkedHashMap<>(evidence.toMap());
    result.put("diagnosticOnly", true);
    result.put("authorizesApply", false);
    return ToolResult.success(result);
  }

  private static String idFor(ToolExecutionContext ctx, Component component) {
    for (final var entry : ctx.circuitContext().components().entrySet()) {
      if (entry.getValue() == component) return entry.getKey();
    }
    return "<unmapped>";
  }

  private static Location requestedLocation(
      Map<String, Object> args,
      ToolExecutionContext ctx,
      ComponentFactory factory,
      AttributeSet attrs) {
    if (args.get("x") instanceof Number x && args.get("y") instanceof Number y) {
      return Location.create(x.intValue(), y.intValue(), true);
    }
    final var bounds = ctx.workspace().circuit().getBounds();
    if (factory == Pin.FACTORY) {
      final var output = Pin.OUTPUT.equals(attrs.getValue(Pin.ATTR_TYPE));
      final var count =
          ctx.workspace().circuit().getNonWires().stream()
              .filter(component -> component.getFactory() == Pin.FACTORY)
              .filter(
                  component ->
                      output
                          == Pin.OUTPUT.equals(component.getAttributeSet().getValue(Pin.ATTR_TYPE)))
              .count();
      final var pinX =
          output
              ? Math.max(300, bounds.getX() + bounds.getWidth() + 100)
              : Math.max(20, bounds.getX() - 100);
      final var pinY = output ? 100 + (int) count * 80 : 50 + (int) count * 100;
      return Location.create(pinX, pinY, true);
    }
    final var x = Math.max(100, bounds.getX() + bounds.getWidth() + 100);
    final var y = Math.max(100, bounds.getY() + 40);
    return Location.create(x, y, true);
  }

  private static int portIndex(String raw, int size) {
    final var normalized = raw == null ? "" : raw.trim().toLowerCase();
    final var digits = normalized.startsWith("p") ? normalized.substring(1) : normalized;
    final int index;
    try {
      index = Integer.parseInt(digits);
    } catch (NumberFormatException e) {
      throw new IllegalArgumentException("Port must be pN or N: " + raw);
    }
    if (index < 0 || index >= size)
      throw new IllegalArgumentException("Port index out of range: " + raw);
    return index;
  }

  private static void copyCompatibleAttributes(AttributeSet source, AttributeSet target) {
    if (source == null
        || target == null
        || source.getAttributes() == null
        || target.getAttributes() == null) return;
    for (final var targetAttr : target.getAttributes()) {
      final var sourceAttr = findAttribute(source, targetAttr.getName());
      if (sourceAttr == null) continue;
      try {
        final var value = CircuitDigest.attributeValue(source, sourceAttr);
        setParsed(target, targetAttr, value);
      } catch (RuntimeException ignored) {
        // Not all same-named attributes share the exact value domain across factories.
      }
    }
  }

  private static void applyAttributes(AttributeSet attrs, Map<String, Object> values) {
    for (final var entry : values.entrySet()) {
      final var attr = findAttribute(attrs, entry.getKey());
      if (attr == null) throw new IllegalArgumentException("Unknown attribute: " + entry.getKey());
      setParsed(attrs, attr, String.valueOf(entry.getValue()));
    }
  }

  private static Attribute<?> findAttribute(AttributeSet attrs, String name) {
    if (attrs == null || attrs.getAttributes() == null || name == null) return null;
    for (final var attr : attrs.getAttributes())
      if (attr.getName().equalsIgnoreCase(name)) return attr;
    return null;
  }

  @SuppressWarnings({"rawtypes", "unchecked"})
  private static Object parseAttribute(Attribute<?> attr, String value) {
    return ((Attribute) attr).parse(value);
  }

  @SuppressWarnings({"rawtypes", "unchecked"})
  private static void setParsed(AttributeSet attrs, Attribute<?> attr, String value) {
    attrs.setValue((Attribute) attr, ((Attribute) attr).parse(value));
  }

  private static String stringArg(Map<String, Object> args, String name) {
    final var value = args.get(name);
    if (!(value instanceof String s) || s.isBlank())
      throw new IllegalArgumentException("Missing string argument: " + name);
    return s;
  }

  private static int intArg(Map<String, Object> args, String name, int defaultValue) {
    final var value = args.get(name);
    return value instanceof Number number ? number.intValue() : defaultValue;
  }

  private static boolean booleanArg(Map<String, Object> args, String name, boolean defaultValue) {
    final var value = args.get(name);
    return value instanceof Boolean b ? b : defaultValue;
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> mapArg(Map<String, Object> args, String name) {
    final var value = args.get(name);
    if (value == null) return Map.of();
    if (!(value instanceof Map<?, ?> map))
      throw new IllegalArgumentException(name + " must be an object");
    final var result = new LinkedHashMap<String, Object>();
    for (final var entry : map.entrySet())
      result.put(String.valueOf(entry.getKey()), entry.getValue());
    return result;
  }

  private static List<String> stringList(Object value) {
    if (!(value instanceof List<?> list))
      throw new IllegalArgumentException("Expected an array of strings");
    final var result = new ArrayList<String>();
    for (final var item : list) {
      if (!(item instanceof String s))
        throw new IllegalArgumentException("Expected an array of strings");
      result.add(s);
    }
    return result;
  }

  private static List<List<String>> rows(Object value) {
    if (!(value instanceof List<?> list))
      throw new IllegalArgumentException("rows must be an array");
    final var result = new ArrayList<List<String>>();
    for (final var item : list) result.add(stringList(item));
    return result;
  }

  private static Map<String, Object> schema(Map<String, Object> properties, List<String> required) {
    final var result = new LinkedHashMap<String, Object>();
    result.put("type", "object");
    result.put("properties", properties);
    result.put("required", required);
    result.put("additionalProperties", false);
    return result;
  }

  private static Map<String, Object> stringProp(String description) {
    return Map.of("type", "string", "description", description);
  }

  private static Map<String, Object> integerProp(String description) {
    return Map.of("type", "integer", "description", description);
  }

  private static Map<String, Object> booleanProp(String description) {
    return Map.of("type", "boolean", "description", description);
  }

  private static Map<String, Object> objectProp(String description) {
    return Map.of(
        "type",
        "object",
        "description",
        description,
        "additionalProperties",
        Map.of("type", "string"));
  }

  private static Map<String, Object> stringArrayProp(String description) {
    return Map.of("type", "array", "description", description, "items", Map.of("type", "string"));
  }

  private static Map<String, Object> arrayProp(String description) {
    return Map.of(
        "type",
        "array",
        "description",
        description,
        "items",
        Map.of("type", "array", "items", Map.of("type", "string")));
  }

  private static Map<String, Object> signalsProp(String description) {
    return Map.of(
        "type",
        "array",
        "description",
        description,
        "items",
        schema(
            Map.of("name", stringProp("Signal name"), "width", integerProp("Positive bit width")),
            List.of("name", "width")));
  }
}
