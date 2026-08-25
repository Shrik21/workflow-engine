import { WorkflowConnection, WorkflowNode } from '../../core/models/workflow.models';

/**
 * Geometry and layout for the workflow canvas.
 *
 * Pure functions with no Angular and no DOM, so the parts most likely to be subtly wrong, port
 * placement, edge routing and automatic layout, can be tested directly.
 */

/**
 * The node card is square, and its name is drawn <em>outside</em> it.
 *
 * <p>A wide card had to hold its own text, which capped the icon at badge size and made every node look
 * alike at a glance — you read the label to tell a Slack node from a MongoDB one. A square tile gives the
 * icon the whole card, so the mark identifies the node and the label underneath confirms it.
 *
 * <p>These two are the <em>card</em> only. The label overflows below it and is deliberately not part of the
 * box: edges terminate on the card, hit-testing is the card, and layout spacing leaves room for the text
 * through {@link ROW_SPACING} rather than by inflating the rectangle.
 */
export const NODE_WIDTH = 92;
export const NODE_HEIGHT = 92;

/** Room reserved under the card for the name. Not part of {@link nodeRect} — see the note above. */
export const NODE_LABEL_HEIGHT = 34;

export const GRID = 10;

/** Horizontal gap between layers: the card plus enough room for an edge to curve legibly. */
export const LAYER_SPACING = 196;

/** Vertical gap between rows: the card, its label, and a clear channel between neighbours. */
export const ROW_SPACING = 152;

export const MIN_ZOOM = 0.3;
export const MAX_ZOOM = 2.2;

export interface Point {
  x: number;
  y: number;
}

export interface Rect extends Point {
  width: number;
  height: number;
}

export interface Viewport {
  pan: Point;
  zoom: number;
}

/** The rectangle a node occupies, defaulting to the origin when it has no stored coordinates. */
export function nodeRect(node: WorkflowNode): Rect {
  return {
    x: numberOr(node.presentation?.x, 0),
    y: numberOr(node.presentation?.y, 0),
    width: NODE_WIDTH,
    height: NODE_HEIGHT,
  };
}

/** Which way {@link autoLayout} advances through the graph. */
export type LayoutDirection = 'horizontal' | 'vertical';

/** The four sides a connection can attach to. */
export type Side = 'top' | 'right' | 'bottom' | 'left';

export const SIDES: readonly Side[] = ['top', 'right', 'bottom', 'left'] as const;

/**
 * The outward unit normal of a side.
 *
 * <p>This is what makes an edge leave a node perpendicular to the face it starts from, which is the whole
 * difference between a graph that reads vertically and one whose lines all set off sideways first.
 */
export function sideNormal(side: Side): Point {
  switch (side) {
    case 'top':
      return { x: 0, y: -1 };
    case 'bottom':
      return { x: 0, y: 1 };
    case 'left':
      return { x: -1, y: 0 };
    default:
      return { x: 1, y: 0 };
  }
}

/**
 * A point on one side of a node.
 *
 * <p>Several ports on the same side spread along it so that three decision branches are distinguishable
 * rather than stacked at one point. A single port sits at the centre of its side.
 *
 * @param rect  the node
 * @param side  which face
 * @param index which port, when the side carries several
 * @param total how many ports share the side
 */
export function portOn(rect: Rect, side: Side, index = 0, total = 1): Point {
  const count = Math.max(1, total);
  const vertical = side === 'left' || side === 'right';
  const span = vertical ? rect.height : rect.width;
  // Inset so the outermost port of a crowded side does not sit on the card's corner radius.
  const usable = span - 16;
  const step = usable / (count + 1);
  const offset = count === 1 ? span / 2 : 8 + step * (index + 1);

  switch (side) {
    case 'top':
      return { x: rect.x + offset, y: rect.y };
    case 'bottom':
      return { x: rect.x + offset, y: rect.y + rect.height };
    case 'left':
      return { x: rect.x, y: rect.y + offset };
    default:
      return { x: rect.x + rect.width, y: rect.y + offset };
  }
}

