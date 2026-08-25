import { Injectable, computed, signal } from '@angular/core';
import { NodeCatalogEntry } from '../../core/models/node.models';
import {
  WorkflowConnection,
  WorkflowNode,
  WorkflowRequest,
  WorkflowResponse,
  WorkflowStatus,
  WorkflowTrigger,
} from '../../core/models/workflow.models';
import { SchemaForm } from '../../shared/forms/schema-form';
import { Point, autoLayout, hasCoordinates, snap } from './graph-geometry';

/**
 * The workflow being edited.
 *
 * Provided by the designer component rather than at the root, so navigating away discards the draft
 * instead of leaving a half-edited graph to reappear later under a different workflow's name.
 *
 * The store holds only the definition. Persistence, validation and publishing are the component's job,
 * which keeps this class free of HTTP and therefore trivially testable.
 */
@Injectable()
export class DesignerStore {
  private readonly nodesState = signal<WorkflowNode[]>([]);
  private readonly connectionsState = signal<WorkflowConnection[]>([]);
  private readonly nameState = signal<string>('Untitled workflow');
  private readonly descriptionState = signal<string>('');
  private readonly variablesState = signal<Record<string, unknown>>({});
  private readonly triggersState = signal<WorkflowTrigger[]>([]);
  private readonly metadataState = signal<Record<string, unknown>>({});

  private readonly workflowIdState = signal<string | null>(null);
  private readonly statusState = signal<WorkflowStatus>('DRAFT');
  private readonly publishedVersionState = signal<number | null>(null);
  /** The draft version number: what the next publish will produce, and the version files attach to. */
  private readonly versionState = signal<number>(1);
  private readonly dirtyState = signal(false);

  private readonly selectedNodeIdState = signal<string | null>(null);
  private readonly selectedConnectionState = signal<number | null>(null);

  readonly nodes = this.nodesState.asReadonly();
  readonly connections = this.connectionsState.asReadonly();
  readonly name = this.nameState.asReadonly();
  readonly description = this.descriptionState.asReadonly();
  readonly variables = this.variablesState.asReadonly();
  readonly triggers = this.triggersState.asReadonly();
  readonly workflowId = this.workflowIdState.asReadonly();
  readonly status = this.statusState.asReadonly();
  readonly publishedVersion = this.publishedVersionState.asReadonly();
  readonly version = this.versionState.asReadonly();
  readonly dirty = this.dirtyState.asReadonly();
  readonly selectedNodeId = this.selectedNodeIdState.asReadonly();
  readonly selectedConnectionIndex = this.selectedConnectionState.asReadonly();

  readonly selectedNode = computed(() => {
    const id = this.selectedNodeIdState();
    return id ? (this.nodesState().find((node) => node.id === id) ?? null) : null;
  });

  readonly isNew = computed(() => this.workflowIdState() === null);

  /**
   * Problems the designer can see without asking the engine.
   *
   * Deliberately a subset of what the engine validates, and never a replacement for it: publishing is
   * the authority. These are the structural mistakes worth flagging while the operator is still
   * drawing, because each one is invisible on the canvas.
   */
  readonly localIssues = computed<string[]>(() => {
    const issues: string[] = [];
    const nodes = this.nodesState();
    const connections = this.connectionsState();

    if (!this.nameState().trim()) {
      issues.push('The workflow needs a name.');
    }
    if (nodes.length === 0) {
      issues.push('The workflow has no nodes.');
      return issues;
    }

    const starts = nodes.filter((node) => node.type === 'START');
    if (starts.length === 0) {
      issues.push('Add a Start node: a workflow needs exactly one.');
    } else if (starts.length > 1) {
      issues.push(`There are ${starts.length} Start nodes; a workflow needs exactly one.`);
    }
    if (!nodes.some((node) => node.type === 'END')) {
      issues.push('Add an End node: a workflow needs at least one.');
    }

    const compensations = new Set(
      nodes.map((node) => node.compensationNodeId).filter((id): id is string => !!id),
    );
    const targeted = new Set(connections.map((connection) => connection.target));
    for (const node of nodes) {
      if (node.type === 'START') {
        continue;
      }
      if (!targeted.has(node.id) && !compensations.has(node.id)) {
        issues.push(`"${label(node)}" has no incoming connection.`);
      }
    }
    for (const node of nodes) {
      if (node.type === 'END') {
        continue;
      }
      if (!connections.some((connection) => connection.source === node.id)) {
        issues.push(`"${label(node)}" has no outgoing connection.`);
      }
    }
    for (const node of nodes) {
      if (node.type === 'DECISION' && (node.conditions ?? []).length === 0 && !node.defaultBranch) {
        issues.push(`Decision "${label(node)}" has no conditions and no default branch.`);
      }
    }
    return issues;
  });

