import { ChangeDetectionStrategy, Component, computed, inject, input, signal } from '@angular/core';
import { NodeApiService } from '../../core/api/node-api.service';
import { resolveCatalogEntry } from '../../core/models/node.models';
import { DecisionCondition, WorkflowNode } from '../../core/models/workflow.models';
import { KvEditor } from '../../shared/forms/kv-editor';
import { SchemaForm } from '../../shared/forms/schema-form';
import { NodeGlyph } from '../../shared/ui/node-glyph';
import { DesignerStore } from './designer.store';
import { FormNodeConfig } from './form-node-config';
import { AiAgentConfig } from './ai-agent-config';
import {
  carryOverConfiguration,
  groupOperations,
  matchesOperation,
  operationStatus,
  operationsFor,
} from './plugin-operations';

/**
 * The property panel for the selected node.
 *
 * Two rendering strategies, chosen deliberately:
 *
 * - **Built-in types get purpose-built editors.** A decision node's branches are structural, not
 *   configuration: each one becomes a port on the canvas and an outgoing edge. Rendering them from the
 *   generic schema would produce a string map, which cannot express a branch and an expression as a
 *   pair, and would leave the operator editing something the canvas could not draw.
 * - **Plugin types get the schema-driven form.** The engine does not know what a SendGrid node needs
 *   and neither does this panel; the plugin published a schema and {@link SchemaForm} renders it. This
 *   is the seam that means a new integration needs no front-end change.
 *
 * Everything a node has in common, identity, mappings, retry, error policy, timeout, is edited the
 * same way for both.
 */
