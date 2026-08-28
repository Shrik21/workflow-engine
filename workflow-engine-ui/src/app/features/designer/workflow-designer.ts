import {
  ChangeDetectionStrategy,
  Component,
  computed,
  effect,
  HostListener,
  inject,
  input,
  signal,
} from '@angular/core';
import { ActivatedRoute, Router } from '@angular/router';
import { MarketplaceApiService } from '../../core/api/marketplace-api.service';
import { NodeApiService } from '../../core/api/node-api.service';
import { SecretApiService } from '../../core/api/secret-api.service';
import { WorkflowApiService } from '../../core/api/workflow-api.service';
import { NotificationService } from '../../core/notification.service';
import { AuthStateService } from '../../core/auth/auth-state.service';
import { NodeCatalogEntry } from '../../core/models/node.models';
import {
  ValidationResponse,
  WorkflowAuditEntry,
  WorkflowTrigger,
} from '../../core/models/workflow.models';
import { KvEditor } from '../../shared/forms/kv-editor';
import { AgoPipe } from '../../shared/pipes/format.pipes';
import { Modal } from '../../shared/ui/modal';
import { StatusPill } from '../../shared/ui/status-pill';
import { Icon } from '../../shared/ui/icon';
import { DesignerStore } from './designer.store';
import { GraphCanvas } from './graph-canvas';
import { NodePalette } from './node-palette';
import { NodeProperties } from './node-properties';
import { AccessControl } from './access-control';
import { WorkflowFiles } from '../workflows/workflow-files';
import { PluginUpgradeDialog, UpgradeCandidate } from './plugin-upgrade-dialog';
import { ScheduleEditor, ScheduleChange } from './schedule-editor';
import { Point } from './graph-geometry';

type Dialog = 'none' | 'settings' | 'run' | 'json' | 'plugins' | 'history' | 'leave';

/**
 * The workflow designer.
 *
 * Three panes: the palette built from the live node catalogue, the canvas, and the property panel for
 * whatever is selected. The toolbar carries the operations that change a workflow's state, and it
 * distinguishes them clearly, because they are not equivalent: saving keeps a definition editable,
 * publishing snapshots an immutable version that executions pin, and running starts real work.
 *
 * The store holds the draft and is provided here, so leaving the page discards it rather than leaking
 * one workflow's edits into another.
 */
