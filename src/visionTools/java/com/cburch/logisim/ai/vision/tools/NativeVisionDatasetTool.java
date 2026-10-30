/*
 * Logisim-evolution - digital logic design tool and simulator
 * Copyright by the Logisim-evolution developers
 *
 * https://github.com/logisim-evolution/
 *
 * This is free software released under GNU GPLv3 license
 */

package com.cburch.logisim.ai.vision.tools;

import com.cburch.logisim.ai.tools.FactoryResolver;
import com.cburch.logisim.ai.util.SimpleJson;
import com.cburch.logisim.ai.vision.importer.CircuitImportBuilder;
import com.cburch.logisim.ai.vision.importer.CircuitImportPlanner;
import com.cburch.logisim.ai.vision.model.VisionIr;
import com.cburch.logisim.ai.vision.model.VisionIrCodec;
import com.cburch.logisim.ai.vision.model.VisionIrValidator;
import com.cburch.logisim.circuit.Circuit;
import com.cburch.logisim.circuit.CircuitState;
import com.cburch.logisim.comp.Component;
import com.cburch.logisim.comp.ComponentDrawContext;
import com.cburch.logisim.comp.ComponentFactory;
import com.cburch.logisim.data.Attribute;
import com.cburch.logisim.data.AttributeSet;
import com.cburch.logisim.data.BitWidth;
import com.cburch.logisim.data.Bounds;
import com.cburch.logisim.data.Direction;
import com.cburch.logisim.data.Location;
import com.cburch.logisim.file.Loader;
import com.cburch.logisim.file.LogisimFile;
import com.cburch.logisim.instance.StdAttr;
import com.cburch.logisim.prefs.AppPreferences;
import com.cburch.logisim.proj.Project;
import com.cburch.logisim.std.gates.GateAttributes;
import com.cburch.logisim.std.wiring.Pin;
import java.awt.Color;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import javax.imageio.ImageIO;

/** Developer-only generator and validator for native-rendered circuit-vision datasets. */
public final class NativeVisionDatasetTool {
  private static final String GENERATOR_VERSION = "native-logisim-dataset/0.5";
  private static final int WIDTH = 800;
  private static final int HEIGHT = 520;
  private static final int GRID = 10;
  private static final int DEFAULT_TOPOLOGIES = 100;
  private static final int DEFAULT_VARIANTS = 2;
  private static final long DEFAULT_SEED = 20261024L;
  private static final List<Kind> BINARY_GATE_KINDS =
      List.of(Kind.AND, Kind.OR, Kind.NAND, Kind.NOR, Kind.XOR, Kind.XNOR);
  private static final List<Kind> GATE_KINDS =
      List.of(Kind.AND, Kind.OR, Kind.NOT, Kind.NAND, Kind.NOR, Kind.XOR, Kind.XNOR);
  private static final List<Kind> CLASS_KINDS =
      List.of(
          Kind.INPUT_PIN,
          Kind.OUTPUT_PIN,
          Kind.AND,
          Kind.OR,
          Kind.NOT,
          Kind.NAND,
          Kind.NOR,
          Kind.XOR,
          Kind.XNOR);
  private static final Map<Kind, Integer> CLASS_IDS = classIds();
  private static final RenderProfile NATIVE_RENDER_PROFILE =
      new RenderProfile("native-grid", true, 0xFFFFFF, 0xD8D8D8, true, 1.0);
  private static final List<RenderProfile> AUGMENTED_RENDER_PROFILES =
      List.of(
          new RenderProfile("light-grid", true, 0xFFFFFF, 0xE8E8E8, true, 1.0),
          new RenderProfile("gridless", false, 0xFFFFFF, 0xFFFFFF, true, 1.0),
          new RenderProfile("crisp-grid", true, 0xFFFFFF, 0xD8D8D8, false, 1.0),
          new RenderProfile("soft-resample", true, 0xFFFFFF, 0xD8D8D8, true, 0.88),
          new RenderProfile("off-white-grid", true, 0xFAFAFA, 0xD4D4D4, true, 1.0));

  private NativeVisionDatasetTool() {}

  /** Command-line entry point used by the Gradle {@code generateVisionDataset} task. */
  public static void main(String[] args) throws Exception {
    System.setProperty("java.awt.headless", "true");
    final var options = Options.parse(args);
    if (options.help()) {
      printUsage();
      return;
    }
    if (options.validateOnly() != null) {
      System.out.println(prettyJson(validateDataset(options.validateOnly())));
      return;
    }
    final var summary =
        generateDataset(
            options.output(),
            options.topologies(),
            options.variants(),
            options.seed(),
            options.evaluation(),
            options.excludeDataset());
    System.out.println(prettyJson(summary));
  }

