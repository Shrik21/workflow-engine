import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { Router, RouterLink } from '@angular/router';
import { NotificationService } from '../../core/notification.service';
import { AuthService } from '../../core/auth/auth.service';
import { policyViolations, scorePassword } from '../../core/auth/auth.models';
import { BrandMark } from '../../shared/ui/brand-mark';

/**
 * The registration screen.
 *
 * There is no role selector, and there is no field that could carry one. Every account created here is a
 * USER; only an administrator can grant anything more. That is enforced by the server, whose request type has
 * no roles field at all, so the absence of a control here is the visible half of a structural guarantee
 * rather than a hidden input somebody could edit.
 *
 * The strength meter and the rule checklist come from the server's own policy endpoint, so what the user is
 * told matches what will actually be enforced.
 */
@Component({
  selector: 'wf-register',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [RouterLink, BrandMark],
  template: `
    <div class="auth">
      <div class="auth__panel">
        <div class="auth__brand">
          <wf-brand-mark [size]="36" />
          <div>
            <h1>OrchPilot</h1>
            <p>Workflow Platform</p>
          </div>
        </div>

        <h2>Create an account</h2>
        <p class="auth__intro small muted">
          New accounts can build and run their own workflows. Plugin management, secrets and user
          administration are granted by an administrator.
        </p>

        @if (error(); as message) {
          <div class="notice notice--error" role="alert" id="register-error">
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

        @if (!policy().registrationEnabled) {
          <div class="notice notice--warning">
            Self-registration is turned off on this installation. Ask an administrator to create your
            account.
          </div>
        }

        <form (submit)="submit($event)" novalidate>
          <div class="grid-2 auth__names">
            <div class="field">
              <label class="field__label" for="firstName">First name</label>
              <input
                id="firstName"
                type="text"
                autocomplete="given-name"
                [value]="firstName()"
                [disabled]="busy() || !policy().registrationEnabled"
                (input)="firstName.set($any($event.target).value)"
              />
            </div>
            <div class="field">
              <label class="field__label" for="lastName">Last name</label>
              <input
                id="lastName"
                type="text"
                autocomplete="family-name"
                [value]="lastName()"
                [disabled]="busy() || !policy().registrationEnabled"
                (input)="lastName.set($any($event.target).value)"
              />
            </div>
          </div>

          <div class="field">
            <label class="field__label" for="username">
              Username <span class="required" aria-hidden="true">*</span>
            </label>
            <input
              id="username"
              type="text"
              autocomplete="username"
              autocapitalize="none"
              spellcheck="false"
              required
              [attr.aria-invalid]="error() ? 'true' : null"
              [value]="username()"
              [disabled]="busy() || !policy().registrationEnabled"
              (input)="username.set($any($event.target).value)"
            />
            <p class="field__hint">Letters, digits, dots, dashes and underscores.</p>
          </div>

          <div class="field">
            <label class="field__label" for="email">
              Email <span class="required" aria-hidden="true">*</span>
            </label>
            <input
              id="email"
              type="email"
              autocomplete="email"
              autocapitalize="none"
              spellcheck="false"
              required
              [value]="email()"
              [disabled]="busy() || !policy().registrationEnabled"
              (input)="email.set($any($event.target).value)"
            />
          </div>

          <div class="field">
            <label class="field__label" for="password">
              Password <span class="required" aria-hidden="true">*</span>
            </label>
            <div class="password">
              <input
                id="password"
                [type]="revealed() ? 'text' : 'password'"
                autocomplete="new-password"
                required
                [value]="password()"
                [disabled]="busy() || !policy().registrationEnabled"
                (input)="password.set($any($event.target).value)"
              />
              <button
                class="password__toggle"
                type="button"
                [attr.aria-label]="revealed() ? 'Hide password' : 'Show password'"
                [attr.aria-pressed]="revealed()"
                [disabled]="busy() || !policy().registrationEnabled"
                (click)="revealed.set(!revealed())"
              >
                {{ revealed() ? 'Hide' : 'Show' }}
              </button>
            </div>

            @if (password().length > 0) {
              <div class="strength" [attr.data-strength]="strength()">
                <div class="strength__bars" aria-hidden="true">
                  @for (bar of [1, 2, 3, 4]; track bar) {
                    <span class="strength__bar" [class.strength__bar--on]="bar <= strengthLevel()"></span>
                  }
                </div>
                <span class="strength__label">{{ strengthLabel() }}</span>
              </div>
            }

            <ul class="rules">
              @for (rule of policy().rules; track rule) {
                <li [class.rules__met]="isMet(rule)">
                  <span aria-hidden="true">{{ isMet(rule) ? '✓' : '·' }}</span>
                  {{ rule }}
                </li>
              }
            </ul>
          </div>

          <div class="field">
            <label class="field__label" for="confirm">
              Confirm password <span class="required" aria-hidden="true">*</span>
            </label>
            <input
              id="confirm"
              [type]="revealed() ? 'text' : 'password'"
              autocomplete="new-password"
              required
              [value]="confirm()"
              [disabled]="busy() || !policy().registrationEnabled"
              (input)="confirm.set($any($event.target).value)"
            />
            @if (confirm().length > 0 && !passwordsMatch()) {
              <p class="field__error" role="alert">The passwords do not match.</p>
            }
          </div>

          <button class="btn btn--primary auth__submit" type="submit" [disabled]="!canSubmit()">
            @if (busy()) {
              Creating account…
            } @else {
              Create account
            }
          </button>
        </form>

        <p class="auth__footer">
          Already have an account?
          <a routerLink="/login">Sign in</a>
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
        background: linear-gradient(160deg, var(--hl-blue) 0%, #001b37 100%);
      }

      .auth__panel {
        width: 100%;
        max-width: 520px;
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
        margin: 0 0 var(--space-2);
      }

      .auth__intro {
        margin: 0 0 var(--space-4);
      }

      .auth__names {
        grid-template-columns: repeat(auto-fit, minmax(140px, 1fr));
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

      .password__toggle:hover:not(:disabled) {
        background: var(--hl-grey-100);
        color: var(--text);
      }

      .password__toggle:focus-visible {
        outline: none;
        box-shadow: var(--focus-ring);
      }

      .password__toggle:disabled {
        opacity: 0.5;
        cursor: not-allowed;
      }

      .strength {
        display: flex;
        align-items: center;
        gap: var(--space-2);
        margin-top: var(--space-2);
      }

      .strength__bars {
        display: flex;
        gap: 3px;
        flex: 1;
      }

      .strength__bar {
        height: 4px;
        flex: 1;
        border-radius: 2px;
        background: var(--hl-grey-300);
      }

      .strength__bar--on {
        background: var(--strength-color);
      }

      .strength__label {
        font-size: var(--text-xs);
        font-weight: bold;
        color: var(--strength-color);
        min-width: 74px;
        text-align: right;
      }

      .strength[data-strength='weak'] {
        --strength-color: var(--hl-error);
      }
      .strength[data-strength='medium'] {
        --strength-color: var(--hl-warning);
      }
      .strength[data-strength='strong'] {
        --strength-color: var(--hl-accent-green);
      }
      .strength[data-strength='very-strong'] {
        --strength-color: var(--hl-green);
      }

      .rules {
        list-style: none;
        margin: var(--space-3) 0 0;
        padding: 0;
        font-size: var(--text-sm);
        color: var(--text-muted);
        display: grid;
        grid-template-columns: repeat(auto-fit, minmax(180px, 1fr));
        gap: 2px var(--space-3);
      }

      .rules__met {
        color: var(--hl-green-alt);
      }

      .auth__submit {
        width: 100%;
        justify-content: center;
        margin-top: var(--space-2);
        min-height: 38px;
      }

      .auth__footer {
        margin: var(--space-5) 0 0;
        text-align: center;
      }

      .notice ul {
        margin: var(--space-2) 0 0;
        padding-left: 18px;
        font-size: var(--text-sm);
      }

      @media (max-width: 480px) {
        .auth {
          padding: var(--space-4);
          align-items: flex-start;
        }

        .auth__panel {
          padding: var(--space-5);
        }
      }
    `,
  ],
})
export class Register {
  private readonly auth = inject(AuthService);
  private readonly router = inject(Router);
  private readonly notifications = inject(NotificationService);

