import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import {
  AdminApiService,
  RegistryPermission,
  RegistryRole,
} from '../../core/admin-api.service';
import { AuthService } from '../../core/auth/auth.service';
import { NotificationService } from '../../core/notification.service';
import { EmptyState } from '../../shared/empty-state';
import { Modal } from '../../shared/modal';

/**
 * Roles, and the permissions they grant.
 *
 * <h2>The editor can only offer real permissions</h2>
 *
 * The checkboxes are built from `/api/permissions`, which the registry serves from the enum its own checks
 * compare against. A permission cannot be typed in, because one that no check mentions would grant nothing
 * however convincing it looked in this screen.
 *
 * <h2>Editing a system role is allowed; deleting one is not</h2>
 *
 * An installation that wants its managers to also read the audit trail should say so by ticking a box, not by
 * cloning a role and maintaining the copy. Deleting the role every account depends on is different: it locks
 * everybody out of a running registry, so the registry refuses and this screen does not offer it.
 */
@Component({
  selector: 'ps-role-admin',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [EmptyState, Modal],
  template: `
    <div class="page">
      <header class="page-header">
        <div class="page-header__text">
          <h1>Roles</h1>
          <p>
            A role is a named set of permissions. Accounts hold roles; what a role grants is resolved when
            somebody signs in, so changing a role changes what every holder can do without touching a single
            account.
          </p>
        </div>
        @if (auth.hasPermission('ROLE_CREATE')) {
          <div class="toolbar">
            <button class="btn btn--primary" type="button" (click)="openCreate()">Create role</button>
          </div>
        }
      </header>

      @if (loading()) {
        <div class="card"><p class="pad small muted">Loading roles…</p></div>
      } @else if (roles().length === 0) {
        <div class="card"><ps-empty-state heading="No roles" message="Nothing to show." /></div>
      } @else {
        <div class="stack">
          @for (role of roles(); track role.id) {
            <div class="card role">
              <div class="card__header">
                <h3 class="mono">{{ role.name }}</h3>
                @if (role.systemRole) {
                  <span class="tag" title="Shipped with the registry. Editable, but not deletable.">
                    system
                  </span>
                }
                <span class="spacer"></span>
                <span class="small muted">
                  {{ role.permissions.length }} permission(s) · {{ role.userCount }} account(s)
                </span>
                @if (auth.hasPermission('ROLE_UPDATE')) {
                  <button class="btn btn--sm" type="button" (click)="openEdit(role)">Edit</button>
                }
                @if (auth.hasPermission('ROLE_DELETE') && !role.systemRole) {
                  <button class="btn btn--danger btn--sm" type="button" (click)="remove(role)">
                    Delete
                  </button>
                }
              </div>
              @if (role.description) {
                <p class="description">{{ role.description }}</p>
              }
              <div class="permissions">
                @for (permission of role.permissions; track permission) {
                  <span class="tag tag--mono">{{ permission }}</span>
                }
              </div>
            </div>
          }
        </div>
      }
    </div>

    @if (editing(); as role) {
      <ps-modal
        [heading]="role.id ? 'Edit ' + role.name : 'Create a role'"
        subheading="Only permissions this registry implements can be granted."
        width="720px"
        (closed)="editing.set(null)"
      >
        <div class="grid-2">
          <div class="field">
            <label class="field__label" for="role-name">Name</label>
            <input
              id="role-name"
              type="text"
              class="mono"
              [value]="draftName()"
              [disabled]="!!role.id"
              (input)="draftName.set($any($event.target).value)"
            />
            @if (role.id) {
              <p class="field__hint">
                A role's name is what accounts and tokens refer to, so it cannot be changed here.
              </p>
            }
          </div>
          <div class="field">
            <label class="field__label" for="role-description">Description</label>
            <input
              id="role-description"
              type="text"
              [value]="draftDescription()"
              (input)="draftDescription.set($any($event.target).value)"
            />
          </div>
        </div>

        @for (group of groupedPermissions(); track group.key) {
          <fieldset class="group">
            <legend class="field__label">{{ group.label }}</legend>
            @for (permission of group.permissions; track permission.name) {
              <label class="checkbox-row">
                <input
                  type="checkbox"
                  [checked]="draftPermissions().has(permission.name)"
                  (change)="togglePermission(permission.name, $any($event.target).checked)"
                />
                <span>
                  <strong class="mono">{{ permission.name }}</strong>
                  <span class="small muted"> — {{ permission.description }}</span>
                </span>
              </label>
            }
          </fieldset>
        }

        <div modalFooter>
          <button class="btn" type="button" (click)="editing.set(null)">Cancel</button>
          <button class="btn btn--primary" type="button" [disabled]="busy()" (click)="save()">
            {{ role.id ? 'Save' : 'Create' }}
          </button>
        </div>
      </ps-modal>
    }
  `,
  styles: [
    `
      .pad {
        padding: var(--space-4);
      }

      .description {
        margin: 0;
        padding: 0 var(--space-4) var(--space-2);
        color: var(--text-muted);
      }

      .permissions {
        display: flex;
        flex-wrap: wrap;
        gap: 4px;
        padding: 0 var(--space-4) var(--space-4);
      }

      .group {
        border: 1px solid var(--border);
        border-radius: var(--radius);
        padding: var(--space-3);
        margin-top: var(--space-3);
      }

      .group legend {
        padding: 0 var(--space-2);
      }

      .checkbox-row {
        display: flex;
        gap: var(--space-2);
        align-items: baseline;
        padding: 3px 0;
      }
    `,
  ],
})
export class RoleAdmin {
  protected readonly auth = inject(AuthService);

