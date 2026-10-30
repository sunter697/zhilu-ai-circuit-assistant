/*
 * Logisim-evolution - digital logic design tool and simulator
 * Copyright by the Logisim-evolution developers
 *
 * https://github.com/logisim-evolution/
 *
 * This is free software released under GNU GPLv3 license
 */

package com.cburch.logisim.ai.tools;

import com.cburch.logisim.circuit.Circuit;
import com.cburch.logisim.circuit.Wire;
import com.cburch.logisim.comp.Component;
import com.cburch.logisim.comp.EndData;
import com.cburch.logisim.data.Bounds;
import com.cburch.logisim.data.Location;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;

/** Conservative net-aware routing. A rejected route never changes the circuit. */
public final class SafeWireRouter {
  private static final int GRID = 10;
  private static final int MARGIN = 160;
  private static final int MAX_STATES = 60_000;
  private static final int MAX_POINTS = 4_000;
  private static final int[][] DIRECTIONS = {{1, 0}, {0, -1}, {0, 1}, {-1, 0}};

  private SafeWireRouter() {}

  /**
   * Must be planned and committed in the same EDT operation. Only ordinary unidirectional nets are
   * supported.
   */
  public static List<Wire> route(Circuit circuit, EndData from, EndData to) {
    if (from.getType() != EndData.OUTPUT_ONLY || to.getType() != EndData.INPUT_ONLY) {
      throw new IllegalArgumentException(
          "Connect requires an output-only source and input-only destination. "
              + "Bidirectional ports require a bit-level routing tool.");
    }
    if (!from.getWidth().equals(to.getWidth())) {
      throw new IllegalArgumentException(
          "Port widths do not match: " + from.getWidth() + " vs " + to.getWidth());
    }
    final var topology = new Topology(circuit);
    final var source = topology.root(from.getLocation());
    final var destination = topology.root(to.getLocation());
    final Set<Location> allowed = new HashSet<>();
    allowed.add(source);
    allowed.add(destination);
    int drivers = 0;
    for (final var end : topology.ends) {
      if (!allowed.contains(topology.root(end.getLocation()))) continue;
      if (end.getType() == EndData.INPUT_OUTPUT) {
        throw new IllegalArgumentException(
            "The requested net contains a bidirectional port (for example a splitter or tunnel)."
                + " Safe bit-level routing is not supported yet; no wires were changed.");
      }
      if (!from.getWidth().equals(end.getWidth())) {
        throw new IllegalArgumentException(
            "An existing port on the requested net has a different bit width.");
      }
      if (end.isOutput()) drivers++;
    }
    if (drivers != 1) {
      throw new IllegalArgumentException(
          "Connecting these nets would join "
              + drivers
              + " output drivers; exactly one driver is required. No wires were changed.");
    }
    if (source.equals(destination)) return List.of();
    final var obstacles =
        new Obstacles(circuit, topology, allowed, from.getLocation(), to.getLocation());
    return search(topology.pointsOn(source), to.getLocation(), obstacles);
  }

  private static final class Topology {
    private final Map<Location, Location> parent = new HashMap<>();
    private final List<EndData> ends = new ArrayList<>();

    private Topology(Circuit circuit) {
      for (final var component : circuit.getNonWires()) {
        for (final var end : component.getEnds()) {
          add(end.getLocation());
          ends.add(end);
        }
      }
      for (final var wire : circuit.getWires()) {
        add(wire.getEnd0());
        add(wire.getEnd1());
        unite(wire.getEnd0(), wire.getEnd1());
      }
      if (parent.size() > MAX_POINTS)
        throw new IllegalArgumentException("Circuit is too large for bounded safe routing.");
      // WireRepair connects wires at component ports and wire endpoints, not bare interior
      // crossings.
      for (final var wire : circuit.getWires()) {
        for (final var point : parent.keySet()) {
          if (wire.contains(point)) unite(wire.getEnd0(), point);
        }
      }
    }

    private void add(Location point) {
      if (Math.abs((long) point.getX()) > 1_000_000 || Math.abs((long) point.getY()) > 1_000_000) {
        throw new IllegalArgumentException("Port coordinates exceed the bounded routing area.");
      }
      if (point.getX() % GRID != 0 || point.getY() % GRID != 0) {
        throw new IllegalArgumentException(
            "Safe routing requires all wire endpoints and ports on the 10-unit grid.");
      }
      parent.putIfAbsent(point, point);
    }

    private Location root(Location point) {
      final var next = parent.get(point);
      if (next == null) throw new IllegalArgumentException("Port is not present in the circuit.");
      if (next.equals(point)) return point;
      final var result = root(next);
      parent.put(point, result);
      return result;
    }

    private void unite(Location a, Location b) {
      final var ra = root(a);
      final var rb = root(b);
      if (!ra.equals(rb)) parent.put(rb, ra);
    }

    /**
     * Every explicit point on a net is a valid place to continue that net. Starting a new route at
     * the nearest existing endpoint avoids redrawing a long parallel path from the source
     * component when a short terminal segment was removed before reconnecting a component.
     */
    private List<Location> pointsOn(Location requestedRoot) {
      final var result = new ArrayList<Location>();
      for (final var point : List.copyOf(parent.keySet())) {
        if (root(point).equals(requestedRoot)) result.add(point);
      }
      result.sort(Comparator.comparingInt(Location::getY).thenComparingInt(Location::getX));
      return List.copyOf(result);
    }
  }