@Component({
  selector: 'wf-workflow-designer',
  changeDetection: ChangeDetectionStrategy.OnPush,
  providers: [DesignerStore],
  imports: [
    GraphCanvas,
    NodePalette,
    NodeProperties,
    StatusPill,
    Modal,
    KvEditor,
    AccessControl,
    WorkflowFiles,
    PluginUpgradeDialog,
    ScheduleEditor,
    AgoPipe,
    Icon,
  ],
  template: `
    <div class="designer">
      <header class="designer__bar" role="toolbar" aria-label="Workflow actions">
        <div class="designer__identity">
          <button class="btn btn--quiet btn--sm" type="button" (click)="leave()">Back</button>

          <input
            class="designer__name"
            type="text"
            aria-label="Workflow name"
            placeholder="Workflow name"
            [value]="store.name()"
            (input)="store.setName($any($event.target).value)"
          />

          <wf-status-pill [status]="store.status()" />
          @if (store.publishedVersion() !== null) {
            <span class="tag" title="The version executions currently pin"
              >v{{ store.publishedVersion() }} live</span
            >
          }
          @if (store.dirty()) {
            <span class="tag tag--dirty" title="Not yet saved">Unsaved changes</span>
          }
        </div>

        <div class="designer__tools">
          <button class="btn btn--sm" type="button" (click)="dialog.set('settings')">
            Variables &amp; triggers
          </button>
          <button class="btn btn--sm" type="button" (click)="dialog.set('json')">JSON</button>
          <button
            class="btn btn--sm"
            type="button"
            [disabled]="store.isNew()"
            title="Who created this workflow, and who has changed it since"
            (click)="openHistory()"
          >
            History
          </button>
          @if (outdatedPluginNodes() > 0) {
            <button
              class="btn btn--sm btn--attention"
              type="button"
              title="Nodes here pin a plugin version older than the newest installed one"
              (click)="dialog.set('plugins')"
            >
              Plugin updates
              <span class="count">{{ outdatedPluginNodes() }}</span>
            </button>
          }
          <button
            class="btn btn--sm"
            type="button"
            title="Reposition every node by walking the graph from its start node"
            (click)="store.relayout()"
          >
            Auto-layout
          </button>
        </div>

        <div class="designer__actions">
          <button class="btn btn--sm" type="button" [disabled]="busy()" (click)="validate()">
            Validate
          </button>
          <button
            class="btn btn--primary btn--sm"
            type="button"
            [disabled]="busy()"
            title="Save the editable draft. Does not create a runnable version."
            (click)="save()"
          >
            Save draft
          </button>
          <button
            class="btn btn--accent btn--sm"
            type="button"
            [disabled]="busy() || store.isNew()"
            title="Validates, then snapshots an immutable version that executions pin"
            (click)="publish()"
          >
            Publish
          </button>
          <button
            class="btn btn--sm"
            type="button"
            [disabled]="store.status() !== 'PUBLISHED'"
            [title]="
              store.status() === 'PUBLISHED'
                ? 'Start an execution of the published version'
                : 'Publish the workflow before running it'
            "
            (click)="dialog.set('run')"
          >
            Run
          </button>
        </div>
      </header>

      @if (issues().length > 0) {
        <div class="designer__issues">
          <strong class="small">{{ issues().length }} thing(s) to fix before publishing</strong>
          <ul>
            @for (issue of issues(); track issue) {
              <li>{{ issue }}</li>
            }
          </ul>
        </div>
      }
      @if (warnings().length > 0) {
        <div class="designer__issues designer__issues--warning">
          <strong class="small">Warnings, which do not block publishing</strong>
          <ul>
            @for (warning of warnings(); track warning) {
              <li>{{ warning }}</li>
            }
          </ul>
        </div>
      }

      <div class="designer__panes" [style.grid-template-columns]="paneColumns()">
        <aside class="designer__palette" [class.designer__side--collapsed]="paletteCollapsed()">
          @if (paletteCollapsed()) {
            <button
              class="designer__reopen"
              type="button"
              title="Show the node palette"
              aria-label="Show the node palette"
              (click)="togglePalette()"
            >
              <wf-icon name="nodes" [size]="16" />
              <span class="designer__reopen-label">Nodes</span>
            </button>
          } @else {
            <div class="designer__side-head">
              <span class="designer__side-title">Node palette</span>
              <button
                class="designer__collapse"
                type="button"
                title="Hide the node palette"
                aria-label="Hide the node palette"
                (click)="togglePalette()"
              >
                ‹
              </button>
            </div>
            <wf-node-palette
              [entries]="catalog.entries()"
              [loading]="catalog.loading()"
              (addRequested)="addAtCentre($event)"
              (refreshRequested)="catalog.refresh()"
            />
          }
        </aside>

        <main class="designer__canvas">
          <wf-graph-canvas
            [nodes]="store.nodes()"
            [connections]="store.connections()"
            [catalog]="catalog.entries()"
            [selectedNodeId]="store.selectedNodeId()"
            [selectedConnectionIndex]="store.selectedConnectionIndex()"
            (nodeSelected)="store.selectNode($event)"
            (connectionSelected)="store.selectConnection($event)"
            (backgroundClicked)="store.clearSelection()"
            (nodeMoved)="store.moveNode($event.id, $event.point)"
            (connectRequested)="connect($event)"
            (nodeDropped)="drop($event)"
            (deleteRequested)="deleteSelection()"
          />
        </main>

        <aside
          class="designer__properties"
          [class.designer__side--collapsed]="propertiesCollapsed()"
        >
          @if (propertiesCollapsed()) {
            <button
              class="designer__reopen"
              type="button"
              title="Show the properties panel"
              aria-label="Show the properties panel"
              (click)="toggleProperties()"
            >
              <wf-icon name="open" [size]="16" />
              <span class="designer__reopen-label">Properties</span>
            </button>
          } @else {
            <div class="designer__side-head">
              <span class="designer__side-title">Properties</span>
              <button
                class="designer__collapse"
                type="button"
                title="Hide the properties panel"
                aria-label="Hide the properties panel"
                (click)="toggleProperties()"
              >
                ›
              </button>
            </div>
            <wf-node-properties [secretNames]="secretNames()" />
          }
        </aside>
      </div>
    </div>

    @if (dialog() === 'settings') {
      <wf-modal
        heading="Workflow variables and triggers"
        subheading="Variables seed the workflow scope at the start of every execution. Triggers declare the ways it can start."
        width="720px"
        (closed)="dialog.set('none')"
      >
        <div class="field">
          <label class="field__label" for="wf-description">Description</label>
          <textarea
            id="wf-description"
            rows="2"
            [value]="store.description()"
            (input)="store.setDescription($any($event.target).value)"
          ></textarea>
        </div>

        <div class="field">
          <span class="field__label">Workflow variables</span>
          <wf-kv-editor
            [value]="store.variables()"
            keyLabel="variable"
            emptyText="No declared variables."
            (valueChange)="store.setVariables($event)"
          />
          <p class="field__hint">
            Readable as <code>&#36;{{ '{' }}workflow.name{{ '}' }}</code> or just
            <code>&#36;{{ '{' }}name{{ '}' }}</code>. Numbers and booleans are stored as such. A stored
            secret's value is reachable as
            <code>&#36;{{ '{' }}SECRET.name{{ '}' }}</code> — the prefix is required, so a plain
            <code>&#36;{{ '{' }}name{{ '}' }}</code> never resolves to a credential.
            Changes take effect on the next <strong>publish</strong>, not on save.
          </p>
        </div>

        <div class="divider"></div>

        <span class="field__label">Access control</span>
        @if (store.isNew()) {
          <p class="field__hint">
            Save the workflow first. Sharing is stored against the saved workflow, not the draft.
          </p>
        } @else {
          <wf-access-control [workflowId]="store.workflowId()" />
        }

        <div class="divider"></div>

        <span class="field__label">Files</span>
        @if (store.isNew()) {
          <p class="field__hint">
            Save the workflow first. Files are stored against a saved workflow and a specific version.
          </p>
        } @else {
          <!--
            Attached to the draft version, which is the number the next publish will produce — so a file added
            while drafting belongs to the version that publish creates, and no file has to move when it is cut.
          -->
          <wf-workflow-files
            [workflowId]="store.workflowId()!"
            [version]="store.version()"
            [canEdit]="session.has('WORKFLOW_EDIT')"
          />
        }

        <div class="divider"></div>

        <span class="field__label">Triggers</span>
        @for (trigger of store.triggers(); track $index) {
          <div class="trigger">
            <div class="trigger__row">
              <input
                type="text"
                class="mono"
                placeholder="trigger id"
                aria-label="Trigger id"
                [value]="trigger.id"
                (input)="patchTrigger($index, { id: $any($event.target).value })"
              />
              <select
                aria-label="Trigger type"
                [value]="trigger.type"
                (change)="patchTrigger($index, { type: $any($event.target).value })"
              >
                <option value="MANUAL">Manual</option>
                <option value="API">API</option>
                <option value="SCHEDULE">Schedule</option>
                <option value="EVENT">Event</option>
              </select>
              <button class="btn btn--quiet btn--sm" type="button" (click)="removeTrigger($index)">
                Remove
              </button>
            </div>
            @if (trigger.type === 'SCHEDULE') {
              <wf-schedule-editor
                [schedule]="trigger.schedule ?? null"
                [cron]="trigger.cron ?? null"
                [timezone]="trigger.timezone ?? null"
                (changed)="onScheduleChanged($index, $event)"
              />
              <p class="field__hint">
                Fired once across the cluster, not once per instance.
              </p>
            }
            @if (trigger.type === 'EVENT') {
              <input
                type="text"
                class="mono"
                placeholder="ORDER_CREATED"
                aria-label="Event name"
                [value]="trigger.eventName ?? ''"
                (input)="patchTrigger($index, { eventName: $any($event.target).value })"
              />
            }
          </div>
        }
        <button class="btn btn--sm" type="button" (click)="addTrigger()">Add trigger</button>

        <div modalFooter>
          <button class="btn btn--primary" type="button" (click)="dialog.set('none')">Done</button>
        </div>
      </wf-modal>
    }

    @if (dialog() === 'run') {
      <wf-modal
        heading="Run this workflow"
        subheading="Starts the published version. Form data supplied here satisfies the first form node, so a fully specified run completes without parking."
        width="620px"
        (closed)="dialog.set('none')"
      >
        <div class="field">
          <span class="field__label">Input</span>
          <wf-kv-editor
            [value]="runInput()"
            keyLabel="input"
            emptyText="No input."
            (valueChange)="runInput.set($event)"
          />
          <p class="field__hint">
            Readable as <code>&#36;{{ '{' }}input.name{{ '}' }}</code>.
          </p>
        </div>
        <div class="field">
          <span class="field__label">Form data (optional)</span>
          <wf-kv-editor
            [value]="runFormData()"
            keyLabel="field"
            emptyText="None. The execution will park at the first form node."
            (valueChange)="runFormData.set($event)"
          />
        </div>
        <label class="checkbox-row">
          <input
            type="checkbox"
            [checked]="runAsync()"
            (change)="runAsync.set($any($event.target).checked)"
          />
          <span>Run asynchronously and return immediately</span>
        </label>
        <div modalFooter>
          <button class="btn" type="button" (click)="dialog.set('none')">Cancel</button>
          <button class="btn btn--accent" type="button" [disabled]="busy()" (click)="run()">
            Start execution
          </button>
        </div>
      </wf-modal>
    }

    @if (dialog() === 'json') {
      <wf-modal
        heading="Workflow JSON"
        subheading="Edit, paste or copy the definition. Importing replaces the current draft and lays it out if it has no coordinates."
        width="820px"
        (closed)="dialog.set('none')"
      >
        <textarea
          rows="22"
          spellcheck="false"
          aria-label="Workflow JSON"
          [value]="jsonDraft()"
          (input)="jsonDraft.set($any($event.target).value)"
        ></textarea>
        @if (jsonError()) {
          <p class="field__error">{{ jsonError() }}</p>
        }
        <div modalFooter>
          <button class="btn" type="button" (click)="dialog.set('none')">Cancel</button>
          <button class="btn" type="button" (click)="copyJson()">Copy</button>
          <button class="btn btn--primary" type="button" (click)="importJson()">Import</button>
        </div>
      </wf-modal>
    }

    @if (dialog() === 'leave') {
      <wf-modal
        heading="Leave without saving?"
        subheading="Your unsaved changes to this workflow will be lost."
        width="440px"
        (closed)="dialog.set('none')"
      >
        <p>You have unsaved changes. If you leave now, they will not be kept.</p>
        <div modalFooter style="display: flex; gap: var(--space-3); justify-content: flex-end">
          <button class="btn" type="button" (click)="dialog.set('none')">Stay</button>
          <button class="btn btn--danger" type="button" (click)="confirmLeave()">
            Leave without saving
          </button>
        </div>
      </wf-modal>
    }

    @if (dialog() === 'plugins') {
      <wf-plugin-upgrade-dialog
        [nodes]="store.nodes()"
        (applied)="repointNodes($event)"
        (closed)="dialog.set('none')"
      />
    }

    @if (dialog() === 'history') {
      <wf-modal
        heading="Workflow history"
        subheading="Who created this workflow, and who has changed it since. Most recent first."
        width="720px"
        (closed)="dialog.set('none')"
      >
        <div class="history-summary">
          <div>
            <span class="small muted">Created</span>
            <div>
              <strong>{{ meta()?.createdBy || 'unknown' }}</strong>
              <span class="small muted" [title]="meta()?.createdAt ?? ''">
                {{ meta()?.createdAt | ago }}
              </span>
            </div>
          </div>
          <div>
            <span class="small muted">Last updated</span>
            <div>
              <strong>{{ meta()?.updatedBy || 'unknown' }}</strong>
              <span class="small muted" [title]="meta()?.updatedAt ?? ''">
                {{ meta()?.updatedAt | ago }}
              </span>
            </div>
          </div>
        </div>

        @if (historyLoading()) {
          <p class="small muted">Loading history…</p>
        } @else if (history().length === 0) {
          <p class="small muted">
            No change history is recorded for this workflow yet. Edits and publishes made from now on will
            appear here.
          </p>
        } @else {
          <table class="table">
            <thead>
              <tr>
                <th>When</th>
                <th>User</th>
                <th>Action</th>
                <th>Detail</th>
              </tr>
            </thead>
            <tbody>
              @for (entry of history(); track $index) {
                <tr>
                  <td class="small muted" [title]="entry.at ?? ''">{{ entry.at | ago }}</td>
                  <td>{{ entry.actor || 'unknown' }}</td>
                  <td>
                    <span class="tag">{{ actionLabel(entry.action) }}</span>
                    @if (entry.outcome && entry.outcome !== 'OK') {
                      <span class="tag tag--danger">{{ entry.outcome }}</span>
                    }
                  </td>
                  <td class="small muted">{{ detailText(entry.details) }}</td>
                </tr>
              }
            </tbody>
          </table>
        }

        <div modalFooter>
          <button class="btn" type="button" (click)="dialog.set('none')">Close</button>
        </div>
      </wf-modal>
    }
  `,
  styles: [
    `
      :host {
        display: block;
        height: 100%;
        min-height: 0;
      }

      .designer {
        display: flex;
        flex-direction: column;
        height: 100%;
        min-height: 0;
      }

      .designer__bar {
        display: flex;
        align-items: center;
        gap: var(--space-3);
        padding: var(--space-2) var(--space-3);
        background: var(--surface);
        border-bottom: 1px solid var(--border);
        flex-wrap: wrap;
      }

      .designer__identity,
      .designer__tools,
      .designer__actions {
        display: flex;
        align-items: center;
        gap: var(--space-2);
        flex-wrap: wrap;
      }

      .designer__identity {
        flex: 1 1 280px;
        min-width: 0;
      }

      .designer__tools {
        flex: 1 1 auto;
      }

      .designer__actions {
        margin-left: auto;
        padding-left: var(--space-2);
        border-left: 1px solid var(--border);
      }

      .designer__name {
        width: min(280px, 100%);
        font-family: var(--font-brand);
        font-size: var(--text-md);
        font-weight: 600;
      }

      .designer__side-title {
        font-size: var(--text-xs);
        font-weight: 700;
        letter-spacing: 0.06em;
        text-transform: uppercase;
        color: var(--text-muted);
      }

      /* Loud enough to notice, quiet enough not to compete with Publish. */
      .btn--attention {
        border-color: var(--hl-orange-alt);
        color: var(--hl-orange-alt);
      }

      .btn--attention .count {
        display: inline-block;
        margin-left: 6px;
        padding: 0 6px;
        border-radius: 999px;
        background: color-mix(in srgb, var(--hl-orange-alt) 15%, transparent);
        font-size: var(--text-xs);
      }

      .tag--dirty {
        background: var(--notice-warning-bg);
        color: var(--hl-orange-alt);
        font-weight: 600;
      }

      .designer__issues {
        padding: var(--space-2) var(--space-4);
        background: var(--notice-error-bg);
        border-bottom: 1px solid var(--border);
        border-left: 3px solid var(--hl-error);
        max-height: 132px;
        overflow-y: auto;
      }

      .designer__issues--warning {
        background: var(--notice-warning-bg);
        border-left-color: var(--hl-warning);
      }

      .designer__issues ul {
        margin: var(--space-1) 0 0;
        padding-left: 20px;
        font-size: var(--text-sm);
        color: var(--hl-grey-800);
      }

      @media (max-width: 900px) {
        .designer__actions {
          margin-left: 0;
          border-left: none;
          padding-left: 0;
          width: 100%;
        }

        .designer__name {
          width: min(200px, 100%);
        }
      }

      .designer__panes {
        flex: 1;
        min-height: 0;
        display: grid;
        /* Column widths come from [style.grid-template-columns]; this is the pre-hydration fallback. */
        grid-template-columns: 232px 1fr 372px;
      }

      .designer__palette {
        border-right: 1px solid var(--border);
        background: var(--surface);
        min-height: 0;
        display: flex;
        flex-direction: column;
      }

      .designer__canvas {
        min-width: 0;
        min-height: 0;
      }

      .designer__properties {
        border-left: 1px solid var(--border);
        background: var(--surface);
        min-height: 0;
        display: flex;
        flex-direction: column;
      }

      /* A collapsed side is a slim rail holding only its reopen button. */
      .designer__side--collapsed {
        overflow: hidden;
      }

      /* The bar above each panel carrying the collapse control. */
      .designer__side-head {
        display: flex;
        align-items: center;
        justify-content: space-between;
        padding: var(--space-1) var(--space-2);
        border-bottom: 1px solid var(--border);
        flex: 0 0 auto;
      }

      .designer__collapse {
        border: 1px solid var(--border);
        background: var(--surface-sunken);
        color: var(--text-muted);
        border-radius: var(--radius-sm);
        width: 1.5rem;
        height: 1.5rem;
        line-height: 1;
        font-size: var(--text-lg);
        cursor: pointer;
        display: inline-flex;
        align-items: center;
        justify-content: center;
      }

      .designer__collapse:hover {
        color: var(--text);
        border-color: var(--hl-primary, var(--text));
      }

      /* The full-height button shown when a side is collapsed; click anywhere on the rail to reopen. */
      .designer__reopen {
        width: 100%;
        height: 100%;
        border: none;
        background: var(--surface);
        color: var(--text-muted);
        cursor: pointer;
        display: flex;
        flex-direction: column;
        align-items: center;
        gap: var(--space-2);
        padding-top: var(--space-2);
      }

      .designer__reopen:hover {
        color: var(--text);
        background: var(--surface-sunken);
      }

      /* The label reads vertically down the rail, so it fits the 2rem width. */
      .designer__reopen-label {
        writing-mode: vertical-rl;
        font-size: var(--text-xs);
        letter-spacing: 0.04em;
        text-transform: uppercase;
      }

      .trigger {
        display: flex;
        flex-direction: column;
        gap: var(--space-2);
        padding: var(--space-2);
        margin-bottom: var(--space-2);
        border: 1px solid var(--border);
        border-radius: var(--radius-sm);
        background: var(--surface-sunken);
      }

      .trigger__row {
        display: flex;
        gap: var(--space-2);
      }

      @media (max-width: 1200px) {
        .designer__panes {
          grid-template-columns: 200px 1fr 320px;
        }
      }
    `,
  ],
})
export class WorkflowDesigner {
  /**
   * Route parameter: a workflow id, or `new` for a blank draft.
   *
   * Must be an `input()`, not a plain signal: `withComponentInputBinding()` writes route parameters
   * into inputs, and a signal the router cannot write to leaves this permanently undefined, which
   * silently opens a blank draft instead of the requested workflow.
   */
  readonly id = input<string | undefined>(undefined);