  // ------------------------------------------------------------------- loading

  /** Loads an existing workflow, laying it out when it has no stored coordinates. */
  load(workflow: WorkflowResponse): void {
    this.workflowIdState.set(workflow.id);
    this.statusState.set(workflow.status);
    this.publishedVersionState.set(workflow.publishedVersion);
    this.versionState.set(workflow.version ?? 1);
    this.nameState.set(workflow.name ?? '');
    this.descriptionState.set(workflow.description ?? '');
    this.variablesState.set({ ...(workflow.variables ?? {}) });
    this.triggersState.set([...(workflow.triggers ?? [])]);
    this.metadataState.set({ ...(workflow.metadata ?? {}) });
    this.setGraph(workflow.nodes ?? [], workflow.connections ?? []);
    this.dirtyState.set(false);
    this.clearSelection();
  }

  /** Starts a blank workflow with the Start and End nodes already placed. */
  startBlank(): void {
    this.workflowIdState.set(null);
    this.statusState.set('DRAFT');
    this.publishedVersionState.set(null);
    this.versionState.set(1);
    this.nameState.set('Untitled workflow');
    this.descriptionState.set('');
    this.variablesState.set({});
    this.triggersState.set([]);
    this.metadataState.set({});
    this.nodesState.set([
      {
        id: 'start-1',
        type: 'START',
        name: 'Start',
        configuration: {},
        presentation: { x: 80, y: 160 },
      },
      {
        id: 'end-1',
        type: 'END',
        name: 'End',
        configuration: {},
        presentation: { x: 480, y: 160 },
      },
    ]);
    this.connectionsState.set([]);
    this.dirtyState.set(false);
    this.clearSelection();
  }

  /** Replaces the graph, applying automatic layout when coordinates are missing. */
  setGraph(nodes: WorkflowNode[], connections: WorkflowConnection[]): void {
    const copied = nodes.map((node) => ({ ...node }));
    if (!hasCoordinates(copied)) {
      const positions = autoLayout(copied, connections);
      for (const node of copied) {
        const point = positions.get(node.id);
        if (point) {
          node.presentation = { ...(node.presentation ?? {}), x: point.x, y: point.y };
        }
      }
    }
    this.nodesState.set(copied);
    this.connectionsState.set(connections.map((connection) => ({ ...connection })));
  }

  /** Re-runs layout over the current graph, discarding hand placement. */
  relayout(): void {
    const nodes = this.nodesState().map((node) => ({ ...node, presentation: { ...node.presentation } }));
    const positions = autoLayout(nodes, this.connectionsState());
    for (const node of nodes) {
      const point = positions.get(node.id);
      if (point) {
        node.presentation = { ...(node.presentation ?? {}), x: point.x, y: point.y };
      }
    }
    this.nodesState.set(nodes);
    this.touch();
  }

  // -------------------------------------------------------------------- nodes

  /**
   * Adds a node for a catalogue entry.
   *
   * A plugin-contributed type is recorded with its plugin id and the exact version that is loaded, so
   * the node is pinned from the moment it is created. The alternative, leaving the version blank, would
   * silently re-point the node at whatever becomes the default later.
   */
  addNode(entry: NodeCatalogEntry, at: Point): WorkflowNode {
    const node: WorkflowNode = {
      id: this.uniqueId(entry.nodeType),
      type: entry.nodeType,
      name: entry.displayName,
      configuration: SchemaForm.withDefaults(entry.configurationSchema, {}),
      presentation: { x: snap(at.x), y: snap(at.y) },
    };
    if (entry.source === 'PLUGIN') {
      node.pluginId = entry.pluginId ?? undefined;
      node.pluginVersion = entry.pluginVersion ?? undefined;
    }
    if (entry.nodeType === 'DECISION') {
      node.conditions = [];
    }
    if (entry.nodeType === 'FORM') {
      /*
       * No formId. It used to default to the node's own id, which was reasonable when a "form" was just a
       * name a client submitted against, and became actively misleading once forms were real documents: the
       * node looked configured, passed the "must declare a formId" check, and resolved to no published form
       * at run time. Left blank, the picker says "Select a form" and publishing refuses until one is chosen.
       */
      node.waitForInput = true;
    }
    this.nodesState.update((nodes) => [...nodes, node]);
    this.selectNode(node.id);
    this.touch();
    return node;
  }

