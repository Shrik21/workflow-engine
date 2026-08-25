import { ChangeDetectionStrategy, Component, input, output, signal } from '@angular/core';
import { BytesPipe } from './format.pipes';

/** Why a chosen file was refused before anything was read from it. */
export interface FileRejection {
  fileName: string;
  reason: string;
}

/**
 * Choosing a plugin archive, by drop or by dialog.
 *
 * <h2>Both routes, always</h2>
 *
 * Drag and drop is the fast path and it is not available to everybody: it needs a pointer, a visible drop
 * target and a file manager beside the browser. The button is not a fallback bolted on for compliance, it is
 * the route that works with a keyboard, on a phone, and inside a remote desktop. The drop area is itself a
 * labelled button for that reason.
 *
 * <h2>What is checked here</h2>
 *
 * Extension, emptiness and size, and nothing else. Those are the three refusals that need no knowledge of the
 * archive's contents and would otherwise cost a full upload to discover — a 60MB round trip ending in 413 is a
 * poor way to learn a limit. Everything about what the archive *contains* is somebody else's job: the wizard's
 * next step reads the manifest, and the registry decides.
 */
@Component({
  selector: 'ps-plugin-upload-zone',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [BytesPipe],
  template: `
    <div
      class="zone"
      [class.zone--over]="dragging()"
      [class.zone--rejected]="!!rejection()"
      role="button"
      tabindex="0"
      [attr.aria-label]="
        'Choose a plugin JAR, or drop one here. Maximum ' + (maxBytes() | bytes) + '.'
      "
      (click)="picker.click()"
      (keydown.enter)="picker.click()"
      (keydown.space)="$event.preventDefault(); picker.click()"
      (dragover)="onDragOver($event)"
      (dragleave)="dragging.set(false)"
      (drop)="onDrop($event)"
    >
      <span class="zone__glyph" aria-hidden="true">
        <svg viewBox="0 0 24 24" width="34" height="34" fill="none" stroke="currentColor" stroke-width="1.6">
          <path d="M12 16V4m0 0L8 8m4-4 4 4" stroke-linecap="round" stroke-linejoin="round" />
          <path d="M4 16v2a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2v-2" stroke-linecap="round" />
        </svg>
      </span>
      <p class="zone__title">Drop your plugin JAR here</p>
      <p class="small muted">or choose a file from your computer</p>
      <span class="btn btn--sm">Choose JAR</span>
      <p class="small faint">Java .jar archives only, up to {{ maxBytes() | bytes }}</p>
    </div>

    <input
      #picker
      class="sr-only"
      type="file"
      accept=".jar,application/java-archive"
      [attr.aria-label]="'Plugin JAR'"
      (change)="onPicked($event)"
    />

    @if (rejection(); as refused) {
      <p class="field__error" role="alert">
        <strong>{{ refused.fileName }}</strong> was not accepted. {{ refused.reason }}
      </p>
    }
  `,
  styles: [
    `
      .zone {
        display: flex;
        flex-direction: column;
        align-items: center;
        gap: var(--space-2);
        padding: var(--space-6) var(--space-4);
        border: 2px dashed var(--border-strong);
        border-radius: var(--radius-lg);
        background: var(--surface);
        cursor: pointer;
        text-align: center;
      }

      .zone:hover,
      .zone:focus-visible {
        border-color: var(--hl-accent-blue-alt);
        background: color-mix(in srgb, var(--hl-accent-blue-alt) 4%, var(--surface));
      }

      .zone:focus-visible {
        outline: none;
        box-shadow: var(--focus-ring);
      }

      .zone--over {
        border-color: var(--hl-accent-blue-alt);
        background: color-mix(in srgb, var(--hl-accent-blue-alt) 8%, var(--surface));
      }

      .zone--rejected {
        border-color: var(--hl-error);
      }

      .zone__glyph {
        color: var(--hl-accent-blue-alt);
      }

      .zone__title {
        margin: 0;
        font-size: var(--text-md);
      }

      .field__error {
        margin-top: var(--space-2);
      }
    `,
  ],
})
export class PluginUploadZone {
  /** The registry's own limit, so this refuses exactly what the server would. */
  readonly maxBytes = input.required<number>();

  readonly chosen = output<File>();
  readonly rejected = output<FileRejection>();

  protected readonly dragging = signal(false);
  protected readonly rejection = signal<FileRejection | null>(null);

  protected onDragOver(event: DragEvent): void {
    event.preventDefault();
    this.dragging.set(true);
    if (event.dataTransfer) {
      event.dataTransfer.dropEffect = 'copy';
    }
  }

  protected onDrop(event: DragEvent): void {
    event.preventDefault();
    this.dragging.set(false);
    const file = event.dataTransfer?.files?.[0];
    if (file) {
      this.accept(file);
    }
  }

  protected onPicked(event: Event): void {
    const input = event.target as HTMLInputElement;
    const file = input.files?.[0];
    if (file) {
      this.accept(file);
    }
    // Cleared so choosing the same file twice in a row still raises a change event, which matters after a
    // rebuild produces a new archive under the same name.
    input.value = '';
  }

  private accept(file: File): void {
    const reason = this.refuse(file);
    if (reason) {
      const rejection = { fileName: file.name, reason };
      this.rejection.set(rejection);
      this.rejected.emit(rejection);
      return;
    }
    this.rejection.set(null);
    this.chosen.emit(file);
  }

  /**
   * @returns why the file cannot be a plugin archive, or null when it might be
   */
  private refuse(file: File): string | null {
    if (!file.name.toLowerCase().endsWith('.jar')) {
      return 'Only Java .jar files are supported.';
    }
    if (file.size === 0) {
      return 'The file is empty.';
    }
    if (file.size > this.maxBytes()) {
      return `It is larger than the registry accepts. The limit is ${format(this.maxBytes())}.`;
    }
    return null;
  }
}

function format(bytes: number): string {
  const megabytes = bytes / (1024 * 1024);
  return `${megabytes % 1 === 0 ? megabytes : megabytes.toFixed(1)} MB`;
}
