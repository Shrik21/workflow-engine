import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { AdminApiService, SecurityAuditEntry } from '../../core/admin-api.service';
import { EmptyState } from '../../shared/empty-state';
import { AgoPipe } from '../../shared/format.pipes';

/**
 * The security trail.
 *
 * <h2>Failures first</h2>
 *
 * A list of successful sign-ins says very little. The rows worth reading are the refused ones — a password
 * tried five times, a token presented after it was rotated, a viewer reaching for the upload endpoint — so
 * failures are visually distinct and filterable on their own.
 *
 * <h2>Read-only, and open to auditors</h2>
 *
 * Reached with `PLUGIN_AUDIT_READ` rather than an administrative role, so somebody can be given sight of what
 * happened without the ability to change any of it. There is no control on this screen that writes anything.
 */
@Component({
  selector: 'ps-security-audit',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [EmptyState, AgoPipe],
  template: `
    <div class="page">
      <header class="page-header">
        <div class="page-header__text">
          <h1>Security audit</h1>
          <p>
            Sign-ins, token activity and account changes on this registry. Entries record who, what, when and
            from where; they never contain a password, a token or anything derived from one.
          </p>
        </div>
      </header>

      <div class="controls">
        <input
          type="search"
          placeholder="Filter by username"
          aria-label="Filter by username"
          [value]="username()"
          (input)="username.set($any($event.target).value)"
          (keydown.enter)="load()"
        />
        <label>
          <span class="sr-only">Filter by action</span>
          <select [value]="action()" (change)="action.set($any($event.target).value); load()">
            <option value="">All actions</option>
            @for (name of actions(); track name) {
              <option [value]="name">{{ name }}</option>
            }
          </select>
        </label>
        <label>
          <span class="sr-only">Filter by outcome</span>
          <select [value]="outcome()" (change)="outcome.set($any($event.target).value); load()">
            <option value="">All outcomes</option>
            <option value="false">Failures only</option>
            <option value="true">Successes only</option>
          </select>
        </label>
        <button class="btn btn--sm" type="button" (click)="load()">Apply</button>
        <span class="spacer"></span>
        <span class="small muted">{{ total() }} entries</span>
      </div>

      @if (loading()) {
        <div class="card"><p class="pad small muted">Loading the trail…</p></div>
      } @else if (entries().length === 0) {
        <div class="card">
          <ps-empty-state
            heading="Nothing recorded"
            message="No entry matches those filters. Sign-ins, refusals and account changes appear here as they happen."
          />
        </div>
      } @else {
        <div class="card">
          <table class="table">
            <thead>
              <tr>
                <th scope="col">When</th>
                <th scope="col">Action</th>
                <th scope="col">User</th>
                <th scope="col">Outcome</th>
                <th scope="col">From</th>
                <th scope="col">Detail</th>
              </tr>
            </thead>
            <tbody>
              @for (entry of entries(); track entry.id) {
                <tr [class.row--failure]="!entry.success">
                  <td data-label="When" class="small muted">{{ entry.timestamp | ago }}</td>
                  <td data-label="Action"><strong class="mono small">{{ entry.action }}</strong></td>
                  <td data-label="User" class="small">{{ entry.username || '—' }}</td>
                  <td data-label="Outcome">
                    @if (entry.success) {
                      <span class="outcome outcome--ok">● ok</span>
                    } @else {
                      <span class="outcome outcome--fail">✕ refused</span>
                    }
                  </td>
                  <td data-label="From" class="small muted mono">{{ entry.ipAddress || '—' }}</td>
                  <td data-label="Detail" class="small">{{ describe(entry) }}</td>
                </tr>
              }
            </tbody>
          </table>
        </div>
      }
    </div>
  `,
  styles: [
    `
      .pad {
        padding: var(--space-4);
      }

      .controls {
        display: flex;
        gap: var(--space-2);
        align-items: center;
        margin-bottom: var(--space-3);
        flex-wrap: wrap;
      }

      .controls input[type='search'] {
        min-width: 200px;
      }

      .outcome {
        font-size: var(--text-sm);
        white-space: nowrap;
      }

      .outcome--ok {
        color: var(--hl-green);
      }

      .outcome--fail {
        color: var(--hl-error);
        font-weight: bold;
      }

      .row--failure td {
        background: color-mix(in srgb, var(--hl-error) 4%, transparent);
      }
    `,
  ],
})
export class SecurityAudit {
  private readonly api = inject(AdminApiService);

  protected readonly entries = signal<SecurityAuditEntry[]>([]);
  protected readonly actions = signal<string[]>([]);
  protected readonly total = signal(0);
  protected readonly loading = signal(true);

  protected readonly username = signal('');
  protected readonly action = signal('');
  protected readonly outcome = signal('');

  constructor() {
    this.api.auditActions().subscribe({ next: (actions) => this.actions.set(actions) });
    this.load();
  }

  protected load(): void {
    this.loading.set(true);
    this.api
      .listAudit({
        username: this.username().trim() || undefined,
        action: this.action() || undefined,
        success: this.outcome() === '' ? undefined : this.outcome() === 'true',
      })
      .subscribe({
        next: (page) => {
          this.entries.set(page.content ?? []);
          this.total.set(page.totalElements ?? 0);
          this.loading.set(false);
        },
        error: () => this.loading.set(false),
      });
  }

  /** Renders the details map as a sentence. The map holds names and decisions, never values. */
  protected describe(entry: SecurityAuditEntry): string {
    const details = entry.details ?? {};
    const parts = Object.entries(details).map(([key, value]) => `${key}: ${String(value)}`);
    if (entry.resource) {
      parts.unshift(`${entry.resource}${entry.resourceId ? ' ' + entry.resourceId : ''}`);
    }
    return parts.join(' · ') || '—';
  }
}
