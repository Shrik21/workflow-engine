import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { AuthStateService } from '../../core/auth/auth-state.service';
import { AuthService } from '../../core/auth/auth.service';
import { policyViolations } from '../../core/auth/auth.models';
import { AgoPipe } from '../../shared/pipes/format.pipes';
import { StatusPill } from '../../shared/ui/status-pill';

/**
 * The signed-in user's own account.
 *
 * Shows the permissions they actually hold rather than just their roles, because "why can I not upload a
 * plugin" is answered by the permission list and not by the role name.
 */
import { Icon } from '../../shared/ui/icon';
@Component({
  selector: 'wf-profile',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [Icon, StatusPill, AgoPipe],
  template: `
    @if (user(); as profile) {
      <div class="page">
        <div class="page-header">
          <div class="page-header__text">
            <h1>Your account</h1>
            <p>Your identity, what you are permitted to do, and where to change your password.</p>
          </div>
          <div class="toolbar">
            <button class="btn btn--sm" type="button" (click)="refresh()"><wf-icon name="refresh" /><span>Refresh</span></button>
            <button class="btn btn--danger btn--sm" type="button" (click)="signOut()">Sign out</button>
          </div>
        </div>

        <div class="grid-2">
          <div class="card">
            <div class="card__header"><h3>Identity</h3></div>
            <div class="card__body">
              <dl class="facts">
                <div>
                  <dt>Name</dt>
                  <dd>{{ state.displayName() }}</dd>
                </div>
                <div>
                  <dt>Username</dt>
                  <dd class="mono">{{ profile.username }}</dd>
                </div>
                <div>
                  <dt>Email</dt>
                  <dd>{{ profile.email }}</dd>
                </div>
                <div>
                  <dt>Roles</dt>
                  <dd>
                    @for (role of profile.roles; track role) {
                      <span class="tag tag--mono">{{ role }}</span>
                    }
                  </dd>
                </div>
                <div>
                  <dt>Account</dt>
                  <dd>
                    <wf-status-pill [status]="profile.enabled ? 'ACTIVE' : 'INACTIVE'" />
                    @if (profile.accountLocked) {
                      <wf-status-pill status="FAILED" title="Locked by an administrator" />
                    }
                  </dd>
                </div>
                <div>
                  <dt>Last sign-in</dt>
                  <dd [title]="profile.lastLoginAt ?? ''">
                    {{ profile.lastLoginAt ? (profile.lastLoginAt | ago) : 'This is your first' }}
                  </dd>
                </div>
                <div>
                  <dt>Member since</dt>
                  <dd [title]="profile.createdAt ?? ''">{{ profile.createdAt | ago }}</dd>
                </div>
              </dl>
            </div>
          </div>

          <div class="card">
            <div class="card__header">
              <h3>Change password</h3>
            </div>
            <div class="card__body">
              @if (error(); as message) {
                <div class="notice notice--error">
                  <strong>{{ message }}</strong>
                  @if (details().length > 0) {
                    <ul>
                      @for (line of details(); track line) {
                        <li>{{ line }}</li>
                      }
                    </ul>
                  }
                </div>
              }

              <form (submit)="changePassword($event)" novalidate>
                <div class="field">
                  <label class="field__label" for="current">Current password</label>
                  <input
                    id="current"
                    type="password"
                    autocomplete="current-password"
                    [value]="current()"
                    (input)="current.set($any($event.target).value)"
                  />
                  <p class="field__hint">
                    Required even though you are signed in, so that a stolen session cannot be turned into
                    permanent control of your account.
                  </p>
                </div>
                <div class="field">
                  <label class="field__label" for="next">New password</label>
                  <input
                    id="next"
                    type="password"
                    autocomplete="new-password"
                    [value]="next()"
                    (input)="next.set($any($event.target).value)"
                  />
                  @if (next().length > 0 && unmet().length > 0) {
                    <p class="field__error">Still needed: {{ unmet().join(', ') }}</p>
                  }
                </div>
                <div class="field">
                  <label class="field__label" for="confirm">Confirm new password</label>
                  <input
                    id="confirm"
                    type="password"
                    autocomplete="new-password"
                    [value]="confirm()"
                    (input)="confirm.set($any($event.target).value)"
                  />
                  @if (confirm().length > 0 && next() !== confirm()) {
                    <p class="field__error">The passwords do not match.</p>
                  }
                </div>
                <button class="btn btn--primary" type="submit" [disabled]="!canSubmit()">
                  {{ busy() ? 'Changing…' : 'Change password' }}
                </button>
                <p class="field__hint">
                  Every session is signed out afterwards, including this one, so a password change really
                  ends any access someone else had.
                </p>
              </form>
            </div>
          </div>
        </div>

        <div class="card" style="margin-top: var(--space-4)">
          <div class="card__header">
            <h3>What you can do</h3>
            <span class="spacer"></span>
            <span class="small muted">{{ profile.permissions.length }} permission(s)</span>
          </div>
          <div class="card__body">
            <div class="chips">
              @for (permission of profile.permissions; track permission) {
                <span class="tag tag--mono">{{ permission }}</span>
              }
            </div>
            <p class="field__hint">
              Granted by your roles. The server checks each of these on every request; the console only uses
              them to decide what to show you.
            </p>
          </div>
        </div>
      </div>
    }
  `,
  styles: [
    `
      .facts {
        margin: 0;
        display: flex;
        flex-direction: column;
        gap: var(--space-3);
      }

      .facts dt {
        font-size: var(--text-xs);
        text-transform: uppercase;
        letter-spacing: 0.6px;
        color: var(--text-muted);
      }

      .facts dd {
        margin: 2px 0 0;
        display: flex;
        align-items: center;
        gap: var(--space-1);
        flex-wrap: wrap;
      }

      .chips {
        display: flex;
        flex-wrap: wrap;
        gap: var(--space-1);
      }

      .notice ul {
        margin: var(--space-2) 0 0;
        padding-left: 18px;
        font-size: var(--text-sm);
      }
    `,
  ],
})
export class Profile {
  protected readonly state = inject(AuthStateService);
  private readonly auth = inject(AuthService);

