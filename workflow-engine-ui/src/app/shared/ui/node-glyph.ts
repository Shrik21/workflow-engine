import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';

/**
 * Icon hints that map to a brand mark, most specific first.
 *
 * Ordered, not a plain object, because the first match wins and some needles are substrings of others'
 * plugin ids — `orchpilot-gcp-kubernetes` contains both `gcp` and `kubernetes`, and the more specific one
 * has to be checked first or every GKE node would show a Google logo.
 */
const BRANDS: ReadonlyArray<readonly [string, string]> = [
  ['kubernetes', 'brand-kubernetes'],
  ['k8s', 'brand-kubernetes'],
  ['slack', 'brand-slack'],
  ['github', 'brand-github'],
  ['mongodb', 'brand-mongodb'],
  ['mongo', 'brand-mongodb'],
  ['jira', 'brand-jira'],
  ['docker', 'brand-docker'],
  ['registry', 'brand-docker'],
  ['excel', 'brand-excel'],
  ['xlsx', 'brand-excel'],
  ['spreadsheet', 'brand-excel'],
  ['gcp', 'brand-google'],
  ['google', 'brand-google'],
];

/**
 * A small monochrome shape identifying a node type.
 *
 * Plugins publish an `icon` hint as a free-text string, so this maps known hints to inline SVG and
 * falls back to a neutral shape for anything unrecognised. Inline SVG rather than an icon font or a
 * sprite sheet: a plugin uploaded next year cannot ship an asset into this bundle, so the set of
 * shapes has to be finite and local, and unknown hints have to degrade rather than break.
 *
 * These are functional identifiers, not decoration. Every one is paired with a visible text label.
 */
