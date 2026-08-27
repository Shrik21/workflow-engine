import { ChangeDetectionStrategy, Component, computed, effect, input, output, signal } from '@angular/core';
import {
  AbstractControl,
  FormControl,
  FormGroup,
  ReactiveFormsModule,
  ValidatorFn,
  Validators,
} from '@angular/forms';
import { FieldTypeCatalogue, FormField, RenderableForm } from '../../core/models/form.models';

/**
 * Renders a form from its definition.
 *
 * <p>The single renderer used by the designer's Preview and, later, by the human task runtime. That is
 * deliberate and is the reason Preview is worth anything: if preview and runtime had separate
 * implementations they would drift, and the author would be checking their work against something other
 * than what the user will see.
 *
 * <p>Uses Angular reactive forms, so validators are attached from the field definition rather than expressed
 * in the template. Validation here is for immediate feedback only: the server re-validates every submission
 * against the published version, and a client that skipped these checks would simply be refused.
 */
@Component({
  selector: 'wf-dynamic-form',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [ReactiveFormsModule],
  template: `
    <form [formGroup]="group()" (ngSubmit)="onSubmit()" novalidate>
      @if (form().title || form().name) {
        <h3 class="dynamic__title">{{ form().title || form().name }}</h3>
      }

      <div class="dynamic__grid" [style.--columns]="form().columns || 1">
        @for (field of orderedFields(); track field.name || $index) {
          @if (isVisible(field)) {
            <div class="dynamic__cell" [style.--span]="span(field)">
              @switch (field.type) {
                @case ('SECTION') {
                  <h4 class="dynamic__section">{{ field.label }}</h4>
                  @if (field.description) {
                    <p class="small muted">{{ field.description }}</p>
                  }
                }

                @case ('LABEL') {
                  <p class="dynamic__label-only">{{ field.label }}</p>
                }

                @case ('HIDDEN') {
                  <!-- Present in the model and submitted, never shown. -->
                }

                @default {
                  <div class="field">
                    <label class="field__label" [attr.for]="controlId(field)">
                      {{ field.label || field.name }}
                      @if (field.validation.required) {
                        <span class="required" aria-hidden="true">*</span>
                        <span class="sr-only">(required)</span>
                      }
                    </label>

                    @switch (field.type) {
                      @case ('TEXTAREA') {
                        <textarea
                          [id]="controlId(field)"
                          rows="4"
                          [formControlName]="field.name"
                          [placeholder]="field.placeholder || ''"
                        ></textarea>
                      }

                      @case ('CHECKBOX') {
                        <label class="checkbox-row">
                          <input
                            type="checkbox"
                            [id]="controlId(field)"
                            [formControlName]="field.name"
                          />
                          <span class="small muted">{{ field.description || 'Yes' }}</span>
                        </label>
                      }

                      @case ('DROPDOWN') {
                        <select [id]="controlId(field)" [formControlName]="field.name">
                          <option value="">Choose…</option>
                          @for (option of field.options; track option.value) {
                            <option [value]="option.value">{{ option.label || option.value }}</option>
                          }
                        </select>
                      }

                      @case ('RADIO') {
                        <div class="dynamic__choices">
                          @for (option of field.options; track option.value) {
                            <label class="checkbox-row">
                              <input
                                type="radio"
                                [value]="option.value"
                                [formControlName]="field.name"
                              />
                              <span>{{ option.label || option.value }}</span>
                            </label>
                          }
                        </div>
                      }

                      @case ('MULTI_SELECT') {
                        <select [id]="controlId(field)" multiple [formControlName]="field.name" size="4">
                          @for (option of field.options; track option.value) {
                            <option [value]="option.value">{{ option.label || option.value }}</option>
                          }
                        </select>
                      }

                      @case ('CHECKBOX_GROUP') {
                        <div class="dynamic__choices">
                          @for (option of field.options; track option.value) {
                            <label class="checkbox-row">
                              <input
                                type="checkbox"
                                [checked]="isChecked(field, option.value)"
                                [disabled]="field.readOnly === true"
                                (change)="toggleInGroup(field, option.value, $any($event.target).checked)"
                              />
                              <span>{{ option.label || option.value }}</span>
                            </label>
                          }
                        </div>
                      }

                      @default {
                        <input
                          [id]="controlId(field)"
                          [type]="inputType(field)"
                          [formControlName]="field.name"
                          [placeholder]="field.placeholder || ''"
                        />
                      }
                    }

                    @if (field.description && field.type !== 'CHECKBOX') {
                      <p class="field__hint">{{ field.description }}</p>
                    }
                    @if (field.readOnly) {
                      <p class="field__hint">Set by the workflow; not editable here.</p>
                    }
                    @for (message of errorsFor(field); track message) {
                      <p class="field__error">{{ message }}</p>
                    }
                  </div>
                }
              }
            </div>
          }
        }
      </div>

      @if (showActions()) {
        <div class="dynamic__actions">
          @if (allowSubmit()) {
            <button class="btn btn--primary" type="submit" [disabled]="busy()">
              {{ form().submitButtonText || 'Submit' }}
            </button>
          }
          @if (allowSave()) {
            <button class="btn" type="button" [disabled]="busy()" (click)="onSave()">
              {{ form().saveButtonText || 'Save draft' }}
            </button>
          }
          @if (allowSubmit() && group().invalid && group().touched) {
            <span class="small field__error">Some fields need attention.</span>
          }
        </div>
      }
    </form>
  `,
  styles: [
    `
      .dynamic__title {
        margin-bottom: var(--space-4);
      }

      .dynamic__grid {
        display: grid;
        grid-template-columns: repeat(var(--columns, 1), minmax(0, 1fr));
        gap: 0 var(--space-4);
      }

      .dynamic__cell {
        grid-column: span min(var(--span, 1), var(--columns, 1));
        min-width: 0;
      }

      .dynamic__section {
        margin: var(--space-4) 0 var(--space-2);
        padding-bottom: var(--space-1);
        border-bottom: 1px solid var(--border);
      }

      .dynamic__label-only {
        margin: 0 0 var(--space-3);
      }

      .dynamic__choices {
        display: flex;
        flex-direction: column;
        gap: var(--space-1);
      }

      .dynamic__actions {
        display: flex;
        align-items: center;
        gap: var(--space-2);
        margin-top: var(--space-4);
        padding-top: var(--space-4);
        border-top: 1px solid var(--border);
      }
    `,
  ],
})
export class DynamicForm {
  readonly form = input.required<RenderableForm>();