  protected readonly firstName = signal('');
  protected readonly lastName = signal('');
  protected readonly username = signal('');
  protected readonly email = signal('');
  protected readonly password = signal('');
  protected readonly confirm = signal('');
  protected readonly revealed = signal(false);
  protected readonly busy = signal(false);
  protected readonly error = signal<string | null>(null);
  protected readonly details = signal<string[]>([]);

  protected readonly policy = this.auth.passwordPolicy;

  protected readonly strength = computed(() => scorePassword(this.password(), this.policy()));

  protected readonly strengthLevel = computed(() => {
    switch (this.strength()) {
      case 'weak':
        return 1;
      case 'medium':
        return 2;
      case 'strong':
        return 3;
      default:
        return 4;
    }
  });

  protected readonly strengthLabel = computed(() => {
    switch (this.strength()) {
      case 'weak':
        return 'Weak';
      case 'medium':
        return 'Medium';
      case 'strong':
        return 'Strong';
      default:
        return 'Very strong';
    }
  });

  private readonly unmetRules = computed(() => policyViolations(this.password(), this.policy()));

  constructor() {
    // Fetches the rules actually in force, rather than showing a hardcoded guess at them.
    this.auth.loadPasswordPolicy().subscribe();
  }

  protected isMet(rule: string): boolean {
    if (this.password().length === 0) {
      return false;
    }
    // The "not commonly used" rule can only be judged by the server, so it is shown as met until the
    // server says otherwise. Marking it unmet while typing would be misleading.
    return !this.unmetRules().includes(rule);
  }

  protected passwordsMatch(): boolean {
    return this.password() === this.confirm();
  }

  protected canSubmit(): boolean {
    return (
      !this.busy() &&
      this.policy().registrationEnabled &&
      this.username().trim().length >= 3 &&
      this.email().trim().length > 0 &&
      this.password().length > 0 &&
      this.passwordsMatch() &&
      this.unmetRules().length === 0
    );
  }

  protected submit(event: Event): void {
    event.preventDefault();
    if (!this.canSubmit()) {
      return;
    }

    this.busy.set(true);
    this.error.set(null);
    this.details.set([]);

    this.auth
      .register({
        username: this.username().trim(),
        email: this.email().trim(),
        password: this.password(),
        firstName: this.firstName().trim() || null,
        lastName: this.lastName().trim() || null,
      })
      .subscribe({
        next: (user) => {
          this.busy.set(false);
          this.password.set('');
          this.confirm.set('');
          this.notifications.success(
            'Account created successfully',
            `Signed up as ${user.username}. Please sign in.`,
          );
          this.router.navigate(['/login']);
        },
        error: (failure) => {
          this.busy.set(false);
          const described = this.auth.describeError(failure);
          this.error.set(described.message);
          this.details.set(described.details);
        },
      });
  }
}