  /**
   * When truthy, open the run dialog once the workflow has loaded.
   *
   * Bound from the {@code ?openRun} query parameter by {@code withComponentInputBinding()}, so the Run button
   * on the Workflows list can send the operator straight into the run dialog — where they can supply input —
   * rather than firing a run with no input from the list, which for most workflows would be the wrong thing.
   * Named {@code openRun} rather than {@code run} because {@link #run} already executes the workflow.
   */
  readonly openRun = input<string | undefined>(undefined);

  protected readonly store = inject(DesignerStore);
  protected readonly catalog = inject(NodeApiService);

  private readonly workflowApi = inject(WorkflowApiService);
  private readonly secretApi = inject(SecretApiService);
  private readonly notifications = inject(NotificationService);
  protected readonly session = inject(AuthStateService);
  private readonly marketplace = inject(MarketplaceApiService);
  private readonly router = inject(Router);
  private readonly route = inject(ActivatedRoute);

  protected readonly dialog = signal<Dialog>('none');
  protected readonly busy = signal(false);

  /**
   * Whether each side panel is collapsed.
   *
   * Two independent toggles — the palette on the left, the property panel on the right — so an operator with
   * a wide graph and a small screen can reclaim either side for the canvas. Kept in the browser's storage so
   * the choice survives a reload; a designer that forgot its layout on every refresh would be a small papercut
   * repeated all day.
   */
  protected readonly paletteCollapsed = signal(readStoredCollapse('wf-designer-palette-collapsed'));
  protected readonly propertiesCollapsed = signal(readStoredCollapse('wf-designer-properties-collapsed'));

