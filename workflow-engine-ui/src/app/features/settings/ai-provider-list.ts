import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { AiApiService } from '../../core/api/ai-api.service';
import { AI_PROVIDER_GROUPS, AiConnection } from '../../core/models/ai.models';
import { NotificationService } from '../../core/notification.service';
import { AgoPipe } from '../../shared/pipes/format.pipes';
import { EmptyState } from '../../shared/ui/empty-state';
import { Modal } from '../../shared/ui/modal';
import { ConfirmDialog, ConfirmRequest } from '../../shared/ui/confirm-dialog';

/**
 * Settings → AI Providers: the connections that hold AI credentials.
 *
 * <p>Credentials live here and only here — a workflow node references a connection by id, never a key. The list
 * never shows a key (the server never returns one); editing leaves the key field blank to keep the existing
 * credential. Managing connections needs the AI_PROVIDER_MANAGE permission, enforced by the server.
 */
@Component({
  selector: 'wf-ai-provider-list',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [FormsModule, Modal, ConfirmDialog, EmptyState, AgoPipe],
  template: `
    <div class="page">
      <div class="page-header">
        <div class="page-header__text">
          <h1>AI Providers</h1>
          <p>
            Connections to AI providers (OpenAI, Claude, Ollama, …). An AI Agent node references a connection;
            the API key is stored encrypted and never returned or placed in a workflow.
          </p>
        </div>
        <div class="toolbar">
          <button class="btn btn--primary" type="button" (click)="openCreate()">New connection</button>
        </div>
      </div>

      <div class="card">
        @if (connections().length === 0 && !loading()) {
          <wf-empty-state heading="No connections" message="Add one to use the AI Agent node.">
            <button class="btn btn--primary" type="button" (click)="openCreate()">New connection</button>
          </wf-empty-state>
        } @else {
          <table class="table">
            <thead>
              <tr>
                <th>Name</th><th>Provider</th><th>Endpoint</th><th>Key</th><th>Status</th><th>Updated</th>
                <th class="cell-actions">Actions</th>
              </tr>
            </thead>
            <tbody>
              @for (c of connections(); track c.id) {
                <tr>
                  <td>{{ c.name }}</td>
                  <td><span class="tag">{{ c.providerType }}</span></td>
                  <td class="mono small">{{ c.endpoint || '—' }}</td>
                  <td>{{ c.hasKey ? 'set' : '—' }}</td>
                  <td>
                    @if (c.enabled) {
                      <span class="tag tag--ok">Enabled</span>
                    } @else {
                      <span class="tag">Disabled</span>
                    }
                  </td>
                  <td class="small muted">{{ c.updatedAt | ago }}</td>
                  <td class="cell-actions">
                    <button class="btn btn--sm" type="button" [disabled]="busy()" (click)="test(c)">Test</button>
                    <button class="btn btn--sm" type="button" (click)="openEdit(c)">Edit</button>
                    <button class="btn btn--danger btn--sm" type="button" (click)="remove(c)">Delete</button>
                  </td>
                </tr>
              }
            </tbody>
          </table>
        }
      </div>
    </div>

    @if (editing()) {
      <wf-modal
        [heading]="editingId() ? 'Edit connection' : 'New connection'"
        width="520px"
        [dismissable]="!busy()"
        (closed)="editing.set(false)"
      >
        <div class="form">
          <label class="field">
            <span class="field__label">Name</span>
            <input type="text" [(ngModel)]="name" placeholder="OpenAI Production" />
          </label>
          <label class="field">
            <span class="field__label">Provider</span>
            <select [(ngModel)]="providerType">
              @for (group of providerGroups; track group.group) {
                <optgroup [label]="group.group">
                  @for (t of group.types; track t.value) {
                    <option [value]="t.value">{{ t.label }}</option>
                  }
                </optgroup>
              }
            </select>
          </label>
          <label class="field">
            <span class="field__label">Endpoint (for self-hosted / OpenAI-compatible)</span>
            <input type="text" class="mono" [(ngModel)]="endpoint" placeholder="http://localhost:11434" />
          </label>
          <label class="field">
            <span class="field__label">API key {{ editingId() ? '(leave blank to keep)' : '' }}</span>
            <input type="password" autocomplete="new-password" [(ngModel)]="apiKey" />
          </label>
          <label class="check">
            <input type="checkbox" [(ngModel)]="enabled" /> Enabled
          </label>
        </div>
        <div modalFooter style="display:flex; gap:var(--space-3); justify-content:flex-end">
          <button class="btn" type="button" [disabled]="busy()" (click)="editing.set(false)">Cancel</button>
          <button class="btn btn--primary" type="button" [disabled]="busy() || !name().trim()" (click)="save()">
            {{ busy() ? 'Saving…' : 'Save' }}
          </button>
        </div>
      </wf-modal>
    }

    @if (pendingConfirm(); as c) {
      <wf-confirm-dialog
        [heading]="c.heading" [message]="c.message" [confirmLabel]="c.confirmLabel" [danger]="c.danger"
        (confirmed)="runConfirmed()" (cancelled)="pendingConfirm.set(null)"
      />
    }
  `,
  styles: [
    `
      .form { display: flex; flex-direction: column; gap: var(--space-3); }
      .field { display: flex; flex-direction: column; gap: var(--space-1); }
      .field__label { font-size: var(--text-sm); color: var(--text-muted); }
      .check { display: flex; align-items: center; gap: var(--space-2); }
      .tag--ok { background: var(--success-soft, #e6f4ea); color: var(--success, #1e7e34); }
    `,
  ],
})
export class AiProviderList {
  protected readonly providerGroups = AI_PROVIDER_GROUPS;
  private readonly api = inject(AiApiService);
  private readonly notifications = inject(NotificationService);