@Component({
  selector: 'wf-node-properties',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [KvEditor, SchemaForm, NodeGlyph, FormNodeConfig, AiAgentConfig],
  template: `
    @if (node(); as current) {
      <div class="panel">
        <header class="panel__header">
          <wf-node-glyph [icon]="entry()?.icon ?? current.type" [size]="16" />
          <div class="panel__title">
            <strong>{{ current.name || current.id }}</strong>
            <span class="mono small muted">{{ current.type }}</span>
          </div>
          <button
            class="btn btn--quiet btn--sm"
            type="button"
            title="Duplicate this node"
            (click)="store.duplicateNode(current.id)"
          >
            Duplicate
          </button>
          <button
            class="btn btn--danger btn--sm"
            type="button"
            (click)="store.removeNode(current.id)"
          >
            Delete
          </button>
        </header>

        @if (operationStatus() === 'OPERATION_MISSING') {
          <!--
            The plugin is installed and healthy; an update simply dropped this operation. Saying "no loaded
            plugin provides this" would send the author chasing an installation that is already fine, when
            the fix is to pick another operation from the dropdown below — which stays populated for exactly
            this case.
          -->
          <div class="notice notice--warning panel__notice">
            <strong>Operation no longer available.</strong>
            {{ current.pluginId }} no longer provides <code>{{ current.type }}</code
            >. Its other operations still work — choose one below, or reinstall the version that had it.
            Publishing is rejected until this is resolved.
          </div>
        } @else if (operationStatus() === 'PLUGIN_MISSING') {
          <div class="notice notice--warning panel__notice">
            No loaded plugin provides <code>{{ current.type }}</code
            >. The node stays in the definition, but publishing will be rejected until its plugin
            version is active.
          </div>
        }

        <nav class="tabs" role="tablist">
          @for (tab of tabs(); track tab) {
            <button
              class="tab"
              type="button"
              role="tab"
              [class.tab--active]="activeTab() === tab"
              [attr.aria-selected]="activeTab() === tab"
              (click)="activeTab.set(tab)"
            >
              {{ tab }}
            </button>
          }
        </nav>

        <div class="panel__body">
          @switch (activeTab()) {
            @case ('Settings') {
              <div class="field">
                <label class="field__label" for="node-name">Name</label>
                <input
                  id="node-name"
                  type="text"
                  [value]="current.name ?? ''"
                  (input)="patch({ name: $any($event.target).value })"
                />
              </div>

              <div class="field">
                <label class="field__label" for="node-id">Node id</label>
                <input
                  id="node-id"
                  type="text"
                  class="mono"
                  [value]="idDraft() ?? current.id"
                  (input)="idDraft.set($any($event.target).value)"
                  (blur)="commitId(current.id)"
                  (keydown.enter)="commitId(current.id)"
                />
                <p class="field__hint">
                  Used by connections and by <code>&#36;{{ '{' }}node.{{ current.id }}.*{{ '}' }}</code
                  >. Renaming rewires connections automatically, but expressions that reference the old
                  id by name are left untouched.
                </p>
                @if (idError()) {
                  <p class="field__error">{{ idError() }}</p>
                }
              </div>

              <div class="field">
                <label class="field__label" for="node-description">Description</label>
                <textarea
                  id="node-description"
                  rows="2"
                  [value]="current.description ?? ''"
                  (input)="patch({ description: $any($event.target).value })"
                ></textarea>
              </div>

              @if (current.pluginId) {
                <div class="field">
                  <span class="field__label">Plugin</span>
                  <div class="pinned">
                    <span class="tag tag--mono">{{ current.pluginId }}</span>
                    @if (current.pluginVersion) {
                      <span class="tag tag--mono">{{ current.pluginVersion }}</span>
                      <button
                        class="btn btn--quiet btn--sm"
                        type="button"
                        title="Follow the plugin's default version instead of this one"
                        (click)="patch({ pluginVersion: null })"
                      >
                        Unpin version
                      </button>
                    } @else {
                      <span class="small muted">following the default version</span>
                      @if (entry()?.pluginVersion) {
                        <button
                          class="btn btn--quiet btn--sm"
                          type="button"
                          (click)="patch({ pluginVersion: entry()!.pluginVersion })"
                        >
                          Pin {{ entry()!.pluginVersion }}
                        </button>
                      }
                    }
                  </div>
                  <p class="field__hint">
                    A pinned version is honoured exactly, so uploading a newer plugin cannot change what
                    this node does. Unpinned nodes follow whichever version is default.
                  </p>
                </div>
              }
            }

            @case ('Configuration') {
              @if (current.type === 'DECISION') {
                <p class="small muted">
                  Conditions are evaluated in order and the first match wins, so put the specific case
                  first. Choose where each branch goes below — the canvas edge is drawn for you.
                </p>
                @if (duplicateBranch()) {
                  <div class="notice notice--warning">
                    Duplicate decision condition is not allowed — two branches share the same expression, so the
                    second can never be reached.
                  </div>
                }
                @for (condition of conditions(); track $index) {
                  <div class="condition">
                    <div class="condition__row">
                      <input
                        type="text"
                        class="mono"
                        placeholder="branch"
                        aria-label="Branch name"
                        [value]="condition.branch"
                        (input)="setCondition($index, { branch: $any($event.target).value })"
                      />
                      <button
                        class="btn btn--quiet btn--sm"
                        type="button"
                        (click)="removeCondition($index)"
                      >
                        Remove
                      </button>
                    </div>
                    <input
                      type="text"
                      class="mono"
                      placeholder="amount > 10000"
                      aria-label="Expression"
                      [value]="condition.expression"
                      (input)="setCondition($index, { expression: $any($event.target).value })"
                    />
                    <div class="field">
                      <label class="field__label">Next node</label>
                      <select
                        aria-label="Next node"
                        [disabled]="!condition.branch.trim()"
                        (change)="setBranchTarget(condition.branch, $any($event.target).value)"
                      >
                        <option value="" [selected]="!branchTargetId(condition.branch)">
                          Select node…
                        </option>
                        @for (dest of branchDestinations(); track dest.id) {
                          <option [value]="dest.id" [selected]="dest.id === branchTargetId(condition.branch)">
                            {{ nodeLabel(dest.id) }}
                          </option>
                        }
                        @if (targetMissing(branchTargetId(condition.branch))) {
                          <option [value]="branchTargetId(condition.branch)" [selected]="true">
                            ⚠ Node no longer exists
                          </option>
                        }
                      </select>
                      @if (!condition.branch.trim()) {
                        <p class="field__hint">Name the branch first, then choose its destination.</p>
                      } @else if (targetMissing(branchTargetId(condition.branch))) {
                        <p class="field__error">
                          The selected node no longer exists. Choose a new destination.
                        </p>
                      }
                    </div>
                  </div>
                }
                <button class="btn btn--sm" type="button" (click)="addCondition()">
                  Add branch
                </button>

                <div class="field" style="margin-top: var(--space-4)">
                  <label class="field__label">Default branch → Next node</label>
                  <select
                    aria-label="Default branch destination"
                    (change)="setDefaultTarget($any($event.target).value)"
                  >
                    <option value="" [selected]="!defaultTargetId()">None (fail if nothing matches)</option>
                    @for (dest of branchDestinations(); track dest.id) {
                      <option [value]="dest.id" [selected]="dest.id === defaultTargetId()">
                        {{ nodeLabel(dest.id) }}
                      </option>
                    }
                    @if (targetMissing(defaultTargetId())) {
                      <option [value]="defaultTargetId()" [selected]="true">⚠ Node no longer exists</option>
                    }
                  </select>
                  <p class="field__hint">
                    Followed when no condition matches. Without one, such an execution fails rather than
                    continuing.
                  </p>
                  @if (targetMissing(defaultTargetId())) {
                    <p class="field__error">The default node no longer exists. Choose a new destination.</p>
                  }
                </div>

                <div class="notice panel__notice">
                  Expressions read variables and use operators only. Type references, constructors and
                  bean lookups are rejected when the workflow is published.
                </div>
              } @else if (current.type === 'AI_AGENT') {
                <wf-ai-agent-config [node]="current" (nodeChange)="patch($event)" />
              } @else if (current.type === 'FORM') {
                <wf-form-node-config [node]="current" (nodeChange)="patch($event)" />
                <label class="checkbox-row">
                  <input
                    type="checkbox"
                    [checked]="current.waitForInput !== false"
                    (change)="patch({ waitForInput: $any($event.target).checked })"
                  />
                  <span>Wait for input</span>
                </label>
                <p class="field__hint">
                  When waiting, the execution parks and holds no thread, so a form can stay open for
                  days. Turn it off for a fully automated pipeline: the node then fails instead of
                  waiting.
                </p>
                <div class="divider"></div>
                <wf-schema-form
                  [schema]="entry()?.configurationSchema ?? null"
                  [value]="configuration()"
                  [secretNames]="secretNames()"
                  (valueChange)="patch({ configuration: $event })"
                />
              } @else {
                <!--
                  The operation selector. A plugin contributes one node type per operation, which is right for
                  the engine (per-operation risk, per-operation AI tools) but wrong for a palette of 180 rows.
                  So the palette shows one row per plugin and the operation is chosen here instead. Changing it
                  swaps the node's type, which re-resolves the catalogue entry and re-renders the form below
                  with no plugin-specific code anywhere.

                  Note for editors: no backticks in this file's template, they close the template literal.
                -->
                @if (operations().length > 1 || operationStatus() === 'OPERATION_MISSING') {
                  <div class="field">
                    <label class="field__label" for="operation">Operation</label>
                    @if (operations().length > 8) {
                      <!-- Kubernetes has 45 and Jira 39; a bare select is unusable at that size. -->
                      <input
                        type="search"
                        class="operation__search"
                        placeholder="Search operations"
                        aria-label="Search operations"
                        [value]="operationQuery()"
                        (input)="operationQuery.set($any($event.target).value)"
                      />
                    }
                    <!--
                      Selection is marked on the options, not with [value] on the select. Binding [value] here
                      sets the property during the update pass, before the @for below has created any option —
                      the browser then discards it and the select falls back to showing its first entry. The
                      node's type was never wrong; only this control was, and only after a fresh render, which
                      is why it looked like reopening a saved workflow reset the operation.
                    -->
                    <select id="operation" (change)="changeOperation($any($event.target).value)">
                      @if (operationStatus() === 'OPERATION_MISSING') {
                        <!--
                          A select whose value matches no option renders blank, which reads as though the
                          node lost its type. Naming the missing operation instead keeps what the node
                          actually says visible while making clear it is not a choice any more.
                        -->
                        <option [value]="current.type" disabled selected>
                          {{ current.type }} — no longer available
                        </option>
                      }
                      @for (group of operationGroups(); track group.category) {
                        <optgroup [label]="group.category">
                          @for (operation of group.operations; track operation.nodeType) {
                            <option
                              [value]="operation.nodeType"
                              [selected]="operation.nodeType === current.type"
                            >
                              {{ operation.displayName }}
                            </option>
                          }
                        </optgroup>
                      }
                    </select>
                    @if (operationGroups().length === 0) {
                      <p class="field__hint">Nothing matches "{{ operationQuery() }}".</p>
                    } @else {
                      <p class="field__hint">
                        {{ entry()?.description || 'Choose what this node does.' }}
                      </p>
                    }
                    @if (droppedKeys().length > 0) {
                      <!--
                        Said out loud rather than done silently: configuration the new operation does not
                        declare is discarded, and losing work invisibly is what an author cannot recover from.
                      -->
                      <p class="field__hint field__hint--warn">
                        Cleared {{ droppedKeys().join(', ') }} — not used by this operation.
                      </p>
                    }
                  </div>
                  <div class="divider"></div>
                }
                <wf-schema-form
                  [schema]="entry()?.configurationSchema ?? null"
                  [value]="configuration()"
                  [secretNames]="secretNames()"
                  [emptyText]="
                    entry()
                      ? 'This node type declares no configuration.'
                      : 'The schema is unavailable because the plugin is not loaded. Existing values are kept and can be edited as JSON below.'
                  "
                  (valueChange)="patch({ configuration: $event })"
                />
                @if (!entry()) {
                  <div class="field">
                    <label class="field__label" for="raw-config">Configuration (JSON)</label>
                    <textarea
                      id="raw-config"
                      rows="8"
                      spellcheck="false"
                      [value]="configurationJson()"
                      (input)="setConfigurationJson($any($event.target).value)"
                    ></textarea>
                    @if (configError()) {
                      <p class="field__error">{{ configError() }}</p>
                    }
                  </div>
                }
              }
            }

            @case ('Mappings') {
              <div class="field">
                <span class="field__label">Input mapping</span>
                <p class="field__hint">
                  Input name to expression. Values may use
                  <code>&#36;{{ '{' }}variable{{ '}' }}</code> placeholders.
                </p>
                <wf-kv-editor
                  [value]="current.inputMapping ?? {}"
                  keyLabel="input"
                  valuePlaceholder="&#36;{employeeId}"
                  [valuesAreText]="true"
                  emptyText="No input mappings."
                  (valueChange)="patch({ inputMapping: asStringMap($event) })"
                />
              </div>

              <div class="divider"></div>

              <div class="field">
                <span class="field__label">Output mapping</span>
                <p class="field__hint">
                  Output name to destination variable path, for example
                  <code>approved</code> to <code>workflow.approved</code>. Unmapped outputs are still
                  readable as <code>&#36;{{ '{' }}node.{{ current.id }}.name{{ '}' }}</code>.
                </p>
                <wf-kv-editor
                  [value]="current.outputMapping ?? {}"
                  keyLabel="output"
                  valuePlaceholder="workflow.approved"
                  [valuesAreText]="true"
                  emptyText="No output mappings."
                  (valueChange)="patch({ outputMapping: asStringMap($event) })"
                />
              </div>

              @if (entry()?.outputVariables?.length) {
                <div class="divider"></div>
                <span class="field__label">Outputs this node publishes</span>
                <div class="chips">
                  @for (name of entry()!.outputVariables; track name) {
                    <span class="tag tag--mono">{{ name }}</span>
                  }
                </div>
              }

              @if (current.type === 'END') {
                <div class="divider"></div>
                <div class="field">
                  <label class="field__label" for="end-outputs">Result variables</label>
                  <input
                    id="end-outputs"
                    type="text"
                    class="mono"
                    placeholder="workflow.approved, workflow.comments"
                    [value]="(current.outputs ?? []).join(', ')"
                    (input)="setOutputs($any($event.target).value)"
                  />
                  <p class="field__hint">
                    Comma-separated variable paths copied into the execution result under their final
                    path segment.
                  </p>
                </div>
              }
            }

            @case ('Reliability') {
              <div class="field">
                <label class="checkbox-row">
                  <input
                    type="checkbox"
                    [checked]="retryEnabled()"
                    (change)="setRetryEnabled($any($event.target).checked)"
                  />
                  <span>Retry this node on failure</span>
                </label>
                <p class="field__hint">
                  Only failures the node reports as retryable are retried. A rejected request fails
                  once rather than burning the whole budget.
                  @if (entry() && !entry()!.supportsRetry) {
                    This node type declares that retrying it is not meaningful.
                  }
                </p>
              </div>

              @if (retryEnabled()) {
                <div class="grid-tight">
                  <div class="field">
                    <label class="field__label" for="retry-attempts">Max attempts</label>
                    <input
                      id="retry-attempts"
                      type="number"
                      min="1"
                      [value]="current.retry?.maxAttempts ?? 3"
                      (input)="setRetry({ maxAttempts: toNumber($any($event.target).value, 3) })"
                    />
                  </div>
                  <div class="field">
                    <label class="field__label" for="retry-backoff">Backoff (ms)</label>
                    <input
                      id="retry-backoff"
                      type="number"
                      min="0"
                      [value]="current.retry?.backoffMillis ?? 5000"
                      (input)="setRetry({ backoffMillis: toNumber($any($event.target).value, 5000) })"
                    />
                  </div>
                  <div class="field">
                    <label class="field__label" for="retry-multiplier">Multiplier</label>
                    <input
                      id="retry-multiplier"
                      type="number"
                      min="1"
                      step="0.5"
                      [value]="current.retry?.backoffMultiplier ?? 2"
                      (input)="setRetry({ backoffMultiplier: toNumber($any($event.target).value, 2) })"
                    />
                  </div>
                  <div class="field">
                    <label class="field__label" for="retry-max-backoff">Max backoff (ms)</label>
                    <input
                      id="retry-max-backoff"
                      type="number"
                      min="0"
                      [value]="current.retry?.maxBackoffMillis ?? 60000"
                      (input)="
                        setRetry({ maxBackoffMillis: toNumber($any($event.target).value, 60000) })
                      "
                    />
                  </div>
                </div>
              }

              <div class="divider"></div>

              <div class="field">
                <label class="field__label" for="error-policy">When it fails</label>
                <select
                  id="error-policy"
                  [value]="current.errorPolicy ?? 'FAIL_WORKFLOW'"
                  (change)="patch({ errorPolicy: $any($event.target).value })"
                >
                  <option value="FAIL_WORKFLOW">Fail the workflow</option>
                  <option value="SKIP">Skip and continue</option>
                  <option value="CONTINUE">Continue, publishing the error as output</option>
                  <option value="COMPENSATE">Run a compensation node, then fail</option>
                </select>
                <p class="field__hint">{{ errorPolicyHint() }}</p>
              </div>

              @if (current.errorPolicy === 'COMPENSATE') {
                <div class="field">
                  <label class="field__label" for="compensation">Compensation node</label>
                  <!-- Marked on the options for the same reason as the operation select above. -->
                  <select
                    id="compensation"
                    (change)="patch({ compensationNodeId: $any($event.target).value || null })"
                  >
                    <option value="" [selected]="!current.compensationNodeId">Not set</option>
                    @for (candidate of otherNodes(); track candidate.id) {
                      <option
                        [value]="candidate.id"
                        [selected]="candidate.id === current.compensationNodeId"
                      >
                        {{ candidate.name || candidate.id }} ({{ candidate.id }})
                      </option>
                    }
                  </select>
                  <p class="field__hint">
                    Reached by the error policy rather than by an edge, so it needs no incoming
                    connection on the canvas.
                  </p>
                </div>
              }

              <div class="field">
                <label class="field__label" for="timeout">Timeout (ms)</label>
                <input
                  id="timeout"
                  type="number"
                  min="0"
                  placeholder="engine default"
                  [value]="current.timeoutMillis ?? ''"
                  (input)="patch({ timeoutMillis: toOptionalNumber($any($event.target).value) })"
                />
              </div>
            }
          }
        </div>
      </div>
    } @else if (selectedConnection(); as edge) {
      <div class="panel">
        <header class="panel__header">
          <div class="panel__title">
            <strong>Connection</strong>
            <span class="mono small muted">{{ edge.source }} to {{ edge.target }}</span>
          </div>
          <button
            class="btn btn--danger btn--sm"
            type="button"
            (click)="store.removeConnection(store.selectedConnectionIndex()!)"
          >
            Delete
          </button>
        </header>
        <div class="panel__body">
          <div class="field">
            <label class="field__label" for="edge-port">Branch</label>
            <input
              id="edge-port"
              type="text"
              class="mono"
              placeholder="default edge"
              [value]="edge.sourcePort ?? ''"
              (input)="patchConnection({ sourcePort: $any($event.target).value || null })"
            />
            <p class="field__hint">
              Matched against the branch a decision node selects. Leave empty for the default edge,
              which is followed when no branch matches.
            </p>
          </div>
          <div class="field">
            <label class="field__label" for="edge-label">Label</label>
            <input
              id="edge-label"
              type="text"
              [value]="edge.label ?? ''"
              (input)="patchConnection({ label: $any($event.target).value || null })"
            />
          </div>
          <div class="field">
            <label class="field__label" for="edge-condition">Guard expression</label>
            <input
              id="edge-condition"
              type="text"
              class="mono"
              placeholder="optional, e.g. amount > 0"
              [value]="edge.condition ?? ''"
              (input)="patchConnection({ condition: $any($event.target).value || null })"
            />
            <p class="field__hint">
              When set, the edge is only followed if this evaluates true. A guard that cannot be
              evaluated is treated as false and logged.
            </p>
          </div>
        </div>
      </div>
    } @else {
      <div class="panel panel--empty">
        <p class="small muted">Select a node or a connection to edit it.</p>
      </div>
    }
  `,
  styles: [
    `
      :host {
        display: block;
        height: 100%;
        overflow: hidden;
      }

      .panel {
        display: flex;
        flex-direction: column;
        height: 100%;
      }

      .panel--empty {
        padding: var(--space-4);
      }

      .panel__header {
        display: flex;
        align-items: center;
        gap: var(--space-2);
        padding: var(--space-3);
        border-bottom: 1px solid var(--border);
      }

      .panel__title {
        flex: 1;
        min-width: 0;
        display: flex;
        flex-direction: column;
      }

      .panel__title strong {
        overflow: hidden;
        text-overflow: ellipsis;
        white-space: nowrap;
      }

      .panel__notice {
        margin: var(--space-3);
      }

      .tabs {
        display: flex;
        border-bottom: 1px solid var(--border);
        padding: 0 var(--space-2);
        gap: 2px;
      }

      .tab {
        border: none;
        background: transparent;
        padding: var(--space-2) var(--space-3);
        font-family: var(--font-body);
        font-size: var(--text-sm);
        color: var(--text-muted);
        cursor: pointer;
        border-bottom: 2px solid transparent;
      }

      .tab:hover {
        color: var(--text);
      }

      .tab--active {
        color: var(--hl-blue);
        border-bottom-color: var(--hl-blue);
        font-weight: bold;
      }

      .panel__body {
        flex: 1;
        min-height: 0;
        overflow-y: auto;
        padding: var(--space-4);
      }

      .condition {
        display: flex;
        flex-direction: column;
        gap: var(--space-2);
        padding: var(--space-2);
        margin-bottom: var(--space-2);
        border: 1px solid var(--border);
        border-radius: var(--radius-sm);
        background: var(--surface-sunken);
      }

      .condition__row {
        display: flex;
        gap: var(--space-2);
      }

      .grid-tight {
        display: grid;
        grid-template-columns: 1fr 1fr;
        gap: var(--space-3);
      }

      .pinned {
        display: flex;
        align-items: center;
        gap: var(--space-2);
        flex-wrap: wrap;
      }

      .chips {
        display: flex;
        flex-wrap: wrap;
        gap: var(--space-1);
      }
    `,
  ],
})
export class NodeProperties {
  readonly secretNames = input<string[]>([]);