  /** Values to prefill, keyed by field name. In a task these come from the workflow's variables. */
  readonly initialData = input<Record<string, unknown>>({});

  readonly catalogue = input<FieldTypeCatalogue>({});
  readonly showActions = input<boolean>(true);
  /**
   * Whether the Submit button shows and a submit is allowed. Decoupled from {@link showActions} so a form can
   * offer Save Draft without offering Submit — the state a paused or terminated workflow instance puts its form
   * in: editable and saveable, but not submittable.
   */
  readonly allowSubmit = input<boolean>(true);
  readonly allowSave = input<boolean>(false);
  readonly busy = input<boolean>(false);

  /** Preview passes true so nothing can be edited and nothing can be submitted. */
  readonly readOnly = input<boolean>(false);

  readonly submitted = output<Record<string, unknown>>();
  readonly saved = output<Record<string, unknown>>();

  private static nextInstance = 0;
  private readonly instance = DynamicForm.nextInstance++;

  /** Rebuilt whenever the definition changes, which is constantly while the designer is being used. */
  private readonly groupState = signal<FormGroup>(new FormGroup({}));

  protected readonly group = this.groupState.asReadonly();

  protected readonly orderedFields = computed(() =>
    [...this.form().fields].sort((left, right) => (left.order ?? 0) - (right.order ?? 0)),
  );

  constructor() {
    effect(() => {
      // Depends on the definition and the initial data, so editing a field in the designer immediately
      // re-renders Preview with the new validators attached.
      const definition = this.form();
      const initial = this.initialData();
      this.groupState.set(this.build(definition.fields, initial));
    });
  }

  protected controlId(field: FormField): string {
    return `wf-form-${this.instance}-${field.name}`;
  }

  protected span(field: FormField): number {
    return Math.max(1, field.width ?? 1);
  }

  /**
   * Maps a field type to an input type.
   *
   * Only the types a browser input understands. Anything else falls back to text, so an unrecognised field
   * type from a newer server still renders something usable rather than nothing.
   */
  protected inputType(field: FormField): string {
    switch (field.type) {
      case 'NUMBER':
        return 'number';
      case 'EMAIL':
        return 'email';
      case 'PASSWORD':
        return 'password';
      case 'PHONE':
        return 'tel';
      case 'URL':
        return 'url';
      case 'DATE':
        return 'date';
      case 'DATETIME':
        return 'datetime-local';
      case 'TIME':
        return 'time';
      case 'FILE':
      case 'IMAGE':
        return 'file';
      default:
        return 'text';
    }
  }

