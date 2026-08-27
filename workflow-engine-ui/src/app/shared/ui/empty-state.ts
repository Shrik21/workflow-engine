import { ChangeDetectionStrategy, Component, input } from '@angular/core';

/**
 * What a screen shows when it has nothing to show.
 *
 * Always states the next action rather than only the absence. An empty plugin list that says
 * "no plugins" leaves the operator guessing; one that says "upload a JAR to add a node type" does
 * not.
 */
@Component({
  selector: 'wf-empty-state',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="empty">
      <h4>{{ heading() }}</h4>
      @if (message()) {
        <p>{{ message() }}</p>
      }
      <div class="empty__actions">
        <ng-content />
      </div>
    </div>
  `,
  styles: [
    `
      .empty {
        padding: var(--space-7) var(--space-5);
        text-align: center;
        color: var(--text-muted);
      }

      h4 {
        color: var(--hl-grey-800);
        margin-bottom: var(--space-2);
      }

      p {
        margin: 0 auto;
        max-width: 52ch;
      }

      .empty__actions {
        margin-top: var(--space-4);
        display: flex;
        gap: var(--space-2);
        justify-content: center;
      }

      .empty__actions:empty {
        display: none;
      }
    `,
  ],
})
export class EmptyState {
  readonly heading = input.required<string>();
  readonly message = input<string | null>(null);
}
