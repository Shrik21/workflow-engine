import { ChangeDetectionStrategy, Component, input } from '@angular/core';

/**
 * A small monochrome UI icon, drawn inline.
 *
 * <h2>Inline SVG rather than an icon font</h2>
 *
 * Font Awesome and its kin are a network dependency: a package to install or a stylesheet to fetch. This
 * engine's UI is served offline and locked down by a strict content policy, so an icon set that has to be
 * downloaded is one that may not arrive — and a button whose glyph silently failed to load is worse than a
 * text button. So the handful of icons the interface actually uses live here as paths, drawn with
 * {@code currentColor} so they take the surrounding button's colour, and the set is deliberately small and
 * local. It is the same choice, for the same reason, that {@code NodeGlyph} makes for node shapes.
 *
 * <p>An icon is decoration; the accessible name comes from the button's own {@code aria-label} or
 * {@code title}, and the SVG is hidden from assistive technology.
 */
@Component({
  selector: 'wf-icon',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <svg
      [attr.width]="size()"
      [attr.height]="size()"
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      stroke-width="1.8"
      stroke-linecap="round"
      stroke-linejoin="round"
      aria-hidden="true"
      focusable="false"
    >
      @switch (name()) {
        @case ('run') {
          <!-- A play triangle, filled so a small run control reads at a glance. -->
          <path d="M7 4.8 18.5 12 7 19.2z" fill="currentColor" stroke="none" />
        }
        @case ('open') {
          <!-- A pencil, for editing the workflow. -->
          <path d="M4 20h4L18.5 9.5a2.12 2.12 0 0 0-3-3L5 17z" />
          <path d="M13.5 6.5l3 3" />
        }
        @case ('runs') {
          <!-- A clock with a rewind arrow, for the execution history. -->
          <path d="M3.5 12a8.5 8.5 0 1 0 2.6-6.1" />
          <path d="M3 4v4h4" />
          <path d="M12 8v4.2l3 1.8" />
        }
        @case ('delete') {
          <!-- A trash can. -->
          <path d="M4 7h16" />
          <path d="M9 7V5a1 1 0 0 1 1-1h4a1 1 0 0 1 1 1v2" />
          <path d="M6.5 7l.8 12.1a1 1 0 0 0 1 .9h7.4a1 1 0 0 0 1-.9L18.5 7" />
          <path d="M10 11v6M14 11v6" />
        }
        @case ('refresh') {
          <path d="M20 11a8 8 0 1 0-1.5 5" />
          <path d="M20 5v6h-6" />
        }
        @case ('add') {
          <path d="M12 5v14M5 12h14" />
        }
        @case ('export') {
          <!-- A document with an up-and-out arrow, for exporting to a file. -->
          <path d="M14 3H7a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2v-9" />
          <path d="M15 3v5h5" />
          <path d="M12 18v-7" />
          <path d="M9 13l3-3 3 3" />
        }
        @case ('import') {
          <!-- A document with a down-and-in arrow, for importing from a file. -->
          <path d="M14 3H7a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2v-9" />
          <path d="M15 3v5h5" />
          <path d="M12 11v7" />
          <path d="M9 15l3 3 3-3" />
        }
        @case ('workflows') {
          <rect x="4" y="4" width="7" height="7" rx="1.2" />
          <rect x="13" y="4" width="7" height="7" rx="1.2" />
          <rect x="4" y="13" width="7" height="7" rx="1.2" />
          <path d="M13 16.5h7M16.5 13v7" />
        }
        @case ('executions') {
          <path d="M4 6h16M4 12h16M4 18h10" />
          <path d="M17 16l3 2-3 2" />
        }
        @case ('tasks') {
          <path d="M9 6h11M9 12h11M9 18h11" />
          <path d="M4.5 6.5l1.2 1.2L8 5.4" />
          <path d="M4.5 12.5l1.2 1.2L8 11.4" />
          <path d="M4.5 18.5l1.2 1.2L8 17.4" />
        }
        @case ('forms') {
          <path d="M7 3h7l4 4v14a1 1 0 0 1-1 1H7a1 1 0 0 1-1-1V4a1 1 0 0 1 1-1z" />
          <path d="M14 3v4h4" />
          <path d="M9 12h6M9 16h6" />
        }
        @case ('nodes') {
          <circle cx="7" cy="7" r="2.5" />
          <circle cx="17" cy="7" r="2.5" />
          <circle cx="12" cy="17" r="2.5" />
          <path d="M9.2 8.2 10.8 14.8M14.8 8.2 13.2 14.8" />
        }
        @case ('plugins') {
          <path d="M9 3v3M15 3v3" />
          <path d="M7 6h10v5a5 5 0 0 1-10 0z" />
          <path d="M9 16v5M15 16v5" />
        }
        @case ('secrets') {
          <rect x="5" y="11" width="14" height="9" rx="2" />
          <path d="M8 11V8a4 4 0 0 1 8 0v3" />
          <circle cx="12" cy="15.5" r="1.2" fill="currentColor" stroke="none" />
        }
        @case ('settings') {
          <circle cx="12" cy="12" r="3" />
          <path
            d="M12 3.5v2.2M12 18.3v2.2M4.9 6.5l1.6 1.6M17.5 15.9l1.6 1.6M3.5 12h2.2M18.3 12h2.2M4.9 17.5l1.6-1.6M17.5 8.1l1.6-1.6"
          />
        }
        @case ('events') {
          <path d="M13 3 5 14h7l-1 7 8-11h-7z" />
        }
        @case ('users') {
          <circle cx="12" cy="8" r="3.2" />
          <path d="M5 19.5c1.4-3.2 3.8-4.8 7-4.8s5.6 1.6 7 4.8" />
        }
        @case ('groups') {
          <circle cx="9" cy="9" r="2.6" />
          <circle cx="16" cy="9.5" r="2.2" />
          <path d="M3.8 18.5c1.1-2.6 3-3.9 5.4-3.9 1.2 0 2.2.3 3.1.9" />
          <path d="M13.2 18.5c.7-1.8 2-2.8 3.7-2.8 1.5 0 2.7.7 3.5 2" />
        }
        @case ('menu') {
          <path d="M4 7h16M4 12h16M4 17h16" />
        }
        @case ('close') {
          <path d="M6 6l12 12M18 6 6 18" />
        }
        @default {
          <circle cx="12" cy="12" r="8" />
        }
      }
    </svg>
  `,
  styles: [
    `
      :host {
        display: inline-flex;
        align-items: center;
        justify-content: center;
        flex: none;
      }
    `,
  ],
})
export class Icon {
  /** Which icon to draw. */
  readonly name = input<
    | 'run'
    | 'open'
    | 'runs'
    | 'delete'
    | 'refresh'
    | 'add'
    | 'export'
    | 'import'
    | 'workflows'
    | 'executions'
    | 'tasks'
    | 'forms'
    | 'nodes'
    | 'plugins'
    | 'secrets'
    | 'settings'
    | 'events'
    | 'users'
    | 'groups'
    | 'menu'
    | 'close'
  >('run');
  readonly size = input<number>(16);
}
