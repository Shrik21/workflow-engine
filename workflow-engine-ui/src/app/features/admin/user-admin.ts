import { ChangeDetectionStrategy, Component, effect, inject, signal } from '@angular/core';
import {
  SecurityAuditRecord,
  UserAdminApiService,
} from '../../core/api/user-admin-api.service';
import { Page, emptyPage } from '../../core/models/api.models';
import { AuthStateService } from '../../core/auth/auth-state.service';
import { Role, UserProfile, policyViolations } from '../../core/auth/auth.models';
import { AuthService } from '../../core/auth/auth.service';
import { NotificationService } from '../../core/notification.service';
import { AgoPipe } from '../../shared/pipes/format.pipes';
import { EmptyState } from '../../shared/ui/empty-state';
import { Modal } from '../../shared/ui/modal';
import { ConfirmDialog, ConfirmRequest } from '../../shared/ui/confirm-dialog';
import { StatusPill } from '../../shared/ui/status-pill';

type Dialog = 'none' | 'create' | 'roles' | 'audit';

/** Roles offered in the console. Others exist server-side and are shown if already assigned. */
const ASSIGNABLE_ROLES: Role[] = [
  'ADMIN',
  'USER',
  'WORKFLOW_ADMIN',
  'WORKFLOW_EDITOR',
  'WORKFLOW_VIEWER',
  'PLUGIN_ADMIN',
  'EXECUTION_ADMIN',
];

/**
 * User administration.
 *
 * Every action here is destructive in some way, so each one says what it will actually do before doing it:
 * disabling revokes sessions immediately, changing roles signs the user out, and deleting is irreversible
 * while disabling is not. The server refuses to remove the last administrator and refuses to let an
 * administrator lock themselves out; this screen explains those refusals rather than presenting them as
 * unexplained errors.
 */
