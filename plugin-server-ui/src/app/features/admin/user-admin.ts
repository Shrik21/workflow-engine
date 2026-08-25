import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { AdminApiService, RegistryRole, RegistryUser } from '../../core/admin-api.service';
import { AuthService } from '../../core/auth/auth.service';
import { NotificationService } from '../../core/notification.service';
import { AgoPipe } from '../../shared/format.pipes';
import { EmptyState } from '../../shared/empty-state';
import { Modal } from '../../shared/modal';

/** Which dialog is open, if any. */
type Dialog =
  | { kind: 'none' }
  | { kind: 'create' }
  | { kind: 'reset'; user: RegistryUser }
  | { kind: 'confirm-disable'; user: RegistryUser };

/**
 * Accounts on this registry.
 *
 * <h2>Disable rather than delete</h2>
 *
 * Both are offered and the screen says which to prefer. Deleting an account leaves every audit row naming it
 * pointing at somebody nobody can look up, and those rows are consulted precisely when an account has done
 * something worth investigating.
 *
 * <h2>Every control is permission-gated, and none of that is protection</h2>
 *
 * Buttons are hidden from accounts that lack the permission behind them, so nobody is invited to press
 * something that answers 403. The registry enforces the same rules on every request and is what actually
 * decides.
 */
