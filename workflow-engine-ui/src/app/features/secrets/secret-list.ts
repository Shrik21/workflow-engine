import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { SecretApiService } from '../../core/api/secret-api.service';
import { SecretStatus, SecretSummary } from '../../core/models/plugin.models';
import { NotificationService } from '../../core/notification.service';
import { AuthStateService } from '../../core/auth/auth-state.service';
import { AgoPipe } from '../../shared/pipes/format.pipes';
import { EmptyState } from '../../shared/ui/empty-state';
import { Icon } from '../../shared/ui/icon';
import { PageHeader } from '../../shared/ui/page-header';
import { Modal } from '../../shared/ui/modal';
import { ConfirmDialog, ConfirmRequest } from '../../shared/ui/confirm-dialog';

/**
 * Credential management.
 *
 * There is no way to read a secret back, because the engine exposes none. The screen shows names,
 * scopes and read counts, and the value field is write-only. That is worth being explicit about in the
 * interface itself, so nobody goes looking for a reveal button that should not exist.
 */
@Component({
  selector: 'wf-secret-list',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [Icon, Modal, ConfirmDialog, EmptyState, PageHeader, AgoPipe],
  template: `
    <div class="page">
      <wf-page-header
        title="Secrets"
        description="Credentials plugins use. A workflow references a secret by name; the engine decrypts it at execution time, checks it against the plugin's granted scopes, records the access and keeps the value out of logs and execution records."
      >
        <button class="btn btn--sm" type="button" (click)="load()">
          <wf-icon name="refresh" /><span>Refresh</span>
        </button>
        <button
          class="btn btn--primary"
          type="button"
          [disabled]="!session.has('SECRET_MANAGE')"
          (click)="startCreate()"
        >
          Add secret
        </button>
      </wf-page-header>

      @if (!session.has('SECRET_VIEW')) {
        <div class="notice notice--warning" style="margin-bottom: var(--space-4)">
          Viewing secrets requires the ADMIN role. Ask an administrator if you need access.
        </div>
      } @else if (status() && !status()!.configured) {
        <div class="notice notice--error" style="margin-bottom: var(--space-4)">
          <strong>No encryption key is configured.</strong>
          The engine refuses to store secrets without
          <code>workflow.engine.secrets.master-key</code>, so plugins needing credentials will fail.
          Generate one with <code>openssl rand -base64 32</code> and restart the engine with it set.
        </div>
      }

      <div class="card">
        @if (secrets().length === 0) {
          <wf-empty-state
            heading="No secrets stored"
            message="Add the API keys and tokens your plugins need. Values are encrypted with AES-GCM and never returned by any endpoint."
          >
            <button
              class="btn btn--primary"
              type="button"
              [disabled]="!session.has('SECRET_MANAGE')"
              (click)="startCreate()"
            >
              Add secret
            </button>
          </wf-empty-state>
        } @else {
          <table class="table">
            <thead>
              <tr>
                <th>Name</th>
                <th>Description</th>
                <th>Readable by</th>
                <th>Reads</th>
                <th>Updated</th>
                <th class="cell-actions">Actions</th>
              </tr>
            </thead>
            <tbody>
              @for (secret of secrets(); track secret.name) {
                <tr>
                  <td class="mono">{{ secret.name }}</td>
                  <td class="small muted">{{ secret.description || '' }}</td>
                  <td class="small">
                    @if (secret.allowedPlugins.length === 0) {
                      <span class="muted">any plugin whose scope matches</span>
                    } @else {
                      @for (pluginId of secret.allowedPlugins; track pluginId) {
                        <span class="tag tag--mono">{{ pluginId }}</span>
                      }
                    }
                  </td>
                  <td>{{ secret.readCount }}</td>
                  <td class="small muted">{{ secret.updatedAt | ago }}</td>
                  <td class="cell-actions">
                    <button class="btn btn--sm" type="button" (click)="startRotate(secret)">
                      Replace value
                    </button>
                    <button class="btn btn--danger btn--sm" type="button" (click)="remove(secret)">
                      Delete
                    </button>
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
        [heading]="rotating() ? 'Replace secret value' : 'Add a secret'"
        subheading="The value is encrypted before it is stored and cannot be read back through the API."
        (closed)="editing.set(false)"
      >
        <div class="field">
          <label class="field__label" for="secret-name">Name</label>
          <input
            id="secret-name"
            type="text"
            class="mono"
            placeholder="sendgrid.apiKey"
            [disabled]="rotating()"
            [value]="name()"
            (input)="name.set($any($event.target).value)"
          />
          <p class="field__hint">
            A workflow node references this name. Prefix it by integration so plugin scopes such as
            <code>sendgrid.</code> can grant access to a group of secrets.
          </p>
        </div>

        <div class="field">
          <label class="field__label" for="secret-value">Value</label>
          <input
            id="secret-value"
            type="password"
            autocomplete="off"
            [value]="value()"
            (input)="value.set($any($event.target).value)"
          />
        </div>

        <div class="field">
          <label class="field__label" for="secret-description">Description</label>
          <input
            id="secret-description"
            type="text"
            [value]="description()"
            (input)="description.set($any($event.target).value)"
          />
        </div>

        <div class="field">
          <label class="field__label" for="secret-plugins">Readable by plugins</label>
          <input
            id="secret-plugins"
            type="text"
            class="mono"
            placeholder="sendgrid, slack"
            [value]="allowedPlugins()"
            (input)="allowedPlugins.set($any($event.target).value)"
          />
          <p class="field__hint">
            Comma-separated plugin ids. Empty means any plugin whose declared scope covers the name.
            Both checks must pass, so this narrows access rather than granting it.
          </p>
        </div>

        <div modalFooter>
          <button class="btn" type="button" (click)="editing.set(false)">Cancel</button>
          <button
            class="btn btn--primary"
            type="button"
            [disabled]="!canSave() || busy()"
            (click)="save()"
          >
            Save
          </button>
        </div>
      </wf-modal>
    }

    @if (pendingConfirm(); as c) {
      <wf-confirm-dialog
        [heading]="c.heading"
        [message]="c.message"
        [confirmLabel]="c.confirmLabel"
        [danger]="c.danger"
        (confirmed)="runConfirmed()"
        (cancelled)="pendingConfirm.set(null)"
      />
    }
  `,
})
export class SecretList {
  protected readonly session = inject(AuthStateService);

