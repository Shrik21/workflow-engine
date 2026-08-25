import { ChangeDetectionStrategy, Component, computed, input, output, signal } from '@angular/core';
import { ConfigSchema } from '../../core/models/node.models';
import { KvEditor } from './kv-editor';
import {
  SchemaField,
  applyDefaults,
  coerceScalar,
  isRequired,
  missingRequired,
  toFields,
  visibleFields,
} from './schema-fields';

/**
 * Renders a form from a plugin-published configuration schema.
 *
 * This component is the reason a plugin uploaded to a running engine is immediately usable in the
 * designer. It knows nothing about SendGrid, Slack or REST; it knows how to render the schema dialect
 * the SDK emits. Adding an integration therefore changes nothing here.
 *
 * Two details that matter in use:
 *
 * - Every control accepts `${...}` placeholders, including numeric and dropdown fields, because a
 *   workflow author routinely wants a variable where a literal would go. That is why the number input
 *   falls back to text when the value is not numeric rather than rejecting it.
 * - A secret field renders a picker over the secret names the operator can see, and is labelled as a
 *   name rather than a value. It has to be unmistakable that the workflow stores a reference, not a
 *   credential.
 */
@Component({
  selector: 'wf-schema-form',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [KvEditor],
  template: `
    @if (fields().length === 0) {
      <p class="small muted">{{ emptyText() }}</p>
    }

    @for (field of shownFields(); track field.name) {
      <div class="field" [class.field--advanced]="field.advanced">
        <label class="field__label" [attr.for]="controlId(field)">
          {{ field.label }}
          @if (isRequiredNow(field)) {
            <span class="required" aria-hidden="true">*</span>
            <span class="sr-only">(required)</span>
          }
        </label>

        @switch (field.control) {
          @case ('boolean') {
            <label class="checkbox-row">
              <input
                type="checkbox"
                [id]="controlId(field)"
                [checked]="asBoolean(field.name)"
                (change)="set(field.name, $any($event.target).checked)"
              />
              <span class="small muted">{{ field.description || 'Enabled' }}</span>
            </label>
          }

          @case ('select') {
            <!--
              Selection is expressed with [selected] on each option rather than [value] on the select. A
              property binding on the select is applied before its options exist, so the browser has nothing
              to match and leaves the value empty: a saved choice rendered as "Not set", and saving the node
              again wrote that back. Every schema-driven select was affected, plugin nodes included.
            -->
            <select [id]="controlId(field)" (change)="set(field.name, $any($event.target).value)">
              <option value="" [selected]="!asText(field.name)">Not set</option>
              @for (option of field.options; track option) {
                <option [value]="option" [selected]="option === asText(field.name)">{{ option }}</option>
              }
              <!--
                A schema-declared choice can still be driven by a variable. When the current value is
                not one of the options, it is offered so that selecting it does not silently discard
                the author's expression.
              -->
              @if (asText(field.name) && !field.options.includes(asText(field.name))) {
                <option [value]="asText(field.name)" [selected]="true">
                  {{ asText(field.name) }} (custom)
                </option>
              }
            </select>
          }

          @case ('number') {
            <input
              type="text"
              inputmode="numeric"
              [id]="controlId(field)"
              [value]="asText(field.name)"
              placeholder="number or \${variable}"
              (input)="set(field.name, coerce($any($event.target).value))"
            />
          }

          @case ('textarea') {
            <textarea
              [id]="controlId(field)"
              rows="5"
              [value]="asText(field.name)"
              (input)="set(field.name, $any($event.target).value)"
            ></textarea>
          }

          @case ('secret') {
            <div class="secret">
              <input
                type="text"
                [id]="controlId(field)"
                [attr.list]="secretNames().length > 0 ? listId : null"
                [value]="asText(field.name)"
                placeholder="name of a stored secret"
                (input)="set(field.name, $any($event.target).value)"
              />
              @if (secretNames().length > 0) {
                <datalist [id]="listId">
                  @for (name of secretNames(); track name) {
                    <option [value]="name"></option>
                  }
                </datalist>
              }
              <p class="field__hint">
                The name of a stored secret, never the credential itself. The engine resolves it at
                execution time and keeps it out of the workflow, the logs and the execution record.
              </p>
            </div>
          }

          @case ('map') {
            <wf-kv-editor
              [value]="asMap(field.name)"
              [keyLabel]="'entry'"
              [emptyText]="'No entries yet.'"
              (valueChange)="set(field.name, $event)"
            />
          }

          @case ('json') {
            <textarea
              [id]="controlId(field)"
              rows="6"
              spellcheck="false"
              [value]="asJson(field.name)"
              (input)="setJson(field.name, $any($event.target).value)"
            ></textarea>
            @if (jsonErrors()[field.name]) {
              <p class="field__error">{{ jsonErrors()[field.name] }}</p>
            }
          }

          @default {
            <input
              type="text"
              [id]="controlId(field)"
              [value]="asText(field.name)"
              (input)="set(field.name, $any($event.target).value)"
            />
          }
        }

        @if (optionHelp(field); as help) {
          <!--
            What the chosen option actually does. A selector with forty-five entries is a list of names an
            author has to already know; a sentence about the one they picked is what makes it choosable.
          -->
          <p class="field__hint field__hint--option">{{ help }}</p>
        }
        @if (field.description && field.control !== 'boolean' && field.control !== 'secret') {
          <p class="field__hint">{{ field.description }}</p>
        }
      </div>
    }

    @if (advancedCount() > 0) {
      <!--
        Placed after the fields rather than before them. Advanced fields sort last, so while collapsed this
        button sits exactly where they would begin — which reads as the boundary it is.
      -->
      <button class="advanced-toggle" type="button" (click)="toggleAdvanced()">
        {{ showAdvanced() ? 'Hide' : 'Show' }} {{ advancedCount() }} advanced
        {{ advancedCount() === 1 ? 'setting' : 'settings' }}
      </button>
    }
  `,
  styles: [
    `
      .field__hint--option {
        color: var(--text);
        font-style: italic;
      }

      .advanced-toggle {
        display: block;
        margin: var(--space-2) 0 var(--space-3);
        padding: 0;
        border: none;
        background: none;
        cursor: pointer;
        font-size: var(--text-xs);
        font-weight: 600;
        color: var(--text-muted);
      }

      .advanced-toggle:hover {
        color: var(--text);
      }

      .advanced-toggle:focus-visible {
        outline: none;
        box-shadow: var(--focus-ring);
      }

      /* Quieter than an everyday field, so the eye still lands on the ones that matter. */
      .field--advanced .field__label {
        color: var(--text-muted);
      }

      .secret {
        display: flex;
        flex-direction: column;
        gap: var(--space-1);
      }
    `,
  ],
})
export class SchemaForm {
  private static nextInstance = 0;