  /** The grid track sizes, narrowing a collapsed side to a slim rail that still holds its reopen button. */
  protected readonly paneColumns = computed(
    () =>
      `${this.paletteCollapsed() ? '2rem' : '232px'} 1fr ` +
      `${this.propertiesCollapsed() ? '2rem' : '372px'}`,
  );

  /** Toggles a side panel and remembers the choice. */
  protected togglePalette(): void {
    this.paletteCollapsed.update((collapsed) => !collapsed);
    storeCollapse('wf-designer-palette-collapsed', this.paletteCollapsed());
  }

  protected toggleProperties(): void {
    this.propertiesCollapsed.update((collapsed) => !collapsed);
    storeCollapse('wf-designer-properties-collapsed', this.propertiesCollapsed());
  }
  protected readonly serverErrors = signal<string[]>([]);
  protected readonly warnings = signal<string[]>([]);
  protected readonly secretNames = signal<string[]>([]);

  protected readonly runInput = signal<Record<string, unknown>>({});
  protected readonly runFormData = signal<Record<string, unknown>>({});

  /** Who created and last changed the loaded workflow; captured on load, shown in the history dialog. */
  protected readonly meta = signal<{
    createdBy: string | null;
    updatedBy: string | null;
    createdAt: string | null;
    updatedAt: string | null;
  } | null>(null);

