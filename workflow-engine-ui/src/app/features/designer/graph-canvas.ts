import {
  ChangeDetectionStrategy,
  Component,
  ElementRef,
  computed,
  effect,
  inject,
  input,
  output,
  signal,
  viewChild,
} from '@angular/core';
import { MarketplaceApiService } from '../../core/api/marketplace-api.service';
import { NodeCatalogEntry, categoryColorVar, resolveCatalogEntry } from '../../core/models/node.models';
import { WorkflowConnection, WorkflowNode } from '../../core/models/workflow.models';
import { PluginIcon } from '../../shared/ui/plugin-icon';
import {
  NODE_HEIGHT,
  NODE_WIDTH,
  Point,
  Rect,
  SIDES,
  Side,
  Viewport,
  edgeMidpoint,
  edgePath,
  facingSides,
  fitViewport,
  inputPort,
  nodeRect,
  outputPort,
  portOn,
  outputPortsOf,
  snap,
  toWorld,
  zoomAt,
} from './graph-geometry';

interface RenderedEdge {
  index: number;
  path: string;
  label: string | null;
  labelAt: Point;
  selected: boolean;
  dangling: boolean;
  guarded: boolean;
}

interface RenderedNode {
  node: WorkflowNode;
  x: number;
  y: number;
  color: string;
  icon: string | null;
  ports: string[];
  portPoints: Array<{ name: string; x: number; y: number }>;
  /**
   * Sides an edge actually arrives on, so an inbound marker is drawn where one is and nowhere else.
   *
   * <p>A fixed left-hand marker was honest while every edge arrived from the left. Now that they attach to
   * whichever face is nearest, a marker on a side no edge uses would point at nothing.
   */
  inboundSides: Side[];
  /** The four faces a new connection can be dragged from. */
  outHandles: Side[];
  inputAt: Point;
  /** The operation this node runs, shown under its name. */
  subtitle: string;
  known: boolean;
  isPlugin: boolean;
  /** The node pins a plugin version older than the newest installed one, or one no longer installed. */
  outdated: boolean;
}

type Interaction =
  | { kind: 'none' }
  | { kind: 'pan'; origin: Point; startPan: Point }
  | { kind: 'drag'; nodeId: string; grab: Point; moved: boolean }
  | {
      kind: 'connect';
      source: string;
      sourcePort: string | null;
      /** The face the drag started from, so the preview leaves the node the way the finished edge will. */
      fromSide: Side;
      from: Point;
      to: Point;
    };

/**
 * The workflow canvas.
 *
 * Rendered as an SVG edge layer beneath absolutely positioned HTML nodes, rather than everything in
 * SVG. Nodes carry wrapping text, badges and focusable controls, all of which are painful in SVG and
 * free in HTML, while edges need real curves, which is the opposite. Both layers share one transform
 * so they stay aligned under pan and zoom.
 *
 * No diagram library. A workflow graph needs four interactions, and a dependency that brings its own
 * rendering model, event system and styling would be harder to make consistent with the rest of the
 * application than the geometry it replaces.
 *
 * Accessibility: a canvas is irreducibly visual, so every node is also a focusable button reachable by
 * keyboard, selection is driven by focus, and Delete removes the selection. The property panel beside
 * the canvas is the non-visual path to everything the canvas shows.
 */
