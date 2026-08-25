import { ChangeDetectionStrategy, Component, effect, inject, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { FormApiService } from '../../core/api/form-api.service';
import { Page, emptyPage } from '../../core/models/api.models';
import { FormStatus, FormSummary } from '../../core/models/form.models';
import { NotificationService } from '../../core/notification.service';
import { AgoPipe } from '../../shared/pipes/format.pipes';
import { EmptyState } from '../../shared/ui/empty-state';
import { StatusPill } from '../../shared/ui/status-pill';
import { ConfirmDialog, ConfirmRequest } from '../../shared/ui/confirm-dialog';

/**
 * The form inventory.
 *
 * <p>Shows the published version alongside the status for the same reason the workflow list does: a form
 * edited after publishing is DRAFT while its published version is still the one workflow nodes reference and
 * tasks render. Collapsing the two into one badge hides exactly the thing that confuses people.
 */
@Component({
  selector: 'wf-form-list',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [RouterLink, StatusPill, ConfirmDialog, EmptyState, AgoPipe],
  template: `
    <div class="page">
      <div class="page-header">
        <div class="page-header__text">
          <h1>Forms</h1>
          <p>
            Forms are designed once and referenced by workflow form nodes. Publishing snapshots an immutable
            version, so editing a form never changes what a task already waiting on it displays.
          </p>
        </div>
        <div class="toolbar">
          <a class="btn btn--primary" routerLink="/forms/new">New form</a>
        </div>
      </div>

      <div class="card">
        <div class="card__header">
          <input
            type="search"
            style="max-width: 260px"
            placeholder="Search by name"
            aria-label="Search forms by name"
            [value]="nameFilter()"
            (input)="onSearch($any($event.target).value)"
          />
          <div class="btn-group">
            @for (option of statusOptions; track option.label) {
              <button
                class="btn btn--sm"
                type="button"
                [class.btn--primary]="statusFilter() === option.value"
                (click)="setStatus(option.value)"
              >
                {{ option.label }}
              </button>
            }
          </div>
          <span class="spacer"></span>
          <span class="small muted">{{ page().totalElements }} total</span>
        </div>

        @if (page().content.length === 0 && !loading()) {
          <wf-empty-state
            heading="No forms yet"
            message="Design one, map its fields to workflow variables, then publish it and reference it from a form node."
          >
            <a class="btn btn--primary" routerLink="/forms/new">New form</a>
          </wf-empty-state>
        } @else {
          <table class="table table--clickable">
            <thead>
              <tr>
                <th>Name</th>
                <th>Status</th>
                <th>Published</th>
                <th>Fields</th>
                <th>Updated</th>
                <th class="cell-actions">Actions</th>
              </tr>
            </thead>
            <tbody>
              @for (form of page().content; track form.id) {
                <tr>
                  <td>
                    <a [routerLink]="['/forms', form.id]">{{ form.name }}</a>
                    @if (form.description) {
                      <div class="small muted truncate" style="max-width: 46ch">
                        {{ form.description }}
                      </div>
                    }
                  </td>
                  <td><wf-status-pill [status]="form.status" /></td>
                  <td>
                    @if (form.publishedVersion) {
                      <span class="tag">v{{ form.publishedVersion }}</span>
                    } @else {
                      <span class="faint small">never published</span>
                    }
                  </td>
                  <td>{{ form.fieldCount }}</td>
                  <td class="small muted" [title]="form.updatedAt ?? ''">{{ form.updatedAt | ago }}</td>
                  <td class="cell-actions">
                    <a class="btn btn--sm" [routerLink]="['/forms', form.id]">Open</a>
                    <button class="btn btn--sm" type="button" (click)="clone(form)">Clone</button>
                    <button class="btn btn--danger btn--sm" type="button" (click)="remove(form)">
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
})
export class FormList {
  protected readonly statusOptions: Array<{ label: string; value: FormStatus | null }> = [
    { label: 'All', value: null },
    { label: 'Draft', value: 'DRAFT' },
    { label: 'Published', value: 'PUBLISHED' },
  ];

  private readonly api = inject(FormApiService);
  private readonly notifications = inject(NotificationService);

  protected readonly page = signal<Page<FormSummary>>(emptyPage());
  protected readonly loading = signal(false);
  protected readonly pendingConfirm = signal<ConfirmRequest | null>(null);
  protected readonly statusFilter = signal<FormStatus | null>(null);
  protected readonly nameFilter = signal('');

  private readonly pageIndex = signal(0);
  private searchTimer: ReturnType<typeof setTimeout> | null = null;

  constructor() {
    effect(() => {
      this.statusFilter();
      this.nameFilter();
      this.pageIndex();
      this.load();
    });
  }

  protected load(): void {
    this.loading.set(true);
    this.api
      .list({ status: this.statusFilter(), name: this.nameFilter(), page: this.pageIndex(), size: 20 })
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
      this.nameFilter.set(value);
    }, 250);
  }

  protected setStatus(status: FormStatus | null): void {
    this.pageIndex.set(0);
    this.statusFilter.set(status);
  }

  protected clone(form: FormSummary): void {
    this.api.clone(form.id).subscribe({
      next: (copy) => {
        this.notifications.success(`Cloned as "${copy.name}"`);
        this.load();
      },
    });
  }

  protected remove(form: FormSummary): void {
    this.pendingConfirm.set({
      heading: 'Delete form?',
      message:
        `Delete "${form.name}"?\n\nThis removes every published version too, so any workflow node ` +
        'referencing it will fail validation.',
      confirmLabel: 'Delete',
      danger: true,
      onConfirm: () => this.doRemove(form),
    });
  }

  private doRemove(form: FormSummary): void {
    this.api.delete(form.id).subscribe({
      next: () => {
        this.notifications.success(`Deleted "${form.name}"`);
        this.load();
      },
    });
  }

  /** Runs the pending confirmed action, then clears the dialog. */
  protected runConfirmed(): void {
    const request = this.pendingConfirm();
    this.pendingConfirm.set(null);
    request?.onConfirm();
  }
}