  protected readonly history = signal<WorkflowAuditEntry[]>([]);
  protected readonly historyLoading = signal(false);
  protected readonly runAsync = signal(false);

  protected readonly jsonDraft = signal('');
  protected readonly jsonError = signal<string | null>(null);

  /**
   * How many nodes pin a plugin version older than the newest installed one.
   *
   * Only counted, not acted on: the toolbar button appears, and the dialog behind it does the explaining.
   * Repointing silently would throw away the guarantee that pinning exists to provide.
   */
  protected readonly outdatedPluginNodes = computed(() => {
    const index = this.marketplace.byPluginId();
    return this.store.nodes().filter((node) => {
      if (!node.pluginId || !node.pluginVersion) {
        return false;
      }
      const view = index.get(node.pluginId);
      const newest = view?.installedVersions.find(
        (entry) => entry.state === 'ACTIVE' || entry.state === 'INSTALLED',
      )?.version;
      return !!newest && newest !== node.pluginVersion;
    }).length;
  });

  /**
   * What the operator must fix. Local structural checks plus whatever the engine last reported.
   *
   * Both are shown because they answer different questions: the local list updates as the graph is
   * drawn, and the engine's list is authoritative about plugins, expressions and cron syntax.
   */
  protected readonly issues = computed(() => {
    const combined = [...this.store.localIssues()];
    for (const error of this.serverErrors()) {
      if (!combined.includes(error)) {
        combined.push(error);
      }
    }
    return combined;
  });