@Component({
  selector: 'wf-graph-canvas',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [PluginIcon],
  template: `
    <div
      #host
      class="canvas"
      tabindex="0"
      role="application"
      aria-label="Workflow canvas"
      [class.canvas--connecting]="interaction().kind === 'connect'"
      [class.canvas--panning]="interaction().kind === 'pan'"
      (pointerdown)="onPointerDown($event)"
      (pointermove)="onPointerMove($event)"
      (pointerup)="onPointerUp($event)"
      (pointercancel)="endInteraction()"
      (wheel)="onWheel($event)"
      (dragover)="onDragOver($event)"
      (drop)="onDrop($event)"
      (keydown)="onKeyDown($event)"
    >
      <div class="canvas__viewport" [style.transform]="transform()">
        <svg class="edges" [attr.width]="canvasSize()" [attr.height]="canvasSize()">
          <defs>
            <marker
              id="wf-arrow"
              viewBox="0 0 10 10"
              refX="9"
              refY="5"
              markerWidth="7"
              markerHeight="7"
              orient="auto-start-reverse"
            >
              <path d="M 0 1 L 9 5 L 0 9 z" fill="var(--hl-grey-600)" />
            </marker>
            <marker
              id="wf-arrow-selected"
              viewBox="0 0 10 10"
              refX="9"
              refY="5"
              markerWidth="7"
              markerHeight="7"
              orient="auto-start-reverse"
            >
              <path d="M 0 1 L 9 5 L 0 9 z" fill="var(--hl-blue)" />
            </marker>
          </defs>

          @for (edge of edges(); track edge.index) {
            <g
              class="edge"
              [class.edge--selected]="edge.selected"
              [class.edge--dangling]="edge.dangling"
            >
              <!-- A wide transparent path gives the edge a usable hit area; 2px of curve does not. -->
              <path class="edge__hit" [attr.d]="edge.path" (pointerdown)="selectEdge($event, edge.index)" />
              <path
                class="edge__line"
                [attr.d]="edge.path"
                [attr.marker-end]="edge.selected ? 'url(#wf-arrow-selected)' : 'url(#wf-arrow)'"
              />
              @if (edge.label) {
                <g [attr.transform]="'translate(' + edge.labelAt.x + ',' + edge.labelAt.y + ')'">
                  <rect
                    class="edge__label-bg"
                    [attr.x]="-(edge.label.length * 3.4 + 8)"
                    y="-9"
                    [attr.width]="edge.label.length * 6.8 + 16"
                    height="18"
                    rx="9"
                  />
                  <text class="edge__label" text-anchor="middle" y="4">{{ edge.label }}</text>
                </g>
              }
            </g>
          }

          @if (pendingEdge(); as pending) {
            <path class="edge__pending" [attr.d]="pending" />
          }
        </svg>

        @for (item of renderedNodes(); track item.node.id) {
          <div
            class="node"
            [class.node--selected]="item.node.id === selectedNodeId()"
            [class.node--unknown]="!item.known"
            [style.left.px]="item.x"
            [style.top.px]="item.y"
            [style.width.px]="NODE_WIDTH"
            [style.height.px]="NODE_HEIGHT"
            [style.--node-color]="item.color"
            [attr.data-node-id]="item.node.id"
          >
            <button
              class="node__body"
              type="button"
              [attr.aria-label]="ariaLabel(item)"
              (focus)="nodeSelected.emit(item.node.id)"
            >
              <wf-plugin-icon
                class="node__icon"
                [pluginId]="item.node.pluginId ?? null"
                [icon]="item.icon"
                [size]="80"
              />
            </button>

            <!--
              The name sits outside the card, so the icon gets the whole tile. It is inside the same
              positioned wrapper, and the wrapper's box is still just the card — edges terminate on the
              card and the label overflows below it, which is why nodeRect() knows nothing about it.
            -->
            <span class="node__label" aria-hidden="true">
              <span class="node__name">{{ item.node.name || item.node.id }}</span>
              <!--
                Only when it says something the name does not. A node keeps its operation's display name
                until somebody renames it, so showing both by default printed every label twice.
              -->
              @if (item.subtitle && item.subtitle !== (item.node.name || item.node.id)) {
                <span class="node__type">{{ item.subtitle }}</span>
              }
            </span>

            @if (item.isPlugin) {
              <span
                class="node__badge"
                [attr.title]="
                  'Provided by plugin ' +
                  item.node.pluginId +
                  (item.node.pluginVersion ? ' ' + item.node.pluginVersion : ' (default version)')
                "
                >{{ item.node.pluginVersion ? 'v' + item.node.pluginVersion : 'plugin' }}</span
              >
            }
            @if (!item.known) {
              <span
                class="node__badge node__badge--warning"
                title="No loaded plugin provides this node type. Publishing will fail until its plugin is active."
                >unavailable</span
              >
            } @else if (item.outdated) {
              <span
                class="node__badge node__badge--outdated"
                title="This node pins an older plugin version than the newest installed one. It keeps running that version until you repoint it."
                >outdated</span
              >
            }

            <!--
              Inbound markers, drawn only on the faces edges actually arrive at. Start can never be a
              target, so it never has any.
            -->
            @if (item.node.type !== 'START') {
              @for (side of item.inboundSides; track side) {
                <span
                  class="port port--in"
                  [class]="'port port--in port--' + side"
                  [attr.data-target]="item.node.id"
                  title="Incoming"
                ></span>
              }
            }

            @if (item.ports.length === 0) {
              @if (item.node.type !== 'END') {
                <!--
                  One handle per face. All four are always present so a connection can be started in any
                  direction; they are faint until the node is hovered, which keeps a canvas of forty nodes
                  from looking like a pegboard.
                -->
                @for (side of item.outHandles; track side) {
                  <span
                    class="port port--out"
                    [class]="'port port--out port--' + side"
                    [attr.data-source]="item.node.id"
                    [attr.data-side]="side"
                    title="Drag to connect"
                  ></span>
                }
              }
            } @else {
              <!--
                Named branches keep a fixed home on the right edge rather than moving between faces. Their
                identity is the point — you have to be able to find "approved" twice running — and a handle
                that relocated as its target moved would not be findable. Their edges still attach
                dynamically; only the handle stays put.
              -->
              @for (port of item.portPoints; track port.name) {
                <span
                  class="port port--out port--named port--right"
                  [attr.data-source]="item.node.id"
                  [attr.data-port]="port.name"
                  [attr.data-side]="'right'"
                  [style.top.px]="port.y - item.y"
                  [attr.title]="'Branch: ' + port.name + '. Drag to connect.'"
                >
                  <span class="port__label">{{ port.name }}</span>
                </span>
              }
            }
          </div>
        }
      </div>

      <div class="canvas__controls">
        <div class="btn-group">
          <button class="btn btn--sm" type="button" title="Zoom out" (click)="zoomBy(1 / 1.2)">
            &minus;
          </button>
          <button class="btn btn--sm" type="button" (click)="resetZoom()" title="Reset zoom">
            {{ zoomPercent() }}%
          </button>
          <button class="btn btn--sm" type="button" title="Zoom in" (click)="zoomBy(1.2)">+</button>
        </div>
        <button class="btn btn--sm" type="button" (click)="fit()">Fit</button>
      </div>

      @if (nodes().length === 0) {
        <div class="canvas__hint">
          <p><strong>Drag a node from the palette</strong> to start building.</p>
          <p class="small muted">
            Connect nodes by dragging from a node's right-hand port to another node. Scroll to zoom,
            drag the background to pan.
          </p>
        </div>
      }
    </div>
  `,
  styles: [
    `
      :host {
        display: block;
        position: relative;
        height: 100%;
        min-height: 0;
      }

      .canvas {
        position: relative;
        height: 100%;
        overflow: hidden;
        background-color: var(--surface);
        /* A dot grid reads as a work surface and makes pan and zoom legible. */
        background-image: radial-gradient(var(--hl-grey-300) 1px, transparent 1px);
        background-size: 20px 20px;
        cursor: grab;
        outline: none;
      }

      .canvas:focus-visible {
        box-shadow: inset 0 0 0 2px var(--hl-accent-blue);
      }

      .canvas--panning {
        cursor: grabbing;
      }

      .canvas--connecting {
        cursor: crosshair;
      }

      .canvas__viewport {
        position: absolute;
        top: 0;
        left: 0;
        transform-origin: 0 0;
        will-change: transform;
      }

      .edges {
        position: absolute;
        top: 0;
        left: 0;
        overflow: visible;
        pointer-events: none;
      }

      .edge__hit {
        stroke: transparent;
        stroke-width: 14;
        fill: none;
        pointer-events: stroke;
        cursor: pointer;
      }

      .edge__line {
        stroke: var(--hl-grey-600);
        stroke-width: 1.6;
        fill: none;
      }

      .edge:hover .edge__line {
        stroke: var(--hl-accent-blue-alt);
      }

      .edge--selected .edge__line {
        stroke: var(--hl-blue);
        stroke-width: 2.4;
      }

      /* An edge pointing at a node that no longer exists. Dashed red so it is obviously broken. */
      .edge--dangling .edge__line {
        stroke: var(--hl-error);
        stroke-dasharray: 5 4;
      }

      .edge__pending {
        stroke: var(--hl-green);
        stroke-width: 2;
        stroke-dasharray: 5 4;
        fill: none;
      }

      .edge__label-bg {
        fill: var(--surface);
        stroke: var(--border);
      }

      .edge__label {
        font-family: var(--font-body);
        font-size: 10px;
        fill: var(--hl-grey-800);
      }

      /* Width and height are bound from NODE_WIDTH and NODE_HEIGHT so the geometry module stays the
         single source of truth for node size. */
      .node {
        position: absolute;
      }

      /* A square tile holding only the icon. The name lives in .node__label, outside the card. */
      .node__body {
        display: flex;
        align-items: center;
        justify-content: center;
        width: 100%;
        height: 100%;
        padding: 0;
        background: var(--surface);
        border: 1px solid var(--border-strong);
        border-radius: 14px;
        box-shadow: var(--shadow-sm);
        cursor: grab;
        color: var(--node-color);
        transition:
          border-color 120ms ease,
          box-shadow 120ms ease;
      }

      .node__body:hover {
        border-color: var(--node-color);
      }

      .node__body:focus-visible {
        outline: none;
        box-shadow: var(--focus-ring);
      }

      /* The selected tile is ringed in its own category colour rather than a fixed blue, so selection
         reads at a glance on a canvas where every node is a different colour. */
      .node--selected .node__body {
        border-color: var(--node-color);
        box-shadow:
          0 0 0 2px var(--surface),
          0 0 0 4px var(--node-color);
      }

      .node--unknown .node__body {
        border-style: dashed;
        background: #fffaf3;
      }

      .node__icon {
        color: var(--node-color);
      }

      /* Overflows the card deliberately; pointer-events off so it never steals a drag from the tile. */
      .node__label {
        position: absolute;
        top: calc(100% + 6px);
        left: 50%;
        transform: translateX(-50%);
        width: 150px;
        display: flex;
        flex-direction: column;
        align-items: center;
        gap: 1px;
        pointer-events: none;
        font-family: var(--font-body);
        text-align: center;
      }

      .node__name {
        font-size: var(--text-base);
        font-weight: 600;
        color: var(--text);
        /* Two lines, then ellipsis: one line truncates too many real node names, three crowds the row. */
        display: -webkit-box;
        -webkit-line-clamp: 2;
        -webkit-box-orient: vertical;
        line-clamp: 2;
        overflow: hidden;
        overflow-wrap: anywhere;
        line-height: 1.25;
      }

      .node__type {
        font-family: var(--font-mono);
        font-size: 10px;
        color: var(--text-muted);
        max-width: 100%;
        overflow: hidden;
        text-overflow: ellipsis;
        white-space: nowrap;
      }

      .node__badge {
        position: absolute;
        top: -9px;
        right: 6px;
        padding: 1px 6px;
        border-radius: 9px;
        font-size: 9px;
        font-weight: bold;
        letter-spacing: 0.3px;
        background: var(--hl-grey-200);
        color: var(--hl-grey-800);
        border: 1px solid var(--border);
        white-space: nowrap;
      }

      .node__badge--warning {
        top: -9px;
        right: auto;
        left: 6px;
        background: #fff3e0;
        border-color: var(--hl-orange);
        color: var(--hl-orange-alt);
      }

      /* Quieter than a warning: an outdated pin still runs, it is simply not the newest thing available. */
      .node__badge--outdated {
        top: -9px;
        right: auto;
        left: 6px;
        background: var(--surface);
        border-style: dashed;
        border-color: var(--hl-orange);
        color: var(--hl-orange-alt);
      }

      .port {
        position: absolute;
        width: 11px;
        height: 11px;
        border-radius: 50%;
        background: var(--surface);
        border: 2px solid var(--node-color);
        transform: translate(-50%, -50%);
      }

      /* Placement is by side rather than by a single hardcoded edge, so the same markup serves all four. */
      .port--left {
        left: 0;
        top: 50%;
      }

      .port--right {
        left: 100%;
        top: 50%;
      }

      .port--top {
        left: 50%;
        top: 0;
      }

      .port--bottom {
        left: 50%;
        top: 100%;
      }

      /* The two ends are shaped differently on purpose: a chevron points into the node and a circle is
         something to grab. Direction is then readable from the node alone, without following the edge. */
      .port--in {
        cursor: default;
        width: 9px;
        height: 12px;
        border: none;
        border-radius: 0;
        background: var(--node-color);
        clip-path: polygon(0 0, 100% 50%, 0 100%);
      }

      /* The chevron always points inward, so each side rotates it rather than needing its own clip-path. */
      .port--in.port--right {
        transform: translate(-50%, -50%) rotate(180deg);
      }

      .port--in.port--top {
        transform: translate(-50%, -50%) rotate(90deg);
      }

      .port--in.port--bottom {
        transform: translate(-50%, -50%) rotate(-90deg);
      }

      .port--out {
        cursor: crosshair;
        /* Faint until the node is hovered: four handles on every node at full strength would read as
           clutter, but hiding them entirely leaves no clue that a connection can start anywhere. */
        opacity: 0.28;
        transition:
          opacity 120ms ease,
          transform 120ms ease;
      }

      .node:hover .port--out,
      .node--selected .port--out,
      .canvas--connecting .port--out {
        opacity: 1;
      }

      /* A named branch is labelled and always relevant, so it is never faded out. */
      .port--named {
        opacity: 1;
      }

      .port--out:hover {
        background: var(--hl-green);
        border-color: var(--hl-green);
        opacity: 1;
        transform: translate(-50%, -50%) scale(1.4);
      }

      .port__label {
        position: absolute;
        left: 14px;
        top: 50%;
        transform: translateY(-50%);
        font-size: 9px;
        font-family: var(--font-body);
        color: var(--hl-grey-700);
        background: var(--surface);
        padding: 0 3px;
        border-radius: 3px;
        white-space: nowrap;
        pointer-events: none;
      }

      .canvas__controls {
        position: absolute;
        right: var(--space-3);
        bottom: var(--space-3);
        display: flex;
        gap: var(--space-2);
        background: var(--surface);
        padding: var(--space-1);
        border-radius: var(--radius);
        box-shadow: var(--shadow-sm);
        border: 1px solid var(--border);
      }

      .canvas__hint {
        position: absolute;
        inset: 0;
        display: flex;
        flex-direction: column;
        align-items: center;
        justify-content: center;
        gap: var(--space-2);
        text-align: center;
        pointer-events: none;
        color: var(--text-muted);
      }

      .canvas__hint p {
        margin: 0;
        max-width: 44ch;
      }
    `,
  ],
})
export class GraphCanvas {
  readonly nodes = input.required<WorkflowNode[]>();
  readonly connections = input.required<WorkflowConnection[]>();
  readonly catalog = input<NodeCatalogEntry[]>([]);