  private readonly api = inject(AdminApiService);
  private readonly notifications = inject(NotificationService);

  protected readonly roles = signal<RegistryRole[]>([]);
  protected readonly permissions = signal<RegistryPermission[]>([]);
  protected readonly loading = signal(true);
  protected readonly busy = signal(false);

  /** The role being edited. An id of '' means a new one. */
  protected readonly editing = signal<{ id: string; name: string } | null>(null);
  protected readonly draftName = signal('');
  protected readonly draftDescription = signal('');
  protected readonly draftPermissions = signal<ReadonlySet<string>>(new Set());

  /** Grouped as the registry grouped them, so the editor reads as sections rather than a wall. */
  protected readonly groupedPermissions = computed(() => {
    const groups = new Map<string, { key: string; label: string; permissions: RegistryPermission[] }>();
    for (const permission of this.permissions()) {
      const existing = groups.get(permission.group) ?? {
        key: permission.group,
        label: permission.groupLabel,
        permissions: [],
      };
      existing.permissions.push(permission);
      groups.set(permission.group, existing);
    }
    return [...groups.values()];
  });

  constructor() {
    this.load();
    this.api.listPermissions().subscribe({ next: (list) => this.permissions.set(list) });
  }

  private load(): void {
    this.loading.set(true);
    this.api.listRoles().subscribe({
      next: (roles) => {
        this.roles.set(roles);
        this.loading.set(false);
      },
      error: () => this.loading.set(false),
    });
  }

  protected openCreate(): void {
    this.draftName.set('');
    this.draftDescription.set('');
    this.draftPermissions.set(new Set());
    this.editing.set({ id: '', name: '' });
  }

  protected openEdit(role: RegistryRole): void {
    this.draftName.set(role.name);
    this.draftDescription.set(role.description ?? '');
    this.draftPermissions.set(new Set(role.permissions));
    this.editing.set({ id: role.id, name: role.name });
  }

  protected togglePermission(permission: string, checked: boolean): void {
    const next = new Set(this.draftPermissions());
    if (checked) {
      next.add(permission);
    } else {
      next.delete(permission);
    }
    this.draftPermissions.set(next);
  }

  protected save(): void {
    const role = this.editing();
    if (!role) {
      return;
    }
    this.busy.set(true);
    const permissions = [...this.draftPermissions()];
    const call = role.id
      ? this.api.updateRole(role.id, this.draftName(), this.draftDescription(), permissions)
      : this.api.createRole(this.draftName(), this.draftDescription(), permissions);

    call.subscribe({
      next: (saved) => {
        this.busy.set(false);
        this.editing.set(null);
        this.notifications.success(`${saved.name} saved`, `${saved.permissions.length} permission(s).`);
        this.load();
      },
      error: () => this.busy.set(false),
    });
  }

  protected remove(role: RegistryRole): void {
    // A role with holders is worth a second thought, and the count is already on screen beside the button.
    const holders = role.userCount > 0 ? ` ${role.userCount} account(s) hold it and will lose it.` : '';
    if (!confirm(`Delete the role ${role.name}?${holders}`)) {
      return;
    }
    this.api.deleteRole(role.id).subscribe({
      next: () => {
        this.notifications.success(`${role.name} deleted`);
        this.load();
      },
    });
  }
}
