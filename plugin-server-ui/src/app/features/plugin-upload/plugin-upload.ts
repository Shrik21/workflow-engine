import { ChangeDetectionStrategy, Component, computed, inject, input, signal } from '@angular/core';
import { Router, RouterLink } from '@angular/router';
import { HttpErrorResponse } from '@angular/common/http';
import { JarManifestReader } from '../../core/jar-manifest.reader';
import { PluginApiService } from '../../core/plugin-api.service';
import { NotificationService } from '../../core/notification.service';
import { PluginManifest } from '../../core/models/manifest.model';
import { PluginUploadResult, PluginVersionSummary } from '../../core/models/plugin.model';
import { environment } from '../../../environments/environment';
import { BytesPipe } from '../../shared/format.pipes';
import { PluginStatusBadge } from '../../shared/plugin-status-badge';
import { PluginUploadZone } from '../../shared/plugin-upload-zone';

type Step = 1 | 2 | 3;

/** What reading the archive and consulting the registry concluded about this upload. */
interface Review {
  manifest: PluginManifest | null;
  /** Why no manifest could be shown. Null when one was read. */
  previewProblem: string | null;
  /** Versions of this plugin the registry already holds. Empty for a plugin it has never seen. */
  existingVersions: PluginVersionSummary[];
  /** True when this exact version is already registered, which the registry will refuse. */
  duplicate: boolean;
}

/**
 * The upload wizard: choose, review, upload.
 *
 * <h2>Why three steps rather than a file field and a button</h2>
 *
 * An archive is opaque. Its filename is a claim, not evidence, and the thing being published is executable
 * code that every workflow engine reading this registry will download and run. The middle step exists so the
 * operator confirms what the archive says it is — plugin, version, nodes, SDK — before it becomes visible to
 * anything. It also catches the two mistakes that cost the most: uploading a version that already exists, and
 * uploading the wrong plugin onto a plugin's page.
 *
 * <h2>The review is a preview, not a verdict</h2>
 *
 * The manifest is read in the browser because the registry has no endpoint that inspects an archive without
 * storing it. That reading is advisory and the screen says so: the registry parses the same file when it
 * arrives, and its answer is the one that decides. A browser that cannot decompress, or an archive this
 * console cannot open, does not block the upload — it proceeds without a preview and lets the registry rule.
 */
