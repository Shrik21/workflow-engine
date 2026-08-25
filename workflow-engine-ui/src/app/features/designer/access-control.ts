import { ChangeDetectionStrategy, Component, computed, effect, inject, input, signal } from '@angular/core';
import { GroupApiService } from '../../core/api/group-api.service';
import { GroupSummary } from '../../core/models/group.models';
import { NotificationService } from '../../core/notification.service';

/**
 * Which groups a workflow is shared with.
 *
 * Saves through `PUT /api/workflows/{id}/access` rather than as part of the workflow definition, matching the
 * server: sharing has different authorization from editing, requiring ownership or ADMIN so that someone
 * granted edit through a group cannot attach further groups and widen their own access. Keeping it on its own
 * endpoint means saving the canvas cannot accidentally change who can see it.
 *
 * The panel shows what each group grants but offers no way to change it here. Permissions belong to the group,
 * not to the workflow; a workflow only answers "which groups may reach this". Editing them per workflow would
 * make a group mean something different in each place it is used.
 */
@Component({
  selector: 'wf-access-control',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="access">
      <p class="small muted">
        Members of an attached group get that group's permissions on this workflow. With no groups attached,
        only you and an administrator can reach it.
      </p>

      @if (!canManage()) {
        <div class="notice notice--warning">
          Only the workflow's owner or an administrator can change sharing. You can see the current groups
          but not edit them.
        </div>
      }

      @if (unresolved().length > 0) {
        <div class="notice notice--warning">
          {{ unresolved().length }} attached group(s) no longer exist and grant nothing. Save to clear them.
        </div>
      }

      <div class="field">
        <label class="field__label" for="group-picker">Add a group</label>
        <div class="row">
          <select
            id="group-picker"
            [disabled]="!canManage() || addable().length === 0"
            [value]="picked()"
            (change)="picked.set($any($event.target).value)"
          >
            <option value="">
              {{ addable().length === 0 ? 'No further groups available' : 'Choose a group…' }}
            </option>
            @for (group of addable(); track group.id) {
              <option [value]="group.id">{{ group.name }}</option>
            }
          </select>
          <button
            class="btn btn--primary"
            type="button"
            [disabled]="!canManage() || !picked()"
            (click)="add()"
          >
            Add
          </button>
        </div>
      </div>

      @if (attached().length === 0) {
        <p class="small muted">
          No access groups configured. This workflow is accessible only to its owner and to administrators.
        </p>
      } @else {
        <div class="attached">
          @for (group of attached(); track group.id) {
            <div class="attached__item">
              <div class="attached__head">
                <strong>{{ group.name }}</strong>
                @if (!group.enabled) {
                  <span class="tag" title="A disabled group grants nothing">disabled</span>
                }
                <span class="spacer"></span>
                <button
                  class="btn btn--quiet btn--sm"
                  type="button"
                  [disabled]="!canManage()"
                  [attr.aria-label]="'Remove ' + group.name"
                  (click)="remove(group)"
                >
                  Remove
                </button>
              </div>
              @if (group.description) {
                <p class="small muted">{{ group.description }}</p>
              }
            </div>
          }
        </div>
      }

      @if (dirty()) {
        <div class="access__save">
          <button class="btn btn--primary" type="button" [disabled]="busy()" (click)="save()">
            {{ busy() ? 'Saving…' : 'Save access' }}
          </button>
          <button class="btn" type="button" [disabled]="busy()" (click)="reload()">Discard</button>
          <span class="small muted">Sharing is saved separately from the workflow definition.</span>
        </div>
      }
    </div>
  `,
  styles: [
    `
      .access {
        display: block;
      }

      .row select {
        flex: 1;
      }

      .attached__item {
        border: 1px solid var(--border);
        border-left: 3px solid var(--hl-accent-blue);
        border-radius: var(--radius-sm);
        padding: var(--space-2) var(--space-3);
        margin-bottom: var(--space-2);
      }

      .attached__head {
        display: flex;
        align-items: center;
        gap: var(--space-2);
      }

      .attached__item p {
        margin: var(--space-1) 0 0;
      }

      .access__save {
        display: flex;
        align-items: center;
        gap: var(--space-2);
        margin-top: var(--space-3);
        padding-top: var(--space-3);
        border-top: 1px solid var(--border);
      }
    `,
  ],
})
export class AccessControl {
  /** The workflow being shared. Null for a draft that has not been saved yet. */
  readonly workflowId = input<string | null>(null);

  private readonly api = inject(GroupApiService);
  private readonly notifications = inject(NotificationService);

  private readonly all = signal<GroupSummary[]>([]);
  private readonly saved = signal<string[]>([]);

  protected readonly attachedIds = signal<string[]>([]);
  protected readonly unresolved = signal<string[]>([]);
  protected readonly picked = signal('');
  protected readonly busy = signal(false);
  protected readonly canManage = signal(true);

  protected readonly attached = computed(() => {
    const known = new Map(this.all().map((group) => [group.id, group]));
    return this.attachedIds()
      .map((id) => known.get(id))
      .filter((group): group is GroupSummary => !!group);
  });

  protected readonly addable = computed(() => {
    const chosen = new Set(this.attachedIds());
    return this.all().filter((group) => !chosen.has(group.id) && group.enabled);
  });

  protected readonly dirty = computed(() => {
    const before = [...this.saved()].sort().join(',');
    const now = [...this.attachedIds()].sort().join(',');
    return before !== now;
  });

  constructor() {
    // The picker feed is open to any authenticated user, so this works for a workflow owner who is not an
    // administrator: sharing what you own means being able to see group names.
    this.api.available().subscribe({ next: (groups) => this.all.set(groups) });

    effect(() => {
      const id = this.workflowId();
      if (id) {
        this.loadAccess(id);
      }
    });
  }

  protected reload(): void {
    const id = this.workflowId();
    if (id) {
      this.loadAccess(id);
    }
  }

  protected add(): void {
    const id = this.picked();
    if (!id) {
      return;
    }
    this.attachedIds.update((current) => [...new Set([...current, id])]);
    this.picked.set('');
  }

  protected remove(group: GroupSummary): void {
    this.attachedIds.update((current) => current.filter((id) => id !== group.id));
  }

  protected save(): void {
    const id = this.workflowId();
    if (!id) {
      return;
    }
    this.busy.set(true);
    this.api.setWorkflowAccess(id, this.attachedIds()).subscribe({
      next: (access) => {
        this.busy.set(false);
        this.applyAccess(access.groups.map((group) => group.id), access.unresolvedGroupIds);
        this.notifications.success(
          'Access updated',
          access.groups.length === 0
            ? 'No groups attached: only you and an administrator can reach this workflow.'
            : `Shared with ${access.groups.map((group) => group.name).join(', ')}.`,
        );
      },
      error: () => this.busy.set(false),
    });
  }

  private loadAccess(workflowId: string): void {
    this.api.workflowAccess(workflowId).subscribe({
      next: (access) =>
        this.applyAccess(access.groups.map((group) => group.id), access.unresolvedGroupIds),
      // A 403 here means the caller can view the workflow but not its sharing. Render read-only rather
      // than an error: they are allowed to be on this screen.
      error: () => this.canManage.set(false),
    });

    this.api.myPermissions(workflowId).subscribe({
      next: (mine) => this.canManage.set(mine.owner || mine.admin),
      error: () => this.canManage.set(false),
    });
  }

  private applyAccess(ids: string[], unresolved: string[]): void {
    this.attachedIds.set(ids);
    this.saved.set(ids);
    this.unresolved.set(unresolved);
  }
}