  updateNode(id: string, patch: Partial<WorkflowNode>): void {
    this.nodesState.update((nodes) =>
      nodes.map((node) => (node.id === id ? { ...node, ...patch } : node)),
    );
    this.touch();
  }

  /**
   * Renames a node's id, rewriting every edge and compensation reference that pointed at it.
   *
   * Node ids appear in connections, in compensation references and in `${node.<id>.*}` variable paths.
   * The first two are rewritten here; variable paths in expressions are not, because rewriting strings
   * inside author-written expressions would do more damage than leaving them. The property panel warns
   * about that instead.
   */
  renameNode(oldId: string, newId: string): boolean {
    const trimmed = newId.trim();
    if (!trimmed || trimmed === oldId) {
      return false;
    }
    if (this.nodesState().some((node) => node.id === trimmed)) {
      return false;
    }
    this.nodesState.update((nodes) =>
      nodes.map((node) => {
        const next = { ...node };
        if (next.id === oldId) {
          next.id = trimmed;
        }
        if (next.compensationNodeId === oldId) {
          next.compensationNodeId = trimmed;
        }
        return next;
      }),
    );
    this.connectionsState.update((connections) =>
      connections.map((connection) => ({
        ...connection,
        source: connection.source === oldId ? trimmed : connection.source,
        target: connection.target === oldId ? trimmed : connection.target,
      })),
    );
    if (this.selectedNodeIdState() === oldId) {
      this.selectedNodeIdState.set(trimmed);
    }
    this.touch();
    return true;
  }

  moveNode(id: string, at: Point): void {
    this.nodesState.update((nodes) =>
      nodes.map((node) =>
        node.id === id
          ? { ...node, presentation: { ...(node.presentation ?? {}), x: at.x, y: at.y } }
          : node,
      ),
    );
    this.touch();
  }

  /** Removes a node and every edge attached to it, so no edge is left pointing at nothing. */
  removeNode(id: string): void {
    this.nodesState.update((nodes) => nodes.filter((node) => node.id !== id));
    this.connectionsState.update((connections) =>
      connections.filter((connection) => connection.source !== id && connection.target !== id),
    );
    if (this.selectedNodeIdState() === id) {
      this.clearSelection();
    }
    this.touch();
  }

  duplicateNode(id: string): void {
    const source = this.nodesState().find((node) => node.id === id);
    if (!source) {
      return;
    }
    const copy: WorkflowNode = {
      ...structuredCloneSafe(source),
      id: this.uniqueId(source.type),
      presentation: {
        ...(source.presentation ?? {}),
        x: numberOr(source.presentation?.x, 0) + 40,
        y: numberOr(source.presentation?.y, 0) + 60,
      },
    };
    this.nodesState.update((nodes) => [...nodes, copy]);
    this.selectNode(copy.id);
    this.touch();
  }

  // -------------------------------------------------------------- connections

  /**
   * Connects two nodes.
   *
   * Rejects self-loops and exact duplicates. A second edge from the same port to a different target is
   * allowed, because the engine takes the first matching edge and an author may be mid-rewire; the
   * local issue list and the engine's validator both report the ambiguity.
   */
  connect(source: string, target: string, sourcePort: string | null): boolean {
    if (!source || !target || source === target) {
      return false;
    }
    const exists = this.connectionsState().some(
      (connection) =>
        connection.source === source &&
        connection.target === target &&
        (connection.sourcePort ?? null) === (sourcePort ?? null),
    );
    if (exists) {
      return false;
    }
    this.connectionsState.update((connections) => [
      ...connections,
      { source, target, sourcePort: sourcePort ?? null },
    ]);
    this.touch();
    return true;
  }

  updateConnection(index: number, patch: Partial<WorkflowConnection>): void {
    this.connectionsState.update((connections) =>
      connections.map((connection, position) =>
        position === index ? { ...connection, ...patch } : connection,
      ),
    );
    this.touch();
  }

  removeConnection(index: number): void {
    this.connectionsState.update((connections) =>
      connections.filter((_, position) => position !== index),
    );
    if (this.selectedConnectionState() === index) {
      this.clearSelection();
    }
    this.touch();
  }

