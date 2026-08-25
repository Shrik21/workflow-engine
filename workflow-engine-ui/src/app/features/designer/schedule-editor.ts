import {
  ChangeDetectionStrategy,
  Component,
  effect,
  inject,
  input,
  OnDestroy,
  output,
  signal,
} from '@angular/core';
import { DatePipe, NgTemplateOutlet } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { WorkflowApiService } from '../../core/api/workflow-api.service';
import { AgoPipe } from '../../shared/pipes/format.pipes';
import {
  ScheduleConfig,
  ScheduleFrequency,
  SchedulePreview,
} from '../../core/models/workflow.models';

interface FrequencyOption {
  value: ScheduleFrequency;
  label: string;
}

interface DayToggle {
  code: string;
  label: string;
}

/** What the editor hands back to the parent on every change. */
export interface ScheduleChange {
  schedule: ScheduleConfig;
  cron: string | null;
  timezone: string | null;
}

/**
 * The friendly schedule builder: dropdowns, a time picker and day toggles instead of a cron box.
 *
 * <h2>The cron is the server's job, never this component's</h2>
 *
 * The editor only ever assembles a {@link ScheduleConfig} and asks the backend to preview it. The generated
 * cron, the plain-English sentence and the next run times all come back from {@code /schedule/preview}, so the
 * browser never builds or parses cron itself — which is the whole point of the feature and keeps the preview
 * and what actually runs in exact agreement. Editing an existing schedule reconstructs the dropdowns from the
 * stored config, or — for a legacy trigger that has only a cron — from {@code /schedule/parse}, so a person is
 * never confronted with raw cron unless they chose Custom.
 */
