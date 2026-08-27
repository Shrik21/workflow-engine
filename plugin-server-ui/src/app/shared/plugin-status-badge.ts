import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';
import { PluginStatus } from '../core/models/plugin.model';

/**
 * One status, one colour and one shape, everywhere.
 *
 * <h2>Never colour alone</h2>
 *
 * Each badge carries a glyph and the word as well as the colour. A red dot and an amber dot are the same dot
 * to a colour-blind reader, and status is the single most decision-bearing thing on these screens: it decides
 * whether an operator publishes, deprecates or leaves a version alone.
 */
@Component({
  selector: 'ps-plugin-status-badge',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <span class="badge" [style.--badge-color]="colour()" [title]="explanation()">
      <span class="badge__glyph" aria-hidden="true">{{ glyph() }}</span>
      <span class="badge__text">{{ label() }}</span>
      <span class="sr-only">. {{ explanation() }}</span>
    </span>
  `,
  styles: [
    `
      .badge {
        display: inline-flex;
        align-items: center;
        gap: 5px;
        padding: 2px 9px;
        border-radius: 11px;
        font-size: var(--text-xs);
        font-weight: bold;
        letter-spacing: 0.3px;
        white-space: nowrap;
        color: var(--badge-color);
        background: color-mix(in srgb, var(--badge-color) 12%, transparent);
        border: 1px solid color-mix(in srgb, var(--badge-color) 35%, transparent);
      }

      .badge__glyph {
        font-size: 11px;
        line-height: 1;
      }
    `,
  ],
})
export class PluginStatusBadge {
  readonly status = input.required<PluginStatus | string | null | undefined>();

  private readonly normalised = computed(() =>
    String(this.status() ?? 'UNKNOWN').toUpperCase(),
  );

  protected readonly label = computed(() => this.normalised().replace(/_/g, ' '));

  protected readonly colour = computed(() => {
    switch (this.normalised()) {
      case 'ACTIVE':
        return 'var(--hl-green)';
      case 'DRAFT':
        return 'var(--hl-info)';
      case 'INACTIVE':
        return 'var(--hl-grey-600)';
      case 'DEPRECATED':
        return 'var(--hl-orange-alt)';
      case 'REVOKED':
        return 'var(--hl-error)';
      default:
        return 'var(--hl-grey-600)';
    }
  });

  /** A shape as well as a colour, so the status survives being printed or read without colour. */
  protected readonly glyph = computed(() => {
    switch (this.normalised()) {
      case 'ACTIVE':
        return '●';
      case 'DRAFT':
        return '○';
      case 'INACTIVE':
        return '◌';
      case 'DEPRECATED':
        return '▲';
      case 'REVOKED':
        return '✕';
      default:
        return '?';
    }
  });

  protected readonly explanation = computed(() => {
    switch (this.normalised()) {
      case 'ACTIVE':
        return 'Published and installable by workflow services.';
      case 'DRAFT':
        return 'Uploaded but not published, so no workflow service can see it yet.';
      case 'INACTIVE':
        return 'Withdrawn from the catalogue. Not offered for new installations.';
      case 'DEPRECATED':
        return 'Superseded. Still downloads, so workflows pinned to it keep running.';
      case 'REVOKED':
        return 'Withdrawn as unsafe. Downloads are refused.';
      default:
        return 'Status unknown to this console.';
    }
  });
}
