import { ChangeDetectionStrategy, Component, input } from '@angular/core';

/**
 * Consistent page title row used by list and settings screens.
 *
 * Project actions into the default slot (or mark them with the {@code pageActions} attribute). Does not
 * own routing or permissions — callers keep their existing toolbar buttons and handlers.
 */
@Component({
  selector: 'wf-page-header',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <header class="page-header">
      <div class="page-header__text">
        <h1>{{ title() }}</h1>
        @if (description()) {
          <p>{{ description() }}</p>
        }
      </div>
      <div class="page-header__actions toolbar">
        <ng-content select="[pageActions]" />
        <ng-content />
      </div>
    </header>
  `,
})
export class PageHeader {
  readonly title = input.required<string>();
  readonly description = input<string | null>(null);
}