  private static Map<String, Object> generateDataset(
      Path output,
      int topologyCount,
      int variants,
      long seed,
      boolean evaluation,
      Path excludeDataset)
      throws Exception {
    if (output == null) throw new IllegalArgumentException("--output is required");
    if (topologyCount <= 0) throw new IllegalArgumentException("--topologies must be positive");
    if (variants <= 0 || variants > 20) {
      throw new IllegalArgumentException("--variants must be between 1 and 20");
    }
    final var root = output.toAbsolutePath().normalize();
    if (Files.exists(root)) {
      throw new IllegalArgumentException("Output directory already exists: " + root);
    }
    createDatasetDirectories(root);

    final var random = new Random(seed);
    final var exclusion = loadExclusion(excludeDataset);
    final var semanticHashes = new HashSet<String>();
    final var manifest = new ArrayList<Map<String, Object>>();
    final var classCounts = new LinkedHashMap<String, Long>();
    final var gateSizeCounts = new LinkedHashMap<String, Long>();
    final var splitCounts = new LinkedHashMap<String, Long>();
    final var familyCounts = new LinkedHashMap<String, Long>();
    final var renderProfileCounts = new LinkedHashMap<String, Long>();
    final var rejectionReasons = new LinkedHashMap<String, Long>();
    for (final var kind : CLASS_KINDS) classCounts.put(kind.name(), 0L);
    for (final var split : List.of("train", "val", "test")) splitCounts.put(split, 0L);

    var attempts = 0;
    var duplicateCandidates = 0;
    var excludedCandidates = 0;
    final var maxAttempts = Math.max(10_000, topologyCount * 300);
    try (var context = new NativeContext()) {
      while (semanticHashes.size() < topologyCount) {
        if (++attempts > maxAttempts) {
          throw new IllegalStateException(
              "Could not produce " + topologyCount + " valid unique topologies");
        }
        final var topology = randomTopology(random);
        final var semanticHash = sha256(topology.signature().getBytes(StandardCharsets.UTF_8));
        if (semanticHashes.contains(semanticHash)) {
          duplicateCandidates++;
          continue;
        }
        if (exclusion.semanticHashes().contains(semanticHash)) {
          excludedCandidates++;
          continue;
        }

        final var renderedVariants = new ArrayList<RenderedSample>();
        try {
          for (var variant = 0; variant < variants; variant++) {
            renderedVariants.add(
                renderSample(context, topology, semanticHash, random.nextLong(), variant));
          }
        } catch (IllegalArgumentException e) {
          // Random trees can occasionally create a crossing or body overlap. Reject the whole
          // semantic group so no invalid sample or cross-split duplicate reaches the dataset.
          rejectionReasons.merge(safeMessage(e), 1L, Long::sum);
          continue;
        }

        semanticHashes.add(semanticHash);
        final var topologyIndex = semanticHashes.size();
        final var split = evaluation ? "test" : splitForHash(semanticHash);
        familyCounts.merge(topology.family(), 1L, Long::sum);
        for (final var sample : renderedVariants) {
          final var stem =
              String.format(
                  Locale.ROOT,
                  "native_%06d_v%02d_%s",
                  topologyIndex,
                  sample.variant(),
                  semanticHash.substring(0, 10));
          final var imageRelative = Path.of("images", split, stem + ".png");
          final var labelRelative = Path.of("labels", split, stem + ".txt");
          final var irRelative = Path.of("ir", split, stem + ".json");
          Files.write(root.resolve(imageRelative), sample.png());
          Files.writeString(
              root.resolve(labelRelative), sample.yolo(), StandardCharsets.UTF_8);
          Files.writeString(
              root.resolve(irRelative),
              VisionIrCodec.toJson(sample.ir()) + System.lineSeparator(),
              StandardCharsets.UTF_8);

          final var record = new LinkedHashMap<String, Object>();
          record.put("generatorVersion", GENERATOR_VERSION);
          record.put("datasetRole", evaluation ? "evaluation" : "training");
          record.put("topologyIndex", topologyIndex);
          record.put("variant", sample.variant());
          record.put("sampleSeed", Long.toUnsignedString(sample.seed()));
          record.put("family", topology.family());
          record.put("renderProfile", sample.renderProfile().name());
          record.put("renderSettings", sample.renderProfile().manifestValue());
          record.put("semanticHash", semanticHash);
          record.put("topology", topology.signature());
          record.put("split", split);
          record.put("image", slash(imageRelative));
          record.put("label", slash(labelRelative));
          record.put("ir", slash(irRelative));
          record.put("imageSha256", sample.ir().source().sha256());
          record.put("componentCount", sample.ir().components().size());
          record.put("netCount", sample.ir().nets().size());
          record.put(
              "junctionCount",
              sample.ir().nets().stream().mapToInt(net -> net.junctions().size()).sum());
          manifest.add(record);
          splitCounts.merge(split, 1L, Long::sum);
          renderProfileCounts.merge(sample.renderProfile().name(), 1L, Long::sum);
          for (final var component : sample.ir().components()) {
            classCounts.merge(component.kind().name(), 1L, Long::sum);
            final var gateSize = component.attributes().get("size");
            if (gateSize instanceof String size) {
              gateSizeCounts.merge(size, 1L, Long::sum);
            }
          }
        }
        if (topologyIndex % 25 == 0 || topologyIndex == topologyCount) {
          System.out.printf(
              Locale.ROOT, "generated %d/%d topologies%n", topologyIndex, topologyCount);
        }
      }
    }

    final var manifestText = new StringBuilder();
    for (final var record : manifest) {
      manifestText.append(SimpleJson.stringify(record)).append('\n');
    }
    Files.writeString(root.resolve("manifest.jsonl"), manifestText, StandardCharsets.UTF_8);
    Files.writeString(root.resolve("data.yaml"), dataYaml(evaluation), StandardCharsets.UTF_8);
    final var summary = new LinkedHashMap<String, Object>();
    summary.put("generatorVersion", GENERATOR_VERSION);
    summary.put("datasetRole", evaluation ? "evaluation" : "training");
    summary.put("seed", seed);
    summary.put("topologies", topologyCount);
    summary.put("variantsPerTopology", variants);
    summary.put("samples", manifest.size());
    summary.put("candidateAttempts", attempts);
    summary.put("duplicateCandidates", duplicateCandidates);
    summary.put("excludedCandidates", excludedCandidates);
    summary.put("excludedSemanticHashes", exclusion.semanticHashes().size());
    if (exclusion.manifestSha256() != null) {
      summary.put("excludeManifestSha256", exclusion.manifestSha256());
    }
    summary.put("rejectedCandidates", rejectionReasons.values().stream().mapToLong(Long::longValue).sum());
    summary.put("rejectionReasons", rejectionReasons);
    summary.put("splits", splitCounts);
    summary.put("families", familyCounts);
    summary.put("renderProfiles", renderProfileCounts);
    summary.put("gateSizes", gateSizeCounts);
    summary.put("classes", classCounts);
    Files.writeString(
        root.resolve("summary.json"),
        prettyJson(summary) + System.lineSeparator(),
        StandardCharsets.UTF_8);
    return validateDataset(root);
  }

  private static RenderedSample renderSample(
      NativeContext context,
      Topology topology,
      String semanticHash,
      long seed,
      int variant) {
    final var random = new Random(seed);
    final var deltaX = (random.nextInt(3) - 1) * GRID;
    final var deltaY = (random.nextInt(3) - 1) * GRID;
    final var layout = layout(topology, deltaX, deltaY);
    final var components = createComponents(context.file(), topology, layout, random);
    final var nets = route(topology, components);
    final var renderProfile = renderProfile(variant, random);

    final var placeholderSource = new VisionIr.Source("0".repeat(64), WIDTH, HEIGHT);
    final var placeholderIr =
        buildIr(placeholderSource, semanticHash, topology, components, nets);
    VisionIrValidator.validateOrThrow(placeholderIr);
    final var plan =
        new CircuitImportPlanner()
            .plan(placeholderIr, context.file(), "NativeVisionGenerated");
    final var built = new CircuitImportBuilder().buildDetached(context.project(), plan);
    final var png = renderPng(context.project(), built.circuit(), random, renderProfile);
    final var imageHash = sha256(png);
    final var ir =
        buildIr(new VisionIr.Source(imageHash, WIDTH, HEIGHT), semanticHash, topology, components, nets);
    VisionIrValidator.validateOrThrow(ir);
    return new RenderedSample(seed, variant, png, yolo(ir), ir, renderProfile);
  }

  private static RenderProfile renderProfile(int variant, Random random) {
    if (variant == 0) return NATIVE_RENDER_PROFILE;
    return AUGMENTED_RENDER_PROFILES.get(random.nextInt(AUGMENTED_RENDER_PROFILES.size()));
  }

  private static RenderProfile knownRenderProfile(String name) {
    if (NATIVE_RENDER_PROFILE.name().equals(name)) return NATIVE_RENDER_PROFILE;
    for (final var profile : AUGMENTED_RENDER_PROFILES) {
      if (profile.name().equals(name)) return profile;
    }
    return null;
  }

  private static Topology treeTopology(Random random) {
    final var gateCount = 2 + random.nextInt(5);
    final var root = buildExpression(random, gateCount);
    return new Topology(root, "tree", canonical(root));
  }

  private static Topology randomTopology(Random random) {
    return switch (random.nextInt(10)) {
      case 0, 1 -> layeredDagTopology(random);
      case 2, 3 -> diamondTopology(random);
      default -> treeTopology(random);
    };
  }

  private static Node buildExpression(Random random, int gateCount) {
    if (gateCount == 0) return new Node(Kind.INPUT_PIN, List.of());
    final var kind = GATE_KINDS.get(random.nextInt(GATE_KINDS.size()));
    if (kind == Kind.NOT) {
      return new Node(kind, List.of(buildExpression(random, gateCount - 1)));
    }
    final var leftCount = random.nextInt(gateCount);
    final var rightCount = gateCount - 1 - leftCount;
    return new Node(
        kind,
        List.of(buildExpression(random, leftCount), buildExpression(random, rightCount)));
  }

  private static Topology diamondTopology(Random random) {
    final var shared = new Node(Kind.INPUT_PIN, List.of());
    final var upperAux = new Node(Kind.INPUT_PIN, List.of());
    final var lowerAux = new Node(Kind.INPUT_PIN, List.of());
    final var upperKind = randomBinaryKind(random);
    final var lowerKind = randomBinaryKind(random);
    final var rootKind = randomBinaryKind(random);
    final var upper = new Node(upperKind, List.of(shared, upperAux));
    final var lower = new Node(lowerKind, List.of(shared, lowerAux));
    final var root = new Node(rootKind, List.of(upper, lower));
    final var branchKinds =
        List.of(upperKind.name(), lowerKind.name()).stream().sorted().toList();
    final var signature =
        "DIAMOND("
            + rootKind
            + ","
            + branchKinds.get(0)
            + ","
            + branchKinds.get(1)
            + ",SHARED_INPUT)";
    return new Topology(root, "diamond-fanout", signature);
  }

