import { HttpEventType } from '@angular/common/http';
import { ChangeDetectionStrategy, Component, computed, inject, output, signal } from '@angular/core';
import { PluginApiService } from '../../core/api/plugin-api.service';
import { NotificationService } from '../../core/notification.service';
import { BytesPipe } from '../../shared/pipes/format.pipes';
import { Modal } from '../../shared/ui/modal';

/**
 * The plugin upload dialog.
 *
 * Uploading a JAR is installing executable code into the engine's JVM, and this dialog is written to
 * make that fact and its consequences visible rather than to hide them behind a file picker:
 *
 * - Permissions are the prominent fields, not an advanced section. An empty host allowlist denies all
 *   outbound calls and an empty secret scope denies all credential access, so leaving them blank is a
 *   real decision and the dialog says so.
 * - The warning about class loader isolation is stated plainly, because an operator deciding whether to
 *   install a third-party plugin needs to know that isolation is not a sandbox.
 * - Upload progress is shown, since a plugin bundling its dependencies can be tens of megabytes.
 */
@Component({
  selector: 'wf-plugin-upload',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [Modal, BytesPipe],
  template: `
    <wf-modal
      heading="Install a plugin"
      subheading="The engine validates the archive, stores it in GridFS, loads it in an isolated class loader and registers its node types. No restart."
      width="640px"
      [dismissable]="!busy()"
      (closed)="cancelled.emit()"
    >
      <div class="field">
        <label class="field__label" for="jar">Plugin JAR</label>
        <input id="jar" type="file" accept=".jar" (change)="onFile($event)" />
        @if (file(); as chosen) {
          <p class="field__hint">{{ chosen.name }} · {{ chosen.size | bytes }}</p>
        }
      </div>

      <div class="grid-2">
        <div class="field">
          <label class="field__label" for="hosts">Allowed hosts</label>
          <input
            id="hosts"
            type="text"
            placeholder="api.sendgrid.com, *.slack.com"
            [value]="allowedHosts()"
            (input)="allowedHosts.set($any($event.target).value)"
          />
          <p class="field__hint">
            Comma-separated. Supports a leading wildcard. Empty means the plugin cannot make any
            outbound call.
          </p>
        </div>

        <div class="field">
          <label class="field__label" for="scopes">Secret scopes</label>
          <input
            id="scopes"
            type="text"
            placeholder="sendgrid., slack."
            [value]="secretScopes()"
            (input)="secretScopes.set($any($event.target).value)"
          />
          <p class="field__hint">
            Secret name prefixes this plugin may read. Empty means it can read no credentials at all.
          </p>
        </div>
      </div>

      <label class="checkbox-row">
        <input
          type="checkbox"
          [checked]="activate()"
          (change)="activate.set($any($event.target).checked)"
        />
        <span>Load and activate immediately</span>
      </label>
      <p class="field__hint">
        Leave off to install without loading, then activate when you are ready.
      </p>

      <label class="checkbox-row" style="margin-top: var(--space-3)">
        <input
          type="checkbox"
          [checked]="eventsEnabled()"
          (change)="eventsEnabled.set($any($event.target).checked)"
        />
        <span>May publish business events</span>
      </label>
      <p class="field__hint">
        Events can start other workflows, so this is worth withholding from a plugin that does not need
        it.
      </p>

      <details class="advanced">
        <summary class="small">Advanced</summary>
        <div class="field">
          <label class="field__label" for="checksum">Expected SHA-256</label>
          <input
            id="checksum"
            type="text"
            class="mono"
            placeholder="verify the bytes match what you built"
            [value]="expectedSha256()"
            (input)="expectedSha256.set($any($event.target).value)"
          />
          <p class="field__hint">
            When supplied, the engine rejects the upload unless the received bytes hash to this value.
          </p>
        </div>
        <div class="field">
          <label class="field__label" for="main-class">Main class</label>
          <input
            id="main-class"
            type="text"
            class="mono"
            placeholder="only needed if the JAR declares none"
            [value]="mainClass()"
            (input)="mainClass.set($any($event.target).value)"
          />
        </div>
        <div class="field">
          <label class="field__label" for="description">Description</label>
          <input
            id="description"
            type="text"
            [value]="description()"
            (input)="description.set($any($event.target).value)"
          />
        </div>
      </details>

      <div class="notice notice--warning">
        <strong>A plugin runs with the engine's privileges.</strong>
        Class loader isolation separates dependencies, not permissions: loaded code can open sockets,
        read files the process can read and start threads regardless of the grants above. Install only
        plugins you trust and have reviewed. For third-party or tenant-supplied code, run the engine's
        plugins in a separate container.
      </div>

      @if (progress() !== null) {
        <div class="progress" role="progressbar" [attr.aria-valuenow]="progress()">
          <div class="progress__bar" [style.width.%]="progress()"></div>
        </div>
        <p class="small muted">Uploading… {{ progress() }}%</p>
      }

      <div modalFooter>
        <button class="btn" type="button" [disabled]="busy()" (click)="cancelled.emit()">
          Cancel
        </button>
        <button class="btn btn--primary" type="button" [disabled]="!canSubmit()" (click)="upload()">
          {{ busy() ? 'Installing…' : 'Install' }}
        </button>
      </div>
    </wf-modal>
  `,
  styles: [
    `
      .advanced {
        margin: var(--space-4) 0;
      }

      .advanced summary {
        cursor: pointer;
        color: var(--text-muted);
        margin-bottom: var(--space-3);
      }

      .progress {
        height: 6px;
        border-radius: 3px;
        background: var(--hl-grey-200);
        overflow: hidden;
        margin-top: var(--space-3);
      }

      .progress__bar {
        height: 100%;
        background: var(--hl-green);
        transition: width 0.15s linear;
      }
    `,
  ],
})
export class PluginUpload {
  readonly installed = output<void>();
  readonly cancelled = output<void>();

