import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { Router, RouterLink } from '@angular/router';
import { AuthService } from '../../core/auth/auth.service';

/**
 * The sign-in screen.
 *
 * Deliberately says as little as possible about failures. Whether the username exists, the password was
 * wrong, or the account is disabled, the server answers with one message and this screen shows it verbatim.
 * Helpfully distinguishing them would turn the form into a way to enumerate accounts.
 *
 * The one exception is a lockout, where the server does explain itself, because a user whose correct password
 * suddenly stops working needs to know why and that waiting will fix it.
 */
@Component({
  selector: 'wf-login',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [RouterLink],
  template: `
    <div class="auth">
      <div class="auth__panel">
        <div class="auth__brand">
          <span class="auth__mark" aria-hidden="true">
            <svg width="36" height="36" viewBox="0 0 512 512">
              <circle cx="256" cy="256" r="248" fill="#080D17" />
              <circle cx="256" cy="256" r="148" fill="none" stroke="#3EC9D8" stroke-width="46" />
              <circle cx="256" cy="256" r="62" fill="#3EC9D8" />
              <path d="M 400 118 L 330 224 L 316 174 Z" fill="#F0A24B" />
            </svg>
          </span>
          <div>
            <h1>OrchPilot</h1>
            <p>Workflow Platform</p>
          </div>
        </div>

        <h2>Sign in to your account</h2>

        @if (error(); as message) {
          <div class="notice notice--error" role="alert">
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

        <form (submit)="submit($event)" novalidate>
          <div class="field">
            <label class="field__label" for="username">Username or email</label>
            <input
              id="username"
              name="username"
              type="text"
              autocomplete="username"
              autocapitalize="none"
              spellcheck="false"
              required
              [value]="username()"
              [disabled]="busy()"
              (input)="username.set($any($event.target).value)"
            />
          </div>

          <div class="field">
            <label class="field__label" for="password">Password</label>
            <div class="password">
              <input
                id="password"
                name="password"
                [type]="revealed() ? 'text' : 'password'"
                autocomplete="current-password"
                required
                [value]="password()"
                [disabled]="busy()"
                (input)="password.set($any($event.target).value)"
              />
              <button
                class="password__toggle"
                type="button"
                [attr.aria-label]="revealed() ? 'Hide password' : 'Show password'"
                [attr.aria-pressed]="revealed()"
                (click)="revealed.set(!revealed())"
              >
                {{ revealed() ? 'Hide' : 'Show' }}
              </button>
            </div>
          </div>

          <button class="btn btn--primary auth__submit" type="submit" [disabled]="!canSubmit()">
            @if (busy()) {
              Signing in…
            } @else {
              Sign in
            }
          </button>
        </form>

        <p class="auth__footer">
          Don't have an account?
          <a routerLink="/register">Register</a>
        </p>

        <p class="auth__note small faint">
          Sessions are held in memory and renewed from a cookie the browser will not let scripts read.
          Closing the browser signs you out.
        </p>
      </div>
    </div>
  `,
  styles: [
    `
      .auth {
        min-height: 100vh;
        display: flex;
        align-items: center;
        justify-content: center;
        padding: var(--space-5);
        /* The brand blue, so the sign-in page is unmistakably part of the platform. */
        background: linear-gradient(160deg, var(--hl-blue) 0%, #001b37 100%);
      }

      .auth__panel {
        width: 100%;
        max-width: 420px;
        background: var(--surface);
        border-radius: var(--radius-lg);
        box-shadow: var(--shadow-lg);
        padding: var(--space-6);
      }

      .auth__brand {
        display: flex;
        align-items: center;
        gap: var(--space-3);
        margin-bottom: var(--space-5);
      }

      .auth__mark {
        color: var(--hl-green);
        display: inline-flex;
      }

      .auth__brand h1 {
        font-size: var(--text-xl);
        margin: 0;
        line-height: 1.1;
      }

      .auth__brand p {
        margin: 0;
        font-size: var(--text-sm);
        color: var(--text-muted);
      }

      h2 {
        font-size: var(--text-lg);
        margin-bottom: var(--space-4);
      }

      .password {
        display: flex;
        gap: var(--space-2);
      }

      .password__toggle {
        flex: none;
        border: 1px solid var(--border-strong);
        border-radius: var(--radius-sm);
        background: var(--surface);
        padding: 0 var(--space-3);
        cursor: pointer;
        font-family: var(--font-body);
        font-size: var(--text-sm);
        color: var(--text-muted);
      }

      .password__toggle:hover {
        background: var(--hl-grey-100);
        color: var(--text);
      }

      .auth__submit {
        width: 100%;
        justify-content: center;
        margin-top: var(--space-2);
      }

      .auth__footer {
        margin: var(--space-5) 0 0;
        text-align: center;
        font-size: var(--text-base);
      }

      .auth__note {
        margin: var(--space-4) 0 0;
        text-align: center;
      }

      .notice ul {
        margin: var(--space-2) 0 0;
        padding-left: 18px;
        font-size: var(--text-sm);
      }
    `,
  ],
})
export class Login {
  private readonly auth = inject(AuthService);
  private readonly router = inject(Router);

  protected readonly username = signal('');
  protected readonly password = signal('');
  protected readonly revealed = signal(false);
  protected readonly busy = signal(false);
  protected readonly error = signal<string | null>(null);
  protected readonly details = signal<string[]>([]);

  protected canSubmit(): boolean {
    return !this.busy() && this.username().trim().length > 0 && this.password().length > 0;
  }

  protected submit(event: Event): void {
    event.preventDefault();
    if (!this.canSubmit()) {
      return;
    }

    this.busy.set(true);
    this.error.set(null);
    this.details.set([]);

    this.auth.login({ username: this.username().trim(), password: this.password() }).subscribe({
      next: () => {
        this.busy.set(false);
        // Clear the password from component state as soon as it has been sent.
        this.password.set('');
        // Return the user where they were trying to go, which the guard recorded.
        const returnUrl = new URLSearchParams(window.location.search).get('returnUrl');
        this.router.navigateByUrl(returnUrl && returnUrl.startsWith('/') ? returnUrl : '/workflows');
      },
      error: (failure) => {
        this.busy.set(false);
        this.password.set('');
        const described = this.auth.describeError(failure);
        this.error.set(described.message);
        this.details.set(described.details);
      },
    });
  }
}