/**
 * Horizontal bias when choosing which faces an edge connects.
 *
 * <p>A plain {@code |dx| >= |dy|} comparison flips the edge across the node the moment a drag crosses the
 * exact diagonal, which reads as flicker while someone is positioning a node. Requiring the vertical
 * separation to clearly exceed the horizontal one gives a band either side of 45° where nothing changes, and
 * biases the tie toward left-to-right, which is how a workflow is read when nothing suggests otherwise.
 */
const VERTICAL_THRESHOLD = 1.2;

/**
 * Picks the faces an edge should leave from and arrive at.
 *
 * <p>This is what lets one canvas serve both orientations without a mode switch: a node placed to the right
 * is joined right-to-left, one placed below is joined bottom-to-top, and an edge that doubles back leaves the
 * face it is actually heading towards rather than always setting off rightwards.
 *
 * @param from the source node's rectangle
 * @param to   the target node's rectangle
 * @return the side on each node the edge should use
 */
export function facingSides(from: Rect, to: Rect): { from: Side; to: Side } {
  const dx = to.x + to.width / 2 - (from.x + from.width / 2);
  const dy = to.y + to.height / 2 - (from.y + from.height / 2);

  if (Math.abs(dy) > Math.abs(dx) * VERTICAL_THRESHOLD) {
    return dy >= 0 ? { from: 'bottom', to: 'top' } : { from: 'top', to: 'bottom' };
  }
  return dx >= 0 ? { from: 'right', to: 'left' } : { from: 'left', to: 'right' };
}

/** Where an incoming edge terminates by default: the middle of the left edge. */
export function inputPort(rect: Rect): Point {
  return portOn(rect, 'left');
}

/**
 * Where an outgoing edge starts.
 *
 * A node with several named branches spreads its ports down the right edge so that three decision
 * branches are visually distinguishable rather than overlapping at one point. A single-exit node keeps
 * its port centred.
 */
export function outputPort(rect: Rect, index: number, total: number): Point {
  return portOn(rect, 'right', index, total);
}

/**
 * A cubic bezier between two ports.
 *
 * The control points are pushed horizontally by a distance proportional to the gap, which keeps a
 * short edge from bulging and a long one from looking like a straight line through unrelated nodes.
 * Edges that run backwards, which happens whenever a workflow loops, get a wider bow so they are
 * readable instead of collapsing onto the nodes between them.
 */
export function edgePath(from: Point, to: Point, fromSide: Side = 'right', toSide: Side = 'left'): string {
  const fromNormal = sideNormal(fromSide);
  const toNormal = sideNormal(toSide);

  const dx = to.x - from.x;
  const dy = to.y - from.y;
  const distance = Math.hypot(dx, dy);

  // How far the edge travels in the direction it leaves. Negative means the target is behind the face the
  // edge starts from — a loop back — which needs a wider bow or the curve doubles over the node it left.
  const forward = dx * fromNormal.x + dy * fromNormal.y;
  const strength =
    forward < 40
      ? Math.max(90, Math.abs(forward) * 0.6 + 70)
      : Math.max(40, Math.min(160, forward * 0.5));

  // Control points along each port's own normal, so the curve leaves and arrives perpendicular to the face
  // it touches. With both normals horizontal this is exactly the old left-to-right bezier.
  const c1 = { x: from.x + fromNormal.x * strength, y: from.y + fromNormal.y * strength };
  const c2 = { x: to.x + toNormal.x * strength, y: to.y + toNormal.y * strength };

  // Guards a degenerate path when two nodes sit on top of each other; without it the curve collapses to a
  // point and the arrow marker has no direction to orient by.
  if (distance < 1) {
    return `M ${round(from.x)} ${round(from.y)} L ${round(to.x)} ${round(to.y)}`;
  }

  return `M ${round(from.x)} ${round(from.y)} C ${round(c1.x)} ${round(c1.y)}, ${round(c2.x)} ${round(
    c2.y,
  )}, ${round(to.x)} ${round(to.y)}`;
}