  // --------------------------------------------------------------- selection

  selectNode(id: string | null): void {
    this.selectedNodeIdState.set(id);
    this.selectedConnectionState.set(null);
  }

  selectConnection(index: number | null): void {
    this.selectedConnectionState.set(index);
    this.selectedNodeIdState.set(null);
  }

  clearSelection(): void {
    this.selectedNodeIdState.set(null);
    this.selectedConnectionState.set(null);
  }

  // ------------------------------------------------------------- definition

  setName(name: string): void {
    this.nameState.set(name);
    this.touch();
  }

  setDescription(description: string): void {
    this.descriptionState.set(description);
    this.touch();
  }

  setVariables(variables: Record<string, unknown>): void {
    this.variablesState.set(variables);
    this.touch();
  }

  setTriggers(triggers: WorkflowTrigger[]): void {
    this.triggersState.set(triggers);
    this.touch();
  }

  /** The payload for create or update. Engine-owned fields are absent by construction. */
  toRequest(): WorkflowRequest {
    return {
      name: this.nameState().trim(),
      description: this.descriptionState().trim() || null,
      nodes: this.nodesState(),
      connections: this.connectionsState(),
      variables: this.variablesState(),
      triggers: this.triggersState(),
      metadata: this.metadataState(),
    };
  }

  exportJson(): string {
    return JSON.stringify(this.toRequest(), null, 2);
  }

  /**
   * Replaces the draft from pasted or imported JSON.
   *
   * Accepts both a request payload and a full workflow response, because the obvious thing to paste is
   * whatever the API returned. Engine-owned fields in a response are ignored rather than trusted.
   *
   * @returns an error message, or null on success
   */
  importJson(text: string): string | null {
    let parsed: unknown;
    try {
      parsed = JSON.parse(text);
    } catch (error) {
      return error instanceof Error ? error.message : 'Not valid JSON';
    }
    if (!parsed || typeof parsed !== 'object') {
      return 'Expected a JSON object describing a workflow.';
    }
    const candidate = parsed as Partial<WorkflowResponse> & Partial<WorkflowRequest>;
    if (!Array.isArray(candidate.nodes)) {
      return 'The JSON has no "nodes" array.';
    }
    this.nameState.set(candidate.name?.trim() || 'Imported workflow');
    this.descriptionState.set(candidate.description ?? '');
    this.variablesState.set({ ...(candidate.variables ?? {}) });
    this.triggersState.set([...(candidate.triggers ?? [])]);
    this.metadataState.set({ ...(candidate.metadata ?? {}) });
    this.setGraph(candidate.nodes as WorkflowNode[], (candidate.connections ?? []) as WorkflowConnection[]);
    this.clearSelection();
    this.touch();
    return null;
  }

  /** Called after a successful save, so the unsaved-changes marker clears. */
  markSaved(workflow: WorkflowResponse): void {
    this.workflowIdState.set(workflow.id);
    this.statusState.set(workflow.status);
    this.publishedVersionState.set(workflow.publishedVersion);
    this.versionState.set(workflow.version ?? 1);
    this.dirtyState.set(false);
  }

  markStatus(status: WorkflowStatus, publishedVersion: number | null): void {
    this.statusState.set(status);
    this.publishedVersionState.set(publishedVersion);
  }

  private touch(): void {
    this.dirtyState.set(true);
  }

  /** Produces a readable, unique node id from a node type: `SENDGRID_EMAIL` becomes `sendgrid-email-1`. */
  private uniqueId(nodeType: string): string {
    const base = nodeType.toLowerCase().replace(/[^a-z0-9]+/g, '-').replace(/^-|-$/g, '') || 'node';
    const taken = new Set(this.nodesState().map((node) => node.id));
    let index = 1;
    while (taken.has(`${base}-${index}`)) {
      index++;
    }
    return `${base}-${index}`;
  }
}

function label(node: WorkflowNode): string {
  return node.name?.trim() || node.id;
}

function numberOr(value: unknown, fallback: number): number {
  return typeof value === 'number' && Number.isFinite(value) ? value : fallback;
}

/** structuredClone is not available in every supported browser; JSON round-trip is enough here. */
function structuredCloneSafe<T>(value: T): T {
  return JSON.parse(JSON.stringify(value)) as T;
}