  /**
   * Whether a conditional field is shown.
   *
   * <p>Only an equality or presence check is interpreted here, and deliberately: the server evaluates the
   * same expression with its safe evaluator, and this must never become a place that runs arbitrary
   * JavaScript. An expression this cannot interpret shows the field, which fails open on display and closed
   * on validation, since the server decides what is actually required.
   */
  protected isVisible(field: FormField): boolean {
    const expression = field.visibilityExpression?.trim();
    if (!expression) {
      return true;
    }
    const match = /^([A-Za-z_][A-Za-z0-9_]*)\s*(==|!=)\s*(.+)$/.exec(expression);
    if (!match) {
      return true;
    }
    const [, name, operator, rawExpected] = match;
    const control = this.group().get(name);
    if (!control) {
      return true;
    }
    const expected = rawExpected.trim().replace(/^['"]|['"]$/g, '');
    const actual = control.value;
    const equal =
      expected === 'true' || expected === 'false'
        ? Boolean(actual) === (expected === 'true')
        : String(actual ?? '') === expected;
    return operator === '==' ? equal : !equal;
  }

  protected errorsFor(field: FormField): string[] {
    const control = this.group().get(field.name);
    if (!control || !control.touched || !control.errors) {
      return [];
    }
    const label = field.label || field.name;
    const messages: string[] = [];
    const errors = control.errors;

    if (errors['required']) {
      messages.push(`${label} is required`);
    }
    if (errors['minlength']) {
      messages.push(`Must be at least ${errors['minlength'].requiredLength} characters`);
    }
    if (errors['maxlength']) {
      messages.push(`Must be no more than ${errors['maxlength'].requiredLength} characters`);
    }
    if (errors['min']) {
      messages.push(`Must be at least ${errors['min'].min}`);
    }
    if (errors['max']) {
      messages.push(`Must be no more than ${errors['max'].max}`);
    }
    if (errors['email']) {
      messages.push('Must be a valid email address');
    }
    if (errors['pattern']) {
      messages.push(field.validation.patternMessage || 'Is not in the expected format');
    }
    return messages;
  }

  protected isChecked(field: FormField, value: string): boolean {
    const current = this.group().get(field.name)?.value;
    return Array.isArray(current) && current.includes(value);
  }

  /** Checkbox groups hold an array, which no single native control produces. */
  protected toggleInGroup(field: FormField, value: string, checked: boolean): void {
    const control = this.group().get(field.name);
    if (!control) {
      return;
    }
    const current: string[] = Array.isArray(control.value) ? [...control.value] : [];
    const next = checked ? [...new Set([...current, value])] : current.filter((v) => v !== value);
    control.setValue(next);
    control.markAsTouched();
  }

  protected onSubmit(): void {
    const group = this.group();
    group.markAllAsTouched();
    if (this.readOnly() || !this.allowSubmit() || group.invalid) {
      return;
    }
    this.submitted.emit(this.collect());
  }

  /** A draft is saved as-is: partial data is the point, so validity is not required. */
  protected onSave(): void {
    if (this.readOnly()) {
      return;
    }
    this.saved.emit(this.collect());
  }

  /**
   * Collects the values, including disabled controls.
   *
   * `group.value` omits disabled controls, which would silently drop every read-only field from the
   * submission. `getRawValue` keeps them, and the server decides what to do with them.
   */
  private collect(): Record<string, unknown> {
    return this.group().getRawValue() as Record<string, unknown>;
  }

  private build(fields: FormField[], initial: Record<string, unknown>): FormGroup {
    const controls: Record<string, AbstractControl> = {};
    const collectsValue = this.valueFieldPredicate();

    for (const field of fields) {
      if (!field.name || !collectsValue(field)) {
        continue;
      }
      const seed = initial[field.name] ?? field.defaultValue ?? this.blankFor(field);
      controls[field.name] = new FormControl(
        { value: seed, disabled: field.readOnly === true || this.readOnly() },
        this.validatorsFor(field),
      );
    }
    return new FormGroup(controls);
  }

  /**
   * Whether a field holds a value, according to the server's catalogue.
   *
   * Falls back to a name check when the catalogue has not loaded, so Preview still works on a cold start
   * rather than rendering controls for section headings.
   */
  private valueFieldPredicate(): (field: FormField) => boolean {
    const catalogue = this.catalogue();
    const known = new Map<string, boolean>();
    Object.values(catalogue)
      .flat()
      .forEach((option) => known.set(option.name, option.collectsValue));

    return (field: FormField) => {
      const declared = known.get(field.type);
      if (declared !== undefined) {
        return declared;
      }
      return field.type !== 'SECTION' && field.type !== 'LABEL';
    };
  }

  private blankFor(field: FormField): unknown {
    switch (field.type) {
      case 'CHECKBOX':
        return false;
      case 'MULTI_SELECT':
      case 'CHECKBOX_GROUP':
        return [];
      default:
        return '';
    }
  }

  private validatorsFor(field: FormField): ValidatorFn[] {
    const rule = field.validation ?? {};
    const validators: ValidatorFn[] = [];

    if (rule.required) {
      // requiredTrue would be wrong for an optional checkbox, but a required checkbox does mean "must tick".
      validators.push(field.type === 'CHECKBOX' ? Validators.requiredTrue : Validators.required);
    }
    if (rule.minLength != null) {
      validators.push(Validators.minLength(rule.minLength));
    }
    if (rule.maxLength != null) {
      validators.push(Validators.maxLength(rule.maxLength));
    }
    if (rule.min != null) {
      validators.push(Validators.min(rule.min));
    }
    if (rule.max != null) {
      validators.push(Validators.max(rule.max));
    }
    if (field.type === 'EMAIL') {
      validators.push(Validators.email);
    }
    if (rule.pattern) {
      try {
        validators.push(Validators.pattern(rule.pattern));
      } catch {
        // A broken pattern is reported by the server at publish time. Skipping it here avoids throwing
        // during render, which would blank the whole form over one bad field.
      }
    }
    return validators;
  }
}