@Component({
  selector: 'wf-schedule-editor',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [FormsModule, NgTemplateOutlet, DatePipe, AgoPipe],
  template: `
    <div class="sched">
      <label class="field">
        <span class="field__label">Frequency</span>
        <select [ngModel]="frequency()" (ngModelChange)="setFrequency($event)">
          @for (option of frequencies; track option.value) {
            <option [value]="option.value">{{ option.label }}</option>
          }
        </select>
      </label>

      @switch (frequency()) {
        @case ('EVERY_N_MINUTES') {
          <label class="field">
            <span class="field__label">Every</span>
            <span class="inline">
              <input type="number" min="1" max="59" [ngModel]="interval()" (ngModelChange)="setInterval($event)" />
              <span class="unit">minutes</span>
            </span>
          </label>
        }
        @case ('HOURLY') {
          <label class="field">
            <span class="field__label">At minute past the hour</span>
            <input type="number" min="0" max="59" [ngModel]="minute()" (ngModelChange)="setMinute($event)" />
          </label>
        }
        @case ('EVERY_N_HOURS') {
          <label class="field">
            <span class="field__label">Every</span>
            <span class="inline">
              <input type="number" min="1" max="23" [ngModel]="interval()" (ngModelChange)="setInterval($event)" />
              <span class="unit">hours</span>
            </span>
          </label>
          <label class="field">
            <span class="field__label">Start time</span>
            <input type="time" [ngModel]="time()" (ngModelChange)="setTime($event)" />
          </label>
        }
        @case ('DAILY') {
          <label class="field">
            <span class="field__label">Time</span>
            <input type="time" [ngModel]="time()" (ngModelChange)="setTime($event)" />
          </label>
        }
        @case ('WEEKLY') {
          <ng-container [ngTemplateOutlet]="daysAndTime" />
        }
        @case ('SELECTED_DAYS') {
          <ng-container [ngTemplateOutlet]="daysAndTime" />
        }
        @case ('MONTHLY') {
          <ng-container [ngTemplateOutlet]="monthDayAndTime" />
        }
        @case ('SPECIFIC_DAY_OF_MONTH') {
          <ng-container [ngTemplateOutlet]="monthDayAndTime" />
        }
        @case ('CUSTOM') {
          <label class="field">
            <span class="field__label">Cron expression</span>
            <input
              type="text"
              class="mono"
              placeholder="0 0 9 * * MON-FRI"
              [ngModel]="customCron()"
              (ngModelChange)="setCustomCron($event)"
            />
            <span class="field__hint">
              Spring six-field cron: second minute hour day-of-month month day-of-week.
            </span>
          </label>
        }
      }

      @if (frequency() !== 'CUSTOM' && frequency() !== 'EVERY_MINUTE' && frequency() !== 'EVERY_N_MINUTES') {
        <!-- Timezone is only meaningful once a time-of-day is involved. -->
        <label class="field">
          <span class="field__label">Timezone</span>
          <select [ngModel]="timezoneValue()" (ngModelChange)="setTimezone($event)">
            @for (zone of timezones; track zone) {
              <option [value]="zone">{{ zone }}</option>
            }
          </select>
        </label>
      }

      <div class="preview">
        @if (error()) {
          <p class="preview__error">{{ error() }}</p>
        } @else if (preview(); as p) {
          <p class="preview__desc">{{ p.description }}</p>
          @if (p.nextRuns.length > 0) {
            <div class="preview__runs">
              <span class="preview__runs-label">Next runs</span>
              <ul>
                @for (run of p.nextRuns; track run) {
                  <li>{{ run | date: 'medium' }} <span class="faint">({{ run | ago }})</span></li>
                }
              </ul>
            </div>
          }
        } @else {
          <p class="faint small">Configure the schedule to see a preview.</p>
        }
      </div>
    </div>

    <ng-template #daysAndTime>
      <div class="field">
        <span class="field__label">Days</span>
        <div class="days">
          @for (day of week; track day.code) {
            <button
              type="button"
              class="day"
              [class.day--on]="days().includes(day.code)"
              (click)="toggleDay(day.code)"
            >
              {{ day.label }}
            </button>
          }
        </div>
      </div>
      <label class="field">
        <span class="field__label">Time</span>
        <input type="time" [ngModel]="time()" (ngModelChange)="setTime($event)" />
      </label>
    </ng-template>

    <ng-template #monthDayAndTime>
      <label class="field">
        <span class="field__label">Day of month</span>
        <select [ngModel]="monthDayValue()" (ngModelChange)="setMonthDay($event)">
          <option value="LAST">Last day</option>
          @for (d of monthDays; track d) {
            <option [value]="d">{{ d }}</option>
          }
        </select>
      </label>
      <label class="field">
        <span class="field__label">Time</span>
        <input type="time" [ngModel]="time()" (ngModelChange)="setTime($event)" />
      </label>
    </ng-template>
  `,
  styles: [
    `
      .sched {
        display: flex;
        flex-direction: column;
        gap: var(--space-3);
      }
      .field {
        display: flex;
        flex-direction: column;
        gap: var(--space-1);
      }
      .field__label {
        font-size: var(--text-sm);
        color: var(--text-muted);
      }
      .field__hint {
        font-size: var(--text-xs);
        color: var(--text-muted);
      }
      .inline {
        display: flex;
        align-items: center;
        gap: var(--space-2);
      }
      .inline input {
        width: 90px;
      }
      .unit {
        color: var(--text-muted);
      }
      .days {
        display: flex;
        flex-wrap: wrap;
        gap: var(--space-1);
      }
      .day {
        border: 1px solid var(--border-strong);
        background: var(--surface);
        color: var(--text);
        border-radius: var(--radius-sm);
        padding: 4px 10px;
        cursor: pointer;
        font-size: var(--text-sm);
      }
      .day--on {
        background: var(--hl-accent-blue, #1976d2);
        border-color: var(--hl-accent-blue, #1976d2);
        color: #fff;
      }
      .preview {
        border: 1px solid var(--border);
        border-radius: var(--radius-sm);
        background: var(--surface-sunken);
        padding: var(--space-3);
      }
      .preview__desc {
        margin: 0;
        font-weight: 600;
      }
      .preview__error {
        margin: 0;
        color: var(--danger, #c62828);
        font-size: var(--text-sm);
      }
      .preview__runs {
        margin-top: var(--space-2);
      }
      .preview__runs-label {
        font-size: var(--text-xs);
        text-transform: uppercase;
        letter-spacing: 0.04em;
        color: var(--text-muted);
      }
      .preview__runs ul {
        margin: var(--space-1) 0 0;
        padding-left: var(--space-4);
        font-size: var(--text-sm);
      }
      .faint {
        color: var(--text-muted);
      }
    `,
  ],
})
export class ScheduleEditor implements OnDestroy {
  /** The stored config to edit, if any. */
  readonly schedule = input<ScheduleConfig | null>(null);
  /** A legacy cron to reconstruct from when there is no stored config. */
  readonly cron = input<string | null>(null);
  readonly timezone = input<string | null>(null);