  protected readonly user = this.state.user;

  protected readonly current = signal('');
  protected readonly next = signal('');
  protected readonly confirm = signal('');
  protected readonly busy = signal(false);
  protected readonly error = signal<string | null>(null);
  protected readonly details = signal<string[]>([]);

  protected readonly unmet = computed(() =>
    policyViolations(this.next(), this.auth.passwordPolicy()),
  );

  constructor() {
    this.auth.loadPasswordPolicy().subscribe();
  }

  protected canSubmit(): boolean {
    return (
      !this.busy() &&
      this.current().length > 0 &&
      this.next().length > 0 &&
      this.next() === this.confirm() &&
      this.unmet().length === 0
    );
  }

  protected refresh(): void {
    this.auth.loadCurrentUser().subscribe();
  }

  protected signOut(): void {
    this.auth.logout({ message: 'Signed out.' });
  }

  protected changePassword(event: Event): void {
    event.preventDefault();
    if (!this.canSubmit()) {
      return;
    }

    this.busy.set(true);
    this.error.set(null);
    this.details.set([]);

    this.auth
      .changePassword({
        currentPassword: this.current(),
        newPassword: this.next(),
        confirmPassword: this.confirm(),
      })
      .subscribe({
        // On success the service clears the session and navigates, so there is nothing to do here.
        next: () => this.clearFields(),
        error: (failure) => {
          this.busy.set(false);
          this.clearFields();
          const described = this.auth.describeError(failure);
          this.error.set(described.message);
          this.details.set(described.details);
        },
      });
  }

  /** Clears the plaintext from component state as soon as the request has been sent. */
  private clearFields(): void {
    this.current.set('');
    this.next.set('');
    this.confirm.set('');
  }
}
