import { HttpEventType } from '@angular/common/http';
import { ChangeDetectionStrategy, Component, computed, inject, input, signal } from '@angular/core';
import { WorkflowFileApiService } from '../../core/api/workflow-file-api.service';
import { NotificationService } from '../../core/notification.service';
import { WorkflowFile } from '../../core/models/storage.models';
import { AgoPipe, BytesPipe } from '../../shared/pipes/format.pipes';
import { ConfirmDialog, ConfirmRequest } from '../../shared/ui/confirm-dialog';
import { EmptyState } from '../../shared/ui/empty-state';
import { Icon } from '../../shared/ui/icon';

/**
 * The files attached to one workflow version.
 *
 * <h2>Designed to be embedded</h2>
 *
 * Takes the workflow and version as inputs rather than reading them from the route, so the same component serves
 * the designer's Files panel and a standalone page without a second implementation. `canEdit` is passed in too:
 * the parent already knows whether this user may edit the workflow, and asking again here would mean two sources
 * of truth for one answer.
 *
 * <h2>Why files are version-scoped in the interface as well as the storage</h2>
 *
 * The list shows only the selected version's files. Publishing v2 leaves v1's files untouched and invisible
 * here, which is the point — an execution still running on v1 reads v1's files, and a list that mixed versions
 * would suggest otherwise.
 */
