"""Orthogonal wire extraction for legacy and native Logisim profiles."""

from __future__ import annotations

from collections import defaultdict
from dataclasses import dataclass
import math
from typing import Iterable

import numpy as np
from PIL import Image

from .template_detector import TemplateDetection


@dataclass(frozen=True)
class GridProfile:
    """Image-space position of the Logisim ten-unit circuit grid."""

    spacing: float
    offset_x: float
    offset_y: float
    native_v04: bool = False

    @property
    def scale(self) -> float:
        return self.spacing / 10.0

    def image_point(self, node: tuple[int, int]) -> tuple[float, float]:
        return (
            self.offset_x + node[0] * self.spacing,
            self.offset_y + node[1] * self.spacing,
        )

    def nearest_node(self, x_value: float, y_value: float) -> tuple[int, int]:
        # Raster bounds commonly end exactly half a grid beyond a Logisim
        # anchor (for example x=355 around an anchor at x=350).  Resolve that
        # exact tie toward the lower grid coordinate instead of Python's
        # even-number rounding, which would move every other component.
        def nearest(value: float, offset: float) -> int:
            return math.floor((value - offset) / self.spacing + 0.5 - 1.0e-6)

        return (
            nearest(x_value, self.offset_x),
            nearest(y_value, self.offset_y),
        )


@dataclass(frozen=True)
class WireSegment:
    start: tuple[float, float]
    end: tuple[float, float]


@dataclass(frozen=True)
class WireRegion:
    id: str
    nodes: frozenset[tuple[int, int]]
    edges: frozenset[tuple[tuple[int, int], tuple[int, int]]]
    segments: tuple[WireSegment, ...]


@dataclass(frozen=True)
class WireExtraction:
    profile: GridProfile
    regions: tuple[WireRegion, ...]
    node_regions: dict[tuple[int, int], str]

    def region_for_node(self, node: tuple[int, int]) -> WireRegion | None:
        region_id = self.node_regions.get(node)
        if region_id is None:
            return None
        return next(region for region in self.regions if region.id == region_id)