  protected readonly connections = signal<AiConnection[]>([]);
  protected readonly loading = signal(false);
  protected readonly busy = signal(false);

  protected readonly editing = signal(false);
  protected readonly editingId = signal<string | null>(null);
  protected readonly name = signal('');
  protected readonly providerType = signal('OPENAI');
  protected readonly endpoint = signal('');
  protected readonly apiKey = signal('');
  protected readonly enabled = signal(true);

  protected readonly pendingConfirm = signal<ConfirmRequest | null>(null);

  constructor() {
    this.load();
  }

  protected load(): void {
    this.loading.set(true);
    this.api.connections().subscribe({
      next: (list) => {
        this.connections.set(list);
        this.loading.set(false);
      },
      error: () => this.loading.set(false),
    });
  }

  protected openCreate(): void {
    this.editingId.set(null);
    this.name.set('');
    this.providerType.set('OPENAI');
    this.endpoint.set('');
    this.apiKey.set('');
    this.enabled.set(true);
    this.editing.set(true);
  }

  protected openEdit(c: AiConnection): void {
    this.editingId.set(c.id);
    this.name.set(c.name);
    this.providerType.set(c.providerType);
    this.endpoint.set(c.endpoint ?? '');
    this.apiKey.set('');
    this.enabled.set(c.enabled);
    this.editing.set(true);
  }

  protected save(): void {
    if (this.busy() || !this.name().trim()) {
      return;
    }
    this.busy.set(true);
    const request = {
      name: this.name().trim(),
      providerType: this.providerType(),
      endpoint: this.endpoint().trim() || null,
      apiKey: this.apiKey() || null,
      enabled: this.enabled(),
    };
    const id = this.editingId();
    const call = id ? this.api.update(id, request) : this.api.create(request);
    call.subscribe({
      next: () => {
        this.busy.set(false);
        this.editing.set(false);
        this.notifications.success(id ? 'Connection updated' : 'Connection created');
        this.load();
      },
      error: () => this.busy.set(false),
    });
  }

  protected test(c: AiConnection): void {
    this.busy.set(true);
    this.api.test(c.id).subscribe({
      next: (result) => {
        this.busy.set(false);
        if (result.connected) {
          this.notifications.success(`"${c.name}" connected`);
        } else {
          this.notifications.error?.(`"${c.name}" did not connect`);
        }
      },
      error: () => this.busy.set(false),
    });
  }

  protected remove(c: AiConnection): void {
    this.pendingConfirm.set({
      heading: 'Delete connection?',
      message: `Delete "${c.name}"? Its stored key is removed and any node using it will fail until repointed.`,
      confirmLabel: 'Delete',
      danger: true,
      onConfirm: () =>
        this.api.delete(c.id).subscribe({
          next: () => {
            this.notifications.success(`Deleted "${c.name}"`);
            this.load();
          },
        }),
    });
  }

  protected runConfirmed(): void {
    const request = this.pendingConfirm();
    this.pendingConfirm.set(null);
    request?.onConfirm();
  }
}