@Component({
  selector: 'wf-workflow-files',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [Icon, EmptyState, ConfirmDialog, BytesPipe, AgoPipe],
  template: `
    <div class="files">
      <div class="files__header">
        <div>
          <h2>Files</h2>
          <p class="muted">
            Attached to version {{ version() }}. Each version keeps its own files, so publishing a new version
            never overwrites an earlier one's.
          </p>
        </div>
        <div class="toolbar">
          <button class="btn btn--sm" type="button" (click)="load()">
            <wf-icon name="refresh" /><span>Refresh</span>
          </button>
          <button
            class="btn btn--primary btn--sm"
            type="button"
            [disabled]="!canEdit() || uploading()"
            (click)="picker.click()"
          >
            <wf-icon name="add" /><span>{{ uploading() ? 'Uploading…' : 'Upload file' }}</span>
          </button>
        </div>
      </div>

      <!-- Kept out of the layout entirely; the styled button above drives it. -->
      <input
        #picker
        type="file"
        multiple
        hidden
        (change)="onFilesPicked(picker)"
      />

      @if (uploading()) {
        <div class="progress" role="progressbar" [attr.aria-valuenow]="progress()">
          <div class="progress__bar" [style.width.%]="progress()"></div>
          <span class="progress__label">{{ uploadingName() }} — {{ progress() }}%</span>
        </div>
      }

      @if (storageUnconfigured()) {
        <div class="notice notice--warning">
          <strong>File storage has not been configured.</strong>
          An administrator needs to set a storage location in Settings → File Storage before files can be
          uploaded.
        </div>
      }

      @if (files().length === 0 && !storageUnconfigured()) {
        <wf-empty-state
          heading="No files attached"
          message="Upload templates, sample data or any file this workflow version needs. Nodes reference a file by its id, never by a path."
        >
          <button
            class="btn btn--primary"
            type="button"
            [disabled]="!canEdit()"
            (click)="picker.click()"
          >
            Upload file
          </button>
        </wf-empty-state>
      } @else if (files().length > 0) {
        <table class="table">
          <thead>
            <tr>
              <th>File</th>
              <th>Size</th>
              <th>Version</th>
              <th>Uploaded by</th>
              <th>Uploaded</th>
              <th class="table__actions">Action</th>
            </tr>
          </thead>
          <tbody>
            @for (file of files(); track file.fileId) {
              <tr>
                <td>
                  <span class="file-name">{{ file.fileName }}</span>
                  @if (!file.downloadAvailable) {
                    <!-- The database has a reference the storage cannot satisfy. Worth showing in the list
                         rather than only when somebody clicks Download and gets an error. -->
                    <span class="pill pill--bad" title="The reference exists but the file is missing from storage">
                      Missing
                    </span>
                  }
                  <div class="muted checksum" [title]="file.checksum">
                    SHA-256 {{ file.checksum.slice(0, 16) }}…
                  </div>
                </td>
                <td>{{ file.size | bytes }}</td>
                <td>v{{ file.workflowVersion }}</td>
                <td>{{ file.uploadedBy }}</td>
                <td>{{ file.uploadedAt | ago }}</td>
                <td class="table__actions">
                  <button
                    class="btn btn--sm"
                    type="button"
                    [disabled]="!file.downloadAvailable"
                    (click)="download(file)"
                  >
                    <wf-icon name="export" /><span>Download</span>
                  </button>
                  <button
                    class="btn btn--sm btn--danger"
                    type="button"
                    [disabled]="!canEdit()"
                    (click)="confirmDelete(file)"
                  >
                    <wf-icon name="delete" /><span>Delete</span>
                  </button>
                </td>
              </tr>
            }
          </tbody>
        </table>
      }
    </div>

    @if (confirmRequest(); as c) {
      <wf-confirm-dialog
        [heading]="c.heading"
        [message]="c.message"
        [confirmLabel]="c.confirmLabel"
        [danger]="c.danger"
        (confirmed)="runConfirmed()"
        (cancelled)="confirmRequest.set(null)"
      />
    }
  `,
  styles: [
    `
      .files__header {
        display: flex;
        justify-content: space-between;
        align-items: flex-start;
        gap: var(--space-4);
        margin-bottom: var(--space-4);
        flex-wrap: wrap;
      }

      .files__header h2 {
        margin: 0 0 var(--space-1);
      }

      .muted {
        color: var(--text-muted);
        font-size: 0.85rem;
        margin: 0;
      }

      .checksum {
        font-family: var(--font-mono, monospace);
        font-size: 0.75rem;
      }

      .file-name {
        font-weight: 500;
      }

      .pill--bad {
        margin-left: var(--space-2);
        border-radius: 999px;
        padding: 0.1rem 0.55rem;
        font-size: 0.72rem;
        font-weight: 600;
        background: var(--danger-bg, #3a1616);
        color: var(--danger-fg, #f58585);
      }

      .progress {
        position: relative;
        height: 1.6rem;
        border-radius: var(--radius-sm, 4px);
        background: var(--surface-2, #1d2128);
        overflow: hidden;
        margin-bottom: var(--space-3);
      }

      .progress__bar {
        height: 100%;
        background: var(--accent, #4c8dff);
        transition: width 120ms linear;
      }

      .progress__label {
        position: absolute;
        inset: 0;
        display: flex;
        align-items: center;
        justify-content: center;
        font-size: 0.8rem;
      }

      .table__actions {
        display: flex;
        gap: var(--space-2);
        justify-content: flex-end;
      }
    `,
  ],
})
export class WorkflowFiles {
  readonly workflowId = input.required<string>();
  readonly version = input.required<number>();
  /** Supplied by the parent, which has already resolved whether this user may edit the workflow. */
  readonly canEdit = input<boolean>(false);

  private readonly api = inject(WorkflowFileApiService);
  private readonly notify = inject(NotificationService);

  protected readonly files = signal<WorkflowFile[]>([]);
  protected readonly uploading = signal(false);
  protected readonly uploadingName = signal('');
  protected readonly progress = signal(0);
  protected readonly confirmRequest = signal<ConfirmRequest | null>(null);
  private readonly lastErrorCode = signal<string | null>(null);

  protected readonly storageUnconfigured = computed(
    () => this.lastErrorCode() === 'FILE_STORAGE_NOT_CONFIGURED',
  );

  constructor() {
    // The inputs are required, so the first read happens once Angular has set them.
    queueMicrotask(() => this.load());
  }