  protected readonly store = inject(DesignerStore);
  private readonly catalog = inject(NodeApiService);

  protected readonly activeTab = signal<string>('Settings');
  protected readonly idDraft = signal<string | null>(null);
  protected readonly idError = signal<string | null>(null);
  protected readonly configError = signal<string | null>(null);

  protected readonly node = this.store.selectedNode;

  protected readonly selectedConnection = computed(() => {
    const index = this.store.selectedConnectionIndex();
    return index == null ? null : (this.store.connections()[index] ?? null);
  });

  /**
   * The catalogue entry describing the selected node, or undefined when its plugin is not loaded.
   *
   * Resolved by plugin coordinate as well as by node type, so a node using the generic `PLUGIN` marker
   * type still gets the schema-driven property panel rather than a bare JSON box.
   */
  protected readonly entry = computed(() => {
    const current = this.node();
    return current ? resolveCatalogEntry(current, this.catalog.entries()) : undefined;
  });

  /** Filter text for the operation dropdown; only rendered once a plugin has enough operations to need it. */
  protected readonly operationQuery = signal('');

  /**
   * Configuration keys the last operation change discarded.
   *
   * Held per node id, so switching to a different node clears the note rather than carrying a stale one.
   */
  private readonly dropped = signal<{ nodeId: string; keys: string[] }>({ nodeId: '', keys: [] });

