import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';

/**
 * One status, one colour, everywhere.
 *
 * Execution, workflow and plugin statuses all render through this component so that a colour means
 * the same thing on the list, the timeline, the canvas and the plugin table. The mapping lives here
 * rather than in each screen, which is the only way it stays consistent as screens are added.
 */
@Component({
  selector: 'wf-status-pill',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <span class="pill" [style.--pill-color]="color()" [attr.title]="title() || status()">
      <span class="pill__dot" [class.pill__dot--pulse]="pulsing()"></span>
      {{ label() }}
    </span>
  `,
  styles: [
    `
      .pill {
        display: inline-flex;
        align-items: center;
        gap: 6px;
        padding: 2px 9px 2px 7px;
        border-radius: 11px;
        font-size: var(--text-xs);
        font-weight: bold;
        letter-spacing: 0.3px;
        white-space: nowrap;
        color: var(--pill-color);
        background: color-mix(in srgb, var(--pill-color) 12%, transparent);
        border: 1px solid color-mix(in srgb, var(--pill-color) 35%, transparent);
      }

      .pill__dot {
        width: 7px;
        height: 7px;
        border-radius: 50%;
        background: var(--pill-color);
        flex: none;
      }

      /* Only in-flight statuses animate. A pulsing dot should mean "something is happening now". */
      .pill__dot--pulse {
        animation: pulse 1.4s ease-in-out infinite;
      }

      @keyframes pulse {
        0%,
        100% {
          opacity: 1;
        }
        50% {
          opacity: 0.3;
        }
      }

      @media (prefers-reduced-motion: reduce) {
        .pill__dot--pulse {
          animation: none;
        }
      }
    `,
  ],
})
export class StatusPill {
  readonly status = input.required<string | null | undefined>();
  readonly title = input<string | null>(null);

  readonly label = computed(() => (this.status() ?? 'UNKNOWN').replace(/_/g, ' '));

  readonly pulsing = computed(() => {
    const value = (this.status() ?? '').toUpperCase();
    // A pulsing dot means "something is happening now", so the transient install states qualify and the
    // resting ones do not.
    return (
      value === 'RUNNING' ||
      value === 'PENDING' ||
      value === 'DOWNLOADING' ||
      value === 'VALIDATING' ||
      value === 'STOPPING'
    );
  });

  readonly color = computed(() => {
    switch ((this.status() ?? '').toUpperCase()) {
      // Executions
      case 'PENDING':
        return 'var(--status-pending)';
      case 'RUNNING':
        return 'var(--status-running)';
      case 'WAITING':
        return 'var(--status-waiting)';
      case 'PAUSED':
        return 'var(--status-paused)';
      case 'COMPLETED':
      case 'SUCCESS':
        return 'var(--status-completed)';
      case 'FAILED':
        return 'var(--status-failed)';
      case 'CANCELLED':
        return 'var(--status-cancelled)';
      case 'TERMINATED':
        // A deliberate, permanent end. Shares the failed/danger hue because it is a hard stop, but reads as
        // its own word in the pill.
        return 'var(--status-failed)';
      case 'SKIPPED':
        return 'var(--status-skipped)';
      // Workflows
      case 'DRAFT':
        return 'var(--hl-grey-600)';
      case 'PUBLISHED':
        return 'var(--hl-green)';
      case 'ARCHIVED':
        return 'var(--hl-grey-500)';
      // Plugins
      case 'ACTIVE':
        return 'var(--hl-green)';
      case 'INSTALLED':
        return 'var(--hl-info)';
      case 'INACTIVE':
        return 'var(--hl-grey-600)';
      case 'DELETED':
        return 'var(--hl-grey-500)';
      // Marketplace. These are the engine's comparison of the registry against what is installed, and the
      // colours follow how much attention each deserves: a revoked plugin is the only one that says
      // something already running may be harmful, so it takes the failure colour rather than a warning.
      case 'REVOKED':
        return 'var(--hl-error)';
      case 'INCOMPATIBLE':
        return 'var(--hl-error)';
      case 'UPDATE_AVAILABLE':
        return 'var(--hl-orange-alt)';
      case 'DEPRECATED':
        return 'var(--hl-orange-alt)';
      case 'NOT_INSTALLED':
        return 'var(--hl-grey-600)';
      case 'UNKNOWN_TO_REGISTRY':
        return 'var(--hl-grey-500)';
      // Install states, which sit beside a marketplace status on the detail screen.
      case 'DOWNLOADING':
      case 'VALIDATING':
      case 'STOPPING':
        return 'var(--status-running)';
      case 'DISABLED':
        return 'var(--hl-grey-600)';
      case 'INSTALL_FAILED':
        return 'var(--hl-error)';
      // Human tasks. OPEN borrows the waiting colour because that is what it means: something is parked
      // pending a person. ASSIGNED borrows running, because somebody is now working on it.
      case 'OPEN':
        return 'var(--status-waiting)';
      case 'ASSIGNED':
        return 'var(--status-running)';
      case 'EXPIRED':
        return 'var(--status-failed)';
      default:
        return 'var(--hl-grey-600)';
    }
  });
}