class OrthogonalWireExtractor:
    """Convert connected wire pixels into a graph on the Logisim grid."""

    def __init__(self, edge_coverage: float = 0.55) -> None:
        if not 0.0 < edge_coverage <= 1.0:
            raise ValueError("edge_coverage must be in (0, 1]")
        self.edge_coverage = edge_coverage

    def estimate_grid(self, image: Image.Image) -> GridProfile:
        rgb = np.asarray(image.convert("RGB"), dtype=np.int16)
        spread = np.max(rgb, axis=2) - np.min(rgb, axis=2)
        gray = (np.max(rgb, axis=2) < 230) & (spread <= 5)
        centers = self._small_dot_centers(gray)
        if len(centers) < 20:
            raise ValueError("Not enough background grid dots were found")

        rows: dict[float, list[float]] = defaultdict(list)
        columns: dict[float, list[float]] = defaultdict(list)
        for x_value, y_value in centers:
            rows[y_value].append(x_value)
            columns[x_value].append(y_value)
        horizontal_spacing, offset_x = self._fit_axis_lattice(rows)
        vertical_spacing, offset_y = self._fit_axis_lattice(columns)
        # Downsampling can erase every second dot along only one axis.  When
        # one fitted spacing is a small integer multiple of the other, recover
        # the fundamental grid instead of rejecting an otherwise clean image.
        smaller = min(horizontal_spacing, vertical_spacing)
        larger = max(horizontal_spacing, vertical_spacing)
        multiple = round(larger / smaller)
        if 2 <= multiple <= 4 and abs(larger / multiple - smaller) <= max(
            0.35, smaller * 0.04
        ):
            if horizontal_spacing > vertical_spacing:
                horizontal_spacing /= multiple
            else:
                vertical_spacing /= multiple
        if abs(horizontal_spacing - vertical_spacing) > max(
            0.35, min(horizontal_spacing, vertical_spacing) * 0.03
        ):
            raise ValueError("The Logisim grid is not uniformly scaled")
        spacing = (horizontal_spacing + vertical_spacing) / 2.0
        if not 5.0 <= spacing <= 25.0:
            raise ValueError(f"Unsupported Logisim grid spacing: {spacing}")
        return GridProfile(
            spacing=spacing,
            offset_x=offset_x % spacing,
            offset_y=offset_y % spacing,
        )

    @staticmethod
    def _fit_axis_lattice(groups: dict[float, list[float]]) -> tuple[float, float]:
        """Fit a possibly fractional grid from the longest unobstructed line.

        Screenshots copied through the UI can be resampled by a few percent.
        Median integer pixel differences then round 9.62 or 9.75 to 10 and
        accumulate a large phase error.  A least-squares fit over a full row or
        column retains that fractional spacing.  Integer index gaps also allow
        the selected line to contain a small number of obscured grid dots.
        """

        candidates = [np.sort(values) for values in groups.values() if len(values) >= 8]
        if not candidates:
            raise ValueError("The Logisim grid spacing could not be estimated")
        values = max(candidates, key=len)
        adjacent = np.diff(values)
        plausible = adjacent[(adjacent >= 5.0) & (adjacent <= 25.0)]
        if len(plausible) < 10:
            raise ValueError("The Logisim grid spacing could not be estimated")
        rough_spacing = float(np.median(plausible))
        indices = [0]
        for difference in adjacent:
            indices.append(
                indices[-1] + max(1, int(round(float(difference) / rough_spacing)))
            )
        index_array = np.asarray(indices, dtype=np.float64)
        spacing, offset = np.polyfit(index_array, values, 1)
        residual = np.abs(values - (index_array * spacing + offset))
        if float(np.median(residual)) > 1.0:
            raise ValueError("The Logisim grid is too distorted to fit")
        return float(spacing), float(offset)

    def extract(
        self,
        image: Image.Image,
        components: Iterable[TemplateDetection],
        profile: GridProfile | None = None,
    ) -> WireExtraction:
        components = tuple(components)
        rgb = np.asarray(image.convert("RGB"), dtype=np.int16)
        red, green, blue = rgb[:, :, 0], rgb[:, :, 1], rgb[:, :, 2]
        green_mask = (green >= 65) & (green - red >= 25) & (green - blue >= 25)
        legacy_profile = int(np.count_nonzero(green_mask)) >= 100
        if profile is None:
            # Native v0.4 renders at the Logisim 10-pixel grid with phase zero.
            # Its visible grid dots are centered between circuit coordinates,
            # so the legacy dot estimator would infer a half-grid offset.
            profile = (
                self.estimate_grid(image)
                if legacy_profile
                else self._native_profile(components)
            )
        if legacy_profile:
            wire_mask = green_mask
        else:
            spread = np.max(rgb, axis=2) - np.min(rgb, axis=2)
            wire_mask = (np.max(rgb, axis=2) <= 110) & (spread <= 12)
            blue_wire = (
                (blue >= 80) & ((blue - red) >= 40) & ((blue - green) >= 40)
            )
            wire_mask |= blue_wire
        source_wire_mask = wire_mask.copy()

        # Remove values and colored markings inside component bodies.  Legacy
        # boxes keep a half-grid rim because they may include a short lead;
        # exact native boxes are cleared and their real port neighborhoods are
        # restored from the source mask below.
        native_ports: list[tuple[int, int, int]] = []
        native_isolations: list[tuple[str, list[tuple[int, int]], int]] = []
        for component_index, component in enumerate(components):
            x0, y0, x1, y1 = component.body_box
            if legacy_profile:
                mask_inset = max(2, round(profile.spacing * 0.5))
                inner_x0 = max(0, x0 + mask_inset)
                inner_y0 = max(0, y0 + mask_inset)
                inner_x1 = min(image.width, x1 - mask_inset)
                inner_y1 = min(image.height, y1 - mask_inset)
            else:
                # Native component boxes are exact.  Remove the whole body so
                # black symbol outlines cannot masquerade as black wires.
                inner_x0 = max(0, x0)
                inner_y0 = max(0, y0)
                inner_x1 = min(image.width, x1 + 1)
                inner_y1 = min(image.height, y1 + 1)
            if inner_x0 < inner_x1 and inner_y0 < inner_y1:
                wire_mask[inner_y0:inner_y1, inner_x0:inner_x1] = False
            if not legacy_profile:
                ports = self._native_component_ports(component, profile)
                native_ports.extend(
                    (component_index, port_x, port_y) for port_x, port_y in ports
                )
                radius = max(1, round(profile.spacing * 0.16))
                reach = round(profile.spacing)
                if component.kind == "INPUT_PIN":
                    directions = (1,)
                elif component.kind == "OUTPUT_PIN":
                    directions = (-1,)
                elif component.kind == "NOT":
                    directions = (-1, 1)
                else:
                    directions = (-1, -1, 1)
                for (port_x, port_y), direction in zip(
                    ports, directions, strict=True
                ):
                    outward_reach = (
                        4 * reach if component.kind == "OUTPUT_PIN" else reach
                    )
                    if direction < 0:
                        left = port_x - outward_reach
                        right = port_x + radius + 1
                    else:
                        left = port_x - radius
                        right = port_x + outward_reach + 1
                    horizontal = (
                        slice(max(0, port_y - radius), min(image.height, port_y + radius + 1)),
                        slice(max(0, left), min(image.width, right)),
                    )
                    wire_mask[horizontal] = source_wire_mask[horizontal]
                if component.kind not in ("INPUT_PIN", "OUTPUT_PIN"):
                    native_isolations.append((component.kind, ports, radius))
                if component.kind not in ("INPUT_PIN", "OUTPUT_PIN", "NOT"):
                    # A wire may bend immediately above the top input or below
                    # the bottom input.  Restore only away from the gate center;
                    # restoring toward the center would join the two inputs of
                    # a narrow gate through its black body outline.
                    for (port_x, port_y), direction in zip(
                        ports[:2], (-1, 1), strict=True
                    ):
                        # Wide gates keep an extra body margin beyond the
                        # actual input row, so reach two grid cells before the
                        # source-evidence check and restoration stop.
                        outside_y = port_y + direction * reach * 2
                        if self._vertical_coverage(
                            source_wire_mask,
                            port_y,
                            outside_y,
                            port_x,
                            radius,
                        ) < self.edge_coverage:
                            continue
                        vertical = (
                            slice(
                                max(0, min(port_y, outside_y) - radius),
                                min(image.height, max(port_y, outside_y) + radius + 1),
                            ),
                            slice(
                                max(0, port_x - radius),
                                min(image.width, port_x + radius + 1),
                            ),
                        )
                        wire_mask[vertical] = source_wire_mask[vertical]

        if not legacy_profile:
            # A legal route may leave one gate output and immediately turn
            # vertically along the shared x-coordinate of another gate input.
            # Both bodies hide that short segment.  Restore it only when two
            # ports from different components are grid-aligned and the source
            # pixels contain a continuous vertical conductor; this avoids the
            # old failure where restoring every vertical body stroke shorted
            # the two inputs of a narrow gate.
            radius = max(1, round(profile.spacing * 0.16))
            for index, (first_component, first_x, first_y) in enumerate(native_ports):
                for second_component, second_x, second_y in native_ports[index + 1 :]:
                    if first_component == second_component or first_x != second_x:
                        continue
                    if abs(first_y - second_y) < round(profile.spacing):
                        continue
                    if self._vertical_coverage(
                        source_wire_mask,
                        first_y,
                        second_y,
                        first_x,
                        radius,
                    ) < self.edge_coverage:
                        continue
                    vertical = (
                        slice(
                            max(0, min(first_y, second_y) - radius),
                            min(image.height, max(first_y, second_y) + radius + 1),
                        ),
                        slice(
                            max(0, first_x - radius),
                            min(image.width, first_x + radius + 1),
                        ),
                    )
                    wire_mask[vertical] = source_wire_mask[vertical]

            # Enforce electrical separation across component bodies after all
            # evidence-based restoration. Detection boxes can be off by one
            # or two pixels, especially at 50% zoom, so the original outline
            # must never bridge a NOT input to its output or join the two
            # inputs of a multi-input gate.
            for kind, ports, radius in native_isolations:
                if kind == "NOT":
                    input_x, center_y = ports[0]
                    output_x, _ = ports[1]
                    wire_mask[
                        max(0, center_y - radius) : min(
                            image.height, center_y + radius + 1
                        ),
                        max(0, input_x + radius + 1) : min(
                            image.width, output_x - radius
                        ),
                    ] = False
                else:
                    input_x, top_y = ports[0]
                    _, bottom_y = ports[1]
                    wire_mask[
                        max(0, top_y + radius + 1) : min(
                            image.height, bottom_y - radius
                        ),
                        max(0, input_x - radius) : min(
                            image.width, input_x + radius + 1
                        ),
                    ] = False

        adjacency = self._grid_graph(wire_mask, profile)
        junction_nodes = {
            node
            for node, neighbors in adjacency.items()
            if len(neighbors) == 4
            and self._has_junction_dot(wire_mask, profile, node)
        }
        regions = self._regions(adjacency, profile, junction_nodes)
        memberships: dict[tuple[int, int], list[str]] = defaultdict(list)
        for region in regions:
            for node in region.nodes:
                memberships[node].append(region.id)
        # A non-junction four-way crossing belongs to two electrical regions.
        # Component ports are not valid at such an ambiguous bridge node, so
        # only publish nodes that map to one unambiguous region.
        node_regions = {
            node: region_ids[0]
            for node, region_ids in memberships.items()
            if len(region_ids) == 1
        }
        return WireExtraction(profile, tuple(regions), node_regions)

    @staticmethod
    def _native_component_ports(
        component: TemplateDetection, profile: GridProfile
    ) -> list[tuple[int, int]]:
        """Return native port pixels using the same snapped geometry as topology."""

        x0, y0, x1, y1 = component.body_box
        center_y = (y0 + y1) / 2.0
        anchor = profile.nearest_node(x1, center_y)
        output = tuple(round(value) for value in profile.image_point(anchor))
        if component.kind in ("INPUT_PIN", "OUTPUT_PIN"):
            return [output]

        input_x = profile.nearest_node(x0, center_y)[0]
        if component.kind == "NOT":
            input_node = (input_x, anchor[1])
            return [
                tuple(round(value) for value in profile.image_point(input_node)),
                output,
            ]

        normalized_height = (y1 - y0) / profile.scale
        gate_size = component.gate_size or min(
            ("NARROW", "MEDIUM", "WIDE"),
            key=lambda size: abs(
                normalized_height - {"NARROW": 30.0, "MEDIUM": 50.0, "WIDE": 70.0}[size]
            ),
        )
        input_steps = 1 if gate_size == "NARROW" else 2
        top_input = (input_x, anchor[1] - input_steps)
        bottom_input = (input_x, anchor[1] + input_steps)
        return [
            tuple(round(value) for value in profile.image_point(top_input)),
            tuple(round(value) for value in profile.image_point(bottom_input)),
            output,
        ]

    @staticmethod
    def _native_profile(
        components: Iterable[TemplateDetection],
    ) -> GridProfile:
        """Infer native-render zoom from fixed 20-unit pin bodies.

        Gate boxes cannot separate UI zoom from the NARROW/MEDIUM/WIDE
        attribute, but input and output pins have one fixed logical size.  A
        robust median over both pin axes therefore recovers screenshots that
        were uniformly scaled from the native zero-phase canvas.
        """

        scales: list[float] = []
        for component in components:
            if component.kind not in ("INPUT_PIN", "OUTPUT_PIN"):
                continue
            x0, y0, x1, y1 = component.body_box
            scales.extend(((x1 - x0) / 20.0, (y1 - y0) / 20.0))
        scale = float(np.median(scales)) if scales else 1.0
        if not 0.5 <= scale <= 2.5:
            raise ValueError(f"Unsupported native Logisim scale: {scale}")
        raw_spacing = 10.0 * scale
        anchors: list[float] = []
        for component in components:
            if component.kind not in ("INPUT_PIN", "OUTPUT_PIN"):
                continue
            x0, y0, x1, y1 = component.body_box
            anchors.extend((float(x1), (float(y0) + float(y1)) / 2.0))
        if anchors:
            lower = max(5.0, raw_spacing * 0.9)
            upper = min(25.0, raw_spacing * 1.1)
            candidates = np.linspace(lower, upper, 801)

            def score(candidate: float) -> float:
                alignment = np.mean(
                    [
                        (value / candidate - round(value / candidate)) ** 2
                        for value in anchors
                    ]
                )
                size_penalty = 0.1 * ((candidate - raw_spacing) / raw_spacing) ** 2
                return float(alignment + size_penalty)

            raw_spacing = float(min(candidates, key=score))
        return GridProfile(
            spacing=raw_spacing,
            offset_x=0.0,
            offset_y=0.0,
            native_v04=True,
        )

    @staticmethod
    def _has_junction_dot(
        mask: np.ndarray,
        profile: GridProfile,
        node: tuple[int, int],
    ) -> bool:
        """Distinguish a filled four-way junction from a plain wire cross."""
        center_x, center_y = profile.image_point(node)
        x_value = round(center_x)
        y_value = round(center_y)
        radius = max(2, round(profile.spacing * 0.32))
        search_radius = max(1, round(profile.spacing * 0.16))
        best = 0
        height, width = mask.shape
        for delta_y in range(-search_radius, search_radius + 1):
            for delta_x in range(-search_radius, search_radius + 1):
                left = x_value + delta_x - radius
                right = x_value + delta_x + radius + 1
                top = y_value + delta_y - radius
                bottom = y_value + delta_y + radius + 1
                if left < 0 or top < 0 or right > width or bottom > height:
                    continue
                best = max(best, int(np.count_nonzero(mask[top:bottom, left:right])))
        # Use a scale-relative fill threshold.  A plain orthogonal crossing
        # leaves four substantially empty corners, whereas a Logisim junction
        # dot fills most of this compact crop.
        side = radius * 2 + 1
        return best >= round(side * side * 0.90)

    @staticmethod
    def _small_dot_centers(mask: np.ndarray) -> list[tuple[float, float]]:
        height, width = mask.shape
        visited = np.zeros_like(mask, dtype=bool)
        centers: list[tuple[float, float]] = []
        for start_y, start_x in zip(*np.where(mask)):
            if visited[start_y, start_x]:
                continue
            visited[start_y, start_x] = True
            pending = [(int(start_y), int(start_x))]
            x_values: list[int] = []
            y_values: list[int] = []
            while pending:
                y_value, x_value = pending.pop()
                x_values.append(x_value)
                y_values.append(y_value)
                for delta_y in (-1, 0, 1):
                    for delta_x in (-1, 0, 1):
                        next_y = y_value + delta_y
                        next_x = x_value + delta_x
                        if (
                            0 <= next_y < height
                            and 0 <= next_x < width
                            and mask[next_y, next_x]
                            and not visited[next_y, next_x]
                        ):
                            visited[next_y, next_x] = True
                            pending.append((next_y, next_x))
            if (
                len(x_values) <= 16
                and max(x_values) - min(x_values) <= 3
                and max(y_values) - min(y_values) <= 3
            ):
                centers.append(
                    (
                        sum(x_values) / len(x_values),
                        sum(y_values) / len(y_values),
                    )
                )
        return centers

    @staticmethod
    def _fit_phase(values: np.ndarray, spacing: float) -> float:
        candidates = np.linspace(0.0, spacing, 501, endpoint=False)
        errors = []
        for candidate in candidates:
            remainder = np.mod(values - candidate, spacing)
            errors.append(float(np.median(np.minimum(remainder, spacing - remainder))))
        return float(candidates[int(np.argmin(errors))])

    def _grid_graph(
        self, mask: np.ndarray, profile: GridProfile
    ) -> dict[tuple[int, int], set[tuple[int, int]]]:
        height, width = mask.shape
        min_x = math.ceil(-profile.offset_x / profile.spacing)
        max_x = math.floor((width - 1 - profile.offset_x) / profile.spacing)
        min_y = math.ceil(-profile.offset_y / profile.spacing)
        max_y = math.floor((height - 1 - profile.offset_y) / profile.spacing)
        # Resampled screenshots can drift by two or three pixels between the
        # background-dot lattice and the circuit layer.  The center-only edge
        # coverage check below makes this wider search safe around parallel
        # wires while retaining the required tolerance for that drift.
        radius = max(2, round(profile.spacing * 0.25))
        adjacency: dict[tuple[int, int], set[tuple[int, int]]] = {}

        def connect(first: tuple[int, int], second: tuple[int, int]) -> None:
            adjacency.setdefault(first, set()).add(second)
            adjacency.setdefault(second, set()).add(first)

        for y_index in range(min_y, max_y + 1):
            y_value = profile.offset_y + y_index * profile.spacing
            for x_index in range(min_x, max_x):
                x0 = profile.offset_x + x_index * profile.spacing
                x1 = x0 + profile.spacing
                if self._horizontal_coverage(mask, x0, x1, y_value, radius) >= self.edge_coverage:
                    connect((x_index, y_index), (x_index + 1, y_index))

        for x_index in range(min_x, max_x + 1):
            x_value = profile.offset_x + x_index * profile.spacing
            for y_index in range(min_y, max_y):
                y0 = profile.offset_y + y_index * profile.spacing
                y1 = y0 + profile.spacing
                if self._vertical_coverage(mask, y0, y1, x_value, radius) >= self.edge_coverage:
                    connect((x_index, y_index), (x_index, y_index + 1))
        return adjacency

    @staticmethod
    def _horizontal_coverage(
        mask: np.ndarray, x0: float, x1: float, y_value: float, radius: int
    ) -> float:
        height, width = mask.shape
        left = max(0, math.floor(x0))
        right = min(width - 1, math.ceil(x1))
        center = round(y_value)
        margin = max(1, radius + 1)
        sample_left = min(right, left + margin)
        sample_right = max(sample_left, right - margin)
        strip = mask[
            max(0, center - radius) : min(height, center + radius + 1),
            sample_left : sample_right + 1,
        ]
        return max((float(np.mean(row)) for row in strip), default=0.0)

    @staticmethod
    def _vertical_coverage(
        mask: np.ndarray, y0: float, y1: float, x_value: float, radius: int
    ) -> float:
        height, width = mask.shape
        top = max(0, math.floor(y0))
        bottom = min(height - 1, math.ceil(y1))
        center = round(x_value)
        margin = max(1, radius + 1)
        sample_top = min(bottom, top + margin)
        sample_bottom = max(sample_top, bottom - margin)
        strip = mask[
            sample_top : sample_bottom + 1,
            max(0, center - radius) : min(width, center + radius + 1),
        ]
        return max((float(np.mean(column)) for column in strip.T), default=0.0)

    def _regions(
        self,
        adjacency: dict[tuple[int, int], set[tuple[int, int]]],
        profile: GridProfile,
        junction_nodes: set[tuple[int, int]] | None = None,
    ) -> list[WireRegion]:
        if junction_nodes is None:
            junction_nodes = set()
        all_edges = {
            self._ordered_edge(node, neighbor)
            for node, neighbors in adjacency.items()
            for neighbor in neighbors
        }
        incident: dict[
            tuple[int, int],
            list[tuple[tuple[int, int], tuple[int, int]]],
        ] = defaultdict(list)
        for edge in all_edges:
            incident[edge[0]].append(edge)
            incident[edge[1]].append(edge)

        edge_adjacency: dict[
            tuple[tuple[int, int], tuple[int, int]],
            set[tuple[tuple[int, int], tuple[int, int]]],
        ] = {edge: set() for edge in all_edges}

        def horizontal(edge: tuple[tuple[int, int], tuple[int, int]]) -> bool:
            return edge[0][1] == edge[1][1]

        for node, edges_at_node in incident.items():
            for first_index, first in enumerate(edges_at_node):
                for second in edges_at_node[first_index + 1 :]:
                    # A four-way node without an explicit junction is the
                    # normal Logisim bridge/crossing representation. Keep its
                    # horizontal and vertical conductors independent.
                    # Three-way nodes are T fan-outs and stay connected.
                    if (
                        len(edges_at_node) == 4
                        and node not in junction_nodes
                        and horizontal(first) != horizontal(second)
                    ):
                        continue
                    edge_adjacency[first].add(second)
                    edge_adjacency[second].add(first)

        seen_edges: set[tuple[tuple[int, int], tuple[int, int]]] = set()
        raw_regions: list[
            tuple[
                set[tuple[int, int]],
                set[tuple[tuple[int, int], tuple[int, int]]],
            ]
        ] = []
        for start in sorted(all_edges):
            if start in seen_edges:
                continue
            pending = [start]
            nodes: set[tuple[int, int]] = set()
            edges: set[tuple[tuple[int, int], tuple[int, int]]] = set()
            while pending:
                edge = pending.pop()
                if edge in seen_edges:
                    continue
                seen_edges.add(edge)
                edges.add(edge)
                nodes.update(edge)
                pending.extend(edge_adjacency[edge] - seen_edges)
            raw_regions.append((nodes, edges))

        raw_regions.sort(key=lambda item: min(item[0], key=lambda node: (node[0], node[1])))
        return [
            WireRegion(
                id=f"wire_{index:03d}",
                nodes=frozenset(nodes),
                edges=frozenset(edges),
                segments=tuple(self._merge_segments(edges, profile)),
            )
            for index, (nodes, edges) in enumerate(raw_regions, start=1)
        ]

    @staticmethod
    def _ordered_edge(
        first: tuple[int, int], second: tuple[int, int]
    ) -> tuple[tuple[int, int], tuple[int, int]]:
        return (first, second) if first <= second else (second, first)

    @staticmethod
    def _merge_segments(
        edges: set[tuple[tuple[int, int], tuple[int, int]]], profile: GridProfile
    ) -> list[WireSegment]:
        horizontal: dict[int, list[tuple[int, int]]] = {}
        vertical: dict[int, list[tuple[int, int]]] = {}
        for first, second in edges:
            if first[1] == second[1]:
                horizontal.setdefault(first[1], []).append((first[0], second[0]))
            else:
                vertical.setdefault(first[0], []).append((first[1], second[1]))

        result: list[WireSegment] = []
        for y_index, intervals in sorted(horizontal.items()):
            for start, end in OrthogonalWireExtractor._merge_intervals(intervals):
                result.append(
                    WireSegment(
                        profile.image_point((start, y_index)),
                        profile.image_point((end, y_index)),
                    )
                )
        for x_index, intervals in sorted(vertical.items()):
            for start, end in OrthogonalWireExtractor._merge_intervals(intervals):
                result.append(
                    WireSegment(
                        profile.image_point((x_index, start)),
                        profile.image_point((x_index, end)),
                    )
                )
        return result

    @staticmethod
    def _merge_intervals(intervals: list[tuple[int, int]]) -> list[tuple[int, int]]:
        ordered = sorted((min(start, end), max(start, end)) for start, end in intervals)
        merged: list[list[int]] = []
        for start, end in ordered:
            if not merged or start > merged[-1][1]:
                merged.append([start, end])
            else:
                merged[-1][1] = max(merged[-1][1], end)
        return [(start, end) for start, end in merged]


__all__ = [
    "GridProfile",
    "OrthogonalWireExtractor",
    "WireExtraction",
    "WireRegion",
    "WireSegment",
]
