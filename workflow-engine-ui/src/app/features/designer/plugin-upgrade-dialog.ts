import { ChangeDetectionStrategy, Component, computed, inject, input, output, signal } from '@angular/core';
import { MarketplaceApiService } from '../../core/api/marketplace-api.service';
import { PluginStatusView } from '../../core/models/marketplace.models';
import { WorkflowNode } from '../../core/models/workflow.models';
import { Modal } from '../../shared/ui/modal';

/** One node whose pinned version is not the newest installed one. */
export interface UpgradeCandidate {
  nodeId: string;
  nodeName: string;
  pluginId: string;
  /** The version the node pins today. */
  from: string;
  /** The newest usable version installed on this engine. */
  to: string;
  /** Whether the pinned version is still installed at all. */
  pinnedStillInstalled: boolean;
}

/**
 * Repoints workflow nodes from an old plugin version to a newer installed one.
 *
 * <h2>Why this exists on the workflow rather than on the plugin</h2>
 *
 * The engine refuses to uninstall a plugin version while a published workflow pins it, and names the
 * workflows. That refusal is correct and it leaves the operator with a job the plugin screens cannot do:
 * every fix is an edit to a workflow. This dialog is that job, done where the workflow is already open.
 *
 * <h2>Pinning is not a mistake to be corrected</h2>
 *
 * A pinned version is a deliberate guarantee that publishing a new plugin cannot change what a node does, so
 * this never repoints anything on its own. It lists the candidates, explains what each change means, and moves
 * only what is selected. A node whose pinned version has been uninstalled is called out separately, because
 * that one is not a preference any more — it will fail at execution.
 */
@Component({
  selector: 'wf-plugin-upgrade-dialog',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [Modal],
  template: `
    <wf-modal
      heading="Update plugin versions"
      subheading="Nodes in this workflow that pin a plugin version older than the newest one installed."
      width="680px"
      (closed)="closed.emit()"
    >
      @if (candidates().length === 0) {
        <p>
          Every plugin node here either follows the default version or already pins the newest version
          installed on this engine. Nothing to update.
        </p>
      } @else {
        @if (broken().length > 0) {
          <div class="notice notice--error">
            {{ broken().length }} node(s) pin a version that is no longer installed. Those nodes fail at
            execution until they are repointed or their version is reinstalled.
          </div>
        }

        <p class="small muted">
          A pinned version is honoured exactly, so a node keeps running the plugin it was published against
          until somebody changes it here. Repointing takes effect when the workflow is saved, and applies to
          new executions once it is published.
        </p>

        <table class="table">
          <thead>
            <tr>
              <th class="cell-check">
                <input
                  type="checkbox"
                  aria-label="Select every node"
                  [checked]="allSelected()"
                  (change)="toggleAll($any($event.target).checked)"
                />
              </th>
              <th>Node</th>
              <th>Plugin</th>
              <th>Pinned</th>
              <th>Newest installed</th>
            </tr>
          </thead>
          <tbody>
            @for (candidate of candidates(); track candidate.nodeId) {
              <tr [class.row--broken]="!candidate.pinnedStillInstalled">
                <td class="cell-check">
                  <input
                    type="checkbox"
                    [attr.aria-label]="'Update ' + candidate.nodeName"
                    [checked]="selected().has(candidate.nodeId)"
                    (change)="toggle(candidate.nodeId, $any($event.target).checked)"
                  />
                </td>
                <td>
                  <strong>{{ candidate.nodeName }}</strong>
                  <div class="mono small muted">{{ candidate.nodeId }}</div>
                </td>
                <td class="mono small">{{ candidate.pluginId }}</td>
                <td class="mono small">
                  {{ candidate.from }}
                  @if (!candidate.pinnedStillInstalled) {
                    <span class="tag tag--danger">not installed</span>
                  }
                </td>
                <td class="mono small">{{ candidate.to }}</td>
              </tr>
            }
          </tbody>
        </table>
      }

      <div modalFooter>
        <button class="btn" type="button" (click)="closed.emit()">Cancel</button>
        @if (candidates().length > 0) {
          <button
            class="btn btn--primary"
            type="button"
            [disabled]="selected().size === 0"
            (click)="apply()"
          >
            Update {{ selected().size }} node(s)
          </button>
        }
      </div>
    </wf-modal>
  `,
  styles: [
    `
      .cell-check {
        width: 32px;
      }

      .row--broken td {
        background: #fdecec;
      }

      .tag--danger {
        color: var(--hl-error);
        border-color: var(--hl-error);
      }

      .table {
        margin-top: var(--space-3);
      }
    `,
  ],
})
export class PluginUpgradeDialog {
  readonly nodes = input.required<WorkflowNode[]>();

