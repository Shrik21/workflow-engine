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
    'run' | 'open' | 'runs' | 'delete' | 'refresh' | 'add' | 'export' | 'import'
  >('run');
  readonly size = input<number>(16);
}