  readonly schema = input<ConfigSchema | null>(null);
  readonly value = input<Record<string, unknown> | null>(null);
  /** Secret names offered by the secret-reference picker. */
  readonly secretNames = input<string[]>([]);
  readonly emptyText = input<string>('This node type declares no configuration.');

  readonly valueChange = output<Record<string, unknown>>();

  private readonly instance = SchemaForm.nextInstance++;
  protected readonly listId = `wf-secret-names-${this.instance}`;

  /** Per-field JSON parse errors, so a malformed structure is reported without losing the text. */
  private readonly jsonErrorState = signal<Record<string, string>>({});
  protected readonly jsonErrors = this.jsonErrorState.asReadonly();

  /** Raw text for JSON controls while it is mid-edit and not yet parseable. */
  private readonly jsonDraft = signal<Record<string, string>>({});

  /** Every field the schema declares, whether or not it currently applies. */
  readonly allFields = computed(() => toFields(this.schema()));

  /**
   * The fields to render for the values currently entered.
   *
   * A schema may hide fields that do not apply to the chosen operation — see `visibleWhen`. The hidden ones
   * keep their values and are still submitted; this is a smaller form, not a smaller configuration.
   */
  readonly fields = computed(() => visibleFields(this.allFields(), this.value()));

  /** Whether the advanced block is expanded. Per form instance, and deliberately not remembered. */
  protected readonly showAdvanced = signal(false);

  protected readonly advancedCount = computed(
    () => this.fields().filter((field) => field.advanced).length,
  );