@Component({
  selector: 'ps-plugin-upload',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [RouterLink, PluginUploadZone, PluginStatusBadge, BytesPipe],
  template: `
    <div class="page">
      <header class="page-header">
        <div class="page-header__text">
          <h1>{{ pluginId() ? 'Upload a new version' : 'Upload a plugin' }}</h1>
          <p>
            @if (pluginId()) {
              Adding a version to <strong class="mono">{{ pluginId() }}</strong
              >. The archive's own manifest decides which plugin and version it is.
            } @else {
              Publishing an archive here makes it available to every workflow engine that reads this
              registry. The registry stores and serves it; it never loads or runs it.
            }
          </p>
        </div>
        <div class="toolbar">
          <a class="btn btn--sm" routerLink="/plugins">Cancel</a>
        </div>
      </header>

      <ol class="steps" aria-label="Upload progress">
        @for (label of stepLabels; track label; let index = $index) {
          <li
            class="steps__item"
            [class.steps__item--current]="step() === index + 1"
            [class.steps__item--done]="step() > index + 1"
            [attr.aria-current]="step() === index + 1 ? 'step' : null"
          >
            <span class="steps__number">{{ step() > index + 1 ? '✓' : index + 1 }}</span>
            <span>{{ label }}</span>
          </li>
        }
      </ol>

      @switch (step()) {
        @case (1) {
          <section class="card card--pad">
            <h2>Select plugin JAR</h2>
            <ps-plugin-upload-zone [maxBytes]="maxBytes" (chosen)="onChosen($event)" />

            @if (file(); as chosen) {
              <div class="chosen">
                <div>
                  <strong class="mono">{{ chosen.name }}</strong>
                  <div class="small muted">
                    {{ chosen.size | bytes }} · {{ chosen.type || 'application/java-archive' }} · modified
                    {{ modifiedAt(chosen) }}
                  </div>
                </div>
                <button class="btn btn--quiet btn--sm" type="button" (click)="reset()">Remove</button>
              </div>
            }

            <div class="actions">
              <button
                class="btn btn--primary"
                type="button"
                [disabled]="!file() || inspecting()"
                (click)="toReview()"
              >
                {{ inspecting() ? 'Reading archive…' : 'Continue' }}
              </button>
            </div>
          </section>
        }

        @case (2) {
          <section class="card card--pad">
            <h2>Review plugin</h2>

            @if (review(); as reviewed) {
              @if (reviewed.previewProblem) {
                <div class="notice notice--warning">
                  {{ reviewed.previewProblem }}
                  <div class="small">
                    You can still upload. The registry inspects the archive itself and will refuse it if it
                    is not a valid plugin.
                  </div>
                </div>
              }

              @if (reviewed.manifest; as manifest) {
                <div class="grid-2 facts">
                  <div><span class="fact__label">Name</span>{{ manifest.name || manifest.pluginId }}</div>
                  <div><span class="fact__label">Plugin ID</span><span class="mono">{{ manifest.pluginId }}</span></div>
                  <div><span class="fact__label">Version</span><span class="mono">{{ manifest.version }}</span></div>
                  <div><span class="fact__label">Vendor</span>{{ manifest.vendor || '—' }}</div>
                  <div><span class="fact__label">Plugin type</span>{{ manifest.pluginType || '—' }}</div>
                  <div><span class="fact__label">Main class</span><span class="mono small">{{ manifest.mainClass || '—' }}</span></div>
                  <div><span class="fact__label">Java</span>{{ manifest.javaVersion || '—' }}</div>
                  <div><span class="fact__label">SDK</span>{{ manifest.sdkVersion || '—' }}</div>
                </div>

                @if (manifest.description) {
                  <p class="description">{{ manifest.description }}</p>
                }

                @if (manifest.nodes.length > 0) {
                  <h3>Nodes it contributes</h3>
                  <div class="nodes">
                    @for (node of manifest.nodes; track node.nodeType) {
                      <div class="node">
                        <strong>{{ node.displayName || node.nodeType }}</strong>
                        <div class="mono small muted">{{ node.nodeType }}</div>
                        @if (node.category) {
                          <span class="tag">{{ node.category }}</span>
                        }
                        @if (node.description) {
                          <p class="small muted">{{ node.description }}</p>
                        }
                      </div>
                    }
                  </div>
                } @else {
                  <p class="small muted">
                    This archive declares no node types. That is valid for a utility or trigger plugin.
                  </p>
                }

                @if (mismatch()) {
                  <div class="notice notice--error" role="alert">
                    This archive is <strong class="mono">{{ manifest.pluginId }}</strong
                    >, but you are uploading a version of <strong class="mono">{{ pluginId() }}</strong
                    >. Uploading it would register a different plugin.
                  </div>
                }

                @if (reviewed.duplicate) {
                  <div class="notice notice--error" role="alert">
                    <strong>Version already exists.</strong>
                    {{ manifest.pluginId }} {{ manifest.version }} is already registered. A published
                    version is immutable, so this upload would be refused. Build a new version and try again.
                  </div>
                } @else if (reviewed.existingVersions.length > 0) {
                  <div class="notice">
                    <strong>Existing plugin.</strong>
                    {{ manifest.pluginId }} is already registered with
                    {{ reviewed.existingVersions.length }} version(s), latest
                    <span class="mono">{{ reviewed.existingVersions[0].version }}</span
                    >. This upload adds <span class="mono">{{ manifest.version }}</span> as a new version of
                    it, not a second plugin.
                  </div>
                }
              }

              <p class="small faint">
                Read from the archive in your browser for this preview. The registry validates it
                independently on upload and its verdict is the one that counts.
              </p>
            }

            <div class="actions">
              <button class="btn" type="button" (click)="step.set(1)">Back</button>
              <button
                class="btn btn--primary"
                type="button"
                [disabled]="blocked()"
                (click)="step.set(3)"
              >
                Continue to upload
              </button>
            </div>
          </section>
        }

        @case (3) {
          <section class="card card--pad">
            <h2>Upload</h2>

            @if (result(); as uploaded) {
              <div class="notice notice--success" role="status">
                <strong>Uploaded successfully.</strong>
                {{ uploaded.pluginId }} {{ uploaded.version }} is now registered.
              </div>
              <div class="grid-2 facts">
                <div><span class="fact__label">Plugin ID</span><span class="mono">{{ uploaded.pluginId }}</span></div>
                <div><span class="fact__label">Version</span><span class="mono">{{ uploaded.version }}</span></div>
                <div>
                  <span class="fact__label">Status</span>
                  <ps-plugin-status-badge [status]="uploaded.status" />
                </div>
                <div><span class="fact__label">Checksum</span><span class="mono small">{{ uploaded.checksum }}</span></div>
              </div>
              @if (uploaded.nextStep) {
                <div class="notice notice--warning">{{ uploaded.nextStep }}</div>
              }
              <div class="actions">
                <a class="btn btn--primary" [routerLink]="['/plugins', uploaded.pluginId]">View plugin</a>
                <button class="btn" type="button" (click)="reset()">Upload another</button>
              </div>
            } @else if (failure(); as problem) {
              <div class="notice notice--error" role="alert">
                <strong>The upload was refused.</strong>
                {{ problem }}
              </div>
              <div class="actions">
                <button class="btn" type="button" (click)="reset()">Choose another file</button>
                <button class="btn btn--primary" type="button" (click)="upload()">Try again</button>
              </div>
            } @else {
              <div class="summary">
                <div><span class="fact__label">Plugin</span>{{ review()?.manifest?.pluginId || file()?.name }}</div>
                <div><span class="fact__label">Version</span><span class="mono">{{ review()?.manifest?.version || 'from the archive' }}</span></div>
                <div><span class="fact__label">File</span><span class="mono">{{ file()?.name }}</span></div>
              </div>

              @if (uploading()) {
                <div class="progress" role="status" aria-live="polite">
                  <div
                    class="progress__bar"
                    role="progressbar"
                    [attr.aria-valuenow]="percent()"
                    aria-valuemin="0"
                    aria-valuemax="100"
                    [attr.aria-label]="'Upload ' + percent() + ' per cent complete'"
                  >
                    <span class="progress__fill" [style.width.%]="percent()"></span>
                  </div>
                  <div class="small muted">
                    {{ percent() }}% — do not close this page while the upload is in progress.
                  </div>
                </div>
              } @else {
                <div class="actions">
                  <button class="btn" type="button" (click)="step.set(2)">Back</button>
                  <button class="btn btn--primary" type="button" (click)="upload()">Upload plugin</button>
                </div>
              }
            }
          </section>
        }
      }
    </div>
  `,
  styles: [
    `
      .steps {
        display: flex;
        gap: var(--space-4);
        list-style: none;
        margin: 0 0 var(--space-4);
        padding: 0;
        flex-wrap: wrap;
      }

      .steps__item {
        display: flex;
        align-items: center;
        gap: var(--space-2);
        color: var(--text-muted);
        font-size: var(--text-sm);
      }

      .steps__number {
        display: grid;
        place-items: center;
        width: 24px;
        height: 24px;
        border-radius: 50%;
        border: 1px solid var(--border-strong);
        font-size: var(--text-xs);
      }

      .steps__item--current {
        color: var(--text);
        font-weight: bold;
      }

      .steps__item--current .steps__number {
        border-color: var(--hl-accent-blue-alt);
        background: var(--hl-accent-blue-alt);
        color: #fff;
      }

      .steps__item--done .steps__number {
        border-color: var(--hl-green);
        color: var(--hl-green);
      }

      .card--pad {
        padding: var(--space-4);
      }

      .chosen {
        display: flex;
        align-items: center;
        justify-content: space-between;
        gap: var(--space-3);
        margin-top: var(--space-3);
        padding: var(--space-3);
        border: 1px solid var(--border);
        border-radius: var(--radius);
        background: var(--surface-sunken);
      }

      .actions {
        display: flex;
        gap: var(--space-2);
        margin-top: var(--space-4);
      }

      .facts > div {
        display: flex;
        flex-direction: column;
        gap: 2px;
        padding: var(--space-2) 0;
      }

      .fact__label {
        font-size: var(--text-xs);
        text-transform: uppercase;
        letter-spacing: 0.6px;
        color: var(--text-muted);
      }

      .description {
        color: var(--text-muted);
      }

      .nodes {
        display: grid;
        grid-template-columns: repeat(auto-fill, minmax(230px, 1fr));
        gap: var(--space-3);
      }

      .node {
        border: 1px solid var(--border);
        border-radius: var(--radius);
        padding: var(--space-3);
      }

      .node p {
        margin: var(--space-2) 0 0;
      }

      .summary {
        display: flex;
        gap: var(--space-5);
        flex-wrap: wrap;
        margin-bottom: var(--space-4);
      }

      .summary > div {
        display: flex;
        flex-direction: column;
        gap: 2px;
      }

      .progress__bar {
        height: 12px;
        border-radius: 6px;
        background: var(--hl-grey-200);
        overflow: hidden;
        margin-bottom: var(--space-2);
      }

      .progress__fill {
        display: block;
        height: 100%;
        background: var(--hl-accent-blue-alt);
        transition: width 0.2s linear;
      }

      .notice + .notice,
      .notice + .grid-2,
      .grid-2 + h3 {
        margin-top: var(--space-3);
      }
    `,
  ],
})
export class PluginUpload {
  /** Present when the wizard was opened from a plugin's page, via /plugins/:pluginId/upload-version. */
  readonly pluginId = input<string | undefined>(undefined);