@Component({
  selector: 'ps-user-admin',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [ReactiveFormsModule, EmptyState, Modal, AgoPipe],
  template: `
    <div class="page">
      <header class="page-header">
        <div class="page-header__text">
          <h1>Users</h1>
          <p>
            Accounts on this registry. They are this service's own: separate from the workflow platform's
            accounts, with their own passwords and their own permissions.
          </p>
        </div>
        @if (auth.hasPermission('USER_CREATE')) {
          <div class="toolbar">
            <button class="btn btn--primary" type="button" (click)="openCreate()">Create user</button>
          </div>
        }
      </header>

      @if (loading()) {
        <div class="card"><p class="pad small muted">Loading accounts…</p></div>
      } @else if (users().length === 0) {
        <div class="card">
          <ps-empty-state heading="No accounts" message="Nothing to show yet." />
        </div>
      } @else {
        <div class="card">
          <table class="table">
            <thead>
              <tr>
                <th scope="col">Username</th>
                <th scope="col">Email</th>
                <th scope="col">Roles</th>
                <th scope="col">Status</th>
                <th scope="col">Last sign-in</th>
                <th scope="col" class="cell-actions">Actions</th>
              </tr>
            </thead>
            <tbody>
              @for (user of users(); track user.id) {
                <tr>
                  <td data-label="Username">
                    <strong>{{ user.username }}</strong>
                    @if (user.serviceAccount) {
                      <span class="tag" title="A machine identity; it cannot sign in here">service</span>
                    }
                    <div class="small muted">{{ user.displayName }}</div>
                  </td>
                  <td data-label="Email" class="small">{{ user.email }}</td>
                  <td data-label="Roles">
                    @for (role of user.roles; track role) {
                      <span class="tag tag--mono">{{ role }}</span>
                    }
                  </td>
                  <td data-label="Status">
                    @if (!user.enabled) {
                      <span class="status status--off">◌ Disabled</span>
                    } @else if (user.accountLocked) {
                      <span class="status status--locked">✕ Locked</span>
                    } @else {
                      <span class="status status--on">● Active</span>
                    }
                    @if (user.mustChangePassword) {
                      <div class="small muted">must change password</div>
                    }
                  </td>
                  <td data-label="Last sign-in" class="small muted">
                    {{ user.lastLoginAt ? (user.lastLoginAt | ago) : 'never' }}
                  </td>
                  <td class="cell-actions">
                    @if (auth.hasPermission('USER_UPDATE')) {
                      <button class="btn btn--sm" type="button" (click)="openReset(user)">
                        Reset password
                      </button>
                      @if (user.enabled) {
                        <button class="btn btn--danger btn--sm" type="button" (click)="askDisable(user)">
                          Disable
                        </button>
                      } @else {
                        <button class="btn btn--accent btn--sm" type="button" (click)="setEnabled(user, true)">
                          Enable
                        </button>
                      }
                    }
                  </td>
                </tr>
              }
            </tbody>
          </table>
        </div>
      }
    </div>

    @if (dialog().kind === 'create') {
      <ps-modal
        heading="Create an account"
        subheading="The password is hashed immediately and never stored or shown again."
        width="600px"
        (closed)="close()"
      >
        <form [formGroup]="createForm">
          <div class="grid-2">
            <div class="field">
              <label class="field__label" for="username">Username</label>
              <input id="username" type="text" formControlName="username" />
            </div>
            <div class="field">
              <label class="field__label" for="email">Email</label>
              <input id="email" type="email" formControlName="email" />
            </div>
            <div class="field">
              <label class="field__label" for="firstName">First name</label>
              <input id="firstName" type="text" formControlName="firstName" />
            </div>
            <div class="field">
              <label class="field__label" for="lastName">Last name</label>
              <input id="lastName" type="text" formControlName="lastName" />
            </div>
          </div>

          <div class="field">
            <label class="field__label" for="password">Initial password</label>
            <input id="password" type="text" formControlName="password" />
            <p class="field__hint">
              Shown as text because you have to pass it on. The account must replace it at first sign-in.
            </p>
          </div>

          <fieldset class="roles">
            <legend class="field__label">Roles</legend>
            @for (role of roles(); track role.id) {
              <label class="checkbox-row">
                <input
                  type="checkbox"
                  [checked]="selectedRoles().has(role.name)"
                  (change)="toggleRole(role.name, $any($event.target).checked)"
                />
                <span>
                  <strong class="mono">{{ role.name }}</strong>
                  <span class="small muted"> — {{ role.description }}</span>
                </span>
              </label>
            }
            @if (selectedRoles().size === 0) {
              <p class="field__error">An account must have at least one role.</p>
            }
          </fieldset>
        </form>

        <div modalFooter>
          <button class="btn" type="button" (click)="close()">Cancel</button>
          <button
            class="btn btn--primary"
            type="button"
            [disabled]="createForm.invalid || selectedRoles().size === 0 || busy()"
            (click)="create()"
          >
            Create
          </button>
        </div>
      </ps-modal>
    }

    @if (dialog(); as open) {
      @if (open.kind === 'reset') {
        <ps-modal
          [heading]="'Reset the password of ' + open.user.username"
          subheading="Every session it holds is revoked, and it must choose a new password at next sign-in."
          (closed)="close()"
        >
          <div class="field">
            <label class="field__label" for="newPassword">Temporary password</label>
            <input
              id="newPassword"
              type="text"
              [value]="temporaryPassword()"
              (input)="temporaryPassword.set($any($event.target).value)"
            />
            <p class="field__hint">
              You will need to pass this on, so it is shown as text. It works once.
            </p>
          </div>
          <div modalFooter>
            <button class="btn" type="button" (click)="close()">Cancel</button>
            <button
              class="btn btn--primary"
              type="button"
              [disabled]="temporaryPassword().length === 0 || busy()"
              (click)="resetPassword(open.user)"
            >
              Reset password
            </button>
          </div>
        </ps-modal>
      }

      @if (open.kind === 'confirm-disable') {
        <ps-modal
          [heading]="'Disable ' + open.user.username + '?'"
          subheading="They will not be able to sign in, and every session they hold ends immediately."
          [dismissable]="false"
          (closed)="close()"
        >
          <p>
            Disabling is preferred over deletion: the audit trail keeps naming somebody who can still be
            looked up. The account can be enabled again at any time.
          </p>
          <div modalFooter>
            <button class="btn" type="button" (click)="close()">Cancel</button>
            <button class="btn btn--danger" type="button" (click)="setEnabled(open.user, false)">
              Disable
            </button>
          </div>
        </ps-modal>
      }
    }
  `,
  styles: [
    `
      .pad {
        padding: var(--space-4);
      }

      .status {
        white-space: nowrap;
        font-size: var(--text-sm);
      }

      .status--on {
        color: var(--hl-green);
      }

      .status--off {
        color: var(--hl-grey-600);
      }

      .status--locked {
        color: var(--hl-error);
        font-weight: bold;
      }

      .roles {
        border: 1px solid var(--border);
        border-radius: var(--radius);
        padding: var(--space-3);
        margin-top: var(--space-3);
      }

      .roles legend {
        padding: 0 var(--space-2);
      }

      .checkbox-row {
        display: flex;
        gap: var(--space-2);
        align-items: baseline;
        padding: 4px 0;
      }

      .tag {
        margin-right: 4px;
      }
    `,
  ],
})
export class UserAdmin {
  protected readonly auth = inject(AuthService);