  constructor() {
    this.catalog.ensureLoaded();
    this.loadSecretNames();

    // Loads whenever the route id changes, including navigating from one workflow to another.
    effect(() => {
      const id = this.id();
      if (!id || id === 'new') {
        this.store.startBlank();
        return;
      }
      this.workflowApi.get(id).subscribe({
        next: (workflow) => {
          this.store.load(workflow);
          // Kept out of the editable store, which is about the graph; the history dialog reads it.
          this.meta.set({
            createdBy: workflow.createdBy,
            updatedBy: workflow.updatedBy,
            createdAt: workflow.createdAt,
            updatedAt: workflow.updatedAt,
          });
        },
        error: () => this.router.navigate(['/workflows']),
      });
    });

    // Keeps the JSON dialog's text in step with the draft until the operator edits it.
    effect(() => {
      if (this.dialog() === 'json') {
        this.jsonDraft.set(this.store.exportJson());
        this.jsonError.set(null);
      }
    });

    // Opens the run dialog when the list sent us here with ?run, once the workflow has loaded and is
    // published. The query parameter is then stripped, so closing the dialog or refreshing does not reopen it.
    effect(() => {
      if (
        this.openRun() &&
        this.dialog() === 'none' &&
        this.store.status() === 'PUBLISHED' &&
        this.store.workflowId() === this.id()
      ) {
        this.dialog.set('run');
        void this.router.navigate([], {
          relativeTo: this.route,
          queryParams: {},
          replaceUrl: true,
        });
      }
    });
  }

  // --------------------------------------------------------------- canvas glue

  protected drop(event: { nodeType: string; point: Point }): void {
    const entry = this.catalog.find(event.nodeType);
    if (!entry) {
      this.notifications.warning(
        `Unknown node type ${event.nodeType}`,
        'Its plugin may have been deactivated. Refresh the palette.',
      );
      return;
    }
    this.store.addNode(entry, event.point);
  }

