"""Component-port matching and Vision IR assembly for fixed-scale screenshots."""

from __future__ import annotations

from collections import Counter, defaultdict
from dataclasses import dataclass
from hashlib import sha256
import json
from pathlib import Path
from typing import Iterable

from PIL import Image

from .template_detector import FixedScaleTemplateDetector, TemplateDetection
from .wire_extractor import GridProfile, OrthogonalWireExtractor, WireExtraction


SCHEMA_VERSION = "vision-ir/0.1"


@dataclass(frozen=True)
class _Port:
    component_id: str
    id: str
    direction: str
    node: tuple[int, int]


@dataclass(frozen=True)
class _Component:
    id: str
    detection: TemplateDetection
    anchor: tuple[int, int]
    ports: tuple[_Port, ...]
    gate_size: str | None


class FixedScaleTopologyRecognizer:
    """Build validated-shape Vision IR without silently accepting bad topology."""

    def __init__(
        self,
        component_detector: FixedScaleTemplateDetector | None = None,
        wire_extractor: OrthogonalWireExtractor | None = None,
    ) -> None:
        self.component_detector = component_detector or FixedScaleTemplateDetector()
        self.wire_extractor = wire_extractor or OrthogonalWireExtractor()

    def recognize_path(self, path: Path) -> dict[str, object]:
        image_bytes = path.read_bytes()
        with Image.open(path) as image:
            return self.recognize(image, sha256(image_bytes).hexdigest())

    def recognize(
        self,
        image: Image.Image,
        source_sha256: str,
        detections: Iterable[TemplateDetection] | None = None,
        profile: GridProfile | None = None,
    ) -> dict[str, object]:
        if detections is None:
            if profile is None and hasattr(self.component_detector, "detect_scaled"):
                try:
                    profile = self.wire_extractor.estimate_grid(image)
                except ValueError:
                    # Gridless native renders intentionally use the fixed
                    # native profile selected later by the wire extractor.
                    pass
            if profile is not None and hasattr(self.component_detector, "detect_scaled"):
                detected = list(
                    self.component_detector.detect_scaled(image, profile.scale)
                )
            else:
                detected = list(self.component_detector.detect(image))
        else:
            detected = list(detections)
        extraction = self.wire_extractor.extract(image, detected, profile)
        components = self._components(
            detected,
            extraction.profile,
            extraction.node_regions,
        )
        return self._assemble_ir(image, source_sha256, components, extraction)

    @staticmethod
    def _components(
        detections: list[TemplateDetection],
        profile: GridProfile,
        node_regions: dict[tuple[int, int], str] | None = None,
    ) -> list[_Component]:
        counters: Counter[str] = Counter()
        result: list[_Component] = []
        for detection in sorted(
            detections, key=lambda item: (item.body_box[0], item.body_box[1], item.kind)
        ):
            counters[detection.kind] += 1
            component_id = f"{detection.kind.lower()}_{counters[detection.kind]:03d}"
            anchor, raw_ports, gate_size = FixedScaleTopologyRecognizer._port_geometry(
                detection, profile
            )
            if (
                detection.kind == "OUTPUT_PIN"
                and node_regions is not None
                and anchor not in node_regions
            ):
                for distance in range(1, 4):
                    candidate = (anchor[0] - distance, anchor[1])
                    if candidate in node_regions:
                        anchor = candidate
                        raw_ports = [("in", "INPUT", candidate)]
                        break
            ports = tuple(
                _Port(component_id, port_id, direction, node)
                for port_id, direction, node in raw_ports
            )
            result.append(_Component(component_id, detection, anchor, ports, gate_size))
        return result

    @staticmethod
    def _port_geometry(
        detection: TemplateDetection, profile: GridProfile
    ) -> tuple[
        tuple[int, int],
        list[tuple[str, str, tuple[int, int]]],
        str | None,
    ]:
        x0, y0, x1, y1 = detection.body_box
        center_y = (y0 + y1) / 2.0
        if detection.kind == "OUTPUT_PIN":
            anchor = profile.nearest_node(
                x1 if profile.native_v04 else x0,
                center_y,
            )
            return anchor, [("in", "INPUT", anchor)], None

        anchor = profile.nearest_node(x1, center_y)
        if detection.kind == "INPUT_PIN":
            return anchor, [("out", "OUTPUT", anchor)], None
        if profile.native_v04:
            input_x = profile.nearest_node(x0, center_y)[0]
            if detection.kind == "NOT":
                gate_size = detection.gate_size or FixedScaleTopologyRecognizer._not_size(
                    (x1 - x0) / profile.scale
                )
                return (
                    anchor,
                    [("out", "OUTPUT", anchor), ("in0", "INPUT", (input_x, anchor[1]))],
                    gate_size,
                )
            gate_size = detection.gate_size or FixedScaleTopologyRecognizer._gate_size(
                (y1 - y0) / profile.scale
            )
            input_steps = {"NARROW": 1, "MEDIUM": 2, "WIDE": 2}[gate_size]
            top_input = (input_x, anchor[1] - input_steps)
            bottom_input = (input_x, anchor[1] + input_steps)
            return (
                anchor,
                [
                    ("out", "OUTPUT", anchor),
                    ("in0", "INPUT", top_input),
                    ("in1", "INPUT", bottom_input),
                ],
                gate_size,
            )
        if detection.kind == "NOT":
            gate_size = detection.gate_size or FixedScaleTopologyRecognizer._not_size(
                (x1 - x0) / profile.scale
            )
            input_node = (
                anchor[0] - (2 if gate_size == "NARROW" else 3),
                anchor[1],
            )
            return (
                anchor,
                [("out", "OUTPUT", anchor), ("in0", "INPUT", input_node)],
                gate_size,
            )

        gate_size = detection.gate_size or FixedScaleTopologyRecognizer._gate_size(
            (y1 - y0) / profile.scale
        )
        inverted = detection.kind in ("NAND", "NOR", "XNOR")
        axis_steps = {"NARROW": 3, "MEDIUM": 5, "WIDE": 7}[gate_size]
        if inverted:
            axis_steps += 1
        if detection.kind in ("XOR", "XNOR"):
            # The extra input-side curve makes XOR one grid unit wider than OR.
            # XNOR likewise remains one unit wider than NOR; the output bubble is
            # already accounted for by the inverted-gate width calculation.
            axis_steps += 1
        # Logisim keeps two-input WIDE gates at the same +/-20-unit input
        # separation as MEDIUM gates; the additional height is body margin.
        input_steps = {"NARROW": 1, "MEDIUM": 2, "WIDE": 2}[gate_size]
        return (
            anchor,
            [
                ("out", "OUTPUT", anchor),
                ("in0", "INPUT", (anchor[0] - axis_steps, anchor[1] - input_steps)),
                ("in1", "INPUT", (anchor[0] - axis_steps, anchor[1] + input_steps)),
            ],
            gate_size,
        )

    @staticmethod
    def _gate_size(logical_height: float) -> str:
        """Select the nearest legal multi-input Logisim gate size."""

        return min(
            ((30.0, "NARROW"), (50.0, "MEDIUM"), (70.0, "WIDE")),
            key=lambda item: abs(logical_height - item[0]),
        )[1]

    @staticmethod
    def _not_size(logical_width: float) -> str:
        """NOT supports only the 20-unit narrow and 30-unit wide forms."""

        return "NARROW" if abs(logical_width - 20.0) < abs(logical_width - 30.0) else "WIDE"

    def _assemble_ir(
        self,
        image: Image.Image,
        source_sha256: str,
        components: list[_Component],
        extraction: WireExtraction,
    ) -> dict[str, object]:
        by_region: dict[str, list[_Port]] = defaultdict(list)
        issues: list[dict[str, object]] = []
        for component in components:
            for port in component.ports:
                region_id = extraction.node_regions.get(port.node)
                if region_id is None:
                    issues.append(
                        self._issue(
                            "UNCONNECTED_PORT",
                            f"No wire reaches {port.component_id}:{port.id}",
                            port.component_id,
                        )
                    )
                else:
                    by_region[region_id].append(port)

        regions_by_id = {region.id: region for region in extraction.regions}
        nets: list[dict[str, object]] = []
        net_index = 1
        for region_id, ports in sorted(by_region.items()):
            unique_ports = sorted(
                {(port.component_id, port.id): port for port in ports}.values(),
                key=lambda port: (port.direction != "OUTPUT", port.component_id, port.id),
            )
            drivers = [port for port in unique_ports if port.direction == "OUTPUT"]
            if len(drivers) != 1:
                issues.append(
                    self._issue(
                        "MULTIPLE_DRIVERS" if len(drivers) > 1 else "MISSING_DRIVER",
                        f"Wire region {region_id} has {len(drivers)} drivers",
                        region_id,
                    )
                )
                continue
            if len(unique_ports) < 2:
                issues.append(
                    self._issue(
                        "UNCONNECTED_WIRE",
                        f"Wire region {region_id} reaches only one component port",
                        region_id,
                    )
                )
                continue
            region = regions_by_id[region_id]
            terminal_nodes = {port.node for port in unique_ports}
            used_edges = self._terminal_tree_edges(
                set(region.edges),
                drivers[0].node,
                terminal_nodes,
            )
            degrees: Counter[tuple[int, int]] = Counter()
            for first, second in used_edges:
                degrees[first] += 1
                degrees[second] += 1
            segments = OrthogonalWireExtractor._merge_segments(
                used_edges,
                extraction.profile,
            )
            nets.append(
                {
                    "id": f"net_{net_index:03d}",
                    "endpoints": [
                        {"componentId": port.component_id, "portId": port.id}
                        for port in unique_ports
                    ],
                    "segments": [
                        [
                            segment.start[0],
                            segment.start[1],
                            segment.end[0],
                            segment.end[1],
                        ]
                        for segment in segments
                    ],
                    "junctions": [
                        list(extraction.profile.image_point(node))
                        for node, degree in sorted(degrees.items())
                        if degree >= 3 and node not in terminal_nodes
                    ],
                    "confidence": 0.94,
                }
            )
            net_index += 1

        for region_id in sorted(regions_by_id.keys() - by_region.keys()):
            region = regions_by_id[region_id]
            if self._inside_component_artifact(
                region.nodes,
                components,
                extraction.profile,
            ):
                continue
            if extraction.profile.native_v04 and len(region.edges) <= 3:
                # Native blue pin labels can leave one- or two-edge glyph
                # fragments on grid rows after resampling.  They do not reach
                # a component port and are too short to be a supported wire.
                continue
            issues.append(
                self._issue(
                    "UNMATCHED_WIRE",
                    f"Wire region {region_id} does not reach a component port",
                    region_id,
                )
            )

        ir_components = [
            self._component_json(component, extraction.profile) for component in components
        ]
        semantic_payload = {
            "components": [
                (component["id"], component["kind"], component["anchor"])
                for component in ir_components
            ],
            "nets": [net["endpoints"] for net in nets],
        }
        semantic_hash = sha256(
            json.dumps(
                semantic_payload, sort_keys=True, separators=(",", ":")
            ).encode("utf-8")
        ).hexdigest()
        return {
            "schemaVersion": SCHEMA_VERSION,
            "source": {
                "sha256": source_sha256,
                "width": image.width,
                "height": image.height,
            },
            "profile": {
                "theme": "light",
                "gateShape": "ansi",
                "gridPixels": extraction.profile.spacing,
                "scale": extraction.profile.scale,
                "offsetX": extraction.profile.offset_x,
                "offsetY": extraction.profile.offset_y,
            },
            "components": ir_components,
            "nets": nets,
            "unresolved": issues,
            "semanticHash": semantic_hash,
        }

    @staticmethod
    def _inside_component_artifact(
        nodes: frozenset[tuple[int, int]],
        components: list[_Component],
        profile: GridProfile,
    ) -> bool:
        """Ignore value/label glyphs that are local to a component body."""

        if not nodes:
            return False
        points = [profile.image_point(node) for node in nodes]
        for component in components:
            x0, y0, x1, y1 = component.detection.body_box
            if profile.native_v04 and component.detection.kind == "INPUT_PIN":
                if all(
                    x0 - 2 * profile.spacing <= x_value <= x1 + 1
                    and y0 - profile.spacing <= y_value <= y1 + profile.spacing
                    for x_value, y_value in points
                ):
                    return True
            if all(
                x0 - profile.spacing <= x_value <= x1 + profile.spacing
                and y0 - profile.spacing <= y_value <= y1 + profile.spacing
                for x_value, y_value in points
            ):
                return True
        return False

    @staticmethod
    def _component_json(
        component: _Component, profile: GridProfile
    ) -> dict[str, object]:
        attributes: dict[str, object] = {"bitWidth": 1}
        if component.detection.kind not in ("INPUT_PIN", "OUTPUT_PIN"):
            attributes["inputs"] = 1 if component.detection.kind == "NOT" else 2
        if component.gate_size is not None:
            attributes["size"] = component.gate_size
        anchor = profile.image_point(component.anchor)
        return {
            "id": component.id,
            "kind": component.detection.kind,
            "bodyBox": list(component.detection.body_box),
            "anchor": [anchor[0], anchor[1]],
            "facing": "EAST",
            "attributes": attributes,
            "ports": [
                {
                    "id": port.id,
                    "direction": port.direction,
                    "point": list(profile.image_point(port.node)),
                }
                for port in component.ports
            ],
            "confidence": component.detection.confidence,
        }

    @staticmethod
    def _issue(code: str, message: str, subject_id: str) -> dict[str, object]:
        return {
            "code": code,
            "message": message,
            "severity": "ERROR",
            "subjectId": subject_id,
        }

    @staticmethod
    def _terminal_tree_edges(
        edges: set[tuple[tuple[int, int], tuple[int, int]]],
        root: tuple[int, int],
        terminals: set[tuple[int, int]],
    ) -> set[tuple[tuple[int, int], tuple[int, int]]]:
        """Keep a deterministic shortest-path tree between electrical ports.

        Native pin labels can touch a real conductor after aggressive image
        resampling and form a small closed loop. Leaf-only pruning cannot
        remove that loop, while a wire net never needs a cycle to preserve its
        electrical connectivity. A BFS tree rooted at the driver retains
        every sink path and discards both loops and local glyph spurs.
        """

        adjacency: dict[tuple[int, int], set[tuple[int, int]]] = defaultdict(set)
        for first, second in edges:
            adjacency[first].add(second)
            adjacency[second].add(first)
        parents: dict[tuple[int, int], tuple[int, int] | None] = {root: None}
        pending = [root]
        for node in pending:
            for neighbor in sorted(adjacency.get(node, ())):
                if neighbor in parents:
                    continue
                parents[neighbor] = node
                pending.append(neighbor)

        result: set[tuple[tuple[int, int], tuple[int, int]]] = set()
        for terminal in sorted(terminals - {root}):
            if terminal not in parents:
                return FixedScaleTopologyRecognizer._prune_nonterminal_spurs(
                    edges, terminals
                )
            node = terminal
            while parents[node] is not None:
                parent = parents[node]
                assert parent is not None
                result.add(tuple(sorted((node, parent))))
                node = parent
        return result

    @staticmethod
    def _prune_nonterminal_spurs(
        edges: set[tuple[tuple[int, int], tuple[int, int]]],
        terminals: set[tuple[int, int]],
    ) -> set[tuple[tuple[int, int], tuple[int, int]]]:
        """Remove label/pin glyph branches that do not terminate at a port."""
        adjacency: dict[tuple[int, int], set[tuple[int, int]]] = defaultdict(set)
        for first, second in edges:
            adjacency[first].add(second)
            adjacency[second].add(first)
        pending = [
            node
            for node, neighbors in adjacency.items()
            if len(neighbors) <= 1 and node not in terminals
        ]
        while pending:
            node = pending.pop()
            neighbors = adjacency.pop(node, set())
            for neighbor in neighbors:
                if neighbor not in adjacency:
                    continue
                adjacency[neighbor].discard(node)
                if len(adjacency[neighbor]) <= 1 and neighbor not in terminals:
                    pending.append(neighbor)
        return {
            OrthogonalWireExtractor._ordered_edge(node, neighbor)
            for node, neighbors in adjacency.items()
            for neighbor in neighbors
            if node <= neighbor
        }


__all__ = ["FixedScaleTopologyRecognizer", "SCHEMA_VERSION"]
