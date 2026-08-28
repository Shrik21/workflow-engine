import { ChangeDetectionStrategy, Component, input } from '@angular/core';

/**
 * The OrchPilot wordmark glyph used on the shell and authentication screens so branding stays identical.
 */
@Component({
  selector: 'wf-brand-mark',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <svg
      [attr.width]="size()"
      [attr.height]="size()"
      viewBox="0 0 512 512"
      aria-hidden="true"
      focusable="false"
    >
      <circle cx="256" cy="256" r="248" fill="#080D17" />
      <circle cx="256" cy="256" r="148" fill="none" stroke="#3EC9D8" stroke-width="46" />
      <circle cx="256" cy="256" r="62" fill="#3EC9D8" />
      <path d="M 400 118 L 330 224 L 316 174 Z" fill="#F0A24B" />
    </svg>
  `,
  styles: [
    `
      :host {
        display: inline-flex;
        flex: none;
        line-height: 0;
      }
    `,
  ],
})
export class BrandMark {
  readonly size = input<number>(30);
}