  readonly changed = output<ScheduleChange>();

  protected readonly frequencies: FrequencyOption[] = [
    { value: 'EVERY_MINUTE', label: 'Every minute' },
    { value: 'EVERY_N_MINUTES', label: 'Every N minutes' },
    { value: 'HOURLY', label: 'Hourly' },
    { value: 'EVERY_N_HOURS', label: 'Every N hours' },
    { value: 'DAILY', label: 'Daily' },
    { value: 'WEEKLY', label: 'Weekly' },
    { value: 'SELECTED_DAYS', label: 'Selected days' },
    { value: 'MONTHLY', label: 'Monthly' },
    { value: 'SPECIFIC_DAY_OF_MONTH', label: 'Specific day of month' },
    { value: 'CUSTOM', label: 'Custom (cron)' },
  ];

  protected readonly week: DayToggle[] = [
    { code: 'MON', label: 'Mon' },
    { code: 'TUE', label: 'Tue' },
    { code: 'WED', label: 'Wed' },
    { code: 'THU', label: 'Thu' },
    { code: 'FRI', label: 'Fri' },
    { code: 'SAT', label: 'Sat' },
    { code: 'SUN', label: 'Sun' },
  ];

  protected readonly monthDays = Array.from({ length: 31 }, (_, i) => i + 1);
  protected readonly timezones = buildTimezones();

  private readonly api = inject(WorkflowApiService);

  protected readonly frequency = signal<ScheduleFrequency>('DAILY');
  protected readonly time = signal<string>('10:30');
  protected readonly interval = signal<number>(15);
  protected readonly minute = signal<number>(0);
  protected readonly days = signal<string[]>([]);
  protected readonly monthDay = signal<number | 'LAST'>(1);
  protected readonly customCron = signal<string>('');
  protected readonly timezoneValue = signal<string>(detectTimezone());

  protected readonly preview = signal<SchedulePreview | null>(null);
  protected readonly error = signal<string | null>(null);

  private debounce: ReturnType<typeof setTimeout> | null = null;
  private initialised = false;

  constructor() {
    // One-time seed from the inputs when the panel opens on a trigger.
    effect(() => {
      const stored = this.schedule();
      const legacyCron = this.cron();
      const tz = this.timezone();
      if (this.initialised) {
        return;
      }
      this.initialised = true;
      if (tz) {
        this.timezoneValue.set(tz);
      }
      if (stored && stored.frequency) {
        this.load(stored);
        this.refresh();
      } else if (legacyCron && legacyCron.trim()) {
        // Reconstruct the friendly choices from an existing cron, server-side.
        this.api.scheduleParse(legacyCron, tz ?? null).subscribe({
          next: (result) => {
            this.load(result.schedule);
            this.refresh();
          },
          error: () => {
            this.frequency.set('CUSTOM');
            this.customCron.set(legacyCron);
            this.refresh();
          },
        });
      } else {
        this.refresh();
      }
    });
  }

  ngOnDestroy(): void {
    if (this.debounce) {
      clearTimeout(this.debounce);
    }
  }

  protected monthDayValue(): string {
    return this.monthDay() === 'LAST' ? 'LAST' : String(this.monthDay());
  }

  protected setFrequency(value: ScheduleFrequency): void {
    this.frequency.set(value);
    // Sensible defaults so a freshly chosen frequency previews immediately.
    if ((value === 'WEEKLY' || value === 'SELECTED_DAYS') && this.days().length === 0) {
      this.days.set(['MON']);
    }
    this.refresh();
  }

  protected setTime(value: string): void {
    this.time.set(value || '10:30');
    this.refresh();
  }

  protected setInterval(value: number): void {
    this.interval.set(Number(value) || 1);
    this.refresh();
  }