@Component({
  selector: 'wf-user-admin',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [StatusPill, EmptyState, Modal, ConfirmDialog, AgoPipe],
  template: `
    <div class="page">
      <div class="page-header">
        <div class="page-header__text">
          <h1>Users</h1>
          <p>
            Accounts, roles and access. A USER can build and run their own workflows; plugin management,
            secrets and this page require ADMIN.
          </p>
        </div>
        <div class="toolbar">
          <button class="btn btn--sm" type="button" (click)="openAudit()">Security log</button>
          <button class="btn btn--primary" type="button" (click)="openCreate()">Add user</button>
        </div>
      </div>

      <div class="card">
        <div class="card__header">
          <input
            type="search"
            style="max-width: 260px"
            placeholder="Search name, username or email"
            aria-label="Search users"
            [value]="search()"
            (input)="onSearch($any($event.target).value)"
          />
          <div class="btn-group">
            <button
              class="btn btn--sm"
              type="button"
              [class.btn--primary]="roleFilter() === null"
              (click)="setRole(null)"
            >
              All
            </button>
            <button
              class="btn btn--sm"
              type="button"
              [class.btn--primary]="roleFilter() === 'ADMIN'"
              (click)="setRole('ADMIN')"
            >
              Admins
            </button>
            <button
              class="btn btn--sm"
              type="button"
              [class.btn--primary]="roleFilter() === 'USER'"
              (click)="setRole('USER')"
            >
              Users
            </button>
          </div>
          <span class="spacer"></span>
          <span class="small muted">{{ page().totalElements }} account(s)</span>
        </div>

        @if (page().content.length === 0 && !loading()) {
          <wf-empty-state heading="No accounts match" message="Clear the filters, or add a user.">
            <button class="btn btn--primary" type="button" (click)="openCreate()">Add user</button>
          </wf-empty-state>
        } @else {
          <table class="table">
            <thead>
              <tr>
                <th>Name</th>
                <th>Username</th>
                <th>Email</th>
                <th>Roles</th>
                <th>Status</th>
                <th>Last login</th>
                <th>Created</th>
                <th class="cell-actions">Actions</th>
              </tr>
            </thead>
            <tbody>
              @for (user of page().content; track user.id) {
                <tr [class.row--self]="isSelf(user)">
                  <td>
                    {{ displayName(user) }}
                    @if (isSelf(user)) {
                      <span class="tag">you</span>
                    }
                  </td>
                  <td class="mono">{{ user.username }}</td>
                  <td class="small">{{ user.email }}</td>
                  <td>
                    @for (role of user.roles; track role) {
                      <span class="tag tag--mono">{{ role }}</span>
                    }
                  </td>
                  <td>
                    <wf-status-pill [status]="user.enabled ? 'ACTIVE' : 'INACTIVE'" />
                    @if (user.accountLocked) {
                      <wf-status-pill status="FAILED" title="Locked" />
                    }
                  </td>
                  <td class="small muted" [title]="user.lastLoginAt ?? ''">
                    {{ user.lastLoginAt ? (user.lastLoginAt | ago) : 'never' }}
                  </td>
                  <td class="small muted">{{ user.createdAt | ago }}</td>
                  <td class="cell-actions">
                    <button class="btn btn--sm" type="button" (click)="openRoles(user)">Roles</button>
                    @if (user.enabled) {
                      <button
                        class="btn btn--sm"
                        type="button"
                        [disabled]="isSelf(user) || busy()"
                        [title]="isSelf(user) ? 'You cannot disable your own account' : 'Revokes access immediately'"
                        (click)="setEnabled(user, false)"
                      >
                        Disable
                      </button>
                    } @else {
                      <button
                        class="btn btn--sm"
                        type="button"
                        [disabled]="busy()"
                        (click)="setEnabled(user, true)"
                      >
                        Enable
                      </button>
                    }
                    @if (user.accountLocked) {
                      <button class="btn btn--sm" type="button" [disabled]="busy()" (click)="setLocked(user, false)">
                        Unlock
                      </button>
                    } @else {
                      <button
                        class="btn btn--sm"
                        type="button"
                        [disabled]="isSelf(user) || busy()"
                        (click)="setLocked(user, true)"
                      >
                        Lock
                      </button>
                    }
                    <button
                      class="btn btn--danger btn--sm"
                      type="button"
                      [disabled]="isSelf(user) || busy()"
                      (click)="remove(user)"
                    >
                      Delete
                    </button>
                  </td>
                </tr>
              }
            </tbody>
          </table>

          @if (page().totalPages > 1) {
            <div class="card__footer">
              <button class="btn btn--sm" type="button" [disabled]="page().first" (click)="goTo(page().number - 1)">
                Previous
              </button>
              <span class="small muted" style="align-self: center">
                Page {{ page().number + 1 }} of {{ page().totalPages }}
              </span>
              <button class="btn btn--sm" type="button" [disabled]="page().last" (click)="goTo(page().number + 1)">
                Next
              </button>
            </div>
          }
        }
      </div>
    </div>

    @if (dialog() === 'create') {
      <wf-modal
        heading="Add a user"
        subheading="The initial password must satisfy the same policy as a self-registration and is hashed immediately."
        width="620px"
        (closed)="dialog.set('none')"
      >
        <div class="grid-2">
          <div class="field">
            <label class="field__label" for="new-username">Username</label>
            <input id="new-username" type="text" class="mono" [value]="form.username()"
                   (input)="form.username.set($any($event.target).value)" />
          </div>
          <div class="field">
            <label class="field__label" for="new-email">Email</label>
            <input id="new-email" type="email" [value]="form.email()"
                   (input)="form.email.set($any($event.target).value)" />
          </div>
          <div class="field">
            <label class="field__label" for="new-first">First name</label>
            <input id="new-first" type="text" [value]="form.firstName()"
                   (input)="form.firstName.set($any($event.target).value)" />
          </div>
          <div class="field">
            <label class="field__label" for="new-last">Last name</label>
            <input id="new-last" type="text" [value]="form.lastName()"
                   (input)="form.lastName.set($any($event.target).value)" />
          </div>
        </div>

        <div class="field">
          <label class="field__label" for="new-password">Initial password</label>
          <input id="new-password" type="password" autocomplete="new-password" [value]="form.password()"
                 (input)="form.password.set($any($event.target).value)" />
          @if (form.password().length > 0 && passwordIssues().length > 0) {
            <p class="field__error">Still needed: {{ passwordIssues().join(', ') }}</p>
          }
          <p class="field__hint">
            Share it over a channel the recipient can trust, and have them change it after signing in.
          </p>
        </div>

        <div class="field">
          <span class="field__label">Roles</span>
          <div class="roles">
            @for (role of assignableRoles; track role) {
              <label class="checkbox-row">
                <input type="checkbox" [checked]="form.roles().includes(role)"
                       (change)="toggleFormRole(role, $any($event.target).checked)" />
                <span class="mono">{{ role }}</span>
              </label>
            }
          </div>
          @if (form.roles().includes('ADMIN')) {
            <p class="field__error">
              ADMIN grants everything, including installing plugins, which runs code inside the engine.
            </p>
          }
        </div>

        <div modalFooter>
          <button class="btn" type="button" (click)="dialog.set('none')">Cancel</button>
          <button class="btn btn--primary" type="button" [disabled]="!canCreate()" (click)="create()">
            Create user
          </button>
        </div>
      </wf-modal>
    }

    @if (dialog() === 'roles' && selected(); as user) {
      <wf-modal
        [heading]="'Roles for ' + user.username"
        subheading="Saving signs the user out of every session, so their new authorities apply from the next sign-in."
        (closed)="dialog.set('none')"
      >
        <div class="roles">
          @for (role of allRolesFor(user); track role) {
            <label class="checkbox-row">
              <input type="checkbox" [checked]="draftRoles().includes(role)"
                     (change)="toggleDraftRole(role, $any($event.target).checked)" />
              <span class="mono">{{ role }}</span>
            </label>
          }
        </div>
        @if (draftRoles().length === 0) {
          <p class="field__error">A user needs at least one role.</p>
        }
        @if (isSelf(user) && !draftRoles().includes('ADMIN')) {
          <p class="field__error">
            You cannot remove your own ADMIN role. Ask another administrator to do it.
          </p>
        }
        <div modalFooter>
          <button class="btn" type="button" (click)="dialog.set('none')">Cancel</button>
          <button class="btn btn--primary" type="button" [disabled]="!canSaveRoles(user)" (click)="saveRoles(user)">
            Save roles
          </button>
        </div>
      </wf-modal>
    }

    @if (dialog() === 'audit') {
      <wf-modal
        heading="Security log"
        subheading="Sign-ins, failures, refreshes, role changes and access denials. Contains no passwords or tokens."
        width="900px"
        (closed)="dialog.set('none')"
      >
        @if (auditRecords().length === 0) {
          <p class="small muted">No records yet.</p>
        } @else {
          <table class="table">
            <thead>
              <tr>
                <th>When</th>
                <th>Event</th>
                <th>Account</th>
                <th>Actor</th>
                <th>Outcome</th>
                <th>From</th>
              </tr>
            </thead>
            <tbody>
              @for (record of auditRecords(); track record.id) {
                <tr>
                  <td class="small muted" [title]="record.at ?? ''">{{ record.at | ago }}</td>
                  <td class="mono small">{{ record.event }}</td>
                  <td class="small">{{ record.username || record.userId || '' }}</td>
                  <td class="small muted">{{ record.actorUsername || '' }}</td>
                  <td class="small">
                    @if (record.success) {
                      <span class="ok">ok</span>
                    } @else {
                      <span class="bad">{{ record.reason || 'failed' }}</span>
                    }
                  </td>
                  <td class="small muted mono">{{ record.ipAddress || '' }}</td>
                </tr>
              }
            </tbody>
          </table>
        }
        <div modalFooter>
          <button class="btn" type="button" (click)="dialog.set('none')">Close</button>
        </div>
      </wf-modal>
    }

    @if (pendingConfirm(); as c) {
      <wf-confirm-dialog
        [heading]="c.heading"
        [message]="c.message"
        [confirmLabel]="c.confirmLabel"
        [danger]="c.danger"
        (confirmed)="runConfirmed()"
        (cancelled)="pendingConfirm.set(null)"
      />
    }
  `,
  styles: [
    `
      .roles {
        display: grid;
        grid-template-columns: repeat(auto-fit, minmax(190px, 1fr));
        gap: var(--space-2);
      }

      .row--self {
        background: #f2f8fd;
      }

      .ok {
        color: var(--hl-green-alt);
      }

      .bad {
        color: var(--hl-error);
      }
    `,
  ],
})
export class UserAdmin {
  protected readonly assignableRoles = ASSIGNABLE_ROLES;

