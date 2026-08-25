import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { GroupApiService } from '../../core/api/group-api.service';
import { UserAdminApiService } from '../../core/api/user-admin-api.service';
import { Group, GroupMember, WorkflowPermission } from '../../core/models/group.models';
import { UserProfile } from '../../core/auth/auth.models';
import { NotificationService } from '../../core/notification.service';
import { AgoPipe } from '../../shared/pipes/format.pipes';
import { EmptyState } from '../../shared/ui/empty-state';
import { Modal } from '../../shared/ui/modal';
import { ConfirmDialog, ConfirmRequest } from '../../shared/ui/confirm-dialog';
import { StatusPill } from '../../shared/ui/status-pill';

type Dialog = 'none' | 'editor' | 'members';

/**
 * Group administration: the list, the editor, and membership.
 *
 * One screen with dialogs rather than four routes. Creating a group, choosing its permissions and adding
 * members are one task performed in one sitting, and splitting them across pages would mean navigating away
 * from a group you just created to make it useful.
 *
 * Permission checkboxes are rendered from the server's catalogue, so a permission added to the backend enum
 * appears here with no change to this file.
 */
import { Icon } from '../../shared/ui/icon';
@Component({
  selector: 'wf-group-admin',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [Icon, StatusPill, EmptyState, Modal, ConfirmDialog, AgoPipe],
  template: `
    <div class="page">
      <div class="page-header">
        <div class="page-header__text">
          <h1>Groups</h1>
          <p>
            A group is a set of workflow permissions plus the people who hold them. Attaching a group to a
            workflow is what grants its members access to that workflow, and nothing else does.
          </p>
        </div>
        <div class="toolbar">
          <button class="btn btn--sm" type="button" (click)="load()"><wf-icon name="refresh" /><span>Refresh</span></button>
          <button class="btn btn--primary" type="button" (click)="openCreate()">Create group</button>
        </div>
      </div>

      <div class="card">
        <div class="card__header">
          <input
            type="search"
            style="max-width: 280px"
            placeholder="Search groups"
            aria-label="Search groups"
            [value]="search()"
            (input)="onSearch($any($event.target).value)"
          />
          <span class="spacer"></span>
          <span class="small muted">{{ groups().length }} group(s)</span>
        </div>

        @if (groups().length === 0 && !loading()) {
          <wf-empty-state
            heading="No groups yet"
            message="Create one, choose what it may do, add members, then attach it to a workflow from the designer's Access Control panel."
          >
            <button class="btn btn--primary" type="button" (click)="openCreate()">Create group</button>
          </wf-empty-state>
        } @else {
          <table class="table">
            <thead>
              <tr>
                <th>Group</th>
                <th>Members</th>
                <th>Permissions</th>
                <th>Status</th>
                <th>Created</th>
                <th class="cell-actions">Actions</th>
              </tr>
            </thead>
            <tbody>
              @for (group of groups(); track group.id) {
                <tr>
                  <td>
                    <strong>{{ group.name }}</strong>
                    @if (group.description) {
                      <div class="small muted truncate" style="max-width: 46ch">
                        {{ group.description }}
                      </div>
                    }
                  </td>
                  <td>
                    <button class="btn btn--quiet btn--sm" type="button" (click)="openMembers(group)">
                      {{ group.memberCount }}
                    </button>
                  </td>
                  <td>
                    @if (group.permissions.length === 0) {
                      <span class="faint small">grants nothing yet</span>
                    } @else {
                      <span class="tag" [title]="group.permissions.join(', ')">
                        {{ group.permissions.length }} permission(s)
                      </span>
                    }
                  </td>
                  <td><wf-status-pill [status]="group.enabled ? 'ACTIVE' : 'INACTIVE'" /></td>
                  <td class="small muted">{{ group.createdAt | ago }}</td>
                  <td class="cell-actions">
                    <button class="btn btn--sm" type="button" (click)="openEdit(group)">Edit</button>
                    <button class="btn btn--sm" type="button" (click)="openMembers(group)">Members</button>
                    @if (group.enabled) {
                      <button
                        class="btn btn--sm"
                        type="button"
                        title="Revokes everything this group grants, without deleting it or losing its members"
                        (click)="setEnabled(group, false)"
                      >
                        Disable
                      </button>
                    } @else {
                      <button class="btn btn--sm" type="button" (click)="setEnabled(group, true)">
                        Enable
                      </button>
                    }
                    <button class="btn btn--danger btn--sm" type="button" (click)="remove(group)">
                      Delete
                    </button>
                  </td>
                </tr>
              }
            </tbody>
          </table>
        }
      </div>
    </div>

    @if (dialog() === 'editor') {
      <wf-modal
        [heading]="editing() ? 'Edit ' + form.name() : 'Create a group'"
        subheading="Permissions apply to every workflow this group is attached to. Changes take effect immediately for every member."
        width="720px"
        (closed)="dialog.set('none')"
      >
        <div class="grid-2">
          <div class="field">
            <label class="field__label" for="group-name">Name</label>
            <input
              id="group-name"
              type="text"
              [value]="form.name()"
              (input)="form.name.set($any($event.target).value)"
            />
          </div>
          <div class="field">
            <label class="field__label" for="group-status">Status</label>
            <select
              id="group-status"
              [value]="form.enabled() ? 'true' : 'false'"
              (change)="form.enabled.set($any($event.target).value === 'true')"
            >
              <option value="true">Active</option>
              <option value="false">Disabled</option>
            </select>
          </div>
        </div>

        <div class="field">
          <label class="field__label" for="group-description">Description</label>
          <input
            id="group-description"
            type="text"
            placeholder="Who is in this group and why"
            [value]="form.description()"
            (input)="form.description.set($any($event.target).value)"
          />
        </div>

        <div class="divider"></div>

        <span class="field__label">Permissions</span>
        <p class="field__hint">
          Rendered from the server's catalogue, so this list is always what the engine actually enforces.
        </p>

        @for (category of categories(); track category) {
          <fieldset class="perms">
            <legend>{{ category }}</legend>
            <div class="perms__grid">
              @for (permission of catalogue()[category]; track permission.name) {
                <label class="checkbox-row">
                  <input
                    type="checkbox"
                    [checked]="form.permissions().includes(permission.name)"
                    (change)="togglePermission(permission.name, $any($event.target).checked)"
                  />
                  <span>{{ permission.label }}</span>
                </label>
              }
            </div>
          </fieldset>
        }

        @if (form.permissions().length === 0) {
          <p class="field__hint">
            A group with no permissions grants nothing. That is a valid starting point; attach it to
            workflows and add permissions later.
          </p>
        }

        <div modalFooter>
          <button class="btn" type="button" (click)="dialog.set('none')">Cancel</button>
          <button class="btn btn--primary" type="button" [disabled]="!canSave()" (click)="save()">
            {{ editing() ? 'Save changes' : 'Create group' }}
          </button>
        </div>
      </wf-modal>
    }

    @if (dialog() === 'members' && selected(); as group) {
      <wf-modal
        [heading]="'Members of ' + group.name"
        subheading="Adding someone grants them this group's permissions on every workflow it is attached to, from their next request."
        width="720px"
        (closed)="dialog.set('none')"
      >
        <div class="field">
          <label class="field__label" for="add-member">Add a user</label>
          <div class="row">
            <select
              id="add-member"
              [value]="memberToAdd()"
              (change)="memberToAdd.set($any($event.target).value)"
            >
              <option value="">Choose a user…</option>
              @for (candidate of addableUsers(); track candidate.id) {
                <option [value]="candidate.id">
                  {{ candidate.username }} ({{ candidate.email }})
                </option>
              }
            </select>
            <button
              class="btn btn--primary"
              type="button"
              [disabled]="!memberToAdd() || busy()"
              (click)="addMember(group)"
            >
              Add
            </button>
          </div>
          @if (addableUsers().length === 0) {
            <p class="field__hint">Every account is already a member of this group.</p>
          }
        </div>

        <div class="divider"></div>

        @if (members().length === 0) {
          <p class="small muted">No members yet. This group grants nothing until someone is in it.</p>
        } @else {
          <table class="table">
            <thead>
              <tr>
                <th>Name</th>
                <th>Username</th>
                <th>Status</th>
                <th>Joined</th>
                <th class="cell-actions"></th>
              </tr>
            </thead>
            <tbody>
              @for (member of members(); track member.userId) {
                <tr>
                  <td>{{ member.displayName }}</td>
                  <td class="mono small">{{ member.username }}</td>
                  <td><wf-status-pill [status]="member.enabled ? 'ACTIVE' : 'INACTIVE'" /></td>
                  <td class="small muted" [title]="member.addedBy ?? ''">{{ member.joinedAt | ago }}</td>
                  <td class="cell-actions">
                    <button
                      class="btn btn--danger btn--sm"
                      type="button"
                      [disabled]="busy()"
                      (click)="removeMember(group, member)"
                    >
                      Remove
                    </button>
                  </td>
                </tr>
              }
            </tbody>
          </table>
        }

        <div modalFooter>
          <button class="btn btn--primary" type="button" (click)="dialog.set('none')">Done</button>
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
      .perms {
        border: 1px solid var(--border);
        border-radius: var(--radius-sm);
        padding: var(--space-2) var(--space-3) var(--space-3);
        margin-bottom: var(--space-3);
      }

      .perms legend {
        font-size: var(--text-xs);
        text-transform: uppercase;
        letter-spacing: 0.6px;
        color: var(--text-muted);
        padding: 0 var(--space-2);
      }

      .perms__grid {
        display: grid;
        grid-template-columns: repeat(auto-fit, minmax(210px, 1fr));
        gap: var(--space-2);
      }

      .row select {
        flex: 1;
      }
    `,
  ],
})
export class GroupAdmin {
  private readonly api = inject(GroupApiService);
  private readonly userApi = inject(UserAdminApiService);
  private readonly notifications = inject(NotificationService);