  protected readonly droppedKeys = computed(() => {
    const record = this.dropped();
    return record.nodeId === this.node()?.id ? record.keys : [];
  });

  /**
   * Why this node's operation could not be resolved, if it could not.
   *
   * Drives which warning the panel shows, because a missing plugin and a removed operation need opposite
   * advice — one is an administrator's problem, the other the author fixes in the dropdown below.
   */
  protected readonly operationStatus = computed(() => {
    const current = this.node();
    return current
      ? operationStatus(current, this.catalog.entries(), this.entry())
      : ('OK' as const);
  });

  /**
   * Every operation this node's plugin offers. Empty for a built-in, which has none.
   *
   * The node's own `pluginId` is passed so the list survives its node type being removed by a plugin
   * update: that is precisely when the author needs the dropdown most.
   */
  protected readonly operations = computed(() =>
    operationsFor(this.node()?.type, this.catalog.entries(), this.node()?.pluginId),
  );

  /**
   * The dropdown's contents, filtered and grouped.
   *
   * The selected operation is always included even when it does not match the filter — a select whose current
   * value is absent from its options renders as blank, which reads as though the node lost its configuration.
   */
  protected readonly operationGroups = computed(() => {
    const term = this.operationQuery();
    const selected = this.node()?.type;
    const matching = this.operations().filter(
      (operation) => operation.nodeType === selected || matchesOperation(operation, term),
    );
    return groupOperations(matching);
  });