  /**
   * Which plugin versions are installed, read from the shared store rather than passed in.
   *
   * The canvas already takes the node catalogue as an input because the designer owns that fetch. This is
   * different: it is a cache several unrelated screens share, and threading it through the designer purely
   * to reach a badge would make the designer responsible for something it does not otherwise use.
   */
  private readonly marketplace = inject(MarketplaceApiService);
  protected readonly pluginStatus = this.marketplace.byPluginId;
  readonly selectedNodeId = input<string | null>(null);
  readonly selectedConnectionIndex = input<number | null>(null);

  readonly nodeSelected = output<string>();
  readonly connectionSelected = output<number>();
  readonly backgroundClicked = output<void>();
  readonly nodeMoved = output<{ id: string; point: Point }>();
  readonly connectRequested = output<{ source: string; sourcePort: string | null; target: string }>();
  readonly nodeDropped = output<{ nodeType: string; point: Point }>();
  readonly deleteRequested = output<void>();

  protected readonly NODE_HEIGHT = NODE_HEIGHT;
  protected readonly NODE_WIDTH = NODE_WIDTH;

  private readonly hostRef = viewChild.required<ElementRef<HTMLElement>>('host');

  private readonly viewportState = signal<Viewport>({ pan: { x: 40, y: 40 }, zoom: 1 });
  private readonly interactionState = signal<Interaction>({ kind: 'none' });
  private fitted = false;

