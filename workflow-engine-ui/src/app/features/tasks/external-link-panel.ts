import { ChangeDetectionStrategy, Component, computed, effect, inject, input, signal } from '@angular/core';
import { ExternalLinkApiService } from '../../core/api/external-link-api.service';
import { NotificationService } from '../../core/notification.service';
import { ExternalLinkSummary } from '../../core/models/public-form.models';
import { AgoPipe } from '../../shared/pipes/format.pipes';

/**
 * Manages a task's external (public) form link from inside the internal task view.
 *
 * <h2>The URL is shown once</h2>
 *
 * Generating or regenerating a link returns the URL, which is displayed here for copying that one time; a later
 * visit shows only the link's status and expiry, never the URL, because the server never returns the token
 * again. To hand the customer a fresh link after that, an operator regenerates — which revokes the old one.
 */
@Component({
  selector: 'wf-external-link-panel',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [AgoPipe],
  template: `
    <div class="ext">
      <div class="ext__head">
        <strong>External form link</strong>
        @if (activeLink(); as link) {
          <span class="badge badge--ok">{{ link.status }}</span>
        }
      </div>

      @if (generatedUrl(); as url) {
        <p class="small muted">Copy this link and send it to the customer. It is shown only once.</p>
        <div class="ext__url">
          <input type="text" readonly [value]="url" (focus)="selectAll($event)" />
          <button class="btn btn--sm btn--primary" type="button" (click)="copy(url)">Copy</button>
        </div>
      } @else if (activeLink(); as link) {
        <p class="small muted">
          A link is active
          @if (link.expiresAt) {
            · expires {{ link.expiresAt | ago }}
          }
          · {{ link.submissionCount }}/{{ link.maxSubmissions }} submitted. The URL was shown once when it was
          generated.
        </p>
      } @else {
        <p class="small muted">No active link. Generate one to let an external customer complete this form.</p>
      }

      <div class="ext__actions">
        @if (!activeLink()) {
          <button class="btn btn--sm btn--primary" type="button" [disabled]="busy()" (click)="generate()">
            Generate external link
          </button>
        } @else {
          <button class="btn btn--sm" type="button" [disabled]="busy()" (click)="regenerate()">
            Generate new link
          </button>
          <button class="btn btn--sm btn--danger" type="button" [disabled]="busy()" (click)="revoke()">
            Revoke link
          </button>
        }
      </div>
    </div>
  `,
  styles: [
    `
      .ext {
        border: 1px solid var(--border);
        border-radius: var(--radius);
        padding: var(--space-3);
        margin-bottom: var(--space-3);
        background: var(--surface-sunken, #f7f9fb);
      }
      .ext__head {
        display: flex;
        align-items: center;
        gap: var(--space-2);
        margin-bottom: var(--space-2);
      }
      .ext__url {
        display: flex;
        gap: var(--space-2);
        margin: var(--space-2) 0;
      }
      .ext__url input {
        flex: 1;
        font-family: var(--font-mono, monospace);
        font-size: var(--text-sm);
      }
      .ext__actions {
        display: flex;
        gap: var(--space-2);
        margin-top: var(--space-2);
      }
      .badge {
        font-size: var(--text-xs);
        padding: 1px 8px;
        border-radius: 999px;
      }
      .badge--ok {
        background: var(--success-soft, #e6f4ea);
        color: var(--success, #1e7e34);
      }
    `,
  ],
})
export class ExternalLinkPanel {
  readonly taskId = input.required<string>();

  private readonly api = inject(ExternalLinkApiService);
  private readonly notifications = inject(NotificationService);

  protected readonly links = signal<ExternalLinkSummary[]>([]);
  protected readonly generatedUrl = signal<string | null>(null);
  protected readonly busy = signal(false);

  protected readonly activeLink = computed(() =>
    this.links().find((link) => link.status === 'ACTIVE') ?? null,
  );

  constructor() {
    effect(() => {
      const id = this.taskId();
      if (id) {
        this.generatedUrl.set(null);
        this.reload(id);
      }
    });
  }

  private reload(taskId: string): void {
    this.api.list(taskId).subscribe({ next: (links) => this.links.set(links) });
  }

  protected generate(): void {
    if (this.busy()) {
      return;
    }
    this.busy.set(true);
    this.api.generate(this.taskId()).subscribe({
      next: (response) => {
        this.busy.set(false);
        this.generatedUrl.set(this.absolute(response.url));
        this.reload(this.taskId());
        this.notifications.success('External link generated');
      },
      error: () => this.busy.set(false),
    });
  }

  protected regenerate(): void {
    if (this.busy()) {
      return;
    }
    this.busy.set(true);
    this.api.regenerate(this.taskId()).subscribe({
      next: (response) => {
        this.busy.set(false);
        this.generatedUrl.set(this.absolute(response.url));
        this.reload(this.taskId());
        this.notifications.success('New link generated', 'The old link has been revoked.');
      },
      error: () => this.busy.set(false),
    });
  }

  protected revoke(): void {
    if (this.busy()) {
      return;
    }
    this.busy.set(true);
    this.api.revoke(this.taskId()).subscribe({
      next: (links) => {
        this.busy.set(false);
        this.links.set(links);
        this.generatedUrl.set(null);
        this.notifications.success('Link revoked', 'The URL no longer works.');
      },
      error: () => this.busy.set(false),
    });
  }

  protected copy(url: string): void {
    navigator.clipboard?.writeText(url).then(
      () => this.notifications.success('Link copied'),
      () => this.notifications.error('Could not copy', 'Copy it manually from the field.'),
    );
  }

  protected selectAll(event: Event): void {
    (event.target as HTMLInputElement).select();
  }

  /** Turns a relative link (the default) into an absolute URL the operator can send. */
  private absolute(url: string): string {
    return url.startsWith('http') ? url : window.location.origin + url;
  }
}