  /** Emitted with the nodes to repoint, so the store stays the only thing that mutates the graph. */
  readonly applied = output<UpgradeCandidate[]>();
  readonly closed = output<void>();

  private readonly marketplace = inject(MarketplaceApiService);

  private readonly deselected = signal<ReadonlySet<string>>(new Set());

  /**
   * Nodes pinned to something older than the newest installed version.
   *
   * Only *installed* versions are offered as a target. Repointing a node at a version the registry publishes
   * but this engine does not have would produce a workflow that fails validation the moment it is saved,
   * which is a worse outcome than leaving it on an old version that works.
   */
  protected readonly candidates = computed<UpgradeCandidate[]>(() => {
    const index = this.marketplace.byPluginId();
    const found: UpgradeCandidate[] = [];

    for (const node of this.nodes()) {
      const pluginId = node.pluginId;
      const pinned = node.pluginVersion;
      if (!pluginId || !pinned) {
        // An unpinned node already follows the default version, so there is nothing to move.
        continue;
      }
      const view = index.get(pluginId);
      const newest = newestUsable(view);
      if (!newest || newest === pinned) {
        continue;
      }
      found.push({
        nodeId: node.id,
        nodeName: node.name || node.id,
        pluginId,
        from: pinned,
        to: newest,
        pinnedStillInstalled: (view?.installedVersions ?? []).some(
          (entry) => entry.version === pinned,
        ),
      });
    }
    return found;
  });

  protected readonly broken = computed(() =>
    this.candidates().filter((candidate) => !candidate.pinnedStillInstalled),
  );

  /** Everything is selected unless explicitly deselected, which is the useful default for a bulk action. */
  protected readonly selected = computed(() => {
    const excluded = this.deselected();
    return new Set(
      this.candidates()
        .map((candidate) => candidate.nodeId)
        .filter((id) => !excluded.has(id)),
    );
  });

  protected readonly allSelected = computed(
    () => this.candidates().length > 0 && this.selected().size === this.candidates().length,
  );

  protected toggle(nodeId: string, checked: boolean): void {
    const next = new Set(this.deselected());
    if (checked) {
      next.delete(nodeId);
    } else {
      next.add(nodeId);
    }
    this.deselected.set(next);
  }

  protected toggleAll(checked: boolean): void {
    this.deselected.set(
      checked ? new Set() : new Set(this.candidates().map((candidate) => candidate.nodeId)),
    );
  }

  protected apply(): void {
    const chosen = this.selected();
    this.applied.emit(this.candidates().filter((candidate) => chosen.has(candidate.nodeId)));
  }
}

/**
 * The newest version installed here that could actually serve a node.
 *
 * Ordering comes from the server, which lists installed versions newest first by semantic precedence. Sorting
 * again in the browser would be a second implementation of that rule, and the two would disagree on the first
 * pre-release anybody publishes.
 */
function newestUsable(view: PluginStatusView | undefined): string | null {
  if (!view) {
    return null;
  }
  const usable = view.installedVersions.find(
    (entry) => entry.state === 'ACTIVE' || entry.state === 'INSTALLED',
  );
  return usable?.version ?? view.installedVersion ?? null;
}