  protected setMinute(value: number): void {
    this.minute.set(Math.max(0, Math.min(59, Number(value) || 0)));
    this.refresh();
  }

  protected setMonthDay(value: string): void {
    this.monthDay.set(value === 'LAST' ? 'LAST' : Number(value));
    this.refresh();
  }

  protected setCustomCron(value: string): void {
    this.customCron.set(value);
    this.refresh();
  }

  protected setTimezone(value: string): void {
    this.timezoneValue.set(value);
    this.refresh();
  }

  protected toggleDay(code: string): void {
    const current = this.days();
    this.days.set(
      current.includes(code) ? current.filter((d) => d !== code) : [...current, code],
    );
    this.refresh();
  }

  /** Loads a config into the controls. */
  private load(config: ScheduleConfig): void {
    this.frequency.set(config.frequency ?? 'DAILY');
    if (config.time) {
      this.time.set(config.time);
    }
    if (config.interval != null) {
      this.interval.set(config.interval);
    }
    if (config.minute != null) {
      this.minute.set(config.minute);
    }
    if (config.daysOfWeek?.length) {
      this.days.set([...config.daysOfWeek]);
    }
    if (config.lastDayOfMonth) {
      this.monthDay.set('LAST');
    } else if (config.dayOfMonth != null) {
      this.monthDay.set(config.dayOfMonth);
    }
    if (config.cron) {
      this.customCron.set(config.cron);
    }
  }

  /** Assembles the current config from the controls. */
  private build(): ScheduleConfig {
    const frequency = this.frequency();
    const config: ScheduleConfig = { frequency };
    switch (frequency) {
      case 'EVERY_N_MINUTES':
      case 'EVERY_N_HOURS':
        config.interval = this.interval();
        if (frequency === 'EVERY_N_HOURS') {
          config.time = this.time();
        }
        break;
      case 'HOURLY':
        config.minute = this.minute();
        break;
      case 'DAILY':
        config.time = this.time();
        break;
      case 'WEEKLY':
      case 'SELECTED_DAYS':
        config.daysOfWeek = this.days();
        config.time = this.time();
        break;
      case 'MONTHLY':
      case 'SPECIFIC_DAY_OF_MONTH':
        if (this.monthDay() === 'LAST') {
          config.lastDayOfMonth = true;
        } else {
          config.dayOfMonth = this.monthDay() as number;
        }
        config.time = this.time();
        break;
      case 'CUSTOM':
        config.cron = this.customCron();
        break;
    }
    return config;
  }

  /** Debounced backend preview + emit to the parent. */
  private refresh(): void {
    if (this.debounce) {
      clearTimeout(this.debounce);
    }
    this.debounce = setTimeout(() => this.doPreview(), 300);
  }

  private doPreview(): void {
    const schedule = this.build();
    const tz = this.timezoneValue();
    this.api.schedulePreview(schedule, tz, 5).subscribe({
      next: (preview) => {
        this.preview.set(preview);
        this.error.set(null);
        this.changed.emit({ schedule, cron: preview.cron, timezone: tz });
      },
      error: (response: { error?: { message?: string } }) => {
        this.preview.set(null);
        this.error.set(response?.error?.message ?? 'This schedule is not valid.');
        // Still emit the config so the parent keeps the user's choices; cron stays whatever it was.
        this.changed.emit({ schedule, cron: null, timezone: tz });
      },
    });
  }
}

/** The browser's timezone, as the sensible default (the spec's "user/tenant timezone"). */
function detectTimezone(): string {
  try {
    return Intl.DateTimeFormat().resolvedOptions().timeZone || 'UTC';
  } catch {
    return 'UTC';
  }
}

/** A curated timezone list with the browser's own zone and UTC guaranteed present. */
function buildTimezones(): string[] {
  const common = [
    'UTC',
    'Asia/Kolkata',
    'Asia/Dubai',
    'Asia/Singapore',
    'Asia/Tokyo',
    'Europe/London',
    'Europe/Paris',
    'Europe/Berlin',
    'America/New_York',
    'America/Chicago',
    'America/Los_Angeles',
    'Australia/Sydney',
  ];
  const detected = detectTimezone();
  return Array.from(new Set([detected, ...common]));
}
