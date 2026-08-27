import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { NotificationService } from '../../core/notification.service';

/**
 * Renders the notification stack.
 *
 * Detail lines are rendered as a list because the engine's validation failures arrive that way. An
 * operator publishing a broken workflow gets every problem at once here, which is the difference
 * between one round trip and a dozen.
 */
@Component({
  selector: 'wf-toast-host',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="toasts" role="log" aria-live="polite">
      @for (toast of notifications.notifications(); track toast.id) {
        <div class="toast" [class]="'toast--' + toast.kind">
          <div class="toast__body">
            <strong>{{ toast.title }}</strong>
            @if (toast.details.length > 0) {
              <ul>
                @for (line of toast.details; track line) {
                  <li>{{ line }}</li>
                }
              </ul>
            }
          </div>
          <button
            class="toast__close"
            type="button"
            aria-label="Dismiss"
            (click)="notifications.dismiss(toast.id)"
          >
            &times;
          </button>
        </div>
      }
    </div>
  `,
  styles: [
    `
      .toasts {
        position: fixed;
        right: var(--space-4);
        bottom: var(--space-4);
        z-index: var(--z-toast);
        display: flex;
        flex-direction: column;
        gap: var(--space-2);
        width: 420px;
        max-width: calc(100vw - var(--space-6));
        pointer-events: none;
      }

      .toast {
        pointer-events: auto;
        display: flex;
        align-items: flex-start;
        gap: var(--space-2);
        padding: var(--space-3);
        border-radius: var(--radius);
        border-left: 3px solid var(--toast-color);
        background: var(--surface);
        box-shadow: var(--shadow);
        font-size: var(--text-base);
      }

      .toast--success {
        --toast-color: var(--hl-success);
      }
      .toast--error {
        --toast-color: var(--hl-error);
      }
      .toast--warning {
        --toast-color: var(--hl-warning);
      }
      .toast--info {
        --toast-color: var(--hl-info);
      }

      .toast__body {
        flex: 1;
        min-width: 0;
      }

      strong {
        display: block;
        color: var(--hl-grey-900);
      }

      ul {
        margin: var(--space-2) 0 0;
        padding-left: 18px;
        color: var(--text-muted);
        font-size: var(--text-sm);
        max-height: 220px;
        overflow-y: auto;
      }

      li {
        margin-bottom: 2px;
        word-break: break-word;
      }

      .toast__close {
        border: none;
        background: transparent;
        cursor: pointer;
        font-size: 18px;
        line-height: 1;
        color: var(--text-faint);
        padding: 0 2px;

        &:hover {
          color: var(--text);
        }
      }
    `,
  ],
})
export class ToastHost {
  protected readonly notifications = inject(NotificationService);
}