  protected addAtCentre(entry: NodeCatalogEntry): void {
    // Offset each addition so repeated double-clicks do not stack nodes exactly on top of each other.
    const count = this.store.nodes().length;
    this.store.addNode(entry, { x: 160 + (count % 4) * 40, y: 140 + (count % 5) * 40 });
  }

  /**
   * Repoints the chosen nodes at a newer installed plugin version.
   *
   * Left unsaved on purpose. Changing which version a published workflow runs is exactly the kind of edit
   * somebody should look at before committing, and the draft is already marked dirty for them to save or
   * abandon.
   */
  protected repointNodes(candidates: UpgradeCandidate[]): void {
    for (const candidate of candidates) {
      this.store.updateNode(candidate.nodeId, { pluginVersion: candidate.to });
    }
    this.dialog.set('none');
    if (candidates.length > 0) {
      this.notifications.success(
        `${candidates.length} node(s) repointed`,
        'Save the workflow, then publish it for running executions to pick the change up.',
      );
    }
  }

  protected connect(event: { source: string; sourcePort: string | null; target: string }): void {
    if (!this.store.connect(event.source, event.target, event.sourcePort)) {
      this.notifications.info('That connection already exists, or is not allowed.');
    }
  }

  protected deleteSelection(): void {
    const nodeId = this.store.selectedNodeId();
    if (nodeId) {
      this.store.removeNode(nodeId);
      return;
    }
    const index = this.store.selectedConnectionIndex();
    if (index != null) {
      this.store.removeConnection(index);
    }
  }

  // ------------------------------------------------------------------ toolbar

  protected save(): void {
    const request = this.store.toRequest();
    if (!request.name) {
      this.notifications.error('The workflow needs a name before it can be saved.');
      return;
    }
    this.busy.set(true);
    const id = this.store.workflowId();
    const call = id ? this.workflowApi.update(id, request) : this.workflowApi.create(request);
    call.subscribe({
      next: (workflow) => {
        this.busy.set(false);
        this.store.markSaved(workflow);
        this.notifications.success(`Saved "${workflow.name}"`);
        if (!id) {
          // Replace the URL so a reload returns to the saved workflow rather than a blank draft.
          this.router.navigate(['/workflows', workflow.id], { replaceUrl: true });
        }
      },
      error: () => this.busy.set(false),
    });
  }

  protected validate(): void {
    const id = this.store.workflowId();
    if (!id) {
      this.notifications.info('Save the workflow first', 'Validation runs against the saved definition.');
      return;
    }
    if (this.store.dirty()) {
      this.notifications.warning(
        'There are unsaved changes',
        'Validation runs against the last saved definition, not what is on the canvas.',
      );
    }
    this.busy.set(true);
    this.workflowApi.validate(id).subscribe({
      next: (result) => {
        this.busy.set(false);
        this.applyValidation(result);
      },
      error: () => this.busy.set(false),
    });
  }

  protected publish(): void {
    const id = this.store.workflowId();
    if (!id) {
      return;
    }
    if (this.store.dirty()) {
      this.notifications.info('Saving before publishing');
      this.save();
    }
    this.busy.set(true);
    this.workflowApi.publish(id).subscribe({
      next: (result) => {
        this.busy.set(false);
        this.serverErrors.set([]);
        this.warnings.set(result.warnings ?? []);
        this.store.markStatus('PUBLISHED', result.version);
        this.notifications.success(
          `Published version ${result.version}`,
          ...(result.warnings ?? []),
        );
      },
      error: (error) => {
        this.busy.set(false);
        // The interceptor has already shown the message; keeping the list here puts every problem
        // in front of the operator while they fix the graph.
        const details = error?.error?.details;
        this.serverErrors.set(Array.isArray(details) ? details : []);
      },
    });
  }

  /** Opens the history dialog and loads the workflow's change log, newest first (the server sorts it). */
  protected openHistory(): void {
    this.dialog.set('history');
    const id = this.store.workflowId();
    if (!id) {
      this.history.set([]);
      return;
    }
    this.historyLoading.set(true);
    this.history.set([]);
    this.workflowApi.audit(id).subscribe({
      next: (page) => {
        this.history.set(page.content);
        this.historyLoading.set(false);
      },
      error: () => this.historyLoading.set(false),
    });
  }

  /** Turns an audit action code into a short label: WORKFLOW_UPDATED -> "Updated". */
  protected actionLabel(action: string): string {
    const stripped = action.replace(/^WORKFLOW_/, '').toLowerCase().replace(/_/g, ' ');
    return stripped.charAt(0).toUpperCase() + stripped.slice(1);
  }

  /** A compact one-line rendering of an audit entry's details, or empty when there are none. */
  protected detailText(details: Record<string, unknown>): string {
    const entries = Object.entries(details ?? {});
    if (entries.length === 0) {
      return '';
    }
    return entries
      .map(([key, value]) => `${key}: ${typeof value === 'object' ? JSON.stringify(value) : value}`)
      .join(', ');
  }