  private readonly api = inject(SecretApiService);
  private readonly notifications = inject(NotificationService);

  protected readonly secrets = signal<SecretSummary[]>([]);
  protected readonly status = signal<SecretStatus | null>(null);
  protected readonly editing = signal(false);
  protected readonly rotating = signal(false);
  protected readonly busy = signal(false);
  protected readonly pendingConfirm = signal<ConfirmRequest | null>(null);

  protected readonly name = signal('');
  protected readonly value = signal('');
  protected readonly description = signal('');
  protected readonly allowedPlugins = signal('');

  constructor() {
    if (this.session.has('SECRET_VIEW')) {
      this.load();
    }
  }

  protected load(): void {
    if (!this.session.has('SECRET_VIEW')) {
      return;
    }
    this.api.list().subscribe({ next: (secrets) => this.secrets.set(secrets) });
    this.api.status().subscribe({ next: (status) => this.status.set(status) });
  }

  protected canSave(): boolean {
    return this.name().trim().length > 0 && this.value().length > 0;
  }

  protected startCreate(): void {
    this.rotating.set(false);
    this.name.set('');
    this.value.set('');
    this.description.set('');
    this.allowedPlugins.set('');
    this.editing.set(true);
  }

  protected startRotate(secret: SecretSummary): void {
    this.rotating.set(true);
    this.name.set(secret.name);
    this.value.set('');
    this.description.set(secret.description ?? '');
    this.allowedPlugins.set(secret.allowedPlugins.join(', '));
    this.editing.set(true);
  }

  protected save(): void {
    this.busy.set(true);
    this.api
      .put(this.name().trim(), {
        value: this.value(),
        description: this.description().trim() || null,
        allowedPlugins: this.allowedPlugins()
          .split(',')
          .map((entry) => entry.trim())
          .filter((entry) => entry.length > 0),
      })
      .subscribe({
        next: () => {
          this.busy.set(false);
          this.editing.set(false);
          // Clear the plaintext from component state as soon as it has been sent.
          this.value.set('');
          this.notifications.success(`Stored "${this.name().trim()}"`);
          this.load();
        },
        error: () => this.busy.set(false),
      });
  }

  protected remove(secret: SecretSummary): void {
    this.pendingConfirm.set({
      heading: 'Delete secret?',
      message: `Delete "${secret.name}"?\n\nAny workflow node referencing it will fail at execution time.`,
      confirmLabel: 'Delete',
      danger: true,
      onConfirm: () => this.doRemove(secret),
    });
  }

  private doRemove(secret: SecretSummary): void {
    this.api.delete(secret.name).subscribe({
      next: () => {
        this.notifications.success(`Deleted "${secret.name}"`);
        this.load();
      },
    });
  }

  /** Runs the pending confirmed action, then clears the dialog. */
  protected runConfirmed(): void {
    const request = this.pendingConfirm();
    this.pendingConfirm.set(null);
    request?.onConfirm();
  }
}
