import { ChangeDetectionStrategy, Component, computed, input, output, signal } from '@angular/core';
import { coerceScalar } from './schema-fields';

interface Row {
  key: string;
  text: string;
}

/**
 * Editor for a free-form key/value map: HTTP headers, query parameters, workflow variables, input and
 * output mappings.
 *
 * Rows are held as local state rather than being derived from the bound value on every keystroke.
 * That is necessary, not incidental: a map keyed by the input field's own contents loses focus and
 * reorders itself while the operator is still typing a key, and duplicate or empty keys cannot exist
 * at all, which makes adding a row impossible.
 *
 * Values are coerced, so `5000` becomes a number and `true` a boolean, while `${amount}` and ordinary
 * prose stay strings. Set `valuesAreText` for maps the engine expects to be entirely textual, such as
 * output mappings, where coercing a variable path would be wrong.
 */
@Component({
  selector: 'wf-kv-editor',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="kv">
      @if (rows().length === 0) {
        <p class="kv__empty small muted">{{ emptyText() }}</p>
      }
      @for (row of rows(); track $index) {
        <div class="kv__row">
          <input
            type="text"
            class="kv__key"
            [attr.aria-label]="keyLabel() + ' name'"
            [placeholder]="keyPlaceholder()"
            [value]="row.key"
            (input)="setKey($index, $any($event.target).value)"
          />
          <input
            type="text"
            class="kv__value"
            [attr.aria-label]="valueLabel()"
            [placeholder]="valuePlaceholder()"
            [value]="row.text"
            (input)="setValue($index, $any($event.target).value)"
          />
          <button
            class="btn btn--quiet btn--sm"
            type="button"
            [attr.aria-label]="'Remove ' + (row.key || 'row')"
            (click)="removeRow($index)"
          >
            Remove
          </button>
        </div>
      }
      <button class="btn btn--sm" type="button" (click)="addRow()">Add {{ keyLabel() }}</button>
      @if (duplicateKeys().length > 0) {
        <p class="field__error">Duplicate keys are ignored: {{ duplicateKeys().join(', ') }}</p>
      }
    </div>
  `,
  styles: [
    `
      .kv {
        display: flex;
        flex-direction: column;
        gap: var(--space-2);
        align-items: flex-start;
      }

      .kv__row {
        display: grid;
        grid-template-columns: minmax(90px, 1fr) minmax(120px, 1.6fr) auto;
        gap: var(--space-2);
        width: 100%;
      }

      .kv__key {
        font-family: var(--font-mono);
        font-size: var(--text-sm);
      }

      .kv__empty {
        margin: 0;
      }
    `,
  ],
})
export class KvEditor {
  readonly value = input<Record<string, unknown> | null>(null);
  readonly keyLabel = input<string>('entry');
  readonly valueLabel = input<string>('Value');
  readonly keyPlaceholder = input<string>('name');
  readonly valuePlaceholder = input<string>('value');
  readonly emptyText = input<string>('No entries yet.');
  readonly valuesAreText = input<boolean>(false);

  readonly valueChange = output<Record<string, unknown>>();

  private readonly rowState = signal<Row[] | null>(null);

  /**
   * Local rows once the operator has started editing, otherwise derived from the bound value. This is
   * what lets an external load replace the contents while a fresh component still shows what it was
   * given.
   */
  readonly rows = computed<Row[]>(() => {
    const local = this.rowState();
    if (local) {
      return local;
    }
    return Object.entries(this.value() ?? {}).map(([key, raw]) => ({
      key,
      text: toText(raw),
    }));
  });

  readonly duplicateKeys = computed(() => {
    const seen = new Set<string>();
    const duplicates = new Set<string>();
    for (const row of this.rows()) {
      const key = row.key.trim();
      if (!key) {
        continue;
      }
      if (seen.has(key)) {
        duplicates.add(key);
      }
      seen.add(key);
    }
    return [...duplicates];
  });

  addRow(): void {
    this.rowState.set([...this.rows(), { key: '', text: '' }]);
    // No emit: an empty row is not yet a map entry, and emitting would add a blank key.
  }

  removeRow(index: number): void {
    const next = [...this.rows()];
    next.splice(index, 1);
    this.commit(next);
  }

  setKey(index: number, key: string): void {
    const next = [...this.rows()];
    next[index] = { ...next[index], key };
    this.commit(next);
  }

  setValue(index: number, text: string): void {
    const next = [...this.rows()];
    next[index] = { ...next[index], text };
    this.commit(next);
  }

  private commit(rows: Row[]): void {
    this.rowState.set(rows);
    const result: Record<string, unknown> = {};
    for (const row of rows) {
      const key = row.key.trim();
      if (!key) {
        continue;
      }
      result[key] = this.valuesAreText() ? row.text : coerceScalar(row.text);
    }
    this.valueChange.emit(result);
  }
}

function toText(value: unknown): string {
  if (value == null) {
    return '';
  }
  if (typeof value === 'object') {
    try {
      return JSON.stringify(value);
    } catch {
      return String(value);
    }
  }
  return String(value);
}