  private static final class Obstacles {
    private final Set<Location> blockedPorts = new HashSet<>();
    private final List<Wire> blockedWires = new ArrayList<>();
    private final List<Bounds> bodies = new ArrayList<>();
    private final Map<Location, Boolean> cache = new HashMap<>();
    private final Location start;
    private final Location goal;
    private final int minX;
    private final int minY;
    private final int maxX;
    private final int maxY;

    private Obstacles(
        Circuit circuit, Topology topology, Set<Location> allowed, Location start, Location goal) {
      this.start = start;
      this.goal = goal;
      for (final var end : topology.ends) {
        if (!allowed.contains(topology.root(end.getLocation())))
          blockedPorts.add(end.getLocation());
      }
      for (final var wire : circuit.getWires()) {
        if (!allowed.contains(topology.root(wire.getEnd0()))) blockedWires.add(wire);
      }
      for (final Component component : circuit.getNonWires()) bodies.add(component.getBounds());
      final var bounds = circuit.getBounds();
      minX = Math.min(bounds.getX(), Math.min(start.getX(), goal.getX())) - MARGIN;
      minY = Math.min(bounds.getY(), Math.min(start.getY(), goal.getY())) - MARGIN;
      maxX =
          Math.max(bounds.getX() + bounds.getWidth(), Math.max(start.getX(), goal.getX())) + MARGIN;
      maxY =
          Math.max(bounds.getY() + bounds.getHeight(), Math.max(start.getY(), goal.getY()))
              + MARGIN;
    }

    private boolean blocked(Location point) {
      return cache.computeIfAbsent(
          point,
          p -> {
            if (p.getX() < minX || p.getX() > maxX || p.getY() < minY || p.getY() > maxY)
              return true;
            if (blockedPorts.contains(p)) return true;
            for (final var wire : blockedWires) if (wire.contains(p)) return true;
            if (p.equals(start) || p.equals(goal)) return false;
            for (final var body : bodies) {
              if (p.getX() > body.getX()
                  && p.getX() < (long) body.getX() + body.getWidth()
                  && p.getY() > body.getY()
                  && p.getY() < (long) body.getY() + body.getHeight()) return true;
            }
            return false;
          });
    }
  }

  private record State(Location point, int direction) {}

  private record Node(State state, int cost, int estimate, Node previous) {}

  private static List<Wire> search(List<Location> starts, Location goal, Obstacles obstacles) {
    if (starts.isEmpty()) throw new IllegalArgumentException("Source net has no routing points.");
    final var open =
        new PriorityQueue<Node>(
            Comparator.comparingInt(Node::estimate)
                .thenComparingInt(Node::cost)
                .thenComparingInt(n -> n.state.point.getY())
                .thenComparingInt(n -> n.state.point.getX())
                .thenComparingInt(n -> n.state.direction));
    final var best = new HashMap<State, Integer>();
    for (final var start : starts) {
      final var initial = new State(start, -1);
      open.add(new Node(initial, 0, distance(start, goal), null));
      best.put(initial, 0);
    }
    int visited = 0;
    while (!open.isEmpty() && visited++ < MAX_STATES) {
      final var node = open.remove();
      if (node.cost != best.get(node.state)) continue;
      if (node.state.point.equals(goal)) return segments(node);
      if (Thread.currentThread().isInterrupted())
        throw new IllegalStateException("Routing was cancelled.");
      for (var direction = 0; direction < DIRECTIONS.length; direction++) {
        final var delta = DIRECTIONS[direction];
        final var point =
            Location.create(
                node.state.point.getX() + delta[0] * GRID,
                node.state.point.getY() + delta[1] * GRID,
                false);
        if (obstacles.blocked(point)) continue;
        final var state = new State(point, direction);
        final var cost =
            node.cost
                + GRID
                + (node.state.direction < 0 || direction == node.state.direction ? 0 : 4);
        if (cost >= best.getOrDefault(state, Integer.MAX_VALUE)) continue;
        best.put(state, cost);
        open.add(new Node(state, cost, cost + distance(point, goal), node));
      }
    }
    throw new IllegalArgumentException(
        "No safe route was found within the routing budget. "
            + "Move components or remove conflicting wires; no wires were changed.");
  }

  private static int distance(Location a, Location b) {
    return Math.abs(a.getX() - b.getX()) + Math.abs(a.getY() - b.getY());
  }

  private static List<Wire> segments(Node last) {
    final var points = new ArrayList<Location>();
    for (var node = last; node != null; node = node.previous) points.add(node.state.point);
    Collections.reverse(points);
    final var result = new ArrayList<Wire>();
    var start = points.get(0);
    for (int i = 1; i < points.size() - 1; i++) {
      final var previous = points.get(i - 1);
      final var current = points.get(i);
      final var next = points.get(i + 1);
      if ((previous.getX() == current.getX()) != (current.getX() == next.getX())) {
        result.add(Wire.create(start, current));
        start = current;
      }
    }
    if (!start.equals(points.getLast())) result.add(Wire.create(start, points.getLast()));
    return List.copyOf(result);
  }
}
