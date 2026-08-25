import { ChangeDetectionStrategy, Component, computed, effect, inject, input, output, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { AiApiService } from '../../core/api/ai-api.service';
import { AiConnection, AiModel, AiTool, AiToolSelection } from '../../core/models/ai.models';
import { WorkflowNode } from '../../core/models/workflow.models';

type AgentMode = 'SIMPLE' | 'TOOL_CALLING' | 'AUTONOMOUS' | 'SUPERVISED';
type OutputType = 'TEXT' | 'JSON' | 'OBJECT' | 'ARRAY' | 'BOOLEAN' | 'NUMBER';
interface InputRow {
  name: string;
  expression: string;
}

/**
 * The AI Agent node's configuration panel — a purpose-built editor, the way the Form and Decision nodes have
 * one, so the AI node feels native rather than bolted on.
 *
 * <p>It never asks for a key or a raw provider list: the provider comes from a stored <em>connection</em>
 * (managed under Settings → AI Providers), and the models come from that connection's provider, discovered
 * live. The panel only ever writes a plain configuration object onto the node — connection id, model, prompt,
 * output shape, limits — which the backend executor reads. Prompts may reference workflow variables with
 * <span class="mono">$&#123;name&#125;</span>, resolved safely by the engine at run time.
 */
@Component({
  selector: 'wf-ai-agent-config',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [FormsModule, RouterLink],
  template: `
    <div class="ai">
      <label class="field">
        <span class="field__label">AI provider connection</span>
        @if (connections().length === 0) {
          <div class="notice">
            <p>No AI provider connections yet.</p>
            <a class="btn btn--sm" routerLink="/settings/ai-providers">Add a connection</a>
          </div>
        } @else {
          <select [ngModel]="connectionId()" (ngModelChange)="setConnection($event)">
            <option value="">Select a connection…</option>
            @for (c of connections(); track c.id) {
              <option [value]="c.id">{{ c.name }} ({{ c.providerType }})</option>
            }
          </select>
        }
      </label>

      @if (connectionId()) {
        <label class="field">
          <span class="field__label">Model</span>
          <span class="inline">
            <select [ngModel]="model()" (ngModelChange)="setModel($event)" style="flex:1">
              <option value="">Select a model…</option>
              @for (m of models(); track m.id) {
                <option [value]="m.id">{{ m.displayName }}</option>
              }
              @if (model() && !modelInList()) {
                <option [value]="model()" [selected]="true">{{ model() }}</option>
              }
            </select>
            <button class="btn btn--sm" type="button" [disabled]="loadingModels()" (click)="loadModels()">
              Refresh
            </button>
          </span>
        </label>
      }

      <label class="field">
        <span class="field__label">Agent mode</span>
        <select [ngModel]="agentMode()" (ngModelChange)="setMode($event)">
          <option value="SIMPLE">Simple</option>
          <option value="TOOL_CALLING">Tool calling</option>
          <option value="AUTONOMOUS">Autonomous</option>
          <option value="SUPERVISED">Supervised</option>
        </select>
        @if (agentMode() === 'SUPERVISED') {
          <span class="field__hint">
            Supervised: destructive tools run only when approved. Approve them below, or grant approval at run time.
          </span>
        }
      </label>

      <label class="field">
        <span class="field__label">System instructions</span>
        <textarea rows="3" [ngModel]="systemInstructions()" (ngModelChange)="setSystem($event)"
          placeholder="You are a customer support agent…"></textarea>
      </label>

      <label class="field">
        <span class="field__label">Prompt</span>
        <textarea rows="4" [ngModel]="prompt()" (ngModelChange)="setPrompt($event)"
          placeholder="Resolve this issue: &#36;{customerIssue}"></textarea>
        <span class="field__hint">Use <span class="mono">&#36;&#123;variable&#125;</span> to insert workflow variables.</span>
      </label>

      <div class="field">
        <span class="field__label">Inputs (optional)</span>
        @for (row of inputRows(); track $index) {
          <div class="row inputrow">
            <input type="text" placeholder="name" [ngModel]="row.name"
              (ngModelChange)="updateInput($index, 'name', $event)" />
            <input type="text" class="mono" placeholder="&#36;&#123;ticket.body&#125;" [ngModel]="row.expression"
              (ngModelChange)="updateInput($index, 'expression', $event)" />
            <button type="button" class="icon-btn" (click)="removeInput($index)" title="Remove">✕</button>
          </div>
        }
        <button type="button" class="link-btn" (click)="addInput()">+ Add input</button>
        <span class="field__hint">
          Mapped workflow data is appended to the prompt as a labelled data block — kept as data, never as
          instructions, so it can inform the model without overriding it.
        </span>
      </div>

      <div class="field">
        <label class="inline">
          <input type="checkbox" [checked]="memoryEnabled()" (change)="setMemoryEnabled($any($event.target).checked)" />
          <span class="field__label">Remember this conversation within the run</span>
        </label>
        @if (memoryEnabled()) {
          <input type="text" class="mono" placeholder="memory key (default)" [ngModel]="memoryKey()"
            (ngModelChange)="setMemoryKey($event)" />
          <span class="field__hint">
            Agents sharing this key in the same workflow run see each other's prior turns. Memory is scoped to the
            run and holds only prompts and answers — it never leaves the execution.
          </span>
        }
      </div>

      <div class="row">
        <label class="field" style="flex:1">
          <span class="field__label">Output type</span>
          <select [ngModel]="outputType()" (ngModelChange)="setOutputType($event)">
            <option value="TEXT">Text</option>
            <option value="JSON">JSON</option>
            <option value="OBJECT">Object</option>
            <option value="ARRAY">Array</option>
            <option value="BOOLEAN">Boolean</option>
            <option value="NUMBER">Number</option>
          </select>
        </label>
        <label class="field" style="flex:1">
          <span class="field__label">Output variable</span>
          <input type="text" class="mono" [ngModel]="outputVariable()" (ngModelChange)="setOutputVariable($event)" />
        </label>
      </div>

      @if (outputType() !== 'TEXT') {
        <label class="field">
          <span class="field__label">JSON schema (optional)</span>
          <textarea rows="4" class="mono" [ngModel]="schemaText()" (ngModelChange)="setSchema($event)"
            placeholder='{ "type":"object", "properties":{"category":{"type":"string"}}, "required":["category"] }'></textarea>
          @if (schemaError()) {
            <span class="field__error">{{ schemaError() }}</span>
          }
        </label>
        <label class="field">
          <span class="field__label">Repair attempts</span>
          <input type="number" min="0" max="3" [ngModel]="repairAttempts()"
            (ngModelChange)="setRepairAttempts($event)" placeholder="1" />
          <span class="field__hint">
            If the output misses the schema, re-prompt the model with the errors this many times before failing.
          </span>
        </label>
      }

      <div class="field">
        <span class="field__label">Tools</span>
        @if (tools().length === 0) {
          <span class="field__hint">No installed plugins are available as tools.</span>
        } @else {
          <div class="tools">
            @for (tool of tools(); track tool.pluginId + tool.nodeType) {
              <label class="tool">
                <input type="checkbox" [checked]="isToolSelected(tool)" (change)="toggleTool(tool)" />
                <span>
                  {{ tool.displayName }}
                  <span class="faint small">({{ tool.pluginId }})</span>
                  @if (tool.supportsAI) {
                    <span class="tag tag--ai">AI-ready</span>
                  }
                  @if (tool.destructive) {
                    <span class="tag tag--danger">Destructive</span>
                  }
                </span>
              </label>
            }
          </div>
          <span class="field__hint">
            The agent may only call the tools you select, and only with the running user's plugin permissions.
          </span>
        }
      </div>

      @if (supervised() && destructiveSelected().length > 0) {
        <div class="field">
          <span class="field__label">Approvals (supervised)</span>
          <div class="tools">
            @for (tool of destructiveSelected(); track tool.pluginId + tool.nodeType) {
              <label class="tool">
                <input type="checkbox" [checked]="isApproved(tool)" (change)="toggleApprove(tool)" />
                <span>Auto-approve <strong>{{ tool.displayName }}</strong></span>
              </label>
            }
          </div>
          <span class="field__hint">
            In supervised mode a destructive tool runs only if approved. Auto-approve it here, or leave it unchecked
            and grant approval at run time by writing the tool name into
            <span class="mono">&#36;&#123;approvedTools&#125;</span> from an upstream human-task or Form node.
            Unapproved calls are blocked, reported in <span class="mono">pendingApprovals</span>, and handed back to
            the model as data.
          </span>
        </div>
      }

      @if (toolsSelected()) {
        <div class="field">
          <span class="field__label">Agent limits</span>
          <div class="row">
            <label class="field" style="flex:1">
              <span class="field__label">Max iterations</span>
              <input type="number" min="1" max="25" [ngModel]="maxIterations()"
                (ngModelChange)="setLimit('maxIterations', $event)" placeholder="5" />
            </label>
            <label class="field" style="flex:1">
              <span class="field__label">Max tool calls</span>
              <input type="number" min="1" max="50" [ngModel]="maxToolCalls()"
                (ngModelChange)="setLimit('maxToolCalls', $event)" placeholder="10" />
            </label>
          </div>
          <span class="field__hint">
            The loop stops at whichever bound comes first — iterations, tool calls or the timeout below — then the
            agent is asked once more with no tools so it always returns an answer.
          </span>
        </div>
      }

      <details class="advanced">
        <summary>Advanced</summary>
        <div class="row">
          <label class="field" style="flex:1">
            <span class="field__label">Temperature</span>
            <input type="number" min="0" max="2" step="0.1" [ngModel]="temperature()" (ngModelChange)="setLimit('temperature', $event)" />
          </label>
          <label class="field" style="flex:1">
            <span class="field__label">Max tokens</span>
            <input type="number" min="1" [ngModel]="maxTokens()" (ngModelChange)="setLimit('maxTokens', $event)" />
          </label>
        </div>
        <div class="row">
          <label class="field" style="flex:1">
            <span class="field__label">Timeout (s)</span>
            <input type="number" min="1" [ngModel]="timeoutSeconds()" (ngModelChange)="setLimit('timeoutSeconds', $event)" />
          </label>
          <label class="field" style="flex:1">
            <span class="field__label">Retry count</span>
            <input type="number" min="0" [ngModel]="retryCount()" (ngModelChange)="setLimit('retryCount', $event)" />
          </label>
        </div>
      </details>
    </div>
  `,
  styles: [
    `
      .ai { display: flex; flex-direction: column; gap: var(--space-3); }
      .field { display: flex; flex-direction: column; gap: var(--space-1); }
      .field__label { font-size: var(--text-sm); color: var(--text-muted); }
      .field__hint { font-size: var(--text-xs); color: var(--text-muted); }
      .field__error { font-size: var(--text-xs); color: var(--danger, #c62828); }
      .row { display: flex; gap: var(--space-3); }
      .inline { display: flex; gap: var(--space-2); align-items: center; }
      .notice { border: 1px solid var(--border); border-radius: var(--radius-sm); padding: var(--space-3);
        background: var(--surface-sunken); display: flex; flex-direction: column; gap: var(--space-2); }
      .advanced summary { cursor: pointer; font-size: var(--text-sm); color: var(--text-muted); }
      .tools { display: flex; flex-direction: column; gap: var(--space-1); }
      .tool { display: flex; align-items: center; gap: var(--space-2); cursor: pointer; }
      .faint { color: var(--text-muted); }
      .tag--ai { background: var(--hl-accent-blue, #1976d2); color: #fff; font-size: 10px;
        padding: 1px 6px; border-radius: 999px; }
      .tag--danger { background: var(--danger, #c62828); color: #fff; font-size: 10px;
        padding: 1px 6px; border-radius: 999px; }
      .inputrow { align-items: center; }
      .inputrow input:first-of-type { flex: 0 0 34%; }
      .inputrow input:nth-of-type(2) { flex: 1; }
      .icon-btn { background: none; border: none; color: var(--text-muted); cursor: pointer;
        font-size: var(--text-sm); padding: 0 var(--space-1); }
      .link-btn { align-self: flex-start; background: none; border: none; color: var(--hl-accent-blue, #1976d2);
        cursor: pointer; font-size: var(--text-sm); padding: 0; }
    `,
  ],
})
export class AiAgentConfig {
  readonly node = input.required<WorkflowNode>();
  readonly nodeChange = output<Partial<WorkflowNode>>();

  private readonly api = inject(AiApiService);

  protected readonly connections = signal<AiConnection[]>([]);
  protected readonly models = signal<AiModel[]>([]);
  protected readonly tools = signal<AiTool[]>([]);
  protected readonly loadingModels = signal(false);
  protected readonly schemaError = signal<string | null>(null);

  private config(): Record<string, unknown> {
    return (this.node().configuration as Record<string, unknown> | undefined) ?? {};
  }
  private output(): Record<string, unknown> {
    return (this.config()['output'] as Record<string, unknown> | undefined) ?? {};
  }
  private limits(): Record<string, unknown> {
    return (this.config()['limits'] as Record<string, unknown> | undefined) ?? {};
  }
  private memory(): Record<string, unknown> {
    return (this.config()['memory'] as Record<string, unknown> | undefined) ?? {};
  }

  protected readonly connectionId = computed(() => String(this.config()['providerConnectionId'] ?? ''));
  protected readonly model = computed(() => String(this.config()['model'] ?? ''));
  protected readonly agentMode = computed<AgentMode>(
    () => (String(this.config()['agentMode'] ?? 'SIMPLE') as AgentMode),
  );
  protected readonly systemInstructions = computed(() => String(this.config()['systemInstructions'] ?? ''));
  protected readonly prompt = computed(() => String(this.config()['prompt'] ?? ''));
  protected readonly outputType = computed<OutputType>(
    () => (String(this.output()['type'] ?? 'TEXT') as OutputType),
  );
  protected readonly outputVariable = computed(() => String(this.output()['variable'] ?? 'aiResponse'));
  protected readonly schemaText = computed(() => {
    const schema = this.output()['schema'];
    return schema && Object.keys(schema).length ? JSON.stringify(schema, null, 2) : '';
  });
  protected readonly temperature = computed(() => this.limits()['temperature'] ?? null);
  protected readonly maxTokens = computed(() => this.limits()['maxTokens'] ?? null);
  protected readonly timeoutSeconds = computed(() => this.limits()['timeoutSeconds'] ?? null);
  protected readonly retryCount = computed(() => this.limits()['retryCount'] ?? null);
  protected readonly maxIterations = computed(() => this.limits()['maxIterations'] ?? null);
  protected readonly maxToolCalls = computed(() => this.limits()['maxToolCalls'] ?? null);

  /** Tool selection is what turns a single completion into a bounded agent loop, so the loop limits show then. */
  protected readonly toolsSelected = computed(() => this.selectedTools().length > 0);
  protected readonly supervised = computed(() => this.agentMode() === 'SUPERVISED');

  private approvedTools(): string[] {
    const raw = this.config()['approvedTools'];
    return Array.isArray(raw) ? (raw as string[]) : [];
  }

  /** The selected tools that are destructive — the ones a supervised agent must have approved before running. */
  protected readonly destructiveSelected = computed(() =>
    this.tools().filter((t) => t.destructive && this.isToolSelected(t)),
  );

  protected readonly repairAttempts = computed(() => this.output()['repairAttempts'] ?? null);
  protected readonly memoryEnabled = computed(() => this.memory()['enabled'] === true);
  protected readonly memoryKey = computed(() => String(this.memory()['key'] ?? ''));

  /** Mapped inputs edited as ordered rows; stored on the node as a {name, expression} list. */
  protected readonly inputRows = computed<InputRow[]>(() => {
    const raw = this.config()['inputs'];
    return Array.isArray(raw) ? (raw as InputRow[]) : [];
  });

  protected readonly modelInList = computed(() =>
    this.models().some((m) => m.id === this.model()),
  );

  private selectedTools(): AiToolSelection[] {
    const tools = this.config()['tools'];
    return Array.isArray(tools) ? (tools as AiToolSelection[]) : [];
  }

  protected isToolSelected(tool: AiTool): boolean {
    return this.selectedTools().some(
      (s) => s.pluginId === tool.pluginId && s.nodeType === tool.nodeType,
    );
  }

  protected toggleTool(tool: AiTool): void {
    const current = this.selectedTools();
    const next = this.isToolSelected(tool)
      ? current.filter((s) => !(s.pluginId === tool.pluginId && s.nodeType === tool.nodeType))
      : [...current, { pluginId: tool.pluginId, nodeType: tool.nodeType }];
    this.patchConfig({ tools: next });
  }

  protected isApproved(tool: AiTool): boolean {
    return this.approvedTools().includes(tool.toolName);
  }

  protected toggleApprove(tool: AiTool): void {
    const current = this.approvedTools();
    const next = this.isApproved(tool)
      ? current.filter((n) => n !== tool.toolName)
      : [...current, tool.toolName];
    this.patchConfig({ approvedTools: next });
  }

  constructor() {
    this.api.connections().subscribe({ next: (list) => this.connections.set(list) });
    this.api.tools().subscribe({ next: (list) => this.tools.set(list) });
    // Load models whenever a connection is present (including on first open of an existing node).
    effect(() => {
      const id = this.connectionId();
      if (id) {
        this.loadModels();
      } else {
        this.models.set([]);
      }
    });
  }

  protected loadModels(): void {
    const id = this.connectionId();
    if (!id) {
      return;
    }
    this.loadingModels.set(true);
    this.api.models(id).subscribe({
      next: (list) => {
        this.models.set(list);
        this.loadingModels.set(false);
      },
      error: () => this.loadingModels.set(false),
    });
  }

  private patchConfig(patch: Record<string, unknown>): void {
    this.nodeChange.emit({
      configuration: { ...this.config(), ...patch } as Record<string, unknown>,
    } as Partial<WorkflowNode>);
  }
  private patchOutput(patch: Record<string, unknown>): void {
    this.patchConfig({ output: { ...this.output(), ...patch } });
  }
  private patchLimits(patch: Record<string, unknown>): void {
    this.patchConfig({ limits: { ...this.limits(), ...patch } });
  }

  protected setConnection(id: string): void {
    // Changing connection clears the model, since it belongs to the previous provider.
    this.patchConfig({ providerConnectionId: id, model: '' });
  }
  protected setModel(model: string): void {
    this.patchConfig({ model });
  }
  protected setMode(agentMode: string): void {
    this.patchConfig({ agentMode });
  }
  protected setSystem(systemInstructions: string): void {
    this.patchConfig({ systemInstructions });
  }
  protected setPrompt(prompt: string): void {
    this.patchConfig({ prompt });
  }
  protected setOutputType(type: string): void {
    this.patchOutput({ type });
  }
  protected setOutputVariable(variable: string): void {
    this.patchOutput({ variable });
  }
  protected setSchema(text: string): void {
    if (!text.trim()) {
      this.schemaError.set(null);
      this.patchOutput({ schema: {} });
      return;
    }
    try {
      const parsed = JSON.parse(text);
      this.schemaError.set(null);
      this.patchOutput({ schema: parsed });
    } catch {
      this.schemaError.set('Not valid JSON.');
    }
  }
  protected setLimit(key: string, value: number | null): void {
    this.patchLimits({ [key]: value === null || (value as unknown as string) === '' ? null : Number(value) });
  }
  protected setMemoryEnabled(enabled: boolean): void {
    this.patchConfig({ memory: { ...this.memory(), enabled } });
  }
  protected setMemoryKey(key: string): void {
    this.patchConfig({ memory: { ...this.memory(), key } });
  }
  protected setRepairAttempts(value: number | null): void {
    this.patchOutput({
      repairAttempts: value === null || (value as unknown as string) === '' ? null : Number(value),
    });
  }

  protected addInput(): void {
    this.patchConfig({ inputs: [...this.inputRows(), { name: '', expression: '' }] });
  }
  protected updateInput(index: number, field: keyof InputRow, value: string): void {
    const next = this.inputRows().map((row, i) => (i === index ? { ...row, [field]: value } : row));
    this.patchConfig({ inputs: next });
  }
  protected removeInput(index: number): void {
    this.patchConfig({ inputs: this.inputRows().filter((_, i) => i !== index) });
  }
}
