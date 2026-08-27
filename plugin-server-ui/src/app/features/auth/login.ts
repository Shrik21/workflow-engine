import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { ActivatedRoute, Router } from '@angular/router';
import { AuthService } from '../../core/auth/auth.service';

/**
 * Sign-in.
 *
 * <h2>These are the registry's own accounts</h2>
 *
 * Not the workflow platform's. The registry keeps its own users, roles, permissions and tokens, and a token the
 * platform issued is not accepted here — publishing to this registry distributes executable code to every
 * engine that reads it, so who may do that is a decision this service makes alone.
 *
 * <p>The page says so in as many words, because the two consoles look alike and sit on the same host. An
 * earlier version of this text told operators to sign in with their platform account, which is credentials this
 * service will always refuse: five attempts at that locks the account for fifteen minutes, and nothing on
 * screen explains why.
 */
@Component({
  selector: 'ps-login',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [ReactiveFormsModule],
  template: `
    <div class="page">
      <form class="card" [formGroup]="form" (ngSubmit)="submit()">
        <h1>Plugin Registry</h1>
        <p class="small muted">
          This registry has its own accounts. A workflow platform sign-in will not work here, and neither
          console's session affects the other.
        </p>

        <div class="field">
          <label class="field__label" for="username">Username or email</label>
          <input id="username" type="text" autocomplete="username" formControlName="username" />
          @if (invalid('username')) {
            <p class="field__error">Enter your username.</p>
          }
        </div>

        <div class="field">
          <label class="field__label" for="password">Password</label>
          <input
            id="password"
            [type]="revealed() ? 'text' : 'password'"
            autocomplete="current-password"
            formControlName="password"
          />
          <button class="link" type="button" (click)="revealed.set(!revealed())">
            {{ revealed() ? 'Hide' : 'Show' }} password
          </button>
          @if (invalid('password')) {
            <p class="field__error">Enter your password.</p>
          }
        </div>

        @if (failure()) {
          <div class="notice notice--error" role="alert">{{ failure() }}</div>
        }

        <button class="btn btn--primary" type="submit" [disabled]="busy()">
          {{ busy() ? 'Signing in…' : 'Sign in' }}
        </button>
      </form>
    </div>
  `,
  styles: [
    `
      .page {
        min-height: 100vh;
        display: grid;
        place-items: center;
        padding: var(--space-5);
        background: var(--surface-sunken);
      }

      .card {
        width: 100%;
        max-width: 380px;
        background: var(--surface);
        border: 1px solid var(--border);
        border-radius: var(--radius-lg);
        box-shadow: var(--shadow-lg);
        padding: var(--space-5);
        display: flex;
        flex-direction: column;
        gap: var(--space-3);
      }

      h1 {
        margin: 0;
      }

      .link {
        align-self: flex-start;
        background: none;
        border: none;
        padding: 0;
        color: var(--hl-accent-blue-alt);
        font-size: var(--text-xs);
        cursor: pointer;
      }

      .btn {
        margin-top: var(--space-2);
      }
    `,
  ],
})
export class Login {
  private readonly auth = inject(AuthService);
  private readonly router = inject(Router);
  private readonly route = inject(ActivatedRoute);
  private readonly builder = inject(FormBuilder);

  protected readonly form = this.builder.nonNullable.group({
    username: ['', Validators.required],
    password: ['', Validators.required],
  });

  protected readonly busy = signal(false);
  protected readonly revealed = signal(false);
  protected readonly failure = signal<string | null>(null);

  protected invalid(control: 'username' | 'password'): boolean {
    const field = this.form.controls[control];
    return field.invalid && (field.dirty || field.touched);
  }

  protected submit(): void {
    if (this.form.invalid) {
      this.form.markAllAsTouched();
      return;
    }
    this.busy.set(true);
    this.failure.set(null);

    const { username, password } = this.form.getRawValue();
    this.auth.login(username, password).subscribe({
      next: () => {
        this.busy.set(false);
        const returnUrl = this.route.snapshot.queryParamMap.get('returnUrl') ?? '/plugins';
        void this.router.navigateByUrl(returnUrl);
      },
      error: (error: unknown) => {
        this.busy.set(false);
        this.failure.set(describe(error));
      },
    });
  }
}

/**
 * What to put on screen when a sign-in fails.
 *
 * <p>The distinction that matters is whether the registry answered. It refusing the credentials and it not
 * being there are different problems with different fixes, and the previous version of this told anybody who
 * mistyped a password that the service might be unreachable — which sent at least one operator looking at
 * processes and ports instead of at what they had typed.
 *
 * <p>When the registry did answer, its own sentence is used. One message covers a wrong password, an unknown
 * account, a disabled one and a locked one, deliberately: saying which would confirm to anybody who asks that
 * an account exists. The note about repeated attempts is shown always, for the same reason — it is the one
 * thing worth knowing after a few failures, and it reveals nothing about any particular account.
 */
function describe(error: unknown): string {
  const failure = error as { status?: number; error?: { message?: unknown } } | null;
  const status = failure?.status ?? 0;
  const fromRegistry =
    typeof failure?.error?.message === 'string' ? failure.error.message : null;

  if (status === 0) {
    return 'The plugin registry did not respond. Check that it is running on its configured address.';
  }
  if (status >= 500) {
    return 'The plugin registry could not handle the sign-in. Check its log.';
  }
  return `${fromRegistry ?? 'That username and password were not accepted.'} After five failed attempts an account is locked for fifteen minutes.`;
}