/** The midpoint of an edge, where its label is placed. */
export function edgeMidpoint(from: Point, to: Point): Point {
  return { x: (from.x + to.x) / 2, y: (from.y + to.y) / 2 };
}

/** Snaps a coordinate to the grid so hand-placed nodes still line up. */
export function snap(value: number, grid = GRID): number {
  return Math.round(value / grid) * grid;
}

/** Converts a point in screen space to canvas space. */
export function toWorld(point: Point, viewport: Viewport): Point {
  return {
    x: (point.x - viewport.pan.x) / viewport.zoom,
    y: (point.y - viewport.pan.y) / viewport.zoom,
  };
}

export function clampZoom(zoom: number): number {
  return Math.min(MAX_ZOOM, Math.max(MIN_ZOOM, zoom));
}

/**
 * Zooms about a fixed screen point, so the node under the cursor stays under the cursor.
 *
 * Zooming about the origin instead is the single most common reason a canvas feels wrong to use: the
 * content the operator was looking at slides away as they zoom in on it.
 */
export function zoomAt(viewport: Viewport, screenPoint: Point, factor: number): Viewport {
  const zoom = clampZoom(viewport.zoom * factor);
  const applied = zoom / viewport.zoom;
  return {
    zoom,
    pan: {
      x: screenPoint.x - (screenPoint.x - viewport.pan.x) * applied,
      y: screenPoint.y - (screenPoint.y - viewport.pan.y) * applied,
    },
  };
}

/** The smallest rectangle containing every node, or null when there are none. */
export function boundingBox(nodes: WorkflowNode[]): Rect | null {
  if (nodes.length === 0) {
    return null;
  }
  let minX = Number.POSITIVE_INFINITY;
  let minY = Number.POSITIVE_INFINITY;
  let maxX = Number.NEGATIVE_INFINITY;
  let maxY = Number.NEGATIVE_INFINITY;
  for (const node of nodes) {
    const rect = nodeRect(node);
    minX = Math.min(minX, rect.x);
    minY = Math.min(minY, rect.y);
    maxX = Math.max(maxX, rect.x + rect.width);
    maxY = Math.max(maxY, rect.y + rect.height);
  }
  return { x: minX, y: minY, width: maxX - minX, height: maxY - minY };
}

/**
 * A viewport that fits every node inside the given canvas size.
 *
 * Zoom is capped at 1 so fitting a two-node workflow does not magnify it to fill the screen, which
 * looks broken.
 */
export function fitViewport(nodes: WorkflowNode[], canvas: { width: number; height: number }): Viewport {
  const box = boundingBox(nodes);
  if (!box || canvas.width <= 0 || canvas.height <= 0) {
    return { pan: { x: 40, y: 40 }, zoom: 1 };
  }
  const padding = 60;
  const zoom = clampZoom(
    Math.min(
      1,
      (canvas.width - padding * 2) / Math.max(box.width, 1),
      (canvas.height - padding * 2) / Math.max(box.height, 1),
    ),
  );
  return {
    zoom,
    pan: {
      x: (canvas.width - box.width * zoom) / 2 - box.x * zoom,
      y: (canvas.height - box.height * zoom) / 2 - box.y * zoom,
    },
  };
}

/**
 * Assigns coordinates by walking the graph forwards from its start node.
 *
 * Needed because a workflow authored as JSON, or imported from elsewhere, usually has no coordinates,
 * and rendering every node stacked at the origin makes the canvas useless. Nodes are placed in layers
 * by their distance from the start, which for a workflow graph produces close to the arrangement a
 * person would draw.
 *
 * Cycles terminate because a node is only ever assigned a layer once. Anything unreachable, such as a
 * compensation node, is appended in a trailing column rather than dropped.
 */