  private static Topology layeredDagTopology(Random random) {
    final var shared = new Node(Kind.INPUT_PIN, List.of());
    final var firstUpperAux = new Node(Kind.INPUT_PIN, List.of());
    final var firstLowerAux = new Node(Kind.INPUT_PIN, List.of());
    final var firstUpperKind = randomBinaryKind(random);
    final var firstLowerKind = randomBinaryKind(random);
    final var firstMergeKind = randomBinaryKind(random);
    final var firstUpper = new Node(firstUpperKind, List.of(shared, firstUpperAux));
    final var firstLower = new Node(firstLowerKind, List.of(shared, firstLowerAux));
    final var firstMerge = new Node(firstMergeKind, List.of(firstUpper, firstLower));

    final var secondUpperAux = new Node(Kind.INPUT_PIN, List.of());
    final var secondLowerAux = new Node(Kind.INPUT_PIN, List.of());
    final var secondUpperKind = randomBinaryKind(random);
    final var secondLowerKind = randomBinaryKind(random);
    final var secondMergeKind = randomBinaryKind(random);
    final var secondUpper =
        new Node(secondUpperKind, List.of(firstMerge, secondUpperAux));
    final var secondLower =
        new Node(secondLowerKind, List.of(firstMerge, secondLowerAux));
    final var root = new Node(secondMergeKind, List.of(secondUpper, secondLower));
    final var signature =
        "LAYERED_DAG("
            + diamondStageSignature(firstMergeKind, firstUpperKind, firstLowerKind)
            + ","
            + diamondStageSignature(secondMergeKind, secondUpperKind, secondLowerKind)
            + ")";
    return new Topology(root, "layered-dag", signature);
  }

  private static String diamondStageSignature(
      Kind mergeKind, Kind upperKind, Kind lowerKind) {
    final var branchKinds =
        List.of(upperKind.name(), lowerKind.name()).stream().sorted().toList();
    return mergeKind + "(" + branchKinds.get(0) + "," + branchKinds.get(1) + ")";
  }

  private static Kind randomBinaryKind(Random random) {
    return BINARY_GATE_KINDS.get(random.nextInt(BINARY_GATE_KINDS.size()));
  }

  private static String canonical(Node node) {
    if (node.kind() == Kind.INPUT_PIN) return "INPUT";
    final var children = node.inputs().stream().map(NativeVisionDatasetTool::canonical).sorted().toList();
    return node.kind() + "(" + String.join(",", children) + ")";
  }

  private static Layout layout(Topology topology, int deltaX, int deltaY) {
    return switch (topology.family()) {
      case "diamond-fanout" -> diamondLayout(topology, deltaX, deltaY);
      case "layered-dag" -> layeredDagLayout(topology, deltaX, deltaY);
      default -> treeLayout(topology, deltaX, deltaY);
    };
  }

  private static Layout treeLayout(Topology topology, int deltaX, int deltaY) {
    final var anchors = new IdentityHashMap<Node, Location>();
    final var leaves = new ArrayList<Node>();
    collectLeaves(topology.root(), leaves, java.util.Collections.newSetFromMap(new IdentityHashMap<>()));
    for (var index = 0; index < leaves.size(); index++) {
      final var y =
          leaves.size() == 1
              ? HEIGHT / 2
              : snap(70.0 + index * (380.0 / (leaves.size() - 1)));
      anchors.put(leaves.get(index), Location.create(80 + deltaX, y + deltaY, true));
    }
    final var depth = new IdentityHashMap<Node, Integer>();
    final var rootDepth = depth(topology.root(), depth);
    final var gates = new ArrayList<Node>();
    collectGatesPostorder(
        topology.root(), gates, java.util.Collections.newSetFromMap(new IdentityHashMap<>()));
    for (final var gate : gates) {
      final var gateDepth = depth.get(gate);
      final var x =
          rootDepth == 1
              ? 520
              : snap(230.0 + (gateDepth - 1) * (360.0 / (rootDepth - 1)));
      final var y =
          snap(
              gate.inputs().stream()
                  .mapToInt(input -> anchors.get(input).getY())
                  .average()
                  .orElse(HEIGHT / 2.0));
      anchors.put(gate, Location.create(x + deltaX, y, true));
    }
    return new Layout(
        anchors,
        Location.create(730 + deltaX, anchors.get(topology.root()).getY(), true));
  }

  private static Layout diamondLayout(Topology topology, int deltaX, int deltaY) {
    final var anchors = new IdentityHashMap<Node, Location>();
    final var root = topology.root();
    final var upper = root.inputs().get(0);
    final var lower = root.inputs().get(1);
    final var shared = upper.inputs().get(0);
    final var upperAux = upper.inputs().get(1);
    final var lowerAux = lower.inputs().get(1);
    anchors.put(shared, Location.create(80 + deltaX, 260 + deltaY, true));
    anchors.put(upperAux, Location.create(260 + deltaX, 190 + deltaY, true));
    anchors.put(lowerAux, Location.create(260 + deltaX, 350 + deltaY, true));
    anchors.put(upper, Location.create(430 + deltaX, 180 + deltaY, true));
    anchors.put(lower, Location.create(430 + deltaX, 340 + deltaY, true));
    anchors.put(root, Location.create(650 + deltaX, 260 + deltaY, true));
    return new Layout(anchors, Location.create(750 + deltaX, 260 + deltaY, true));
  }

  private static Layout layeredDagLayout(Topology topology, int deltaX, int deltaY) {
    final var anchors = new IdentityHashMap<Node, Location>();
    final var secondMerge = topology.root();
    final var secondUpper = secondMerge.inputs().get(0);
    final var secondLower = secondMerge.inputs().get(1);
    final var firstMerge = secondUpper.inputs().get(0);
    final var secondUpperAux = secondUpper.inputs().get(1);
    final var secondLowerAux = secondLower.inputs().get(1);
    final var firstUpper = firstMerge.inputs().get(0);
    final var firstLower = firstMerge.inputs().get(1);
    final var shared = firstUpper.inputs().get(0);
    final var firstUpperAux = firstUpper.inputs().get(1);
    final var firstLowerAux = firstLower.inputs().get(1);

    anchors.put(shared, Location.create(40 + deltaX, 210 + deltaY, true));
    anchors.put(firstUpperAux, Location.create(110 + deltaX, 130 + deltaY, true));
    anchors.put(firstLowerAux, Location.create(110 + deltaX, 310 + deltaY, true));
    anchors.put(firstUpper, Location.create(220 + deltaX, 120 + deltaY, true));
    anchors.put(firstLower, Location.create(220 + deltaX, 300 + deltaY, true));
    anchors.put(firstMerge, Location.create(370 + deltaX, 210 + deltaY, true));
    anchors.put(secondUpperAux, Location.create(440 + deltaX, 140 + deltaY, true));
    anchors.put(secondLowerAux, Location.create(440 + deltaX, 320 + deltaY, true));
    anchors.put(secondUpper, Location.create(540 + deltaX, 130 + deltaY, true));
    anchors.put(secondLower, Location.create(540 + deltaX, 310 + deltaY, true));
    anchors.put(secondMerge, Location.create(680 + deltaX, 220 + deltaY, true));
    return new Layout(anchors, Location.create(760 + deltaX, 220 + deltaY, true));
  }