  /**
   * Switches the node to another operation of the same plugin.
   *
   * `nodeType` is what the workflow stores and what the engine resolves, so this is the whole change — the
   * form below re-renders from the new node type's own schema with no further work.
   */
  protected changeOperation(nodeType: string): void {
    const current = this.node();
    if (!current || !nodeType || nodeType === current.type) {
      return;
    }
    const target = this.catalog.entries().find((candidate) => candidate.nodeType === nodeType);
    const carried = carryOverConfiguration(this.configuration(), target?.configurationSchema ?? null);

    this.dropped.set({ nodeId: current.id, keys: carried.dropped });
    this.patch({ type: nodeType, configuration: carried.configuration });
  }

  protected readonly tabs = computed(() => {
    const current = this.node();
    if (!current) {
      return [];
    }
    // Start nodes have no upstream node to map from and cannot fail in a way a policy would change,
    // so those tabs are omitted rather than shown empty.
    if (current.type === 'START') {
      return ['Settings', 'Configuration', 'Mappings'];
    }
    return ['Settings', 'Configuration', 'Mappings', 'Reliability'];
  });

  protected readonly configuration = computed(() => this.node()?.configuration ?? {});

  protected readonly configurationJson = computed(() => {
    try {
      return JSON.stringify(this.configuration(), null, 2);
    } catch {
      return '{}';
    }
  });