  protected run(): void {
    const id = this.store.workflowId();
    if (!id) {
      return;
    }
    this.busy.set(true);
    this.workflowApi
      .execute(
        id,
        {
          input: this.runInput(),
          formData: Object.keys(this.runFormData()).length > 0 ? this.runFormData() : null,
        },
        { async: this.runAsync() },
      )
      .subscribe({
        next: (execution) => {
          this.busy.set(false);
          this.dialog.set('none');
          this.notifications.success(
            `Execution ${execution.status.toLowerCase()}`,
            `Execution ${execution.executionId}`,
          );
          this.router.navigate(['/executions', execution.executionId]);
        },
        error: () => this.busy.set(false),
      });
  }

  /**
   * Browser close/refresh guard. Complements the in-app leave modal; does not replace it.
   * Returning a string is ignored by modern browsers but still arms the native prompt.
   */
  @HostListener('window:beforeunload', ['$event'])
  protected onBeforeUnload(event: BeforeUnloadEvent): void {
    if (!this.store.dirty()) {
      return;
    }
    event.preventDefault();
    event.returnValue = true;
  }

  protected leave(): void {
    // Unsaved changes get a styled confirmation rather than the browser's confirm(), which cannot be themed
    // and reads as a system error. A clean draft leaves straight away.
    if (this.store.dirty()) {
      this.dialog.set('leave');
      return;
    }
    this.router.navigate(['/workflows']);
  }

  protected confirmLeave(): void {
    this.dialog.set('none');
    this.router.navigate(['/workflows']);
  }

  // ------------------------------------------------------------------ dialogs

  protected addTrigger(): void {
    const count = this.store.triggers().length + 1;
    this.store.setTriggers([
      ...this.store.triggers(),
      { id: `trigger-${count}`, type: 'MANUAL', enabled: true },
    ]);
  }

  protected patchTrigger(index: number, patch: Partial<WorkflowTrigger>): void {
    this.store.setTriggers(
      this.store.triggers().map((trigger, position) =>
        position === index ? { ...trigger, ...patch } : trigger,
      ),
    );
  }

  /**
   * Stores the friendly schedule config, the cron the backend generated for it, and the timezone. Keeping the
   * cron in sync means a saved draft carries a runnable expression even before publish, while the config is
   * what re-opens the dropdowns on edit.
   */
  protected onScheduleChanged(index: number, change: ScheduleChange): void {
    this.patchTrigger(index, {
      schedule: change.schedule,
      cron: change.cron,
      timezone: change.timezone,
    });
  }

  protected removeTrigger(index: number): void {
    this.store.setTriggers(this.store.triggers().filter((_, position) => position !== index));
  }

  protected importJson(): void {
    const error = this.store.importJson(this.jsonDraft());
    if (error) {
      this.jsonError.set(error);
      return;
    }
    this.jsonError.set(null);
    this.dialog.set('none');
    this.notifications.success('Definition imported', 'Save it to persist the change.');
  }

  protected async copyJson(): Promise<void> {
    try {
      await navigator.clipboard.writeText(this.jsonDraft());
      this.notifications.success('JSON copied to the clipboard');
    } catch {
      // Clipboard access is denied in some contexts; the text is already selectable in the dialog.
      this.notifications.info('Copy was blocked', 'Select the text in the dialog and copy manually.');
    }
  }

  private applyValidation(result: ValidationResponse): void {
    this.serverErrors.set(result.errors ?? []);
    this.warnings.set(result.warnings ?? []);
    if (result.valid) {
      this.notifications.success(
        'The workflow is valid',
        ...(result.warnings?.length ? [`${result.warnings.length} warning(s)`] : []),
      );
    } else {
      this.notifications.error(
        `${result.errors.length} problem(s) block publishing`,
        ...result.errors,
      );
    }
  }

  /**
   * Loads secret names for the secret-reference picker, but only when the user may read them.
   *
   * Without one the request would fail with a 401 and produce a misleading error on a screen the
   * operator did not ask to be administrative. The picker simply degrades to a plain text field.
   */
  private loadSecretNames(): void {
    if (!this.session.has('SECRET_VIEW')) {
      return;
    }
    this.secretApi.list().subscribe({
      next: (secrets) => this.secretNames.set(secrets.map((secret) => secret.name)),
      error: () => this.secretNames.set([]),
    });
  }
}

/**
 * Reads a remembered panel-collapse choice, defaulting to open.
 *
 * Wrapped in a try because localStorage throws in a few environments (private-mode Safari, a sandboxed
 * frame), and a designer that would not load because it could not remember a panel state would be a poor
 * trade for a convenience.
 */
function readStoredCollapse(key: string): boolean {
  try {
    return localStorage.getItem(key) === 'true';
  } catch {
    return false;
  }
}

function storeCollapse(key: string, collapsed: boolean): void {
  try {
    localStorage.setItem(key, String(collapsed));
  } catch {
    // A browser that refuses storage still gets a working toggle for this session; only persistence is lost.
  }
}