  protected readonly interaction = this.interactionState.asReadonly();

  /**
   * Fits the view the first time a non-empty graph arrives.
   *
   * Only once: re-fitting on every change would yank the viewport away from the operator each time
   * they added a node.
   */
  constructor() {
    effect(() => {
      const nodes = this.nodes();
      if (this.fitted || nodes.length === 0) {
        return;
      }
      const host = this.hostRef().nativeElement;
      if (host.clientWidth === 0) {
        return;
      }
      this.fitted = true;
      this.viewportState.set(
        fitViewport(nodes, { width: host.clientWidth, height: host.clientHeight }),
      );
    });
  }

  protected readonly transform = computed(() => {
    const { pan, zoom } = this.viewportState();
    return `translate(${pan.x}px, ${pan.y}px) scale(${zoom})`;
  });

  protected readonly zoomPercent = computed(() => Math.round(this.viewportState().zoom * 100));

  /** The SVG layer is sized generously so edges are never clipped as the graph grows. */
  protected readonly canvasSize = computed(() => {
    const nodes = this.nodes();
    const furthest = nodes.reduce((max, node) => {
      const rect = nodeRect(node);
      return Math.max(max, rect.x + rect.width, rect.y + rect.height);
    }, 0);
    return Math.max(2000, furthest + 400);
  });