  protected readonly conditions = computed<DecisionCondition[]>(() => this.node()?.conditions ?? []);

  protected readonly otherNodes = computed(() =>
    this.store.nodes().filter((candidate) => candidate.id !== this.node()?.id),
  );

  protected readonly retryEnabled = computed(() => this.node()?.retry?.enabled === true);

  protected readonly errorPolicyHint = computed(() => {
    switch (this.node()?.errorPolicy) {
      case 'SKIP':
        return 'The failure is recorded and the default edge is followed, as if the node had been skipped.';
      case 'CONTINUE':
        return 'The failure is published as node output, so a later decision node can branch on it.';
      case 'COMPENSATE':
        return 'The compensation node runs to undo prior work, then the workflow fails.';
      default:
        return 'The execution is marked FAILED and stops. This is the default.';
    }
  });

  protected patch(patch: Partial<WorkflowNode>): void {
    const current = this.node();
    if (current) {
      this.store.updateNode(current.id, patch);
    }
  }

  protected patchConnection(patch: Partial<{ sourcePort: string | null; label: string | null; condition: string | null }>): void {
    const index = this.store.selectedConnectionIndex();
    if (index != null) {
      this.store.updateConnection(index, patch);
    }
  }

  protected commitId(currentId: string): void {
    const draft = this.idDraft();
    if (draft == null) {
      return;
    }
    this.idDraft.set(null);
    const trimmed = draft.trim();
    if (!trimmed || trimmed === currentId) {
      this.idError.set(null);
      return;
    }
    if (!/^[A-Za-z0-9._-]+$/.test(trimmed)) {
      this.idError.set('Use letters, digits, dots, dashes and underscores only.');
      return;
    }
    this.idError.set(this.store.renameNode(currentId, trimmed) ? null : 'That id is already used.');
  }