  private static Components createComponents(
      LogisimFile file, Topology topology, Layout layout, Random random) {
    final var ids = assignIds(topology.root());
    final var entries = new IdentityHashMap<Node, ComponentEntry>();
    final var ordered = orderedNodes(topology.root());
    var inputIndex = 0;
    for (final var node : ordered) {
      final var label = node.kind() == Kind.INPUT_PIN ? inputLabel(inputIndex++) : "";
      final var gateSize = randomGateSize(node.kind(), random);
      final var component =
          createComponent(file, node.kind(), layout.anchors().get(node), label, gateSize);
      entries.put(
          node,
          new ComponentEntry(
              ids.get(node),
              node.kind(),
              component,
              layout.anchors().get(node),
              label,
              gateSize));
    }
    final var output =
        createComponent(file, Kind.OUTPUT_PIN, layout.outputAnchor(), "Y", null);
    return new Components(
        entries,
        new ComponentEntry(
            "pin_out_001", Kind.OUTPUT_PIN, output, layout.outputAnchor(), "Y", null));
  }

  private static Component createComponent(
      LogisimFile file, Kind kind, Location anchor, String label, String gateSize) {
    final ComponentFactory factory;
    if (kind == Kind.INPUT_PIN || kind == Kind.OUTPUT_PIN) {
      factory = Pin.FACTORY;
    } else {
      factory = FactoryResolver.resolve(file, kind.factoryName());
    }
    final var attributes = factory.createAttributeSet();
    setIfPresent(attributes, StdAttr.FACING, Direction.EAST);
    setIfPresent(attributes, StdAttr.WIDTH, BitWidth.ONE);
    setIfPresent(attributes, StdAttr.LABEL, label);
    if (attributes.containsAttribute(Pin.ATTR_TYPE)) {
      attributes.setValue(Pin.ATTR_TYPE, kind == Kind.OUTPUT_PIN ? Pin.OUTPUT : Pin.INPUT);
    }
    if (attributes.containsAttribute(GateAttributes.ATTR_INPUTS)) {
      attributes.setValue(GateAttributes.ATTR_INPUTS, 2);
    }
    if (kind.isGate()) {
      final var size = attributes.getAttribute("size");
      if (size != null) setParsed(attributes, size, serializedGateSize(kind, gateSize));
    }
    return factory.createComponent(anchor, attributes);
  }

  private static String randomGateSize(Kind kind, Random random) {
    if (!kind.isGate()) return null;
    if (kind == Kind.NOT) return random.nextBoolean() ? "NARROW" : "WIDE";
    return switch (random.nextInt(3)) {
      case 0 -> "NARROW";
      case 1 -> "MEDIUM";
      default -> "WIDE";
    };
  }

  private static String serializedGateSize(Kind kind, String gateSize) {
    if (gateSize == null) throw new IllegalArgumentException("Gate size is required");
    if (kind == Kind.NOT) {
      return switch (gateSize) {
        case "NARROW" -> "20";
        case "WIDE" -> "30";
        default -> throw new IllegalArgumentException("Unsupported NOT size: " + gateSize);
      };
    }
    return switch (gateSize) {
      case "NARROW" -> "30";
      case "MEDIUM" -> "50";
      case "WIDE" -> "70";
      default -> throw new IllegalArgumentException("Unsupported gate size: " + gateSize);
    };
  }

  private static List<NetGeometry> route(Topology topology, Components components) {
    final var consumers = new IdentityHashMap<Node, List<Sink>>();
    for (final var sink : orderedNodes(topology.root())) {
      for (var index = 0; index < sink.inputs().size(); index++) {
        consumers.computeIfAbsent(sink.inputs().get(index), ignored -> new ArrayList<>())
            .add(new Sink(sink, index));
      }
    }
    consumers.computeIfAbsent(topology.root(), ignored -> new ArrayList<>())
        .add(new Sink(null, 0));

    final var nets = new ArrayList<NetGeometry>();
    var netIndex = 1;
    for (final var source : orderedNodes(topology.root())) {
      final var sinks = consumers.get(source);
      if (sinks == null || sinks.isEmpty()) continue;
      final var sourceEntry = components.byNode().get(source);
      final var sourcePoint = port(sourceEntry.component(), 0);
      final var sinkRefs = new ArrayList<Endpoint>();
      final var sinkPoints = new ArrayList<Location>();
      for (final var sink : sinks) {
        final var sinkEntry =
            sink.node() == null ? components.output() : components.byNode().get(sink.node());
        final var endIndex = sinkEntry.kind() == Kind.OUTPUT_PIN ? 0 : sink.inputIndex() + 1;
        sinkRefs.add(new Endpoint(sinkEntry.id(), portId(sinkEntry.kind(), endIndex)));
        sinkPoints.add(port(sinkEntry.component(), endIndex));
      }
      final var segments = new ArrayList<Line>();
      final var junctions = new ArrayList<Location>();
      if (sinkPoints.size() == 1) {
        segments.addAll(singleRoute(sourcePoint, sinkPoints.getFirst(), sinks.getFirst().inputIndex()));
      } else {
        final var junction = fanoutJunction(sourcePoint, sinkPoints);
        addLine(segments, sourcePoint, junction);
        final var minY = sinkPoints.stream().mapToInt(Location::getY).min().orElse(junction.getY());
        final var maxY = sinkPoints.stream().mapToInt(Location::getY).max().orElse(junction.getY());
        // Split the branch trunk at the T-junction. The circuit is electrically connected even
        // with one long vertical Wire, but explicit graph vertices make the topology truth
        // self-contained and directly consumable by the import planner.
        addLine(
            segments,
            Location.create(junction.getX(), minY, true),
            junction);
        addLine(
            segments,
            junction,
            Location.create(junction.getX(), maxY, true));
        for (final var sinkPoint : sinkPoints) {
          addLine(
              segments,
              Location.create(junction.getX(), sinkPoint.getY(), true),
              sinkPoint);
        }
        junctions.add(junction);
      }
      final var endpoints = new ArrayList<Endpoint>();
      endpoints.add(new Endpoint(sourceEntry.id(), "out"));
      endpoints.addAll(sinkRefs);
      nets.add(
          new NetGeometry(
              String.format(Locale.ROOT, "net_%03d", netIndex++),
              endpoints,
              uniqueLines(segments),
              junctions));
    }
    return nets;
  }

  private static Location fanoutJunction(Location source, List<Location> sinks) {
    final var nearestSinkX =
        sinks.stream().mapToInt(Location::getX).min().orElseThrow();
    final var available = nearestSinkX - source.getX();
    if (available < 3 * GRID) {
      throw new IllegalArgumentException("Fanout has insufficient horizontal routing space");
    }
    final var distance = Math.min(4 * GRID, Math.max(2 * GRID, snap(available / 3.0)));
    var junctionX = source.getX() + distance;
    if (junctionX >= nearestSinkX) junctionX = nearestSinkX - GRID;
    return Location.create(junctionX, source.getY(), true);
  }

  private static List<Line> singleRoute(Location start, Location end, int inputIndex) {
    if (start.getY() == end.getY()) return List.of(new Line(start, end));
    var turnX = end.getX() - 40 + inputIndex * 10;
    if (turnX <= start.getX()) turnX = snap((start.getX() + end.getX()) / 2.0);
    final var firstTurn = Location.create(turnX, start.getY(), true);
    final var secondTurn = Location.create(turnX, end.getY(), true);
    final var result = new ArrayList<Line>();
    addLine(result, start, firstTurn);
    addLine(result, firstTurn, secondTurn);
    addLine(result, secondTurn, end);
    return result;
  }

  private static void addLine(List<Line> lines, Location start, Location end) {
    if (!start.equals(end)) lines.add(new Line(start, end));
  }