  protected readonly renderedNodes = computed<RenderedNode[]>(() => {
    const catalog = this.catalog();
    const connections = this.connections();
    return this.nodes().map((node) => {
      const rect = nodeRect(node);
      // Resolved by node type or by plugin coordinate, so a node using the generic PLUGIN marker type
      // is still recognised and still gets its icon, category tint and schema.
      const entry = resolveCatalogEntry(node, catalog);
      const ports = outputPortsOf(node, connections);
      return {
        node,
        x: rect.x,
        y: rect.y,
        color: categoryColorVar(entry?.category ?? fallbackCategory(node.type)),
        icon: entry?.icon ?? fallbackIcon(node.type),
        ports,
        portPoints: ports.map((name, index) => {
          const point = outputPort(rect, index, ports.length);
          return { name, x: point.x, y: point.y };
        }),
        inboundSides: inboundSidesOf(node, rect, connections, this.nodes()),
        outHandles: [...SIDES],
        inputAt: inputPort(rect),
        // The operation's display name, which is what the second line of the card shows. A plugin
        // contributes one node type per operation, so the entry's display name IS the operation —
        // "Delete Kubernetes Pod" rather than the raw K8S_DELETE_POD an author never typed. Falls back
        // to the type when no plugin provides it, which is the only case where the raw value is all
        // there is to show.
        subtitle: entry?.displayName ?? node.type,
        // A node type with no catalogue entry means its plugin is not loaded. Flagged rather than
        // hidden, because the node is still in the definition and publishing will reject it.
        known: !!entry,
        isPlugin: !!node.pluginId,
        outdated: this.isOutdated(node),
      };
    });
  });