  private readonly api = inject(AdminApiService);
  private readonly notifications = inject(NotificationService);
  private readonly builder = inject(FormBuilder);

  protected readonly users = signal<RegistryUser[]>([]);
  protected readonly roles = signal<RegistryRole[]>([]);
  protected readonly loading = signal(true);
  protected readonly busy = signal(false);
  protected readonly dialog = signal<Dialog>({ kind: 'none' });
  protected readonly temporaryPassword = signal('');
  protected readonly selectedRoles = signal<ReadonlySet<string>>(new Set());

  protected readonly createForm = this.builder.nonNullable.group({
    username: ['', [Validators.required, Validators.minLength(3)]],
    email: ['', [Validators.required, Validators.email]],
    firstName: [''],
    lastName: [''],
    password: ['', [Validators.required, Validators.minLength(12)]],
  });

  constructor() {
    this.load();
    this.api.listRoles().subscribe({ next: (roles) => this.roles.set(roles) });
  }

  private load(): void {
    this.loading.set(true);
    this.api.listUsers().subscribe({
      next: (users) => {
        this.users.set(users);
        this.loading.set(false);
      },
      error: () => this.loading.set(false),
    });
  }

  protected openCreate(): void {
    this.createForm.reset();
    this.selectedRoles.set(new Set());
    this.dialog.set({ kind: 'create' });
  }

  protected openReset(user: RegistryUser): void {
    this.temporaryPassword.set('');
    this.dialog.set({ kind: 'reset', user });
  }

  protected askDisable(user: RegistryUser): void {
    this.dialog.set({ kind: 'confirm-disable', user });
  }

  protected close(): void {
    this.dialog.set({ kind: 'none' });
  }

  protected toggleRole(role: string, checked: boolean): void {
    const next = new Set(this.selectedRoles());
    if (checked) {
      next.add(role);
    } else {
      next.delete(role);
    }
    this.selectedRoles.set(next);
  }

  protected create(): void {
    this.busy.set(true);
    const value = this.createForm.getRawValue();
    this.api
      .createUser({
        username: value.username,
        email: value.email,
        firstName: value.firstName,
        lastName: value.lastName,
        password: value.password,
        roles: [...this.selectedRoles()],
        mustChangePassword: true,
      })
      .subscribe({
        next: (created) => {
          this.busy.set(false);
          this.close();
          this.notifications.success(
            `${created.username} created`,
            'They must change the password at first sign-in.',
          );
          this.load();
        },
        error: () => this.busy.set(false),
      });
  }

  protected resetPassword(user: RegistryUser): void {
    this.busy.set(true);
    this.api.resetPassword(user.id, this.temporaryPassword()).subscribe({
      next: () => {
        this.busy.set(false);
        this.close();
        this.notifications.success(
          `Password reset for ${user.username}`,
          'Their sessions were revoked and they must choose a new password.',
        );
        this.load();
      },
      error: () => this.busy.set(false),
    });
  }

  protected setEnabled(user: RegistryUser, enabled: boolean): void {
    this.api.setUserEnabled(user.id, enabled).subscribe({
      next: () => {
        this.close();
        this.notifications.success(`${user.username} ${enabled ? 'enabled' : 'disabled'}`);
        this.load();
      },
    });
  }
}
