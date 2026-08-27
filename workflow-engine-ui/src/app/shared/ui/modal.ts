import {
  AfterViewInit,
  ChangeDetectionStrategy,
  Component,
  ElementRef,
  OnDestroy,
  inject,
  input,
  output,
} from '@angular/core';

/**
 * A focused dialog.
 *
 * Closes on Escape and on backdrop click, because a dialog that traps the operator is worse than one
 * that occasionally closes early. Destructive dialogs pass `dismissable=false` so a stray click cannot
 * dismiss a confirmation the operator is still reading.
 *
 * Focus moves into the dialog on open and returns to the previously focused element on close. Tab cycles
 * inside the dialog while it is open.
 */
@Component({
  selector: 'wf-modal',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div
      class="backdrop"
      (click)="onBackdrop()"
      role="presentation"
    >
      <div
        class="dialog"
        #dialog
        [style.--dialog-width]="width()"
        (click)="$event.stopPropagation()"
        (keydown)="onKeydown($event)"
        role="dialog"
        aria-modal="true"
        [attr.aria-labelledby]="titleId"
      >
        <header class="dialog__header">
          <h3 [id]="titleId">{{ heading() }}</h3>
          <button
            class="btn btn--quiet btn--sm"
            type="button"
            aria-label="Close dialog"
            (click)="closed.emit()"
          >
            Close
          </button>
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
        z-index: var(--z-modal);
        overflow-y: auto;
      }

      .dialog {
        width: var(--dialog-width, 560px);
        max-width: 100%;
        max-height: calc(100vh - 2 * var(--space-6));
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
        flex: none;
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
        overflow: auto;
        min-height: 0;
      }

      .dialog__footer {
        display: flex;
        justify-content: flex-end;
        gap: var(--space-2);
        padding: var(--space-3) var(--space-4);
        border-top: 1px solid var(--border);
        background: var(--surface-sunken);
        border-radius: 0 0 var(--radius-lg) var(--radius-lg);
        flex: none;
      }

      .dialog__footer:empty {
        display: none;
      }
    `,
  ],
})
export class Modal implements AfterViewInit, OnDestroy {
  private static nextId = 0;

  private readonly host = inject(ElementRef<HTMLElement>);
  private previouslyFocused: HTMLElement | null = null;
  protected readonly titleId = `wf-modal-title-${Modal.nextId++}`;

  readonly heading = input.required<string>();
  readonly subheading = input<string | null>(null);
  readonly width = input<string>('560px');
  readonly dismissable = input<boolean>(true);

  readonly closed = output<void>();

  ngAfterViewInit(): void {
    const active = document.activeElement;
    this.previouslyFocused = active instanceof HTMLElement ? active : null;
    // Defer so projected footer buttons exist before we choose a focus target.
    queueMicrotask(() => this.focusInitial());
  }

  ngOnDestroy(): void {
    const restore = this.previouslyFocused;
    if (restore && document.contains(restore)) {
      restore.focus();
    }
  }

  onBackdrop(): void {
    if (this.dismissable()) {
      this.closed.emit();
    }
  }

  onKeydown(event: KeyboardEvent): void {
    if (event.key === 'Escape') {
      if (this.dismissable()) {
        event.preventDefault();
        event.stopPropagation();
        this.closed.emit();
      }
      return;
    }

    if (event.key !== 'Tab') {
      return;
    }

    const focusable = this.focusableElements();
    if (focusable.length === 0) {
      event.preventDefault();
      return;
    }

    const first = focusable[0];
    const last = focusable[focusable.length - 1];
    const current = document.activeElement;

    if (event.shiftKey) {
      if (current === first || !this.host.nativeElement.contains(current)) {
        event.preventDefault();
        last.focus();
      }
    } else if (current === last) {
      event.preventDefault();
      first.focus();
    }
  }

  private focusInitial(): void {
    const focusable = this.focusableElements();
    const preferred =
      focusable.find((el) => el.classList.contains('btn--primary') || el.classList.contains('btn--danger')) ??
      focusable[0];
    preferred?.focus();
  }

  private focusableElements(): HTMLElement[] {
    const root = this.host.nativeElement as HTMLElement;
    const nodes = Array.from(
      root.querySelectorAll(
        'button:not([disabled]), [href], input:not([disabled]), select:not([disabled]), textarea:not([disabled]), [tabindex]:not([tabindex="-1"])',
      ),
    ) as HTMLElement[];
    return nodes.filter((el) => !el.hasAttribute('disabled') && el.offsetParent !== null);
  }
}
