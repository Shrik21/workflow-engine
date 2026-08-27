import { ChangeDetectionStrategy, Component, inject, input, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { Router } from '@angular/router';
import { AdminApiService } from '../../core/admin-api.service';
import { AuthService } from '../../core/auth/auth.service';
import { NotificationService } from '../../core/notification.service';
import { AgoPipe } from '../../shared/format.pipes';

/**
 * The signed-in account, and the one place a password can be changed.
 *
 * <h2>What is shown, and what never is</h2>
 *
 * Name, username, email, roles, effective permissions and the last sign-in. Never the password, never its
 * hash, never a token. None of those are recoverable and none would be useful on a screen if they were.
 *
 * <h2>Changing the password ends every other session</h2>
 *
 * Said plainly before the button is pressed, because it is usually the point: somebody changes their password
 * when they think somebody else has it, and a change that left the other sessions running would not have
 * helped.
 */
@Component({
  selector: 'ps-profile',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [ReactiveFormsModule, AgoPipe],
  template: `
    <div class="page">
      <header class="page-header">
        <div class="page-header__text">
          <h1>Your account</h1>
          <p>Your identity on this plugin registry, which is separate from any other system's.</p>
        </div>
      </header>

      @if (auth.mustChangePassword()) {
        <div class="notice notice--warning banner" role="alert">
          <strong>Choose a new password.</strong>
          This account is using a password somebody else set, so nothing else is available until it is
          replaced.
        </div>
      }

      @if (auth.identity(); as identity) {
        <div class="card card--pad">
          <dl class="facts">
            <div><dt>Name</dt><dd>{{ identity.displayName }}</dd></div>
            <div><dt>Username</dt><dd class="mono">{{ identity.username }}</dd></div>
            <div><dt>Email</dt><dd>{{ identity.email }}</dd></div>
            <div>
              <dt>Last sign-in</dt>
              <dd>{{ identity.lastLoginAt ? (identity.lastLoginAt | ago) : 'this is your first' }}</dd>
            </div>
          </dl>

          <div class="section">
            <span class="fact__label">Roles</span>
            <div class="tags">
              @for (role of identity.roles; track role) {
                <span class="tag tag--mono">{{ role }}</span>
              }
            </div>
          </div>

          <div class="section">
            <span class="fact__label">Permissions ({{ identity.permissions.length }})</span>
            <p class="small muted">
              Resolved from your roles when you signed in. A role changed since then takes effect when your
              token next renews.
            </p>
            <div class="tags">
              @for (permission of identity.permissions; track permission) {
                <span class="tag tag--mono">{{ permission }}</span>
              }
            </div>
          </div>
        </div>
      }

      <div class="card card--pad">
        <h2>Change your password</h2>
        <p class="small muted">
          Your current password is required even though you are signed in, so a stolen session cannot be
          turned into permanent control of the account. Every other session ends when it changes.
        </p>

        @if (policy().length > 0) {
          <ul class="policy small muted">
            @for (rule of policy(); track rule) {
              <li>{{ rule }}</li>
            }
          </ul>
        }

        <form [formGroup]="form" (ngSubmit)="submit()">
          <div class="field">
            <label class="field__label" for="current">Current password</label>
            <input id="current" type="password" autocomplete="current-password" formControlName="currentPassword" />
          </div>
          <div class="field">
            <label class="field__label" for="next">New password</label>
            <input id="next" type="password" autocomplete="new-password" formControlName="newPassword" />
          </div>
          <div class="field">
            <label class="field__label" for="confirm">Confirm new password</label>
            <input id="confirm" type="password" autocomplete="new-password" formControlName="confirmPassword" />
            @if (mismatch()) {
              <p class="field__error">The two passwords do not match.</p>
            }
          </div>

          <button class="btn btn--primary" type="submit" [disabled]="form.invalid || mismatch() || busy()">
            {{ busy() ? 'Changing…' : 'Change password' }}
          </button>
        </form>
      </div>
    </div>
  `,
  styles: [
    `
      .banner {
        margin-bottom: var(--space-4);
      }

      .card {
        margin-bottom: var(--space-4);
      }

      .card--pad {
        padding: var(--space-4);
      }

      .facts {
        display: grid;
        grid-template-columns: repeat(auto-fit, minmax(200px, 1fr));
        gap: var(--space-3);
        margin: 0 0 var(--space-4);
      }

      .facts div {
        display: flex;
        flex-direction: column;
        gap: 2px;
      }

      .facts dt,
      .fact__label {
        font-size: var(--text-xs);
        text-transform: uppercase;
        letter-spacing: 0.6px;
        color: var(--text-muted);
      }

      .facts dd {
        margin: 0;
      }

      .section {
        border-top: 1px solid var(--border);
        padding-top: var(--space-3);
        margin-top: var(--space-3);
      }

      .tags {
        display: flex;
        flex-wrap: wrap;
        gap: 4px;
        margin-top: var(--space-2);
      }

      .policy {
        margin: 0 0 var(--space-3) var(--space-4);
      }

      form {
        max-width: 420px;
      }
    `,
  ],
})
export class Profile {
  /** Set by the guard when it redirected here because the password must change. */
  readonly mustChangePassword = input<string | undefined>(undefined);

  protected readonly auth = inject(AuthService);

  private readonly api = inject(AdminApiService);
  private readonly notifications = inject(NotificationService);
  private readonly router = inject(Router);
  private readonly builder = inject(FormBuilder);

  protected readonly busy = signal(false);
  protected readonly policy = signal<string[]>([]);

  protected readonly form = this.builder.nonNullable.group({
    currentPassword: ['', Validators.required],
    newPassword: ['', [Validators.required, Validators.minLength(12)]],
    confirmPassword: ['', Validators.required],
  });

  constructor() {
    this.api.passwordPolicy().subscribe({ next: (policy) => this.policy.set(policy.rules) });
  }

  protected mismatch(): boolean {
    const { newPassword, confirmPassword } = this.form.getRawValue();
    return confirmPassword.length > 0 && newPassword !== confirmPassword;
  }

  protected submit(): void {
    if (this.form.invalid || this.mismatch()) {
      this.form.markAllAsTouched();
      return;
    }
    this.busy.set(true);
    const { currentPassword, newPassword } = this.form.getRawValue();

    this.api.changePassword(currentPassword, newPassword).subscribe({
      next: () => {
        this.busy.set(false);
        this.auth.markPasswordChanged();
        this.form.reset();
        // The registry revoked every session including this one, so the token in hand is on borrowed time.
        // Signing out now is honest about that rather than waiting for the next request to fail.
        this.notifications.success('Password changed', 'Every session was ended. Sign in again.');
        this.auth.clear();
        void this.router.navigate(['/login']);
      },
      error: () => this.busy.set(false),
    });
  }
}