  protected addCondition(): void {
    // Give the new branch a name up front so its "Next node" dropdown is usable immediately, rather than
    // requiring the operator to name it first. The name is a friendly default they can rename.
    const taken = new Set(this.conditions().map((condition) => condition.branch));
    let n = this.conditions().length + 1;
    let name = `Branch ${n}`;
    while (taken.has(name)) {
      n += 1;
      name = `Branch ${n}`;
    }
    this.patch({ conditions: [...this.conditions(), { branch: name, expression: '' }] });
  }

  protected setCondition(index: number, patch: Partial<DecisionCondition>): void {
    const previous = this.conditions()[index];
    const next = this.conditions().map((condition, position) =>
      position === index ? { ...condition, ...patch } : condition,
    );
    this.patch({ conditions: next });

    // Renaming a branch must carry its destination edge with it: the edge is keyed by the branch name
    // (sourcePort), so without this a rename would orphan the chosen "Next node".
    if (patch.branch !== undefined && previous && patch.branch && patch.branch !== previous.branch) {
      const decisionId = this.node()?.id;
      const edgeIndex = this.store
        .connections()
        .findIndex((c) => c.source === decisionId && c.sourcePort === previous.branch);
      if (edgeIndex >= 0) {
        this.store.updateConnection(edgeIndex, { sourcePort: patch.branch });
      }
    }
  }

  protected removeCondition(index: number): void {
    this.patch({ conditions: this.conditions().filter((_, position) => position !== index) });
  }

  // ---- decision branch destinations (the "Next node" dropdowns) --------------
  //
  // A branch's destination is an edge, not node metadata: the connection whose source is this decision node
  // and whose sourcePort is the branch name. Selecting a node here creates or updates that edge, so the graph
  // the engine runs is the single source of truth — a rename only changes the label, a move changes nothing,
  // and a delete makes the reference show as missing rather than silently keeping a dead id.

  /** Nodes a branch may point to: everything except the Start node and this decision node itself. */
  protected readonly branchDestinations = computed(() =>
    this.store
      .nodes()
      .filter((candidate) => candidate.id !== this.node()?.id && candidate.type !== 'START'),
  );

  /** True when two conditions share the same expression — the engine can never reach the second. */
  protected readonly duplicateBranch = computed(() => {
    const seen = new Set<string>();
    for (const condition of this.conditions()) {
      const key = (condition.expression ?? '').trim();
      if (!key) {
        continue;
      }
      if (seen.has(key)) {
        return true;
      }
      seen.add(key);
    }
    return false;
  });

  /** The node id a branch currently points at, from its edge, or '' when unset. */
  protected branchTargetId(branchName: string): string {
    const decisionId = this.node()?.id;
    const edge = this.store
      .connections()
      .find((c) => c.source === decisionId && (c.sourcePort ?? '') === (branchName ?? ''));
    return edge?.target ?? '';
  }