  private readonly api = inject(PluginApiService);
  private readonly notifications = inject(NotificationService);

  protected readonly file = signal<File | null>(null);
  protected readonly allowedHosts = signal('');
  protected readonly secretScopes = signal('');
  protected readonly expectedSha256 = signal('');
  protected readonly mainClass = signal('');
  protected readonly description = signal('');
  protected readonly activate = signal(true);
  protected readonly eventsEnabled = signal(true);
  protected readonly busy = signal(false);
  protected readonly progress = signal<number | null>(null);

  protected readonly canSubmit = computed(() => !!this.file() && !this.busy());

  protected onFile(event: Event): void {
    const input = event.target as HTMLInputElement;
    this.file.set(input.files?.[0] ?? null);
  }

  protected upload(): void {
    const chosen = this.file();
    if (!chosen) {
      return;
    }
    this.busy.set(true);
    this.progress.set(0);

    this.api
      .upload({
        file: chosen,
        allowedHosts: splitList(this.allowedHosts()),
        secretScopes: splitList(this.secretScopes()),
        expectedSha256: this.expectedSha256() || undefined,
        mainClass: this.mainClass() || undefined,
        description: this.description() || undefined,
        activate: this.activate(),
        eventsEnabled: this.eventsEnabled(),
      })
      .subscribe({
        next: (event) => {
          if (event.type === HttpEventType.UploadProgress && event.total) {
            this.progress.set(Math.round((event.loaded / event.total) * 100));
          }
          if (event.type === HttpEventType.Response) {
            this.busy.set(false);
            this.progress.set(null);
            const version = event.body;
            this.notifications.success(
              `Installed ${version?.pluginId} ${version?.version}`,
              version?.nodeTypes?.length
                ? `Node types now available: ${version.nodeTypes.join(', ')}`
                : 'The plugin contributes no node types.',
            );
            this.installed.emit();
          }
        },
        error: () => {
          // The interceptor reports the reason, including the engine's full rejection list. The dialog
          // stays open so the operator can correct a field and retry without re-picking the file.
          this.busy.set(false);
          this.progress.set(null);
        },
      });
  }
}

function splitList(value: string): string[] {
  return value
    .split(',')
    .map((entry) => entry.trim())
    .filter((entry) => entry.length > 0);
}