  /**
   * Whether a node pins a plugin version that is no longer the newest installed one.
   *
   * Only pinned nodes can be outdated. An unpinned node follows the default version by definition, so there
   * is nothing about it that could fall behind.
   */
  private isOutdated(node: WorkflowNode): boolean {
    if (!node.pluginId || !node.pluginVersion) {
      return false;
    }
    const view = this.pluginStatus().get(node.pluginId);
    const newest = view?.installedVersions.find(
      (entry) => entry.state === 'ACTIVE' || entry.state === 'INSTALLED',
    )?.version;
    return !!newest && newest !== node.pluginVersion;
  }

  protected readonly edges = computed<RenderedEdge[]>(() => {
    const byId = new Map(this.nodes().map((node) => [node.id, node]));
    const connections = this.connections();
    const selected = this.selectedConnectionIndex();

    return connections.map((connection, index) => {
      const source = byId.get(connection.source);
      const target = byId.get(connection.target);
      if (!source || !target) {
        return {
          index,
          path: '',
          label: connection.sourcePort ?? null,
          labelAt: { x: 0, y: 0 },
          selected: selected === index,
          dangling: true,
          guarded: !!connection.condition,
        };
      }
      const sourceRect = nodeRect(source);
      const targetRect = nodeRect(target);
      const ports = outputPortsOf(source, connections);
      const portIndex = connection.sourcePort ? ports.indexOf(connection.sourcePort) : -1;

      // The faces are chosen from where the two nodes actually sit, so the same canvas reads correctly
      // whether an author lays a workflow out left to right or top to bottom. Nothing is stored: move a
      // node and its edges re-attach on the next render.
      const sides = facingSides(sourceRect, targetRect);
      const from = portOn(sourceRect, sides.from, Math.max(0, portIndex), Math.max(1, ports.length));
      const to = portOn(targetRect, sides.to);
      const label = connection.sourcePort ?? (connection.condition ? 'guarded' : null);
      return {
        index,
        path: edgePath(from, to, sides.from, sides.to),
        label,
        labelAt: edgeMidpoint(from, to),
        selected: selected === index,
        dangling: false,
        guarded: !!connection.condition,
      };
    });
  });