@Component({
  selector: 'wf-node-glyph',
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
      @switch (shape()) {
        @case ('play') {
          <path d="M7 4.5 19 12 7 19.5z" />
        }
        @case ('stop') {
          <rect x="5.5" y="5.5" width="13" height="13" rx="2" />
        }
        @case ('branch') {
          <path d="M7 20V9a3 3 0 0 1 3-3h8" />
          <path d="M15 3l3 3-3 3" />
          <path d="M7 20h10" />
        }
        @case ('form') {
          <rect x="4" y="3" width="16" height="18" rx="2" />
          <path d="M8 8h8M8 12h8M8 16h4" />
        }
        @case ('email') {
          <rect x="3" y="5.5" width="18" height="13" rx="2" />
          <path d="M3.5 7l8.5 6 8.5-6" />
        }
        @case ('message') {
          <path d="M4 5h16v10H9l-5 4z" />
        }
        @case ('cloud') {
          <path d="M7 18a4 4 0 0 1 0-8 5.5 5.5 0 0 1 10.5 1.5A3.5 3.5 0 0 1 17 18z" />
        }
        @case ('apiwindow') {
          <!-- A browser window with its title-bar dots and a settings gear, for the REST API node. -->
          <rect x="2.5" y="3.5" width="13" height="12" rx="2" />
          <path d="M2.5 7h13" />
          <circle cx="5" cy="5.25" r="0.55" fill="currentColor" stroke="none" />
          <circle cx="7" cy="5.25" r="0.55" fill="currentColor" stroke="none" />
          <circle cx="9" cy="5.25" r="0.55" fill="currentColor" stroke="none" />
          <!-- Gear overlapping the bottom-right, drawn as a hub with eight short teeth. -->
          <circle cx="17" cy="17" r="2.6" />
          <circle cx="17" cy="17" r="0.9" fill="currentColor" stroke="none" />
          <path
            d="M17 13.4v-1M17 21.6v-1M20.6 17h1M12.4 17h1M19.55 14.45l.7-.7M14.45 19.55l-.7.7M19.55 19.55l.7.7M14.45 14.45l-.7-.7"
          />
        }
        @case ('database') {
          <ellipse cx="12" cy="6" rx="7" ry="3" />
          <path d="M5 6v12c0 1.7 3.1 3 7 3s7-1.3 7-3V6" />
          <path d="M5 12c0 1.7 3.1 3 7 3s7-1.3 7-3" />
        }
        @case ('spark') {
          <path d="M12 3l1.8 5.2L19 10l-5.2 1.8L12 17l-1.8-5.2L5 10l5.2-1.8z" />
        }
        <!--
          Brand marks. These opt out of the stroke-based drawing above by setting their own fill and
          stroke="none" per path, because a logo is a filled shape rather than a line drawing.

          Simplified forms, not the official assets: each is a handful of paths recognisable at 40px, which
          is what a node tile needs. They stay inline for the same reason every other glyph does — a plugin
          uploaded next year cannot ship an asset into this bundle, so the set has to be finite and local,
          and anything unrecognised has to fall through to a neutral shape rather than break.
        -->
        @case ('brand-slack') {
          <g stroke="none">
            <path
              d="M6 14.5a2 2 0 1 1-2-2h2zm1 0a2 2 0 1 1 4 0v5a2 2 0 1 1-4 0z"
              fill="#E01E5A"
            />
            <path d="M9.5 6a2 2 0 1 1 2-2v2zm0 1a2 2 0 1 1 0 4h-5a2 2 0 1 1 0-4z" fill="#36C5F0" />
            <path d="M18 9.5a2 2 0 1 1 2 2h-2zm-1 0a2 2 0 1 1-4 0v-5a2 2 0 1 1 4 0z" fill="#2EB67D" />
            <path d="M14.5 18a2 2 0 1 1-2 2v-2zm0-1a2 2 0 1 1 0-4h5a2 2 0 1 1 0 4z" fill="#ECB22E" />
          </g>
        }
        @case ('brand-github') {
          <path
            stroke="none"
            fill="currentColor"
            d="M12 2a10 10 0 0 0-3.16 19.49c.5.09.68-.22.68-.48v-1.7c-2.78.6-3.37-1.34-3.37-1.34-.45-1.16-1.11-1.47-1.11-1.47-.91-.62.07-.61.07-.61 1 .07 1.53 1.03 1.53 1.03.9 1.53 2.36 1.09 2.93.83.09-.65.35-1.09.63-1.34-2.22-.25-4.55-1.11-4.55-4.94 0-1.09.39-1.98 1.03-2.68-.1-.25-.45-1.27.1-2.65 0 0 .84-.27 2.75 1.02a9.5 9.5 0 0 1 5 0c1.91-1.29 2.75-1.02 2.75-1.02.55 1.38.2 2.4.1 2.65.64.7 1.03 1.59 1.03 2.68 0 3.84-2.34 4.69-4.57 4.94.36.31.68.92.68 1.85v2.74c0 .27.18.58.69.48A10 10 0 0 0 12 2z"
          />
        }
        @case ('brand-mongodb') {
          <path
            stroke="none"
            fill="#00ED64"
            d="M12 2c1.9 3 5.5 5.4 5.5 9.8 0 3.6-2.3 6.4-4.9 7.4l-.3 2.8h-.6l-.3-2.8c-2.6-1-4.9-3.8-4.9-7.4C6.5 7.4 10.1 5 12 2z"
          />
          <path stroke="none" fill="#00684A" d="M12 2v18l-.2 2.2h.4L12 20z" opacity="0.55" />
        }
        @case ('brand-jira') {
          <g stroke="none">
            <path d="M11.5 2 20 10.5a1.5 1.5 0 0 1 0 2.1L11.5 21 7.2 16.7l6.2-6.2-6.2-6.2z" fill="#2684FF" />
            <path d="M11.5 2 7.2 6.3 3 2.1V2z" fill="#0052CC" opacity="0.9" />
            <path d="M11.5 21 7.2 16.7 3 20.9V21z" fill="#0052CC" opacity="0.9" />
          </g>
        }
        @case ('brand-docker') {
          <g stroke="none" fill="#2496ED">
            <rect x="3" y="11" width="2.6" height="2.6" rx="0.3" />
            <rect x="6.1" y="11" width="2.6" height="2.6" rx="0.3" />
            <rect x="9.2" y="11" width="2.6" height="2.6" rx="0.3" />
            <rect x="12.3" y="11" width="2.6" height="2.6" rx="0.3" />
            <rect x="6.1" y="8" width="2.6" height="2.6" rx="0.3" />
            <rect x="9.2" y="8" width="2.6" height="2.6" rx="0.3" />
            <rect x="12.3" y="8" width="2.6" height="2.6" rx="0.3" />
            <rect x="9.2" y="5" width="2.6" height="2.6" rx="0.3" />
            <path
              d="M2 14.2h17.2c.5-1 .4-2 .3-2.4 .8.4 1.4 1.2 1.6 2.2-.1 2.9-2.3 5.6-6.4 5.6-4.9 0-8.9-2.3-10.9-5.4z"
            />
          </g>
        }
        @case ('brand-google') {
          <g stroke="none">
            <path
              d="M21.6 12.2c0-.7-.06-1.4-.18-2H12v3.8h5.4a4.6 4.6 0 0 1-2 3v2.5h3.2c1.9-1.7 3-4.3 3-7.3z"
              fill="#4285F4"
            />
            <path
              d="M12 22c2.7 0 5-.9 6.6-2.4l-3.2-2.5c-.9.6-2 1-3.4 1-2.6 0-4.8-1.7-5.6-4.1H3.1v2.6A10 10 0 0 0 12 22z"
              fill="#34A853"
            />
            <path d="M6.4 14c-.2-.6-.3-1.3-.3-2s.1-1.4.3-2V7.4H3.1a10 10 0 0 0 0 9.2z" fill="#FBBC05" />
            <path
              d="M12 5.9c1.5 0 2.8.5 3.8 1.5l2.8-2.8A10 10 0 0 0 3.1 7.4L6.4 10c.8-2.4 3-4.1 5.6-4.1z"
              fill="#EA4335"
            />
          </g>
        }
        @case ('brand-kubernetes') {
          <g stroke="none" fill="#326CE5">
            <path
              d="M12 2.2 20.4 6v8L12 21.8 3.6 14V6zm0 2.3L5.8 7.2v6.1L12 19.5l6.2-6.2V7.2z"
            />
            <circle cx="12" cy="10.6" r="2.4" />
            <path d="M11.4 13.4h1.2l1.9 4.4-2.5 1.5-2.5-1.5z" />
          </g>
        }
        @case ('brand-excel') {
          <g stroke="none">
            <path d="M14 3H5.5A1.5 1.5 0 0 0 4 4.5v15A1.5 1.5 0 0 0 5.5 21H14z" fill="#185C37" />
            <path d="M14 3h4.5A1.5 1.5 0 0 1 20 4.5v15a1.5 1.5 0 0 1-1.5 1.5H14z" fill="#107C41" />
            <path
              d="M6.4 8.2h2.1l1.3 2.4 1.3-2.4h2.1l-2.3 3.8 2.4 3.8h-2.1l-1.4-2.5-1.4 2.5H6.3l2.4-3.8z"
              fill="#fff"
            />
          </g>
        }
        @default {
          <rect x="4.5" y="4.5" width="15" height="15" rx="3" />
          <circle cx="12" cy="12" r="2.5" />
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
export class NodeGlyph {
  /** The plugin-published icon hint, or a node type when no hint is available. */
  readonly icon = input<string | null>(null);
  readonly size = input<number>(16);

  /** @return whether the resolved shape is a full-colour brand mark rather than a monochrome glyph */
  readonly isBrand = computed(() => this.shape().startsWith('brand-'));

  readonly shape = computed(() => {
    const hint = (this.icon() ?? '').toLowerCase();
    if (!hint) {
      return 'default';
    }

    // Brand marks first. A Slack node's hint contains "slack" and would otherwise be caught by the
    // generic "message" rule below, and a GCP one by "cloud" — the specific mark has to win.
    for (const [needle, mark] of BRANDS) {
      if (hint.includes(needle)) {
        return mark;
      }
    }

    if (hint.includes('play') || hint.includes('start')) {
      return 'play';
    }
    if (hint.includes('stop') || hint.includes('end')) {
      return 'stop';
    }
    if (hint.includes('branch') || hint.includes('decision') || hint.includes('fork')) {
      return 'branch';
    }
    if (hint.includes('form') || hint.includes('human') || hint.includes('task')) {
      return 'form';
    }
    if (hint.includes('mail') || hint.includes('email')) {
      return 'email';
    }
    if (hint.includes('message') || hint.includes('chat') || hint.includes('slack')) {
      return 'message';
    }
    // The REST API plugin's own glyph — a browser window with a gear — checked before the generic
    // rest/api/http rule below so it wins for this node while other HTTP nodes keep the cloud shape.
    if (hint.includes('restapi') || hint.includes('rest-api') || hint.includes('api-call')) {
      return 'apiwindow';
    }
    if (hint.includes('cloud') || hint.includes('http') || hint.includes('rest') || hint.includes('api')) {
      return 'cloud';
    }
    if (hint.includes('data') || hint.includes('sql') || hint.includes('db')) {
      return 'database';
    }
    if (hint.includes('ai') || hint.includes('llm') || hint.includes('spark') || hint.includes('magic')) {
      return 'spark';
    }
    return 'default';
  });
}