  private readonly api = inject(UserAdminApiService);
  private readonly auth = inject(AuthService);
  private readonly state = inject(AuthStateService);
  private readonly notifications = inject(NotificationService);

  protected readonly page = signal<Page<UserProfile>>(emptyPage());
  protected readonly loading = signal(false);
  protected readonly busy = signal(false);
  protected readonly pendingConfirm = signal<ConfirmRequest | null>(null);
  protected readonly dialog = signal<Dialog>('none');
  protected readonly selected = signal<UserProfile | null>(null);
  protected readonly draftRoles = signal<Role[]>([]);
  protected readonly auditRecords = signal<SecurityAuditRecord[]>([]);

  protected readonly search = signal('');
  protected readonly roleFilter = signal<Role | null>(null);
  private readonly pageIndex = signal(0);
  private searchTimer: ReturnType<typeof setTimeout> | null = null;

  protected readonly form = {
    username: signal(''),
    email: signal(''),
    password: signal(''),
    firstName: signal(''),
    lastName: signal(''),
    roles: signal<Role[]>(['USER']),
  };

  constructor() {
    this.auth.loadPasswordPolicy().subscribe();
    effect(() => {
      this.search();
      this.roleFilter();
      this.pageIndex();
      this.load();
    });
  }

  protected passwordIssues(): string[] {
    return policyViolations(this.form.password(), this.auth.passwordPolicy());
  }