  protected readonly pendingEdge = computed(() => {
    const interaction = this.interactionState();
    if (interaction.kind !== 'connect') {
      return null;
    }
    // The far end has no node yet, so it gets the opposite face — the curve then arrives at the cursor
    // from the direction a real edge would.
    return edgePath(interaction.from, interaction.to, interaction.fromSide,
      opposite(interaction.fromSide));
  });

  // ------------------------------------------------------------- interactions

  protected onPointerDown(event: PointerEvent): void {
    const target = event.target as HTMLElement;
    const host = this.hostRef().nativeElement;

    const outPort = target.closest<HTMLElement>('[data-source]');
    if (outPort) {
      const source = outPort.dataset['source']!;
      const sourcePort = outPort.dataset['port'] ?? null;
      const fromSide = (outPort.dataset['side'] as Side | undefined) ?? 'right';
      // Anchored to the handle rather than the cursor, so the preview starts where the finished edge will
      // and does not jump when the pointer moves off the node.
      const sourceNode = this.nodes().find((node) => node.id === source);
      const at = this.worldPoint(event);
      const from = sourceNode ? portOn(nodeRect(sourceNode), fromSide) : at;
      this.interactionState.set({ kind: 'connect', source, sourcePort, fromSide, from, to: at });
      host.setPointerCapture(event.pointerId);
      event.preventDefault();
      event.stopPropagation();
      return;
    }

    const nodeElement = target.closest<HTMLElement>('[data-node-id]');
    if (nodeElement) {
      const id = nodeElement.dataset['nodeId']!;
      const node = this.nodes().find((candidate) => candidate.id === id);
      if (node) {
        const world = this.worldPoint(event);
        const rect = nodeRect(node);
        this.nodeSelected.emit(id);
        this.interactionState.set({
          kind: 'drag',
          nodeId: id,
          grab: { x: world.x - rect.x, y: world.y - rect.y },
          moved: false,
        });
        host.setPointerCapture(event.pointerId);
        event.preventDefault();
      }
      return;
    }

    // Background: pan, and treat it as clearing the selection.
    this.interactionState.set({
      kind: 'pan',
      origin: { x: event.clientX, y: event.clientY },
      startPan: { ...this.viewportState().pan },
    });
    host.setPointerCapture(event.pointerId);
    this.backgroundClicked.emit();
  }

  protected onPointerMove(event: PointerEvent): void {
    const interaction = this.interactionState();
    switch (interaction.kind) {
      case 'pan': {
        const dx = event.clientX - interaction.origin.x;
        const dy = event.clientY - interaction.origin.y;
        this.viewportState.update((viewport) => ({
          ...viewport,
          pan: { x: interaction.startPan.x + dx, y: interaction.startPan.y + dy },
        }));
        break;
      }
      case 'drag': {
        const world = this.worldPoint(event);
        const point = {
          x: snap(world.x - interaction.grab.x),
          y: snap(world.y - interaction.grab.y),
        };
        this.interactionState.set({ ...interaction, moved: true });
        this.nodeMoved.emit({ id: interaction.nodeId, point });
        break;
      }
      case 'connect': {
        this.interactionState.set({ ...interaction, to: this.worldPoint(event) });
        break;
      }
      default:
        break;
    }
  }

  protected onPointerUp(event: PointerEvent): void {
    const interaction = this.interactionState();
    if (interaction.kind === 'connect') {
      // Dropping anywhere on the target node connects, not only on its 11 pixel port. Requiring
      // pixel accuracy to draw an edge is the fastest way to make a canvas frustrating.
      const element = document.elementFromPoint(event.clientX, event.clientY) as HTMLElement | null;
      const targetElement = element?.closest<HTMLElement>('[data-node-id]');
      const target = targetElement?.dataset['nodeId'];
      if (target && target !== interaction.source) {
        this.connectRequested.emit({
          source: interaction.source,
          sourcePort: interaction.sourcePort,
          target,
        });
      }
    }
    this.endInteraction();
  }

  protected endInteraction(): void {
    if (this.interactionState().kind !== 'none') {
      this.interactionState.set({ kind: 'none' });
    }
  }

  protected onWheel(event: WheelEvent): void {
    event.preventDefault();
    const host = this.hostRef().nativeElement.getBoundingClientRect();
    const at = { x: event.clientX - host.left, y: event.clientY - host.top };
    const factor = event.deltaY < 0 ? 1.12 : 1 / 1.12;
    this.viewportState.update((viewport) => zoomAt(viewport, at, factor));
  }

