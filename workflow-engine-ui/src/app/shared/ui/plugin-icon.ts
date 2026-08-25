import { ChangeDetectionStrategy, Component, computed, inject, input } from '@angular/core';
import { DomSanitizer, SafeUrl } from '@angular/platform-browser';
import { NodeApiService } from '../../core/api/node-api.service';
import { NodeGlyph } from './node-glyph';

/**
 * A node's icon: the artwork its plugin shipped, or the built-in glyph when it shipped none.
 *
 * <h2>The one place a sanitiser bypass is used, and why it is safe</h2>
 *
 * Angular refuses `data:image/svg+xml` in a `[src]` binding. Its allowlist covers PNG, JPEG, GIF and WebP but
 * deliberately excludes SVG, because SVG is a document format that can carry script — and Angular cannot know
 * how a given URL will be used.
 *
 * <p>Here it is always an `<img>`, and that is the whole argument. An SVG loaded through `<img>` renders in the
 * browser's *secure static mode*: scripting is disabled and external references are not fetched, whatever the
 * file contains. This is a property of the tag, not of the file, so it holds for artwork this console never
 * inspected. The server additionally sanitises SVG when the plugin is installed, so the stored bytes are safe
 * for any future reader too.
 *
 * <p>Keeping the bypass in this component rather than at each call site means there is exactly one place to
 * check that the tag is still `<img>`. Rendering plugin artwork through `[innerHTML]` instead would put it in
 * the document, where none of the above applies.
 */
@Component({
  selector: 'wf-plugin-icon',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [NodeGlyph],
  template: `
    @if (source(); as url) {
      <img
        class="plugin-icon"
        [src]="url"
        [style.width.px]="size()"
        [style.height.px]="size()"
        alt=""
        aria-hidden="true"
        draggable="false"
      />
    } @else {
      <wf-node-glyph [icon]="icon()" [size]="size()" />
    }
  `,
  styles: [
    `
      :host {
        display: inline-flex;
        align-items: center;
        justify-content: center;
        flex: none;
      }

      .plugin-icon {
        /* contain, not cover: a product mark cropped to fill its box is worse than one with margin. */
        object-fit: contain;
      }
    `,
  ],
})
export class PluginIcon {
  private readonly catalog = inject(NodeApiService);

  private readonly sanitizer = inject(DomSanitizer);

  /** The plugin behind this node, or null for a built-in. */
  readonly pluginId = input<string | null>(null);

  /** The icon hint, used for the built-in glyph when the plugin ships no artwork. */
  readonly icon = input<string | null>(null);

  readonly size = input<number>(24);

  protected readonly source = computed<SafeUrl | null>(() => {
    const url = this.catalog.iconFor(this.pluginId());
    // See the class note: trusted only because the template below binds it to an <img>.
    return url ? this.sanitizer.bypassSecurityTrustUrl(url) : null;
  });
}