  private static List<Line> uniqueLines(List<Line> lines) {
    final var unique = new LinkedHashMap<String, Line>();
    for (final var line : lines) {
      final var first = line.start().compareTo(line.end()) <= 0 ? line.start() : line.end();
      final var second = first == line.start() ? line.end() : line.start();
      unique.put(
          first.getX() + ":" + first.getY() + ":" + second.getX() + ":" + second.getY(),
          new Line(first, second));
    }
    return List.copyOf(unique.values());
  }

  private static VisionIr buildIr(
      VisionIr.Source source,
      String semanticHash,
      Topology topology,
      Components components,
      List<NetGeometry> nets) {
    final var detectedComponents = new ArrayList<VisionIr.DetectedComponent>();
    for (final var node : orderedNodes(topology.root())) {
      detectedComponents.add(detectedComponent(components.byNode().get(node)));
    }
    detectedComponents.add(detectedComponent(components.output()));
    final var detectedNets =
        nets.stream()
            .map(
                net ->
                    new VisionIr.DetectedNet(
                        net.id(),
                        net.endpoints().stream()
                            .map(endpoint -> new VisionIr.NetEndpoint(endpoint.componentId(), endpoint.portId()))
                            .toList(),
                        net.lines().stream()
                            .map(
                                line ->
                                    new VisionIr.Segment(
                                        point(line.start()), point(line.end())))
                            .toList(),
                        net.junctions().stream().map(NativeVisionDatasetTool::point).toList(),
                        1.0))
            .toList();
    return new VisionIr(
        VisionIr.VERSION,
        source,
        new VisionIr.Profile("light", "ansi", GRID, 1.0, 0.0, 0.0),
        detectedComponents,
        detectedNets,
        List.of(),
        semanticHash);
  }

  private static VisionIr.DetectedComponent detectedComponent(ComponentEntry entry) {
    final var bounds = entry.component().getBounds();
    final var attributes = new LinkedHashMap<String, Object>();
    attributes.put("bitWidth", 1);
    if (!entry.label().isBlank()) attributes.put("label", entry.label());
    if (entry.kind().isGate()) {
      attributes.put("inputs", entry.kind() == Kind.NOT ? 1 : 2);
      attributes.put("size", entry.gateSize());
    }
    final var ports = new ArrayList<VisionIr.DetectedPort>();
    for (var index = 0; index < entry.component().getEnds().size(); index++) {
      final var end = entry.component().getEnd(index);
      ports.add(
          new VisionIr.DetectedPort(
              portId(entry.kind(), index),
              end.isOutput() ? VisionIr.PortDirection.OUTPUT : VisionIr.PortDirection.INPUT,
              point(end.getLocation())));
    }
    return new VisionIr.DetectedComponent(
        entry.id(),
        entry.kind().irKind(),
        new VisionIr.Box(
            bounds.getX(),
            bounds.getY(),
            bounds.getX() + bounds.getWidth(),
            bounds.getY() + bounds.getHeight()),
        point(entry.anchor()),
        VisionIr.Facing.EAST,
        attributes,
        ports,
        1.0);
  }