  protected onDragOver(event: DragEvent): void {
    // Both calls are required for a drop to be accepted in every browser.
    event.preventDefault();
    if (event.dataTransfer) {
      event.dataTransfer.dropEffect = 'copy';
    }
  }

  protected onDrop(event: DragEvent): void {
    event.preventDefault();
    const nodeType = event.dataTransfer?.getData('text/plain');
    if (!nodeType) {
      return;
    }
    const world = this.worldPoint(event);
    // Drop point is the cursor, so centre the node on it rather than hanging it off the corner.
    this.nodeDropped.emit({
      nodeType,
      point: { x: snap(world.x - NODE_WIDTH / 2), y: snap(world.y - NODE_HEIGHT / 2) },
    });
  }

  protected onKeyDown(event: KeyboardEvent): void {
    if (event.key === 'Delete' || event.key === 'Backspace') {
      const target = event.target as HTMLElement;
      // Never swallow a keystroke meant for a text field that happens to be inside the canvas.
      if (target.tagName === 'INPUT' || target.tagName === 'TEXTAREA') {
        return;
      }
      event.preventDefault();
      this.deleteRequested.emit();
      return;
    }
    if (event.key === 'Escape') {
      this.endInteraction();
      this.backgroundClicked.emit();
    }
  }

  protected selectEdge(event: PointerEvent, index: number): void {
    event.stopPropagation();
    this.connectionSelected.emit(index);
  }

  protected zoomBy(factor: number): void {
    const host = this.hostRef().nativeElement;
    const centre = { x: host.clientWidth / 2, y: host.clientHeight / 2 };
    this.viewportState.update((viewport) => zoomAt(viewport, centre, factor));
  }

  protected resetZoom(): void {
    this.viewportState.update((viewport) => ({ ...viewport, zoom: 1 }));
  }

  /** Fits every node into view. Also the escape hatch when the operator has panned into empty space. */
  fit(): void {
    const host = this.hostRef().nativeElement;
    this.viewportState.set(
      fitViewport(this.nodes(), { width: host.clientWidth, height: host.clientHeight }),
    );
  }

  protected ariaLabel(item: RenderedNode): string {
    const parts = [item.node.name || item.node.id, item.node.type];
    if (item.isPlugin) {
      parts.push(`plugin ${item.node.pluginId} ${item.node.pluginVersion ?? 'default version'}`);
    }
    if (!item.known) {
      parts.push('node type unavailable');
    }
    return parts.join(', ');
  }

  private worldPoint(event: PointerEvent | DragEvent): Point {
    const host = this.hostRef().nativeElement.getBoundingClientRect();
    return toWorld(
      { x: event.clientX - host.left, y: event.clientY - host.top },
      this.viewportState(),
    );
  }
}

/**
 * The distinct sides that incoming edges attach to for one node.
 *
 * <p>Computed rather than fixed, because which face an edge arrives on now depends on where its source sits.
 * Drawing the marker only where an edge lands keeps the node honest at four sides instead of wrong at one.
 */
function inboundSidesOf(
  node: WorkflowNode,
  rect: Rect,
  connections: WorkflowConnection[],
  nodes: WorkflowNode[],
): Side[] {
  const sides = new Set<Side>();
  for (const connection of connections) {
    if (connection.target !== node.id) {
      continue;
    }
    const source = nodes.find((candidate) => candidate.id === connection.source);
    if (!source) {
      continue;
    }
    sides.add(facingSides(nodeRect(source), rect).to);
  }
  return SIDES.filter((side) => sides.has(side));
}

/** The face opposite a given one. */
function opposite(side: Side): Side {
  switch (side) {
    case "left":
      return "right";
    case "right":
      return "left";
    case "top":
      return "bottom";
    default:
      return "top";
  }
}

/** An icon hint for a node with no catalogue entry, so an unloaded plugin still gets a sensible shape. */
function fallbackIcon(nodeType: string): string {
  return nodeType === 'PLUGIN' ? 'default' : nodeType;
}

/** A sensible tint for a node type with no catalogue entry, so an unloaded plugin still reads correctly. */
function fallbackCategory(nodeType: string): string {
  switch (nodeType) {
    case 'START':
    case 'END':
    case 'DECISION':
      return 'Flow';
    case 'FORM':
      return 'Human';
    default:
      return 'General';
  }
}
