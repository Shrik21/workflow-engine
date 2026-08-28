import { ChangeDetectionStrategy, Component, input } from '@angular/core';

/**
 * Placeholder chrome for in-flight loads.
 *
 * Keeps layout from jumping when a list or panel arrives, without inventing data. Prefer this over
 * replacing an {@link EmptyState} with the word "Loading…".
 */
@Component({
  selector: 'wf-loading-skeleton',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="skel" role="status" [attr.aria-label]="label()" aria-live="polite">
      @if (variant() === 'page') {
        <div class="skeleton skeleton--title"></div>
        <div class="skeleton skeleton--text" style="width: 70%; margin-top: var(--space-3)"></div>
        <div class="skeleton skeleton--block" style="margin-top: var(--space-5)"></div>
        <div class="skeleton skeleton--row" style="margin-top: var(--space-4)"></div>
        <div class="skeleton skeleton--row"></div>
        <div class="skeleton skeleton--row"></div>
      } @else if (variant() === 'table') {
        @for (_ of rows(); track $index) {
          <div class="skeleton skeleton--row"></div>
        }
      } @else {
        <div class="skeleton skeleton--block"></div>
      }
      <span class="sr-only">{{ label() }}</span>
    </div>
  `,
  styles: [
    `
      .skel {
        display: flex;
        flex-direction: column;
        gap: 0;
        padding: var(--space-4);
      }
    `,
  ],
})
export class LoadingSkeleton {
  /** Visual density: full page placeholder, table rows, or a single block. */
  readonly variant = input<'page' | 'table' | 'block'>('page');
  /** How many table rows to draw when variant is table. */
  readonly rows = input<number[]>([1, 2, 3, 4, 5]);
  readonly label = input<string>('Loading');
}
