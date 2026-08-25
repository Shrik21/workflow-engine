import { ChangeDetectionStrategy, Component, input, output } from '@angular/core';

/**
 * A focused dialog.
 *
 * Closes on Escape and on backdrop click, because a dialog that traps the operator is worse than one
 * that occasionally closes early. Destructive dialogs pass `dismissable=false` so a stray click cannot
 * dismiss a confirmation the operator is still reading.
 */
@Component({
  selector: 'wf-modal',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div
      class="backdrop"
      (click)="onBackdrop()"
      (keydown.escape)="onEscape()"
      tabindex="-1"
      role="presentation"
    >
      <div
        class="dialog"
        [style.--dialog-width]="width()"
        (click)="$event.stopPropagation()"
        role="dialog"
        aria-modal="true"
        [attr.aria-label]="heading()"
      >
        <header class="dialog__header">
          <h3>{{ heading() }}</h3>
          <button class="btn btn--quiet btn--sm" type="button" (click)="closed.emit()">Close</button>
        </header>
        @if (subheading()) {
          <p class="dialog__subheading">{{ subheading() }}</p>
        }
        <div class="dialog__body">
          <ng-content />
        </div>
        <footer class="dialog__footer">
          <ng-content select="[modalFooter]" />
        </footer>
      </div>
    </div>
  `,
  styles: [
    `
      .backdrop {
        position: fixed;
        inset: 0;
        background: rgba(0, 45, 91, 0.45);
        display: flex;
        align-items: flex-start;
        justify-content: center;
        padding: var(--space-6) var(--space-4);
        z-index: 200;
        overflow-y: auto;
      }

      .dialog {
        width: var(--dialog-width, 560px);
        max-width: 100%;
        background: var(--surface);
        border-radius: var(--radius-lg);
        box-shadow: var(--shadow-lg);
        display: flex;
        flex-direction: column;
      }

      .dialog__header {
        display: flex;
        align-items: center;
        justify-content: space-between;
        gap: var(--space-3);
        padding: var(--space-4) var(--space-4) var(--space-2);
      }

      .dialog__subheading {
        margin: 0;
        padding: 0 var(--space-4) var(--space-2);
        color: var(--text-muted);
        font-size: var(--text-sm);
      }

      .dialog__body {
        padding: var(--space-4);
        border-top: 1px solid var(--border);
      }

      .dialog__footer {
        display: flex;
        justify-content: flex-end;
        gap: var(--space-2);
        padding: var(--space-3) var(--space-4);
        border-top: 1px solid var(--border);
        background: var(--surface-sunken);
        border-radius: 0 0 var(--radius-lg) var(--radius-lg);
      }

      .dialog__footer:empty {
        display: none;
      }
    `,
  ],
})
export class Modal {
  readonly heading = input.required<string>();
  readonly subheading = input<string | null>(null);
  readonly width = input<string>('560px');
  readonly dismissable = input<boolean>(true);

  readonly closed = output<void>();

  onBackdrop(): void {
    if (this.dismissable()) {
      this.closed.emit();
    }
  }

  onEscape(): void {
    if (this.dismissable()) {
      this.closed.emit();
    }
  }
}
