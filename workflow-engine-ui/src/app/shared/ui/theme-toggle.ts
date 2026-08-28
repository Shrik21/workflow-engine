import { ChangeDetectionStrategy, Component, inject, input } from '@angular/core';
import { ThemeService } from '../../core/theme.service';
import { Icon } from './icon';

/**
 * Cycles appearance between system, light and dark.
 *
 * Presentation only: preference is stored in localStorage by {@link ThemeService}.
 */
@Component({
  selector: 'wf-theme-toggle',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [Icon],
  template: `
    <button
      class="theme-toggle"
      [class.theme-toggle--compact]="compact()"
      type="button"
      [attr.aria-label]="theme.nextLabel()"
      [title]="theme.nextLabel()"
      (click)="theme.cycle()"
    >
      <wf-icon [name]="theme.resolved() === 'dark' ? 'sun' : 'moon'" [size]="compact() ? 16 : 15" />
      @if (!compact()) {
        <span class="theme-toggle__label">{{ theme.label() }}</span>
      }
    </button>
  `,
  styles: [
    `
      .theme-toggle {
        display: inline-flex;
        align-items: center;
        justify-content: center;
        gap: var(--space-2);
        border: 1px solid var(--border-strong);
        background: var(--surface);
        color: var(--text);
        border-radius: var(--radius-sm);
        padding: 6px 10px;
        cursor: pointer;
        font-family: var(--font-body);
        font-size: var(--text-sm);
        line-height: 1;
      }

      .theme-toggle:hover {
        background: var(--control-hover);
      }

      .theme-toggle:focus-visible {
        outline: none;
        box-shadow: var(--focus-ring);
      }

      .theme-toggle--compact {
        width: 100%;
        border-color: rgba(255, 255, 255, 0.2);
        background: transparent;
        color: rgba(255, 255, 255, 0.85);
        padding: 7px var(--space-3);
      }

      .theme-toggle--compact:hover {
        background: rgba(255, 255, 255, 0.12);
        color: var(--text-inverse);
      }

      .theme-toggle__label {
        font-weight: 600;
      }
    `,
  ],
})
export class ThemeToggle {
  protected readonly theme = inject(ThemeService);

  /** Sidebar variant: full-width, inverse colours on the navy rail. */
  readonly compact = input(false);
}