  protected readonly maxBytes = environment.maxJarSizeBytes;
  protected readonly stepLabels = ['Select', 'Review', 'Upload'];

  private readonly api = inject(PluginApiService);
  private readonly reader = inject(JarManifestReader);
  private readonly notifications = inject(NotificationService);
  private readonly router = inject(Router);

  protected readonly step = signal<Step>(1);
  protected readonly file = signal<File | null>(null);
  protected readonly review = signal<Review | null>(null);
  protected readonly inspecting = signal(false);
  protected readonly uploading = signal(false);
  protected readonly percent = signal(0);
  protected readonly result = signal<PluginUploadResult | null>(null);
  protected readonly failure = signal<string | null>(null);

  /** The archive names a different plugin than the page it was opened from. */
  protected readonly mismatch = computed(() => {
    const expected = this.pluginId();
    const actual = this.review()?.manifest?.pluginId;
    return !!expected && !!actual && expected !== actual;
  });

  /** Refuses to go on only where the registry is certain to refuse too. */
  protected readonly blocked = computed(
    () => !this.file() || this.mismatch() || (this.review()?.duplicate ?? false),
  );

  protected onChosen(file: File): void {
    this.file.set(file);
    this.review.set(null);
    this.result.set(null);
    this.failure.set(null);
  }