  protected load(): void {
    this.loading.set(true);
    this.api
      .list({ search: this.search(), role: this.roleFilter(), page: this.pageIndex(), size: 20 })
      .subscribe({
        next: (page) => {
          this.page.set(page);
          this.loading.set(false);
        },
        error: () => this.loading.set(false),
      });
  }

  protected onSearch(value: string): void {
    if (this.searchTimer) {
      clearTimeout(this.searchTimer);
    }
    this.searchTimer = setTimeout(() => {
      this.pageIndex.set(0);
      this.search.set(value);
    }, 250);
  }

  protected setRole(role: Role | null): void {
    this.pageIndex.set(0);
    this.roleFilter.set(role);
  }

  protected goTo(index: number): void {
    this.pageIndex.set(Math.max(0, index));
  }

  protected displayName(user: UserProfile): string {
    const full = `${user.firstName ?? ''} ${user.lastName ?? ''}`.trim();
    return full.length > 0 ? full : user.username;
  }

  /** The console disables self-destructive actions; the server refuses them regardless. */
  protected isSelf(user: UserProfile): boolean {
    return this.state.user()?.id === user.id;
  }

  protected openCreate(): void {
    this.form.username.set('');
    this.form.email.set('');
    this.form.password.set('');
    this.form.firstName.set('');
    this.form.lastName.set('');
    this.form.roles.set(['USER']);
    this.dialog.set('create');
  }

  protected toggleFormRole(role: Role, checked: boolean): void {
    this.form.roles.update((roles) =>
      checked ? [...new Set([...roles, role])] : roles.filter((r) => r !== role),
    );
  }

  protected canCreate(): boolean {
    return (
      !this.busy() &&
      this.form.username().trim().length >= 3 &&
      this.form.email().trim().length > 0 &&
      this.form.password().length > 0 &&
      this.passwordIssues().length === 0 &&
      this.form.roles().length > 0
    );
  }

  protected create(): void {
    this.busy.set(true);
    this.api
      .create({
        username: this.form.username().trim(),
        email: this.form.email().trim(),
        password: this.form.password(),
        firstName: this.form.firstName().trim() || null,
        lastName: this.form.lastName().trim() || null,
        roles: this.form.roles(),
        enabled: true,
      })
      .subscribe({
        next: (user) => {
          this.busy.set(false);
          // Clear the plaintext as soon as it has been sent.
          this.form.password.set('');
          this.dialog.set('none');
          this.notifications.success(`Created ${user.username}`);
          this.load();
        },
        error: () => {
          this.busy.set(false);
          this.form.password.set('');
        },
      });
  }