  /**
   * The default destination's target. Prefers the portless default edge (what this dropdown writes), then a
   * legacy named default-branch edge, so an older workflow that used a named default still displays correctly.
   */
  protected defaultTargetId(): string {
    const decisionId = this.node()?.id;
    const named = this.node()?.defaultBranch;
    const connections = this.store.connections();
    const portless = connections.find((c) => c.source === decisionId && !c.sourcePort);
    const namedEdge = named
      ? connections.find((c) => c.source === decisionId && c.sourcePort === named)
      : undefined;
    return (portless ?? namedEdge)?.target ?? '';
  }

  /** True when a stored destination id no longer matches any node — a deleted target. */
  protected targetMissing(targetId: string): boolean {
    return !!targetId && !this.store.nodes().some((node) => node.id === targetId);
  }

  /** A friendly label for a node id: its name, or its type and a short id — never a bare id. */
  protected nodeLabel(id: string): string {
    const node = this.store.nodes().find((n) => n.id === id);
    if (!node) {
      return 'Node no longer exists';
    }
    return node.name?.trim() ? node.name : `${node.type} · ${node.id.slice(0, 6)}`;
  }

  protected setBranchTarget(branchName: string, nodeId: string): void {
    if (branchName?.trim()) {
      this.setEdgeTarget(branchName, nodeId);
    }
  }

  /**
   * Sets (or clears) the default destination.
   *
   * <p>The engine's decision node fails outright when no condition matches and {@code defaultBranch} is empty —
   * it never reaches the portless default edge on its own. So choosing a default destination both sets a
   * sentinel {@code defaultBranch} name (so the "nothing matched" path is taken) and points the portless
   * default edge at the node (which {@code nextNode} then falls back to). Clearing does the reverse, restoring
   * the "fail when nothing matches" behaviour the empty option describes.
   */
  protected setDefaultTarget(nodeId: string): void {
    if (nodeId) {
      this.patch({ defaultBranch: 'else' });
      this.setEdgeTarget(null, nodeId);
    } else {
      this.patch({ defaultBranch: null });
      this.setEdgeTarget(null, '');
    }
  }

  /** Creates, repoints or removes the branch/default edge to match the chosen destination. */
  private setEdgeTarget(sourcePort: string | null, nodeId: string): void {
    const decisionId = this.node()?.id;
    if (!decisionId) {
      return;
    }
    const index = this.store
      .connections()
      .findIndex((c) => c.source === decisionId && (c.sourcePort ?? null) === (sourcePort ?? null));
    if (!nodeId) {
      if (index >= 0) {
        this.store.removeConnection(index);
      }
      return;
    }
    if (index >= 0) {
      this.store.updateConnection(index, { target: nodeId });
    } else {
      this.store.connect(decisionId, nodeId, sourcePort);
    }
  }

  protected setRetryEnabled(enabled: boolean): void {
    this.patch({
      retry: enabled
        ? {
            enabled: true,
            maxAttempts: this.node()?.retry?.maxAttempts ?? 3,
            backoffMillis: this.node()?.retry?.backoffMillis ?? 5000,
            backoffMultiplier: this.node()?.retry?.backoffMultiplier ?? 2,
            maxBackoffMillis: this.node()?.retry?.maxBackoffMillis ?? 60000,
          }
        : null,
    });
  }

  protected setRetry(patch: Partial<NonNullable<WorkflowNode['retry']>>): void {
    this.patch({ retry: { ...(this.node()?.retry ?? { enabled: true }), ...patch } });
  }

  protected setOutputs(text: string): void {
    const outputs = text
      .split(',')
      .map((value) => value.trim())
      .filter((value) => value.length > 0);
    this.patch({ outputs });
  }

  protected setConfigurationJson(text: string): void {
    if (text.trim().length === 0) {
      this.configError.set(null);
      this.patch({ configuration: {} });
      return;
    }
    try {
      const parsed = JSON.parse(text);
      if (!parsed || typeof parsed !== 'object' || Array.isArray(parsed)) {
        this.configError.set('Configuration must be a JSON object.');
        return;
      }
      this.configError.set(null);
      this.patch({ configuration: parsed as Record<string, unknown> });
    } catch (error) {
      this.configError.set(error instanceof Error ? error.message : 'Not valid JSON');
    }
  }

  /** Mapping values are always textual, so a coerced number is narrowed back to a string map. */
  protected asStringMap(value: Record<string, unknown>): Record<string, string> {
    const result: Record<string, string> = {};
    for (const [key, raw] of Object.entries(value)) {
      result[key] = raw == null ? '' : String(raw);
    }
    return result;
  }

  protected toNumber(value: string, fallback: number): number {
    const parsed = Number(value);
    return Number.isFinite(parsed) ? parsed : fallback;
  }

  protected toOptionalNumber(value: string): number | null {
    if (!value?.trim()) {
      return null;
    }
    const parsed = Number(value);
    return Number.isFinite(parsed) ? parsed : null;
  }
}