export function autoLayout(
  nodes: WorkflowNode[],
  connections: WorkflowConnection[],
  direction: LayoutDirection = 'horizontal',
): Map<string, Point> {
  const positions = new Map<string, Point>();
  if (nodes.length === 0) {
    return positions;
  }

  const outgoing = new Map<string, string[]>();
  const hasIncoming = new Set<string>();
  for (const connection of connections) {
    if (!connection.source || !connection.target) {
      continue;
    }
    const targets = outgoing.get(connection.source) ?? [];
    targets.push(connection.target);
    outgoing.set(connection.source, targets);
    hasIncoming.add(connection.target);
  }

  const byId = new Map(nodes.map((node) => [node.id, node]));

  // Start nodes are the only roots when any exist. Treating every node without an incoming edge as a
  // root would put a compensation node in the first column beside Start, implying it runs first.
  const startNodes = nodes.filter((node) => node.type === 'START').map((node) => node.id);
  const roots =
    startNodes.length > 0
      ? startNodes
      : nodes.filter((node) => !hasIncoming.has(node.id)).map((node) => node.id);
  const queue: Array<{ id: string; layer: number }> = (roots.length > 0 ? roots : [nodes[0].id]).map(
    (id) => ({ id, layer: 0 }),
  );

  const layerOf = new Map<string, number>();
  while (queue.length > 0) {
    const { id, layer } = queue.shift()!;
    if (layerOf.has(id) || !byId.has(id)) {
      continue;
    }
    layerOf.set(id, layer);
    for (const target of outgoing.get(id) ?? []) {
      if (!layerOf.has(target)) {
        queue.push({ id: target, layer: layer + 1 });
      }
    }
  }

  // Unreachable nodes go one column past the deepest layer, keeping them visible and out of the way.
  const deepest = layerOf.size > 0 ? Math.max(...layerOf.values()) : 0;
  for (const node of nodes) {
    if (!layerOf.has(node.id)) {
      layerOf.set(node.id, deepest + 1);
    }
  }

  const rows = new Map<number, string[]>();
  for (const node of nodes) {
    const layer = layerOf.get(node.id) ?? 0;
    const column = rows.get(layer) ?? [];
    column.push(node.id);
    rows.set(layer, column);
  }

  const tallest = Math.max(...[...rows.values()].map((column) => column.length));
  for (const [layer, column] of rows) {
    // Each column is centred against the tallest one, so the graph reads along one spine.
    const offset = ((tallest - column.length) * ROW_SPACING) / 2;
    column.forEach((id, index) => {
      // The two directions are the same layout with the axes exchanged: layers advance along the flow and
      // siblings spread across it. Edges need no help to follow — they pick their faces from the positions.
      const alongFlow = 60 + layer * LAYER_SPACING;
      const acrossFlow = 60 + offset + index * ROW_SPACING;
      positions.set(
        id,
        direction === 'vertical'
          ? { x: snap(acrossFlow), y: snap(alongFlow) }
          : { x: snap(alongFlow), y: snap(acrossFlow) },
      );
    });
  }
  return positions;
}

/** True when every node already has usable coordinates, so layout should be left alone. */
export function hasCoordinates(nodes: WorkflowNode[]): boolean {
  return (
    nodes.length > 0 &&
    nodes.every(
      (node) =>
        Number.isFinite(node.presentation?.x as number) &&
        Number.isFinite(node.presentation?.y as number),
    )
  );
}

/**
 * The outgoing branch names of a node.
 *
 * Taken from the node's decision conditions plus any branch already wired, so an edge drawn for a
 * branch that was later renamed still has a port to attach to instead of disappearing.
 */
export function outputPortsOf(node: WorkflowNode, connections: WorkflowConnection[]): string[] {
  const ports: string[] = [];
  for (const condition of node.conditions ?? []) {
    if (condition.branch && !ports.includes(condition.branch)) {
      ports.push(condition.branch);
    }
  }
  if (node.defaultBranch && !ports.includes(node.defaultBranch)) {
    ports.push(node.defaultBranch);
  }
  for (const connection of connections) {
    if (connection.source === node.id && connection.sourcePort && !ports.includes(connection.sourcePort)) {
      ports.push(connection.sourcePort);
    }
  }
  return ports;
}

function numberOr(value: unknown, fallback: number): number {
  return typeof value === 'number' && Number.isFinite(value) ? value : fallback;
}

function round(value: number): number {
  return Math.round(value * 10) / 10;
}
