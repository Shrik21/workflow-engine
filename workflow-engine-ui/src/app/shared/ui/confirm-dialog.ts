import { ChangeDetectionStrategy, Component, input, output } from '@angular/core';
import { Modal } from './modal';

/**
 * A pending confirmation: the wording to show and the action to run if the operator confirms. A page keeps one
 * of these in a signal and hands it to a {@link ConfirmDialog}, so every native `confirm()` becomes "set the
 * request, run its callback on confirm".
 */
export interface ConfirmRequest {
  heading: string;
  message: string;
  confirmLabel: string;
  danger: boolean;
  onConfirm: () => void;
}

/**
 * A themed confirmation dialog, the styled replacement for the browser's `confirm()`.
 *
 * <p>`confirm()` cannot be themed, blocks the event loop, and reads as a system error rather than part of the
 * application. This wraps {@link Modal} with a message and a Cancel / Confirm pair, and emits `confirmed` or
 * `cancelled` so a page turns a native confirm into `(confirmed)="…"` with no bespoke modal of its own. The
 * message preserves line breaks, so the multi-line wording the old confirms used carries over unchanged.
 */
@Component({
  selector: 'wf-confirm-dialog',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [Modal],
  template: `
    <wf-modal
      [heading]="heading()"
      width="460px"
      [dismissable]="!busy()"
      (closed)="cancelled.emit()"
    >
      <p class="confirm-message">{{ message() }}</p>
      <div modalFooter style="display: flex; gap: var(--space-3); justify-content: flex-end">
        <button class="btn" type="button" [disabled]="busy()" (click)="cancelled.emit()">
          {{ cancelLabel() }}
        </button>
        <button
          class="btn"
          type="button"
          [class.btn--danger]="danger()"
          [class.btn--primary]="!danger()"
          [disabled]="busy()"
          (click)="confirmed.emit()"
        >
          {{ confirmLabel() }}
        </button>
      </div>
    </wf-modal>
  `,
  styles: [
    `
      .confirm-message {
        margin: 0;
        white-space: pre-line;
      }
    `,
  ],
})
export class ConfirmDialog {
  readonly heading = input.required<string>();
  readonly message = input<string>('');
  readonly confirmLabel = input<string>('Confirm');
  readonly cancelLabel = input<string>('Cancel');
  /** Styles the confirm button as destructive (red) rather than primary. */
  readonly danger = input<boolean>(false);
  /** Disables the buttons while the confirmed action is in flight. */
  readonly busy = input<boolean>(false);

  readonly confirmed = output<void>();
  readonly cancelled = output<void>();
}