  protected modifiedAt(file: File): string {
    return new Date(file.lastModified).toLocaleString();
  }

  /**
   * Reads the archive, then asks the registry what it already has for that plugin.
   *
   * The second question is only answerable once the first has produced a plugin id, which is why the
   * duplicate check lives here rather than at selection time.
   */
  protected async toReview(): Promise<void> {
    const file = this.file();
    if (!file) {
      return;
    }
    this.inspecting.set(true);

    const outcome = await this.reader.read(file);
    const manifest = outcome.kind === 'READ' ? outcome.manifest : null;
    const previewProblem =
      outcome.kind === 'READ'
        ? null
        : outcome.kind === 'NO_MANIFEST'
          ? 'This archive declares no META-INF/workflow-plugin.json, so there is nothing to preview. ' +
            'The registry requires that manifest and will refuse the upload without it.'
          : outcome.reason;

    let existingVersions: PluginVersionSummary[] = [];
    if (manifest) {
      existingVersions = await this.existingVersionsOf(manifest.pluginId);
    }

    this.review.set({
      manifest,
      previewProblem,
      existingVersions,
      duplicate: !!manifest && existingVersions.some((row) => row.version === manifest.version),
    });
    this.inspecting.set(false);
    this.step.set(2);
  }

  /** An unknown plugin answers 404, which is an ordinary outcome here rather than a failure. */
  private existingVersionsOf(pluginId: string): Promise<PluginVersionSummary[]> {
    return new Promise((resolve) => {
      this.api.getPluginVersions(pluginId).subscribe({
        next: (versions) => resolve(versions ?? []),
        error: () => resolve([]),
      });
    });
  }

  protected upload(): void {
    const file = this.file();
    if (!file || this.uploading()) {
      return;
    }
    this.uploading.set(true);
    this.failure.set(null);
    this.percent.set(0);

    const target = this.pluginId();
    const call = target ? this.api.uploadVersion(target, file) : this.api.uploadPlugin(file);

    call.subscribe({
      next: (progress) => {
        if (progress.kind === 'PROGRESS') {
          this.percent.set(progress.percent);
          return;
        }
        this.uploading.set(false);
        this.percent.set(100);
        this.result.set(progress.result);
        this.notifications.success(
          `${progress.result.pluginId} ${progress.result.version} uploaded`,
          progress.result.nextStep ?? '',
        );
      },
      error: (error: HttpErrorResponse) => {
        this.uploading.set(false);
        this.failure.set(explain(error));
      },
    });
  }

  protected reset(): void {
    this.file.set(null);
    this.review.set(null);
    this.result.set(null);
    this.failure.set(null);
    this.percent.set(0);
    this.step.set(1);
  }
}

/** The registry writes its refusals to be read, so its own message is preferred wherever it sends one. */
function explain(error: HttpErrorResponse): string {
  const fromServer = typeof error.error?.message === 'string' ? error.error.message : null;
  switch (error.status) {
    case 409:
      return fromServer ?? 'That plugin and version are already registered.';
    case 413:
      return fromServer ?? 'The archive is larger than the registry accepts.';
    case 415:
      return fromServer ?? 'Only Java .jar archives are supported.';
    case 422:
      return fromServer ?? 'The archive is not a valid plugin.';
    case 403:
      return 'You do not have permission to publish to this registry.';
    case 0:
      return 'The registry could not be reached. Nothing was uploaded.';
    default:
      return fromServer ?? `The registry answered ${error.status}.`;
  }
}