  private static byte[] renderPng(
      Project project,
      Circuit circuit,
      Random random,
      RenderProfile renderProfile) {
    final var image = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_RGB);
    final var graphics = image.createGraphics();
    graphics.setColor(new Color(renderProfile.backgroundRgb()));
    graphics.fillRect(0, 0, WIDTH, HEIGHT);
    if (renderProfile.gridVisible()) {
      graphics.setColor(new Color(renderProfile.gridRgb()));
      final var gridOffsetX = random.nextBoolean() ? 0 : 5;
      final var gridOffsetY = random.nextBoolean() ? 0 : 5;
      for (var x = gridOffsetX; x < WIDTH; x += GRID) {
        for (var y = gridOffsetY; y < HEIGHT; y += GRID) graphics.fillRect(x, y, 1, 1);
      }
    }
    final var antialiasValue =
        renderProfile.antialias()
            ? RenderingHints.VALUE_ANTIALIAS_ON
            : RenderingHints.VALUE_ANTIALIAS_OFF;
    final var textAntialiasValue =
        renderProfile.antialias()
            ? RenderingHints.VALUE_TEXT_ANTIALIAS_ON
            : RenderingHints.VALUE_TEXT_ANTIALIAS_OFF;
    graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, antialiasValue);
    graphics.setRenderingHint(
        RenderingHints.KEY_TEXT_ANTIALIASING, textAntialiasValue);

    final var state = CircuitState.createRootState(project, circuit);
    final var context =
        new ComponentDrawContext(null, circuit, state, graphics, graphics, false) {
          @Override
          public Object getGateShape() {
            return AppPreferences.SHAPE_SHAPED;
          }
        };
    context.setShowColor(true);
    // Dataset generation runs outside the simulator propagation thread. Native shapes and wires
    // are rendered without live signal colors; signal-color augmentation is a later render variant.
    context.setShowState(false);
    circuit.draw(context, null);
    graphics.dispose();
    final var rendered =
        renderProfile.resampleScale() == 1.0
            ? image
            : resample(image, renderProfile.resampleScale());
    try {
      final var output = new ByteArrayOutputStream();
      if (!ImageIO.write(rendered, "png", output)) {
        throw new IllegalStateException("No PNG ImageIO writer is installed");
      }
      return output.toByteArray();
    } catch (IOException e) {
      throw new IllegalStateException("Could not encode generated PNG", e);
    }
  }

  private static BufferedImage resample(BufferedImage source, double scale) {
    final var scaledWidth = Math.max(1, (int) Math.round(WIDTH * scale));
    final var scaledHeight = Math.max(1, (int) Math.round(HEIGHT * scale));
    final var scaled =
        new BufferedImage(scaledWidth, scaledHeight, BufferedImage.TYPE_INT_RGB);
    final var downGraphics = scaled.createGraphics();
    downGraphics.setRenderingHint(
        RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
    downGraphics.drawImage(source, 0, 0, scaledWidth, scaledHeight, null);
    downGraphics.dispose();

    final var restored = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_RGB);
    final var upGraphics = restored.createGraphics();
    upGraphics.setRenderingHint(
        RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
    upGraphics.drawImage(scaled, 0, 0, WIDTH, HEIGHT, null);
    upGraphics.dispose();
    return restored;
  }

  private static String yolo(VisionIr ir) {
    final var result = new StringBuilder();
    for (final var component : ir.components()) {
      final var box = component.bodyBox();
      final var centerX = ((box.x0() + box.x1()) / 2.0) / WIDTH;
      final var centerY = ((box.y0() + box.y1()) / 2.0) / HEIGHT;
      final var width = (box.x1() - box.x0()) / WIDTH;
      final var height = (box.y1() - box.y0()) / HEIGHT;
      result.append(
          String.format(
              Locale.ROOT,
              "%d %.8f %.8f %.8f %.8f%n",
              CLASS_IDS.get(Kind.valueOf(component.kind().name())),
              centerX,
              centerY,
              width,
              height));
    }
    return result.toString();
  }

  private static Map<String, Object> validateDataset(Path dataset) throws IOException {
    final var root = dataset.toAbsolutePath().normalize();
    final var manifestPath = root.resolve("manifest.jsonl");
    if (!Files.isRegularFile(manifestPath)) {
      throw new IllegalArgumentException("Missing manifest: " + manifestPath);
    }
    final var lines = Files.readAllLines(manifestPath, StandardCharsets.UTF_8);
    if (lines.stream().allMatch(String::isBlank)) {
      throw new IllegalArgumentException("Dataset manifest is empty");
    }
    final var semanticSplits = new LinkedHashMap<String, String>();
    final var splitCounts = new LinkedHashMap<String, Long>();
    final var classCounts = new LinkedHashMap<String, Long>();
    final var gateSizeCounts = new LinkedHashMap<String, Long>();
    final var familySampleCounts = new LinkedHashMap<String, Long>();
    final var renderProfileSampleCounts = new LinkedHashMap<String, Long>();
    final var topologyVariants = new LinkedHashMap<String, Integer>();
    final var renderAwareHashes = new HashSet<String>();
    final var baselineHashes = new HashSet<String>();
    final var datasetRoles = new HashSet<String>();
    var junctionCount = 0L;
    var sampleCount = 0;
    for (final var line : lines) {
      if (line.isBlank()) continue;
      sampleCount++;
      final var record = SimpleJson.parseObject(line);
      final var datasetRole =
          record.get("datasetRole") == null
              ? "training"
              : requiredString(record, "datasetRole");
      if (!Set.of("training", "evaluation").contains(datasetRole)) {
        throw new IllegalArgumentException("Invalid dataset role: " + datasetRole);
      }
      datasetRoles.add(datasetRole);
      final var split = requiredString(record, "split");
      if (!Set.of("train", "val", "test").contains(split)) {
        throw new IllegalArgumentException("Invalid split: " + split);
      }
      final var semanticHash = requiredString(record, "semanticHash");
      final var expectedSplit = datasetRole.equals("evaluation") ? "test" : splitForHash(semanticHash);
      if (!expectedSplit.equals(split)) {
        throw new IllegalArgumentException("Split does not match semantic hash: " + semanticHash);
      }
      final var oldSplit = semanticSplits.putIfAbsent(semanticHash, split);
      if (oldSplit != null && !oldSplit.equals(split)) {
        throw new IllegalArgumentException(
            "Semantic topology appears in multiple splits: " + semanticHash);
      }
      topologyVariants.merge(semanticHash, 1, Integer::sum);
      final var imagePath = safeResolve(root, requiredString(record, "image"));
      final var labelPath = safeResolve(root, requiredString(record, "label"));
      final var irPath = safeResolve(root, requiredString(record, "ir"));
      for (final var path : List.of(imagePath, labelPath, irPath)) {
        if (!Files.isRegularFile(path)) throw new IllegalArgumentException("Missing file: " + path);
      }
      final var imageBytes = Files.readAllBytes(imagePath);
      final var imageHash = sha256(imageBytes);
      if (!imageHash.equals(requiredString(record, "imageSha256"))) {
        throw new IllegalArgumentException("Image hash mismatch: " + imagePath);
      }
      final var image = ImageIO.read(new ByteArrayInputStream(imageBytes));
      if (image == null || image.getWidth() != WIDTH || image.getHeight() != HEIGHT) {
        throw new IllegalArgumentException("Invalid image dimensions: " + imagePath);
      }
      final var ir = VisionIrCodec.fromJson(Files.readString(irPath, StandardCharsets.UTF_8));
      VisionIrValidator.validateOrThrow(ir);
      for (final var component : ir.components()) {
        final var gateSize = component.attributes().get("size");
        if (gateSize instanceof String size) gateSizeCounts.merge(size, 1L, Long::sum);
      }
      if (!semanticHash.equals(ir.semanticHash()) || !imageHash.equals(ir.source().sha256())) {
        throw new IllegalArgumentException("Manifest and Vision IR disagree: " + irPath);
      }
      final var family = requiredString(record, "family");
      final var variant = requiredInt(record, "variant");
      final var renderProfileValue = record.get("renderProfile");
      if (renderProfileValue == null) {
        renderProfileSampleCounts.merge("legacy-native", 1L, Long::sum);
      } else {
        final var renderProfileName = requiredString(record, "renderProfile");
        final var renderProfile = knownRenderProfile(renderProfileName);
        if (renderProfile == null) {
          throw new IllegalArgumentException("Unknown render profile: " + renderProfileName);
        }
        final var renderSettings = record.get("renderSettings");
        if (!(renderSettings instanceof Map<?, ?>)
            || !SimpleJson.stringify(renderSettings)
                .equals(SimpleJson.stringify(renderProfile.manifestValue()))) {
          throw new IllegalArgumentException(
              "Render settings disagree with profile " + renderProfileName + ": " + imagePath);
        }
        if (variant == 0 && renderProfile != NATIVE_RENDER_PROFILE) {
          throw new IllegalArgumentException(
              "Variant zero must use native-grid: " + imagePath);
        }
        if (variant > 0 && renderProfile == NATIVE_RENDER_PROFILE) {
          throw new IllegalArgumentException(
              "Augmented variants must not use native-grid: " + imagePath);
        }
        renderAwareHashes.add(semanticHash);
        if (variant == 0) baselineHashes.add(semanticHash);
        renderProfileSampleCounts.merge(renderProfileName, 1L, Long::sum);
      }
      final var actualJunctionCount =
          ir.nets().stream().mapToInt(net -> net.junctions().size()).sum();
      if (requiredInt(record, "componentCount") != ir.components().size()
          || requiredInt(record, "netCount") != ir.nets().size()
          || requiredInt(record, "junctionCount") != actualJunctionCount) {
        throw new IllegalArgumentException("Manifest counts disagree with Vision IR: " + irPath);
      }
      final var expectedJunctionCount =
          switch (family) {
            case "tree" -> 0;
            case "diamond-fanout" -> 1;
            case "layered-dag" -> 2;
            default -> -1;
          };
      if (expectedJunctionCount >= 0 && actualJunctionCount != expectedJunctionCount) {
        throw new IllegalArgumentException(
            "Unexpected junction count for " + family + ": " + irPath);
      }
      familySampleCounts.merge(family, 1L, Long::sum);
      junctionCount += actualJunctionCount;
      final var labels = Files.readAllLines(labelPath, StandardCharsets.UTF_8);
      if (labels.size() != ir.components().size()) {
        throw new IllegalArgumentException("YOLO/component count mismatch: " + labelPath);
      }
      for (var index = 0; index < labels.size(); index++) {
        final var fields = labels.get(index).trim().split("\\s+");
        if (fields.length != 5) throw new IllegalArgumentException("Invalid YOLO row: " + labelPath);
        final var classId = Integer.parseInt(fields[0]);
        if (classId < 0 || classId >= CLASS_KINDS.size()) {
          throw new IllegalArgumentException("Invalid YOLO class: " + labelPath);
        }
        final var expectedKind = ir.components().get(index).kind().name();
        if (!CLASS_KINDS.get(classId).name().equals(expectedKind)) {
          throw new IllegalArgumentException(
              "YOLO class does not match Vision IR component " + index + ": " + labelPath);
        }
        for (var field = 1; field < fields.length; field++) {
          final var value = Double.parseDouble(fields[field]);
          if (!Double.isFinite(value) || value < 0.0 || value > 1.0) {
            throw new IllegalArgumentException("Invalid YOLO coordinate: " + labelPath);
          }
        }
        classCounts.merge(CLASS_KINDS.get(classId).name(), 1L, Long::sum);
      }
      splitCounts.merge(split, 1L, Long::sum);
    }
    final var variants = new HashSet<>(topologyVariants.values());
    if (variants.size() != 1) {
      throw new IllegalArgumentException("Topologies do not have a uniform variant count");
    }
    if (!renderAwareHashes.equals(baselineHashes)) {
      throw new IllegalArgumentException("A render-aware topology is missing its native baseline");
    }
    if (datasetRoles.size() != 1) {
      throw new IllegalArgumentException("A dataset cannot mix training and evaluation records");
    }
    final var result = new LinkedHashMap<String, Object>();
    result.put("status", "OK");
    result.put("datasetRole", datasetRoles.iterator().next());
    result.put("samples", sampleCount);
    result.put("uniqueSemanticHashes", semanticSplits.size());
    result.put("variantsPerTopology", variants.iterator().next());
    result.put("splits", splitCounts);
    result.put("familySamples", familySampleCounts);
    result.put("renderProfileSamples", renderProfileSampleCounts);
    result.put("junctions", junctionCount);
    result.put("classes", classCounts);
    result.put("gateSizes", gateSizeCounts);
    return result;
  }

  private static Path safeResolve(Path root, String relative) {
    final var result = root.resolve(relative).normalize();
    if (!result.startsWith(root)) throw new IllegalArgumentException("Path escapes dataset: " + relative);
    return result;
  }

  private static void createDatasetDirectories(Path root) throws IOException {
    for (final var category : List.of("images", "labels", "ir")) {
      for (final var split : List.of("train", "val", "test")) {
        Files.createDirectories(root.resolve(category).resolve(split));
      }
    }
  }

  private static Exclusion loadExclusion(Path dataset) throws IOException {
    if (dataset == null) return new Exclusion(Set.of(), null);
    final var normalized = dataset.toAbsolutePath().normalize();
    final var manifest =
        Files.isDirectory(normalized) ? normalized.resolve("manifest.jsonl") : normalized;
    if (!Files.isRegularFile(manifest)) {
      throw new IllegalArgumentException("Missing exclusion manifest: " + manifest);
    }
    final var bytes = Files.readAllBytes(manifest);
    final var hashes = new LinkedHashSet<String>();
    for (final var line : Files.readAllLines(manifest, StandardCharsets.UTF_8)) {
      if (!line.isBlank()) {
        hashes.add(requiredString(SimpleJson.parseObject(line), "semanticHash"));
      }
    }
    if (hashes.isEmpty()) {
      throw new IllegalArgumentException("Exclusion manifest is empty: " + manifest);
    }
    return new Exclusion(Set.copyOf(hashes), sha256(bytes));
  }

  private static String dataYaml(boolean evaluation) {
    final var result = new StringBuilder("path: .\n");
    if (evaluation) {
      result.append("test: images/test\n");
    } else {
      result.append("train: images/train\nval: images/val\ntest: images/test\n");
    }
    result.append("names:\n");
    for (var index = 0; index < CLASS_KINDS.size(); index++) {
      result.append("  ").append(index).append(": ").append(CLASS_KINDS.get(index)).append('\n');
    }
    return result.toString();
  }

  private static String prettyJson(Map<String, Object> value) {
    // The manifest is compact JSONL; summaries remain readable without adding a JSON dependency.
    return pretty(SimpleJson.stringify(value));
  }

  private static String pretty(String json) {
    final var output = new StringBuilder();
    var indent = 0;
    var quoted = false;
    var escaped = false;
    for (var index = 0; index < json.length(); index++) {
      final var character = json.charAt(index);
      if (quoted) {
        output.append(character);
        if (escaped) escaped = false;
        else if (character == '\\') escaped = true;
        else if (character == '"') quoted = false;
        continue;
      }
      switch (character) {
        case '"' -> {
          quoted = true;
          output.append(character);
        }
        case '{', '[' -> {
          output.append(character).append('\n');
          indent++;
          output.append("  ".repeat(indent));
        }
        case '}', ']' -> {
          output.append('\n');
          indent--;
          output.append("  ".repeat(indent)).append(character);
        }
        case ',' -> output.append(character).append('\n').append("  ".repeat(indent));
        case ':' -> output.append(": ");
        default -> output.append(character);
      }
    }
    return output.toString();
  }

  private static String splitForHash(String hash) {
    final var bucket = Integer.parseInt(hash.substring(0, 2), 16) * 100 / 256;
    if (bucket < 70) return "train";
    return bucket < 85 ? "val" : "test";
  }

  private static int depth(Node node, IdentityHashMap<Node, Integer> depths) {
    final var existing = depths.get(node);
    if (existing != null) return existing;
    final var result =
        node.kind() == Kind.INPUT_PIN
            ? 0
            : node.inputs().stream().mapToInt(input -> depth(input, depths)).max().orElse(0) + 1;
    depths.put(node, result);
    return result;
  }

  private static void collectLeaves(Node node, List<Node> leaves, Set<Node> visited) {
    if (!visited.add(node)) return;
    if (node.kind() == Kind.INPUT_PIN) leaves.add(node);
    else for (final var input : node.inputs()) collectLeaves(input, leaves, visited);
  }

  private static void collectGatesPostorder(Node node, List<Node> gates, Set<Node> visited) {
    if (!visited.add(node)) return;
    for (final var input : node.inputs()) collectGatesPostorder(input, gates, visited);
    if (node.kind() != Kind.INPUT_PIN) gates.add(node);
  }

  private static List<Node> orderedNodes(Node root) {
    final var nodes = new ArrayList<Node>();
    collectNodesPostorder(
        root, nodes, java.util.Collections.newSetFromMap(new IdentityHashMap<>()));
    final var ordered = new ArrayList<Node>();
    nodes.stream().filter(node -> node.kind() == Kind.INPUT_PIN).forEach(ordered::add);
    nodes.stream().filter(node -> node.kind() != Kind.INPUT_PIN).forEach(ordered::add);
    return ordered;
  }

  private static void collectNodesPostorder(Node node, List<Node> nodes, Set<Node> visited) {
    if (!visited.add(node)) return;
    for (final var input : node.inputs()) collectNodesPostorder(input, nodes, visited);
    nodes.add(node);
  }

  private static IdentityHashMap<Node, String> assignIds(Node root) {
    final var result = new IdentityHashMap<Node, String>();
    var inputIndex = 1;
    var gateIndex = 1;
    for (final var node : orderedNodes(root)) {
      if (node.kind() == Kind.INPUT_PIN) {
        result.put(node, String.format(Locale.ROOT, "pin_in_%03d", inputIndex++));
      } else {
        result.put(node, String.format(Locale.ROOT, "gate_%03d", gateIndex++));
      }
    }
    return result;
  }

  private static String inputLabel(int index) {
    return index < 26 ? Character.toString((char) ('A' + index)) : "I" + (index + 1);
  }

  private static Location port(Component component, int index) {
    return component.getEnd(index).getLocation();
  }

  private static String portId(Kind kind, int index) {
    if (kind == Kind.INPUT_PIN) return "out";
    if (kind == Kind.OUTPUT_PIN) return "in";
    return index == 0 ? "out" : "in" + (index - 1);
  }

  private static VisionIr.Point point(Location location) {
    return new VisionIr.Point(location.getX(), location.getY());
  }

  private static int snap(double value) {
    return (int) Math.round(value / GRID) * GRID;
  }

  private static String slash(Path path) {
    return path.toString().replace('\\', '/');
  }

  private static String requiredString(Map<String, Object> map, String key) {
    final var value = map.get(key);
    if (value instanceof String text && !text.isBlank()) return text;
    throw new IllegalArgumentException("Manifest field is missing or invalid: " + key);
  }

  private static int requiredInt(Map<String, Object> map, String key) {
    final var value = map.get(key);
    if (value instanceof Number number
        && number.doubleValue() == number.intValue()) {
      return number.intValue();
    }
    throw new IllegalArgumentException("Manifest field is missing or invalid: " + key);
  }

  private static String safeMessage(Exception exception) {
    final var message = exception.getMessage();
    return message == null || message.isBlank() ? exception.getClass().getSimpleName() : message;
  }

  private static String sha256(byte[] bytes) {
    try {
      return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is not available", e);
    }
  }

  private static Map<Kind, Integer> classIds() {
    final var result = new LinkedHashMap<Kind, Integer>();
    for (var index = 0; index < CLASS_KINDS.size(); index++) {
      result.put(CLASS_KINDS.get(index), index);
    }
    return Map.copyOf(result);
  }

  private static <T> void setIfPresent(AttributeSet attributes, Attribute<T> attribute, T value) {
    if (attributes.containsAttribute(attribute)) attributes.setValue(attribute, value);
  }

  private static <T> void setParsed(
      AttributeSet attributes, Attribute<T> attribute, String serializedValue) {
    attributes.setValue(attribute, attribute.parse(serializedValue));
  }

  private static void printUsage() {
    System.out.println(
        """
        Native Logisim circuit-vision dataset tool

        Generate:
          gradlew generateVisionDataset --args="--output PATH [--topologies N] [--variants N]
              [--seed N] [--evaluation] [--exclude-dataset PATH]"

        Validate:
          gradlew generateVisionDataset --args="--validate-only PATH"

        The output path must not already exist. Semantic hashes, not rendered images, determine
        the train/val/test split, so all variants of one topology stay in the same split.
        Evaluation datasets place every sample in test. Exclusion accepts a dataset directory
        or manifest.jsonl and prevents those semantic topologies from being generated.
        """);
  }

  private enum Kind {
    INPUT_PIN,
    OUTPUT_PIN,
    AND,
    OR,
    NOT,
    NAND,
    NOR,
    XOR,
    XNOR;

    boolean isGate() {
      return this != INPUT_PIN && this != OUTPUT_PIN;
    }

    String factoryName() {
      return switch (this) {
        case AND -> "AND Gate";
        case OR -> "OR Gate";
        case NOT -> "NOT Gate";
        case NAND -> "NAND Gate";
        case NOR -> "NOR Gate";
        case XOR -> "XOR Gate";
        case XNOR -> "XNOR Gate";
        default -> throw new IllegalStateException("Pins do not have gate factory names");
      };
    }

    VisionIr.ComponentKind irKind() {
      return VisionIr.ComponentKind.valueOf(name());
    }
  }

  private record Node(Kind kind, List<Node> inputs) {
    Node {
      inputs = List.copyOf(inputs);
      if (kind == Kind.INPUT_PIN && !inputs.isEmpty()) {
        throw new IllegalArgumentException("Input pins cannot have inputs");
      }
      if (kind == Kind.NOT && inputs.size() != 1) {
        throw new IllegalArgumentException("NOT must have one input");
      }
      if (BINARY_GATE_KINDS.contains(kind) && inputs.size() != 2) {
        throw new IllegalArgumentException(kind + " must have two inputs");
      }
    }
  }

  private record Topology(Node root, String family, String signature) {}

  private record Layout(IdentityHashMap<Node, Location> anchors, Location outputAnchor) {}

  private record ComponentEntry(
      String id,
      Kind kind,
      Component component,
      Location anchor,
      String label,
      String gateSize) {}

  private record Components(
      IdentityHashMap<Node, ComponentEntry> byNode, ComponentEntry output) {}

  private record Sink(Node node, int inputIndex) {}

  private record Endpoint(String componentId, String portId) {}

  private record Line(Location start, Location end) {
    Line {
      if (start.equals(end)) throw new IllegalArgumentException("Wire cannot have zero length");
      if (start.getX() != end.getX() && start.getY() != end.getY()) {
        throw new IllegalArgumentException("Wire must be horizontal or vertical");
      }
    }
  }

  private record NetGeometry(
      String id, List<Endpoint> endpoints, List<Line> lines, List<Location> junctions) {
    NetGeometry {
      endpoints = List.copyOf(endpoints);
      lines = List.copyOf(lines);
      junctions = List.copyOf(junctions);
    }
  }

  private record RenderProfile(
      String name,
      boolean gridVisible,
      int backgroundRgb,
      int gridRgb,
      boolean antialias,
      double resampleScale) {
    RenderProfile {
      if (name == null || name.isBlank()) {
        throw new IllegalArgumentException("Render profile name is required");
      }
      if (resampleScale <= 0.0 || resampleScale > 1.0) {
        throw new IllegalArgumentException("Render resample scale must be in (0, 1]");
      }
    }

    Map<String, Object> manifestValue() {
      final var value = new LinkedHashMap<String, Object>();
      value.put("gridVisible", gridVisible);
      value.put("background", String.format(Locale.ROOT, "#%06X", backgroundRgb));
      value.put("grid", String.format(Locale.ROOT, "#%06X", gridRgb));
      value.put("antialias", antialias);
      value.put("resampleScale", resampleScale);
      return value;
    }
  }

  private record RenderedSample(
      long seed,
      int variant,
      byte[] png,
      String yolo,
      VisionIr ir,
      RenderProfile renderProfile) {}

  private record Exclusion(Set<String> semanticHashes, String manifestSha256) {}

  private record Options(
      Path output,
      Path validateOnly,
      Path excludeDataset,
      int topologies,
      int variants,
      long seed,
      boolean evaluation,
      boolean help) {
    static Options parse(String[] args) {
      Path output = null;
      Path validateOnly = null;
      Path excludeDataset = null;
      var topologies = DEFAULT_TOPOLOGIES;
      var variants = DEFAULT_VARIANTS;
      var seed = DEFAULT_SEED;
      var evaluation = false;
      var help = false;
      for (var index = 0; index < args.length; index++) {
        final var argument = args[index];
        switch (argument) {
          case "--output" -> output = Path.of(value(args, ++index, argument));
          case "--validate-only" -> validateOnly = Path.of(value(args, ++index, argument));
          case "--exclude-dataset" ->
              excludeDataset = Path.of(value(args, ++index, argument));
          case "--topologies" -> topologies = Integer.parseInt(value(args, ++index, argument));
          case "--variants" -> variants = Integer.parseInt(value(args, ++index, argument));
          case "--seed" -> seed = Long.parseLong(value(args, ++index, argument));
          case "--evaluation" -> evaluation = true;
          case "--help", "-h" -> help = true;
          default -> throw new IllegalArgumentException("Unknown argument: " + argument);
        }
      }
      if (!help && (output == null) == (validateOnly == null)) {
        throw new IllegalArgumentException("Use exactly one of --output or --validate-only");
      }
      if (!help && validateOnly != null && (excludeDataset != null || evaluation)) {
        throw new IllegalArgumentException(
            "--evaluation and --exclude-dataset can only be used while generating");
      }
      return new Options(
          output, validateOnly, excludeDataset, topologies, variants, seed, evaluation, help);
    }

    private static String value(String[] args, int index, String option) {
      if (index >= args.length) throw new IllegalArgumentException("Missing value for " + option);
      return args[index];
    }
  }

  private static final class NativeContext implements AutoCloseable {
    private final Loader loader;
    private final LogisimFile file;
    private final Project project;

    NativeContext() {
      loader = new Loader(null);
      AppPreferences.AUTOSAVE_ENABLED.set(false);
      AppPreferences.NEW_INPUT_OUTPUT_SHAPES.set(true);
      AppPreferences.PinAppearance.set(AppPreferences.PIN_APPEAR_DOT_SMALL);
      AppPreferences.COMPONENT_COLOR.set(AppPreferences.DEFAULT_COMPONENT_COLOR);
      AppPreferences.GATE_SHAPE.set(AppPreferences.SHAPE_SHAPED);
      file = LogisimFile.createNew(loader, null);
      file.addLibrary(loader.loadLibrary("#Wiring"));
      file.addLibrary(loader.loadLibrary("#Gates"));
      project = new Project(file);
      file.getCircuits().forEach(circuit -> circuit.setProject(project));
    }

    LogisimFile file() {
      return file;
    }

    Project project() {
      return project;
    }

    @Override
    public void close() {
      project.getSimulator().shutDown();
      file.stopAutosaveThread(false);
    }
  }
}