  protected openRoles(user: UserProfile): void {
    this.selected.set(user);
    this.draftRoles.set([...user.roles]);
    this.dialog.set('roles');
  }

  /** Offers the assignable roles plus any the user already holds, so an unusual role is not silently lost. */
  protected allRolesFor(user: UserProfile): Role[] {
    return [...new Set([...ASSIGNABLE_ROLES, ...user.roles])];
  }

  protected toggleDraftRole(role: Role, checked: boolean): void {
    this.draftRoles.update((roles) =>
      checked ? [...new Set([...roles, role])] : roles.filter((r) => r !== role),
    );
  }

  protected canSaveRoles(user: UserProfile): boolean {
    if (this.busy() || this.draftRoles().length === 0) {
      return false;
    }
    // Mirrors the server's refusal so the button explains itself rather than producing a 409.
    if (this.isSelf(user) && !this.draftRoles().includes('ADMIN')) {
      return false;
    }
    return true;
  }

  protected saveRoles(user: UserProfile): void {
    this.busy.set(true);
    this.api.setRoles(user.id, this.draftRoles()).subscribe({
      next: (updated) => {
        this.busy.set(false);
        this.dialog.set('none');
        this.notifications.success(
          `Roles updated for ${updated.username}`,
          'Their sessions were signed out, so the change applies from their next sign-in.',
        );
        this.load();
      },
      error: () => this.busy.set(false),
    });
  }

  protected setEnabled(user: UserProfile, enabled: boolean): void {
    if (enabled) {
      this.doSetEnabled(user, true);
      return;
    }
    this.pendingConfirm.set({
      heading: 'Disable user?',
      message:
        `Disable ${user.username}?\n\nEvery session is revoked immediately and they cannot sign in. ` +
        'The account and its history are kept, so this is reversible.',
      confirmLabel: 'Disable',
      danger: true,
      onConfirm: () => this.doSetEnabled(user, false),
    });
  }

  private doSetEnabled(user: UserProfile, enabled: boolean): void {
    this.busy.set(true);
    this.api.setStatus(user.id, enabled).subscribe({
      next: () => {
        this.busy.set(false);
        this.notifications.success(`${user.username} ${enabled ? 'enabled' : 'disabled'}`);
        this.load();
      },
      error: () => this.busy.set(false),
    });
  }

  protected setLocked(user: UserProfile, locked: boolean): void {
    this.busy.set(true);
    const call = locked ? this.api.lock(user.id) : this.api.unlock(user.id);
    call.subscribe({
      next: () => {
        this.busy.set(false);
        this.notifications.success(`${user.username} ${locked ? 'locked' : 'unlocked'}`);
        this.load();
      },
      error: () => this.busy.set(false),
    });
  }

  protected remove(user: UserProfile): void {
    this.pendingConfirm.set({
      heading: 'Delete user?',
      message:
        `Delete ${user.username}?\n\nThis cannot be undone. Disabling the account keeps its history and ` +
        'removes access just as effectively, so prefer that unless the account was created in error.',
      confirmLabel: 'Delete',
      danger: true,
      onConfirm: () => this.doRemove(user),
    });
  }

  private doRemove(user: UserProfile): void {
    this.busy.set(true);
    this.api.delete(user.id).subscribe({
      next: () => {
        this.busy.set(false);
        this.notifications.success(`Deleted ${user.username}`);
        this.load();
      },
      error: () => this.busy.set(false),
    });
  }

  /** Runs the pending confirmed action, then clears the dialog. */
  protected runConfirmed(): void {
    const request = this.pendingConfirm();
    this.pendingConfirm.set(null);
    request?.onConfirm();
  }

  protected openAudit(): void {
    this.dialog.set('audit');
    this.auditRecords.set([]);
    this.api.audit({ size: 60 }).subscribe({
      next: (page) => this.auditRecords.set(page.content),
    });
  }
}
