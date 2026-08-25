import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { AiApiService } from '../../core/api/ai-api.service';
import { AiAgentExecution, AiUsageSummary } from '../../core/models/ai.models';
import { AgoPipe } from '../../shared/pipes/format.pipes';
import { EmptyState } from '../../shared/ui/empty-state';

/**
 * Settings → AI Usage: token usage and recent AI Agent runs.
 *
 * <p>Everything here is metadata read from the execution records the engine already writes — provider, model,
 * timing, token counts, tool calls, outcome. By design those records never hold a prompt or a response, so this
 * page exposes nothing sensitive; viewing needs only AI_PROVIDER_VIEW.
 */
@Component({
  selector: 'wf-ai-usage',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [AgoPipe, EmptyState],
  template: `
    <div class="page">
      <div class="page-header">
        <div class="page-header__text">
          <h1>AI Usage</h1>
          <p>
            Token usage and recent AI Agent runs across the engine. Metadata only — prompts and responses are
            never recorded.
          </p>
        </div>
        <div class="toolbar">
          <button class="btn" type="button" (click)="reload()" [disabled]="loading()">Refresh</button>
        </div>
      </div>

      @if (summary(); as s) {
        <div class="stats">
          <div class="stat">
            <span class="stat__value">{{ s.executions }}</span>
            <span class="stat__label">Runs</span>
          </div>
          <div class="stat">
            <span class="stat__value">{{ s.totalTokens }}</span>
            <span class="stat__label">Total tokens</span>
          </div>
          <div class="stat">
            <span class="stat__value">{{ s.inputTokens }}</span>
            <span class="stat__label">Input tokens</span>
          </div>
          <div class="stat">
            <span class="stat__value">{{ s.outputTokens }}</span>
            <span class="stat__label">Output tokens</span>
          </div>
          <div class="stat">
            <span class="stat__value">{{ s.toolCalls }}</span>
            <span class="stat__label">Tool calls</span>
          </div>
        </div>

        <div class="split">
          <div class="card">
            <h2>By provider</h2>
            @if (s.byProvider.length === 0) {
              <p class="faint small">No usage yet.</p>
            } @else {
              <table class="table">
                <thead><tr><th>Provider</th><th class="num">Runs</th><th class="num">Tokens</th></tr></thead>
                <tbody>
                  @for (b of s.byProvider; track b.name) {
                    <tr><td><span class="tag">{{ b.name }}</span></td>
                      <td class="num">{{ b.executions }}</td><td class="num">{{ b.totalTokens }}</td></tr>
                  }
                </tbody>
              </table>
            }
          </div>
          <div class="card">
            <h2>By model</h2>
            @if (s.byModel.length === 0) {
              <p class="faint small">No usage yet.</p>
            } @else {
              <table class="table">
                <thead><tr><th>Model</th><th class="num">Runs</th><th class="num">Tokens</th></tr></thead>
                <tbody>
                  @for (b of s.byModel; track b.name) {
                    <tr><td class="mono small">{{ b.name }}</td>
                      <td class="num">{{ b.executions }}</td><td class="num">{{ b.totalTokens }}</td></tr>
                  }
                </tbody>
              </table>
            }
          </div>
        </div>
      }

      <div class="card">
        <h2>Recent runs</h2>
        @if (executions().length === 0 && !loading()) {
          <wf-empty-state heading="No AI runs yet"
            message="Runs appear here after an AI Agent node executes."></wf-empty-state>
        } @else {
          <table class="table">
            <thead>
              <tr>
                <th>Started</th><th>Provider</th><th>Model</th><th>Status</th>
                <th class="num">Tokens</th><th class="num">Tools</th><th class="num">Blocked</th><th>Stop reason</th>
              </tr>
            </thead>
            <tbody>
              @for (e of executions(); track e.id) {
                <tr>
                  <td>{{ e.startedAt | ago }}</td>
                  <td><span class="tag">{{ e.provider }}</span></td>
                  <td class="mono small">{{ e.model }}</td>
                  <td>
                    @if (e.status === 'COMPLETED') {
                      <span class="tag tag--ok">Completed</span>
                    } @else {
                      <span class="tag tag--err" [title]="e.error || ''">{{ e.status }}</span>
                    }
                  </td>
                  <td class="num">{{ e.totalTokens }}</td>
                  <td class="num">{{ e.toolCalls }}</td>
                  <td class="num">{{ e.blockedToolCalls || '—' }}</td>
                  <td class="small">{{ e.stopReason || '—' }}</td>
                </tr>
              }
            </tbody>
          </table>
        }
      </div>
    </div>
  `,
  styles: [
    `
      .stats { display: flex; flex-wrap: wrap; gap: var(--space-3); margin-bottom: var(--space-4); }
      .stat { flex: 1; min-width: 120px; border: 1px solid var(--border); border-radius: var(--radius-sm);
        padding: var(--space-3); display: flex; flex-direction: column; gap: var(--space-1); background: var(--surface); }
      .stat__value { font-size: var(--text-xl); font-weight: 600; }
      .stat__label { font-size: var(--text-xs); color: var(--text-muted); }
      .split { display: flex; gap: var(--space-4); flex-wrap: wrap; margin-bottom: var(--space-4); }
      .split .card { flex: 1; min-width: 280px; }
      .card h2 { font-size: var(--text-md); margin: 0 0 var(--space-2); }
      .num { text-align: right; }
      .faint { color: var(--text-muted); }
      .tag--ok { background: var(--ok-bg, #e6f4ea); color: var(--ok-fg, #1e7e34); }
      .tag--err { background: var(--danger-bg, #fdecea); color: var(--danger, #c62828); }
    `,
  ],
})
export class AiUsage {
  private readonly api = inject(AiApiService);

  protected readonly summary = signal<AiUsageSummary | null>(null);
  protected readonly executions = signal<AiAgentExecution[]>([]);
  protected readonly loading = signal(false);
  protected readonly hasData = computed(() => this.summary() !== null);

  constructor() {
    this.reload();
  }

  protected reload(): void {
    this.loading.set(true);
    this.api.usage().subscribe({
      next: (s) => this.summary.set(s),
      error: () => this.loading.set(false),
    });
    this.api.executions().subscribe({
      next: (list) => {
        this.executions.set(list);
        this.loading.set(false);
      },
      error: () => this.loading.set(false),
    });
  }
}