  /**
   * What actually renders: the everyday fields, then the advanced ones when asked for.
   *
   * Advanced fields sort last regardless of the order the schema declared them, so the toggle always marks a
   * clean boundary rather than appearing mid-form.
   */
  protected readonly shownFields = computed(() => {
    const all = this.fields();
    const everyday = all.filter((field) => !field.advanced);
    return this.showAdvanced() ? [...everyday, ...all.filter((field) => field.advanced)] : everyday;
  });

  protected toggleAdvanced(): void {
    this.showAdvanced.update((shown) => !shown);
  }

  /** Whether the field must be filled in right now, which `requiredWhen` can make depend on other values. */
  protected isRequiredNow(field: SchemaField): boolean {
    return isRequired(field, this.value());
  }

  /** Help text for the option currently chosen, or null when there is none. */
  protected optionHelp(field: SchemaField): string | null {
    const chosen = (this.value() ?? {})[field.name];
    if (chosen == null || chosen === '') {
      return null;
    }
    return field.optionDescriptions[String(chosen)] ?? null;
  }

  /**
   * Required fields still empty.
   *
   * Checked against every field, not only the visible ones: a required field the operator cannot currently
   * see is still one the engine will refuse the node without, and reporting it here is how they find out
   * before publishing rather than after.
   */
  readonly missing = computed(() => missingRequired(this.allFields(), this.value()));

  protected controlId(field: SchemaField): string {
    return `wf-field-${this.instance}-${field.name}`;
  }

  protected asText(name: string): string {
    const raw = (this.value() ?? {})[name];
    if (raw == null) {
      return '';
    }
    /*
     * A list in a text control is shown comma-separated, not as JSON.
     *
     * A schema can declare a text field whose stored value is an array, because the engine accepts both forms
     * for list-valued keys such as a form node's candidateGroups. Rendering ["Finance approvers"] into the box
     * shows the author quoting and brackets they then have to maintain by hand; the comma form is what they
     * would have typed, and the server splits it back.
     */
    if (Array.isArray(raw)) {
      return raw.map((item) => String(item)).join(', ');
    }
    return typeof raw === 'object' ? JSON.stringify(raw) : String(raw);
  }

  protected asBoolean(name: string): boolean {
    const raw = (this.value() ?? {})[name];
    return raw === true || raw === 'true';
  }

  protected asMap(name: string): Record<string, unknown> {
    const raw = (this.value() ?? {})[name];
    return raw && typeof raw === 'object' && !Array.isArray(raw)
      ? (raw as Record<string, unknown>)
      : {};
  }

  protected asJson(name: string): string {
    const draft = this.jsonDraft()[name];
    if (draft !== undefined) {
      return draft;
    }
    const raw = (this.value() ?? {})[name];
    if (raw == null) {
      return '';
    }
    try {
      return JSON.stringify(raw, null, 2);
    } catch {
      return String(raw);
    }
  }

  protected coerce(text: string): unknown {
    return coerceScalar(text);
  }

  protected set(name: string, value: unknown): void {
    const next = { ...(this.value() ?? {}) };
    if (value === '' || value == null) {
      // Removing the key rather than storing an empty string keeps the persisted configuration free
      // of noise, and lets the engine apply the schema default.
      delete next[name];
    } else {
      next[name] = value;
    }
    this.valueChange.emit(next);
  }

  protected setJson(name: string, text: string): void {
    this.jsonDraft.update((current) => ({ ...current, [name]: text }));

    if (text.trim().length === 0) {
      this.clearJsonError(name);
      this.set(name, '');
      return;
    }
    try {
      const parsed = JSON.parse(text);
      this.clearJsonError(name);
      this.set(name, parsed);
    } catch (error) {
      // The text is kept as a draft so the operator can finish typing; the value is not committed
      // until it parses, so a half-typed structure never reaches the workflow definition.
      this.jsonErrorState.update((current) => ({
        ...current,
        [name]: error instanceof Error ? error.message : 'Not valid JSON',
      }));
    }
  }

  /** Applies schema defaults to a value, for a parent creating a node. */
  static withDefaults(
    schema: ConfigSchema | null | undefined,
    value: Record<string, unknown> | null | undefined,
  ): Record<string, unknown> {
    return applyDefaults(toFields(schema), value);
  }

  private clearJsonError(name: string): void {
    this.jsonErrorState.update((current) => {
      if (!(name in current)) {
        return current;
      }
      const next = { ...current };
      delete next[name];
      return next;
    });
  }
}