  protected readonly groups = signal<Group[]>([]);
  protected readonly members = signal<GroupMember[]>([]);
  protected readonly allUsers = signal<UserProfile[]>([]);
  protected readonly selected = signal<Group | null>(null);
  protected readonly dialog = signal<Dialog>('none');
  protected readonly editing = signal(false);
  protected readonly loading = signal(false);
  protected readonly pendingConfirm = signal<ConfirmRequest | null>(null);
  protected readonly busy = signal(false);
  protected readonly search = signal('');
  protected readonly memberToAdd = signal('');

  protected readonly catalogue = this.api.catalogue;
  protected readonly categories = computed(() => Object.keys(this.catalogue()));

  protected readonly form = {
    name: signal(''),
    description: signal(''),
    enabled: signal(true),
    permissions: signal<WorkflowPermission[]>([]),
  };

  /** Users not already in the selected group, so the picker cannot offer a duplicate. */
  protected readonly addableUsers = computed(() => {
    const existing = new Set(this.members().map((member) => member.userId));
    return this.allUsers().filter((user) => !existing.has(user.id));
  });

  private searchTimer: ReturnType<typeof setTimeout> | null = null;

  constructor() {
    this.api.loadCatalogue().subscribe();
    this.load();
  }

  protected load(): void {
    this.loading.set(true);
    this.api.list(this.search()).subscribe({
      next: (groups) => {
        this.groups.set(groups);
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
      this.search.set(value);
      this.load();
    }, 250);
  }

  protected openCreate(): void {
    this.editing.set(false);
    this.selected.set(null);
    this.form.name.set('');
    this.form.description.set('');
    this.form.enabled.set(true);
    this.form.permissions.set([]);
    this.dialog.set('editor');
  }

  protected openEdit(group: Group): void {
    this.editing.set(true);
    this.selected.set(group);
    this.form.name.set(group.name);
    this.form.description.set(group.description ?? '');
    this.form.enabled.set(group.enabled);
    this.form.permissions.set([...group.permissions]);
    this.dialog.set('editor');
  }

  protected togglePermission(permission: WorkflowPermission, checked: boolean): void {
    this.form.permissions.update((current) =>
      checked ? [...new Set([...current, permission])] : current.filter((p) => p !== permission),
    );
  }

  protected canSave(): boolean {
    return !this.busy() && this.form.name().trim().length >= 2;
  }

  /**
   * Saves the group.
   *
   * Creating sends the permissions with the group; editing sends them separately, because the server keeps
   * granting capability as its own endpoint so that it is separately authorised and separately audited from
   * renaming.
   */
  protected save(): void {
    this.busy.set(true);
    const current = this.selected();

    if (!this.editing() || !current) {
      this.api
        .create({
          name: this.form.name().trim(),
          description: this.form.description().trim() || null,
          permissions: this.form.permissions(),
          enabled: this.form.enabled(),
        })
        .subscribe({
          next: (group) => {
            this.busy.set(false);
            this.dialog.set('none');
            this.notifications.success(
              `Created "${group.name}"`,
              'Add members, then attach it to a workflow from the designer.',
            );
            this.load();
          },
          error: () => this.busy.set(false),
        });
      return;
    }

    this.api
      .update(current.id, {
        name: this.form.name().trim(),
        description: this.form.description().trim() || null,
        enabled: this.form.enabled(),
      })
      .subscribe({
        next: () =>
          this.api.setPermissions(current.id, this.form.permissions()).subscribe({
            next: (group) => {
              this.busy.set(false);
              this.dialog.set('none');
              this.notifications.success(
                `Updated "${group.name}"`,
                `${group.memberCount} member(s) are affected immediately.`,
              );
              this.load();
            },
            error: () => this.busy.set(false),
          }),
        error: () => this.busy.set(false),
      });
  }

  protected setEnabled(group: Group, enabled: boolean): void {
    if (enabled) {
      this.doSetEnabled(group, true);
      return;
    }
    this.pendingConfirm.set({
      heading: 'Disable group?',
      message:
        `Disable "${group.name}"?\n\nIts ${group.memberCount} member(s) immediately lose the access it ` +
        'grants on every workflow it is attached to. The group and its membership are kept, so this is ' +
        'reversible.',
      confirmLabel: 'Disable',
      danger: true,
      onConfirm: () => this.doSetEnabled(group, false),
    });
  }

  private doSetEnabled(group: Group, enabled: boolean): void {
    this.busy.set(true);
    this.api.update(group.id, { enabled }).subscribe({
      next: () => {
        this.busy.set(false);
        this.notifications.success(`"${group.name}" ${enabled ? 'enabled' : 'disabled'}`);
        this.load();
      },
      error: () => this.busy.set(false),
    });
  }

  protected remove(group: Group): void {
    this.pendingConfirm.set({
      heading: 'Delete group?',
      message:
        `Delete "${group.name}"?\n\nThis removes its ${group.memberCount} membership(s) and detaches it ` +
        'from every workflow. Disabling it instead removes access just as effectively and can be undone.',
      confirmLabel: 'Delete',
      danger: true,
      onConfirm: () => this.doRemove(group),
    });
  }

  private doRemove(group: Group): void {
    this.busy.set(true);
    this.api.delete(group.id).subscribe({
      next: () => {
        this.busy.set(false);
        this.notifications.success(`Deleted "${group.name}"`);
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

  protected openMembers(group: Group): void {
    this.selected.set(group);
    this.members.set([]);
    this.memberToAdd.set('');
    this.dialog.set('members');

    this.api.members(group.id).subscribe({ next: (members) => this.members.set(members) });
    // Loaded here rather than at construction: the picker is the only thing that needs the user list, and
    // an administrator may never open this dialog.
    this.userApi.list({ size: 100 }).subscribe({ next: (page) => this.allUsers.set(page.content) });
  }

  protected addMember(group: Group): void {
    const userId = this.memberToAdd();
    if (!userId) {
      return;
    }
    this.busy.set(true);
    this.api.addMember(group.id, userId).subscribe({
      next: () => {
        this.busy.set(false);
        this.memberToAdd.set('');
        this.api.members(group.id).subscribe({ next: (members) => this.members.set(members) });
        this.load();
      },
      error: () => this.busy.set(false),
    });
  }

  protected removeMember(group: Group, member: GroupMember): void {
    this.busy.set(true);
    this.api.removeMember(group.id, member.userId).subscribe({
      next: () => {
        this.busy.set(false);
        this.members.update((current) => current.filter((m) => m.userId !== member.userId));
        this.notifications.success(
          `Removed ${member.username}`,
          'They lose this group’s access on their next request.',
        );
        this.load();
      },
      error: () => this.busy.set(false),
    });
  }
}