  protected load(): void {
    this.api.list(this.workflowId(), this.version()).subscribe({
      next: (files) => {
        this.files.set(files);
        this.lastErrorCode.set(null);
      },
      error: (error) => {
        this.lastErrorCode.set(error?.error?.code ?? null);
        if (!this.storageUnconfigured()) {
          this.notify.error('Could not load files', error?.error?.message ?? '');
        }
      },
    });
  }

  protected onFilesPicked(input: HTMLInputElement): void {
    const chosen = Array.from(input.files ?? []);
    // Reset immediately so picking the same file twice in a row still fires a change event.
    input.value = '';
    if (chosen.length > 0) {
      this.uploadSequentially(chosen, 0);
    }
  }

  /**
   * Uploads one file at a time.
   *
   * Sequential rather than parallel so the progress bar means something and so a batch of large files cannot
   * saturate the connection. The server handles concurrent uploads safely either way — stored names carry a
   * generated id — so this is about the interface, not about correctness.
   */
  private uploadSequentially(queue: File[], index: number): void {
    if (index >= queue.length) {
      this.uploading.set(false);
      this.progress.set(0);
      this.load();
      return;
    }
    const file = queue[index];
    this.uploading.set(true);
    this.uploadingName.set(file.name);
    this.progress.set(0);

    this.api.upload(this.workflowId(), this.version(), file).subscribe({
      next: (event) => {
        if (event.type === HttpEventType.UploadProgress && event.total) {
          this.progress.set(Math.round((100 * event.loaded) / event.total));
        } else if (event.type === HttpEventType.Response) {
          this.notify.success(`Uploaded ${file.name}`);
          this.uploadSequentially(queue, index + 1);
        }
      },
      error: (error) => {
        this.uploading.set(false);
        this.progress.set(0);
        this.lastErrorCode.set(error?.error?.code ?? null);
        this.notify.error(
          `Could not upload ${file.name}`,
          error?.error?.message ?? 'The upload was rejected.',
        );
        // Carry on with the rest: one rejected file should not abandon the others.
        this.uploadSequentially(queue, index + 1);
      },
    });
  }

  protected download(file: WorkflowFile): void {
    this.api.download(this.workflowId(), this.version(), file.fileId).subscribe({
      next: (blob) => this.saveBlob(blob, file.fileName),
      error: (error) =>
        this.notify.error('Could not download', error?.error?.message ?? 'The file could not be read.'),
    });
  }

  /**
   * Hands the blob to the browser as a download.
   *
   * An object URL and a synthetic click, because the endpoint needs the Authorization header the interceptor
   * attaches and a plain link navigation would carry none. The URL is revoked straight away so the blob does not
   * stay pinned in memory for the life of the page.
   */
  private saveBlob(blob: Blob, fileName: string): void {
    const url = URL.createObjectURL(blob);
    const anchor = document.createElement('a');
    anchor.href = url;
    anchor.download = fileName;
    anchor.click();
    URL.revokeObjectURL(url);
  }

  protected confirmDelete(file: WorkflowFile): void {
    this.confirmRequest.set({
      heading: `Delete ${file.fileName}?`,
      message:
        'The file is removed from storage and cannot be recovered. The audit record of it is kept. Other ' +
        'versions of this workflow are not affected.',
      confirmLabel: 'Delete file',
      danger: true,
      onConfirm: () => this.delete(file),
    });
  }

  /** Runs the pending action and clears the dialog, matching the pattern the other screens use. */
  protected runConfirmed(): void {
    const request = this.confirmRequest();
    this.confirmRequest.set(null);
    request?.onConfirm();
  }

  private delete(file: WorkflowFile): void {
    this.api.delete(this.workflowId(), this.version(), file.fileId).subscribe({
      next: () => {
        this.notify.success(`Deleted ${file.fileName}`);
        this.load();
      },
      error: (error) => this.notify.error('Could not delete', error?.error?.message ?? ''),
    });
  }
}
