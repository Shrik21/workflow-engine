import { ChangeDetectionStrategy, Component, computed, effect, inject, input, signal } from '@angular/core';
import { Router } from '@angular/router';
import { FormApiService } from '../../core/api/form-api.service';
import {
  FieldTypeOption,
  FormDefinition,
  FormField,
  VariableDataType,
  emptyForm,
  emptyValidation,
  localFormIssues,
  suggestFieldName,
} from '../../core/models/form.models';
import { NotificationService } from '../../core/notification.service';
import { StatusPill } from '../../shared/ui/status-pill';
import { ConfirmDialog, ConfirmRequest } from '../../shared/ui/confirm-dialog';
import { DynamicForm } from './dynamic-form';

/** Variable data types offered in the mapping panel. */
const DATA_TYPES: VariableDataType[] = [
  'STRING',
  'INTEGER',
  'LONG',
  'DOUBLE',
  'BOOLEAN',
  'DATE',
  'DATETIME',
  'LIST',
];

/**
 * The form designer: palette, canvas, and a properties panel.
 *
 * <p>Three things worth knowing about how it behaves:
 *
 * <ul>
 *   <li><b>Preview uses the runtime renderer.</b> {@link DynamicForm} draws the preview and will draw the
 *       real task, so what the author checks is what the user gets. Two implementations would drift and make
 *       Preview worthless.</li>
 *   <li><b>The palette comes from the server.</b> Field types, their labels and which variable types they may
 *       write are fetched from {@code /api/forms/field-types}, so a type added to the backend appears here
 *       with no change to this file.</li>
 *   <li><b>Undo is a snapshot stack.</b> Editing a form is fiddly and a mis-drop is easy, so every mutation
 *       pushes a copy. Twenty deep, which is enough to recover from a mistake and cheap for a document of
 *       this size.</li>
 * </ul>
 */
@Component({
  selector: 'wf-form-designer',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [StatusPill, DynamicForm, ConfirmDialog],
  template: `
    <div class="designer">
      <header class="designer__bar">
        <button class="btn btn--quiet btn--sm" type="button" (click)="leave()">Back</button>

        <input
          class="designer__name"
          type="text"
          aria-label="Form name"
          placeholder="Form name"
          [value]="form().name"
          (input)="patchForm({ name: $any($event.target).value })"
        />

        <wf-status-pill [status]="form().status ?? 'DRAFT'" />
        @if (form().publishedVersion) {
          <span class="tag" title="The version workflow nodes can reference"
            >v{{ form().publishedVersion }} published</span
          >
        }
        @if (dirty()) {
          <span class="tag tag--dirty">unsaved</span>
        }

        <span class="spacer"></span>

        <div class="btn-group">
          <button
            class="btn btn--sm"
            type="button"
            [class.btn--primary]="mode() === 'design'"
            (click)="mode.set('design')"
          >
            Design
          </button>
          <button
            class="btn btn--sm"
            type="button"
            [class.btn--primary]="mode() === 'preview'"
            (click)="mode.set('preview')"
          >
            Preview
          </button>
        </div>

        <button class="btn btn--sm" type="button" [disabled]="!canUndo()" (click)="undo()">Undo</button>
        <button class="btn btn--sm" type="button" [disabled]="!canRedo()" (click)="redo()">Redo</button>
        <button class="btn btn--primary btn--sm" type="button" [disabled]="busy()" (click)="save()">
          Save
        </button>
        <button
          class="btn btn--accent btn--sm"
          type="button"
          [disabled]="busy() || !form().id"
          title="Snapshots an immutable version that workflow nodes can reference"
          (click)="publish()"
        >
          Publish
        </button>
      </header>

      @if (issues().length > 0) {
        <div class="designer__issues">
          <strong class="small">{{ issues().length }} thing(s) to fix before publishing</strong>
          <ul>
            @for (issue of issues(); track issue) {
              <li>{{ issue }}</li>
            }
          </ul>
        </div>
      }

      @if (mode() === 'preview') {
        <div class="preview">
          <div class="card preview__card">
            <div class="card__header">
              <h3>Preview</h3>
              <span class="spacer"></span>
              <span class="small muted">
                Rendered by the same component the task runtime uses. Submitting here does nothing.
              </span>
            </div>
            <div class="card__body">
              @if (form().fields.length === 0) {
                <p class="small muted">Add a field to see it here.</p>
              } @else {
                <wf-dynamic-form
                  [form]="form()"
                  [catalogue]="api.catalogue()"
                  [readOnly]="true"
                  [allowSave]="true"
                />
              }
            </div>
          </div>
        </div>
      } @else {
        <div class="designer__panes">
          <aside class="designer__palette">
            <div class="palette__search">
              <input
                type="search"
                placeholder="Search field types"
                aria-label="Search field types"
                [value]="paletteQuery()"
                (input)="paletteQuery.set($any($event.target).value)"
              />
            </div>
            @if (paletteGroups().length === 0) {
              <p class="palette__empty small muted">
                No field types match. The catalogue comes from the server.
              </p>
            }
            @for (group of paletteGroups(); track group.category) {
              <div class="palette__group">
                <h4>{{ group.category }}</h4>
                @for (option of group.options; track option.name) {
                  <div
                    class="palette__item"
                    draggable="true"
                    tabindex="0"
                    role="button"
                    [attr.aria-label]="'Add a ' + option.label + ' field'"
                    (dragstart)="onPaletteDragStart($event, option)"
                    (dblclick)="addField(option)"
                    (keydown.enter)="addField(option)"
                  >
                    <span>{{ option.label }}</span>
                    @if (!option.collectsValue) {
                      <span class="tag" title="Presentational: collects no value">layout</span>
                    }
                  </div>
                }
              </div>
            }
            <p class="palette__hint small faint">Drag onto the canvas, or double-click to append.</p>
          </aside>

          <main
            class="designer__canvas"
            (dragover)="onCanvasDragOver($event)"
            (drop)="onCanvasDrop($event, form().fields.length)"
          >
            <div class="canvas__sheet" [style.--columns]="form().columns || 1">
              <div class="canvas__head">
                <input
                  type="text"
                  class="canvas__title"
                  aria-label="Form title"
                  placeholder="Form title shown to the user"
                  [value]="form().title ?? ''"
                  (input)="patchForm({ title: $any($event.target).value })"
                />
                <div class="row">
                  <label class="small muted" for="columns">Layout</label>
                  <select
                    id="columns"
                    [value]="asText(form().columns || 1)"
                    (change)="patchForm({ columns: +$any($event.target).value })"
                  >
                    <option value="1">1 column</option>
                    <option value="2">2 columns</option>
                    <option value="3">3 columns</option>
                  </select>
                </div>
              </div>

              @if (form().fields.length === 0) {
                <p class="canvas__empty">
                  Drag a field type from the palette to start building this form.
                </p>
              }

              @for (field of orderedFields(); track field.id || $index) {
                <div
                  class="canvas__field"
                  [class.canvas__field--selected]="selectedIndex() === $index"
                  [style.--span]="field.width || 1"
                  draggable="true"
                  tabindex="0"
                  role="button"
                  [attr.aria-label]="'Select ' + (field.label || field.name)"
                  (click)="select($index)"
                  (focus)="select($index)"
                  (dragstart)="onFieldDragStart($event, $index)"
                  (dragover)="onCanvasDragOver($event)"
                  (drop)="onCanvasDrop($event, $index)"
                >
                  <div class="canvas__field-head">
                    <strong>{{ field.label || field.name || 'Untitled field' }}</strong>
                    @if (field.validation.required) {
                      <span class="required" aria-hidden="true">*</span>
                    }
                    <span class="tag">{{ typeLabel(field.type) }}</span>
                    <span class="spacer"></span>
                    <button
                      class="btn btn--quiet btn--sm"
                      type="button"
                      aria-label="Move up"
                      [disabled]="$index === 0"
                      (click)="move($index, -1); $event.stopPropagation()"
                    >
                      &uarr;
                    </button>
                    <button
                      class="btn btn--quiet btn--sm"
                      type="button"
                      aria-label="Move down"
                      [disabled]="$index === form().fields.length - 1"
                      (click)="move($index, 1); $event.stopPropagation()"
                    >
                      &darr;
                    </button>
                    <button
                      class="btn btn--quiet btn--sm"
                      type="button"
                      (click)="duplicate($index); $event.stopPropagation()"
                    >
                      Copy
                    </button>
                    <button
                      class="btn btn--danger btn--sm"
                      type="button"
                      (click)="remove($index); $event.stopPropagation()"
                    >
                      Delete
                    </button>
                  </div>
                  <div class="canvas__field-meta small">
                    <span class="mono">{{ field.name || 'unnamed' }}</span>
                    @if (field.variable) {
                      <span class="mono canvas__mapped">&rarr; {{ field.variable }}</span>
                    } @else {
                      <span class="canvas__unmapped">not mapped to a variable</span>
                    }
                  </div>
                </div>
              }
            </div>
          </main>

          <aside class="designer__properties">
            @if (selected(); as field) {
              <div class="props">
                <header class="props__header">
                  <strong>{{ field.label || field.name }}</strong>
                  <span class="tag">{{ typeLabel(field.type) }}</span>
                </header>

                <div class="props__body">
                  <div class="field">
                    <label class="field__label" for="p-label">Label</label>
                    <input
                      id="p-label"
                      type="text"
                      [value]="field.label ?? ''"
                      (input)="patchField({ label: $any($event.target).value })"
                    />
                  </div>

                  <div class="field">
                    <label class="field__label" for="p-name">Field name</label>
                    <input
                      id="p-name"
                      type="text"
                      class="mono"
                      [value]="field.name"
                      (input)="patchField({ name: $any($event.target).value })"
                    />
                    <p class="field__hint">
                      The key the submission uses. Letters, digits and underscores, starting with a letter.
                      Changing it after a draft has been saved breaks that draft.
                    </p>
                  </div>

                  @if (collectsValue(field.type)) {
                    <div class="field">
                      <label class="field__label" for="p-variable">Workflow variable</label>
                      <input
                        id="p-variable"
                        type="text"
                        class="mono"
                        placeholder="employee.name"
                        [value]="field.variable ?? ''"
                        (input)="patchField({ variable: $any($event.target).value })"
                      />
                      <p class="field__hint">
                        Dotted path the value is written to on submission. The server does the mapping using
                        its own copy of this form, so the browser cannot redirect a value elsewhere.
                      </p>
                    </div>

                    <div class="field">
                      <label class="field__label" for="p-vartype">Variable type</label>
                      <select
                        id="p-vartype"
                        [value]="field.variableType ?? ''"
                        (change)="patchField({ variableType: $any($event.target).value || null })"
                      >
                        <option value="">Unchecked</option>
                        @for (type of dataTypes; track type) {
                          <option [value]="type" [disabled]="!isCompatible(field.type, type)">
                            {{ type }}{{ isCompatible(field.type, type) ? '' : ' (incompatible)' }}
                          </option>
                        }
                      </select>
                      <p class="field__hint">
                        Checked when the form is published. Catches mapping a checkbox to a number, which
                        otherwise surfaces as a decision node taking the wrong branch.
                      </p>
                    </div>

                    <div class="field">
                      <label class="field__label" for="p-placeholder">Placeholder</label>
                      <input
                        id="p-placeholder"
                        type="text"
                        [value]="field.placeholder ?? ''"
                        (input)="patchField({ placeholder: $any($event.target).value })"
                      />
                    </div>

                    <div class="field">
                      <label class="field__label" for="p-default">Default value</label>
                      <input
                        id="p-default"
                        type="text"
                        placeholder="literal, or &#36;{employee.name}"
                        [value]="asText(field.defaultValue)"
                        (input)="patchField({ defaultValue: $any($event.target).value })"
                      />
                      <p class="field__hint">
                        Placeholders are resolved from the execution's variables when the task is created, so
                        a form can be prefilled from an earlier node.
                      </p>
                    </div>

                    <label class="checkbox-row">
                      <input
                        type="checkbox"
                        [checked]="field.validation.required === true"
                        (change)="patchValidation({ required: $any($event.target).checked })"
                      />
                      <span>Required</span>
                    </label>

                    <label class="checkbox-row">
                      <input
                        type="checkbox"
                        [checked]="field.readOnly === true"
                        (change)="patchField({ readOnly: $any($event.target).checked })"
                      />
                      <span>Read-only</span>
                    </label>
                    <p class="field__hint">
                      Shown but not editable. Its value comes from the workflow, so whatever the browser sends
                      for it is ignored.
                    </p>

                    @if (hasOptions(field.type)) {
                      <div class="divider"></div>
                      <span class="field__label">Options</span>
                      <p class="field__hint">
                        A submitted value must be one of these. The server enforces it, so a decision node
                        can only ever see a value defined here.
                      </p>
                      @for (option of field.options; track $index) {
                        <div class="option-row">
                          <input
                            type="text"
                            class="mono"
                            placeholder="value"
                            aria-label="Option value"
                            [value]="option.value"
                            (input)="patchOption($index, { value: $any($event.target).value })"
                          />
                          <input
                            type="text"
                            placeholder="label"
                            aria-label="Option label"
                            [value]="option.label"
                            (input)="patchOption($index, { label: $any($event.target).value })"
                          />
                          <button
                            class="btn btn--quiet btn--sm"
                            type="button"
                            (click)="removeOption($index)"
                          >
                            Remove
                          </button>
                        </div>
                      }
                      <button class="btn btn--sm" type="button" (click)="addOption()">Add option</button>
                    }

                    <div class="divider"></div>
                    <span class="field__label">Validation</span>
                    <div class="grid-tight">
                      <div class="field">
                        <label class="field__label" for="p-minlen">Min length</label>
                        <input
                          id="p-minlen"
                          type="number"
                          min="0"
                          [value]="field.validation.minLength ?? ''"
                          (input)="patchValidation({ minLength: toNumber($any($event.target).value) })"
                        />
                      </div>
                      <div class="field">
                        <label class="field__label" for="p-maxlen">Max length</label>
                        <input
                          id="p-maxlen"
                          type="number"
                          min="0"
                          [value]="field.validation.maxLength ?? ''"
                          (input)="patchValidation({ maxLength: toNumber($any($event.target).value) })"
                        />
                      </div>
                      <div class="field">
                        <label class="field__label" for="p-min">Min</label>
                        <input
                          id="p-min"
                          type="number"
                          [value]="field.validation.min ?? ''"
                          (input)="patchValidation({ min: toNumber($any($event.target).value) })"
                        />
                      </div>
                      <div class="field">
                        <label class="field__label" for="p-max">Max</label>
                        <input
                          id="p-max"
                          type="number"
                          [value]="field.validation.max ?? ''"
                          (input)="patchValidation({ max: toNumber($any($event.target).value) })"
                        />
                      </div>
                    </div>

                    <div class="field">
                      <label class="field__label" for="p-pattern">Pattern</label>
                      <input
                        id="p-pattern"
                        type="text"
                        class="mono"
                        placeholder="^[A-Z]+$"
                        [value]="field.validation.pattern ?? ''"
                        (input)="patchValidation({ pattern: $any($event.target).value || null })"
                      />
                    </div>
                    <div class="field">
                      <label class="field__label" for="p-patternmsg">Pattern message</label>
                      <input
                        id="p-patternmsg"
                        type="text"
                        placeholder="Upper-case letters only"
                        [value]="field.validation.patternMessage ?? ''"
                        (input)="patchValidation({ patternMessage: $any($event.target).value || null })"
                      />
                      <p class="field__hint">A regular expression is not an explanation. Write one.</p>
                    </div>

                    <div class="divider"></div>
                    <div class="field">
                      <label class="field__label" for="p-visible">Show only when</label>
                      <input
                        id="p-visible"
                        type="text"
                        class="mono"
                        placeholder="approvalRequired == true"
                        [value]="field.visibilityExpression ?? ''"
                        (input)="patchField({ visibilityExpression: $any($event.target).value || null })"
                      />
                      <p class="field__hint">
                        Compares another field on this form to a value. Evaluated by the engine's safe
                        evaluator, never as JavaScript.
                      </p>
                    </div>
                  } @else {
                    <p class="small muted">
                      This is a presentational field. It collects no value, so it has no variable, no default
                      and no validation.
                    </p>
                  }

                  <div class="divider"></div>
                  <div class="field">
                    <label class="field__label" for="p-width">Width</label>
                    <select
                      id="p-width"
                      [value]="asText(field.width || 1)"
                      (change)="patchField({ width: +$any($event.target).value })"
                    >
                      <option value="1">1 column</option>
                      <option value="2">2 columns</option>
                      <option value="3">Full width</option>
                    </select>
                  </div>

                  <div class="field">
                    <label class="field__label" for="p-desc">Help text</label>
                    <input
                      id="p-desc"
                      type="text"
                      [value]="field.description ?? ''"
                      (input)="patchField({ description: $any($event.target).value })"
                    />
                  </div>
                </div>
              </div>
            } @else {
              <div class="props props--empty">
                <p class="small muted">Select a field to edit it.</p>
                <div class="divider"></div>
                <div class="field">
                  <label class="field__label" for="f-desc">Form description</label>
                  <input
                    id="f-desc"
                    type="text"
                    [value]="form().description ?? ''"
                    (input)="patchForm({ description: $any($event.target).value })"
                  />
                </div>
                <div class="field">
                  <label class="field__label" for="f-submit">Submit button</label>
                  <input
                    id="f-submit"
                    type="text"
                    [value]="form().submitButtonText ?? ''"
                    (input)="patchForm({ submitButtonText: $any($event.target).value })"
                  />
                </div>
                <div class="field">
                  <label class="field__label" for="f-success">Success message</label>
                  <input
                    id="f-success"
                    type="text"
                    [value]="form().successMessage ?? ''"
                    (input)="patchForm({ successMessage: $any($event.target).value })"
                  />
                </div>
              </div>
            }
          </aside>
        </div>
      }
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
  styles: [
    `
      :host {
        display: block;
        height: 100%;
        min-height: 0;
      }

      .designer {
        display: flex;
        flex-direction: column;
        height: 100%;
        min-height: 0;
      }

      .designer__bar {
        display: flex;
        align-items: center;
        gap: var(--space-2);
        padding: var(--space-2) var(--space-3);
        background: var(--surface);
        border-bottom: 1px solid var(--border);
        flex-wrap: wrap;
      }

      .designer__name {
        width: 260px;
        font-family: var(--font-brand);
        font-size: var(--text-md);
        font-weight: 600;
      }

      .tag--dirty {
        background: #fff3e0;
        color: var(--hl-orange-alt);
      }

      .designer__issues {
        padding: var(--space-2) var(--space-4);
        background: #fdecec;
        border-bottom: 1px solid var(--border);
        max-height: 130px;
        overflow-y: auto;
      }

      .designer__issues ul {
        margin: var(--space-1) 0 0;
        padding-left: 20px;
        font-size: var(--text-sm);
      }

      .designer__panes {
        flex: 1;
        min-height: 0;
        display: grid;
        grid-template-columns: 210px 1fr 340px;
      }

      .designer__palette,
      .designer__properties {
        background: var(--surface);
        min-height: 0;
        overflow-y: auto;
      }

      .designer__palette {
        border-right: 1px solid var(--border);
      }

      .designer__properties {
        border-left: 1px solid var(--border);
      }

      .palette__search {
        position: sticky;
        top: 0;
        padding: var(--space-3);
        background: var(--surface);
        border-bottom: 1px solid var(--border);
      }

      .palette__group {
        padding: var(--space-3) var(--space-3) 0;
      }

      .palette__group h4 {
        font-size: var(--text-xs);
        text-transform: uppercase;
        letter-spacing: 0.7px;
        color: var(--text-muted);
        margin-bottom: var(--space-2);
      }

      .palette__item {
        display: flex;
        align-items: center;
        gap: var(--space-2);
        padding: var(--space-2);
        margin-bottom: var(--space-2);
        border: 1px solid var(--border);
        border-left: 3px solid var(--hl-accent-blue);
        border-radius: var(--radius-sm);
        cursor: grab;
        font-size: var(--text-base);
        user-select: none;
      }

      .palette__item:hover {
        background: var(--hl-grey-50);
      }

      .palette__item:focus-visible {
        outline: none;
        box-shadow: var(--focus-ring);
      }

      .palette__empty,
      .palette__hint {
        padding: 0 var(--space-3);
      }

      .designer__canvas {
        min-width: 0;
        min-height: 0;
        overflow-y: auto;
        padding: var(--space-5);
        background: var(--surface-sunken);
      }

      .canvas__sheet {
        max-width: 900px;
        margin: 0 auto;
        background: var(--surface);
        border: 1px solid var(--border);
        border-radius: var(--radius);
        padding: var(--space-4);
        display: grid;
        grid-template-columns: repeat(var(--columns, 1), minmax(0, 1fr));
        gap: var(--space-3);
      }

      .canvas__head {
        grid-column: 1 / -1;
        display: flex;
        align-items: center;
        gap: var(--space-3);
        padding-bottom: var(--space-3);
        border-bottom: 1px solid var(--border);
      }

      .canvas__title {
        flex: 1;
        font-family: var(--font-brand);
        font-size: var(--text-lg);
        font-weight: 600;
        border-color: transparent;
      }

      .canvas__title:hover {
        border-color: var(--border);
      }

      .canvas__empty {
        grid-column: 1 / -1;
        padding: var(--space-7) var(--space-4);
        text-align: center;
        color: var(--text-muted);
        border: 2px dashed var(--border);
        border-radius: var(--radius);
      }

      .canvas__field {
        grid-column: span min(var(--span, 1), var(--columns, 1));
        border: 1px solid var(--border);
        border-left: 3px solid var(--hl-grey-400);
        border-radius: var(--radius-sm);
        padding: var(--space-2) var(--space-3);
        cursor: grab;
        min-width: 0;
      }

      .canvas__field:hover {
        background: var(--hl-grey-50);
      }

      .canvas__field:focus-visible {
        outline: none;
        box-shadow: var(--focus-ring);
      }

      .canvas__field--selected {
        border-left-color: var(--hl-blue);
        box-shadow: 0 0 0 2px rgba(0, 45, 91, 0.18);
      }

      .canvas__field-head {
        display: flex;
        align-items: center;
        gap: var(--space-2);
        flex-wrap: wrap;
      }

      .canvas__field-meta {
        display: flex;
        gap: var(--space-3);
        color: var(--text-muted);
        margin-top: 2px;
      }

      .canvas__mapped {
        color: var(--hl-green-alt);
      }

      .canvas__unmapped {
        color: var(--hl-orange-alt);
      }

      .props__header {
        display: flex;
        align-items: center;
        gap: var(--space-2);
        padding: var(--space-3);
        border-bottom: 1px solid var(--border);
      }

      .props__body,
      .props--empty {
        padding: var(--space-4);
      }

      .grid-tight {
        display: grid;
        grid-template-columns: 1fr 1fr;
        gap: var(--space-3);
      }

      .option-row {
        display: grid;
        grid-template-columns: 1fr 1fr auto;
        gap: var(--space-2);
        margin-bottom: var(--space-2);
      }

      .preview {
        flex: 1;
        min-height: 0;
        overflow-y: auto;
        padding: var(--space-5);
        background: var(--surface-sunken);
      }

      .preview__card {
        max-width: 900px;
        margin: 0 auto;
      }

      @media (max-width: 1200px) {
        .designer__panes {
          grid-template-columns: 180px 1fr 300px;
        }
      }
    `,
  ],
})
export class FormDesigner {
  /** Route parameter: a form id, or `new` for a blank draft. */
  readonly id = input<string | undefined>(undefined);

  protected readonly dataTypes = DATA_TYPES;
  protected readonly api = inject(FormApiService);

  private readonly notifications = inject(NotificationService);
  private readonly router = inject(Router);

  protected readonly form = signal<FormDefinition>(emptyForm());
  protected readonly selectedIndex = signal<number | null>(null);
  protected readonly mode = signal<'design' | 'preview'>('design');
  protected readonly busy = signal(false);
  protected readonly dirty = signal(false);
  protected readonly pendingConfirm = signal<ConfirmRequest | null>(null);
  protected readonly paletteQuery = signal('');
  protected readonly serverErrors = signal<string[]>([]);

  /** Snapshot stacks. A mis-drop is easy in a designer, so undo is not optional. */
  private readonly undoStack = signal<FormDefinition[]>([]);
  private readonly redoStack = signal<FormDefinition[]>([]);

  protected readonly canUndo = computed(() => this.undoStack().length > 0);
  protected readonly canRedo = computed(() => this.redoStack().length > 0);

  protected readonly orderedFields = computed(() =>
    [...this.form().fields].sort((left, right) => (left.order ?? 0) - (right.order ?? 0)),
  );

  protected readonly selected = computed(() => {
    const index = this.selectedIndex();
    return index == null ? null : (this.orderedFields()[index] ?? null);
  });

  protected readonly issues = computed(() => {
    const local = localFormIssues(this.form(), this.api.catalogue());
    for (const error of this.serverErrors()) {
      if (!local.includes(error)) {
        local.push(error);
      }
    }
    return local;
  });

  protected readonly paletteGroups = computed(() => {
    const term = this.paletteQuery().trim().toLowerCase();
    return Object.entries(this.api.catalogue())
      .map(([category, options]) => ({
        category,
        options: options.filter(
          (option) =>
            !term ||
            option.label.toLowerCase().includes(term) ||
            option.name.toLowerCase().includes(term),
        ),
      }))
      .filter((group) => group.options.length > 0);
  });

  constructor() {
    this.api.ensureCatalogue();

    effect(() => {
      const id = this.id();
      if (!id || id === 'new') {
        this.form.set(emptyForm());
        this.dirty.set(false);
        return;
      }
      this.api.get(id).subscribe({
        next: (loaded) => {
          // Normalise: the server omits empty collections, and the editor needs them present.
          loaded.fields = (loaded.fields ?? []).map((field, index) => ({
            ...field,
            order: field.order ?? index,
            options: field.options ?? [],
            validation: field.validation ?? emptyValidation(),
          }));
          this.form.set(loaded);
          this.dirty.set(false);
          this.undoStack.set([]);
          this.redoStack.set([]);
        },
        error: () => this.router.navigate(['/forms']),
      });
    });
  }

  // ------------------------------------------------------------------ catalogue

  private option(type: string): FieldTypeOption | undefined {
    return Object.values(this.api.catalogue())
      .flat()
      .find((candidate) => candidate.name === type);
  }

  protected typeLabel(type: string): string {
    return this.option(type)?.label ?? type;
  }

  protected collectsValue(type: string): boolean {
    const declared = this.option(type);
    return declared ? declared.collectsValue : type !== 'SECTION' && type !== 'LABEL';
  }

  protected hasOptions(type: string): boolean {
    return this.option(type)?.hasOptions ?? false;
  }

  protected isCompatible(type: string, dataType: VariableDataType): boolean {
    const declared = this.option(type);
    return !declared || declared.compatibleTypes.length === 0
      ? true
      : declared.compatibleTypes.includes(dataType);
  }

  // ----------------------------------------------------------- drag and drop

  protected onPaletteDragStart(event: DragEvent, option: FieldTypeOption): void {
    // The type alone: everything else is read from the catalogue at drop time, so the payload cannot go
    // stale between the drag starting and the drop landing.
    event.dataTransfer?.setData('text/plain', `new:${option.name}`);
    if (event.dataTransfer) {
      event.dataTransfer.effectAllowed = 'copy';
    }
  }

  protected onFieldDragStart(event: DragEvent, index: number): void {
    event.dataTransfer?.setData('text/plain', `move:${index}`);
    if (event.dataTransfer) {
      event.dataTransfer.effectAllowed = 'move';
    }
  }

  protected onCanvasDragOver(event: DragEvent): void {
    // Both calls are needed for a drop to be accepted in every browser.
    event.preventDefault();
    if (event.dataTransfer) {
      event.dataTransfer.dropEffect = 'move';
    }
  }

  protected onCanvasDrop(event: DragEvent, targetIndex: number): void {
    event.preventDefault();
    event.stopPropagation();
    const payload = event.dataTransfer?.getData('text/plain');
    if (!payload) {
      return;
    }

    if (payload.startsWith('new:')) {
      const option = this.option(payload.slice(4));
      if (option) {
        this.addField(option, targetIndex);
      }
      return;
    }
    if (payload.startsWith('move:')) {
      const from = Number(payload.slice(5));
      if (Number.isFinite(from)) {
        this.reorder(from, targetIndex);
      }
    }
  }

  // ---------------------------------------------------------------- mutations

  protected addField(option: FieldTypeOption, at?: number): void {
    this.pushUndo();
    const fields = [...this.orderedFields()];
    const taken = fields.map((field) => field.name).filter((name): name is string => !!name);
    const label = option.label;

    const field: FormField = {
      id: null,
      type: option.name,
      name: option.collectsValue ? suggestFieldName(label, taken) : suggestFieldName(label, taken),
      label,
      validation: emptyValidation(),
      options: option.hasOptions
        ? [
            { value: 'option1', label: 'Option 1' },
            { value: 'option2', label: 'Option 2' },
          ]
        : [],
      // Pre-filled with the type's natural variable type, so the compatibility check is meaningful from
      // the moment the field exists rather than only once someone remembers to set it.
      variableType: option.collectsValue ? (option.compatibleTypes[0] ?? null) : null,
      width: 1,
    };

    const index = at == null ? fields.length : Math.max(0, Math.min(at, fields.length));
    fields.splice(index, 0, field);
    this.commit(fields);
    this.selectedIndex.set(index);
  }

  protected select(index: number): void {
    this.selectedIndex.set(index);
  }

  protected move(index: number, delta: number): void {
    this.reorder(index, index + delta);
  }

  private reorder(from: number, to: number): void {
    const fields = [...this.orderedFields()];
    if (from < 0 || from >= fields.length) {
      return;
    }
    const target = Math.max(0, Math.min(to, fields.length - 1));
    if (from === target) {
      return;
    }
    this.pushUndo();
    const [moved] = fields.splice(from, 1);
    fields.splice(target, 0, moved);
    this.commit(fields);
    this.selectedIndex.set(target);
  }

  protected duplicate(index: number): void {
    const fields = [...this.orderedFields()];
    const source = fields[index];
    if (!source) {
      return;
    }
    this.pushUndo();
    const taken = fields.map((field) => field.name).filter((name): name is string => !!name);
    // A fresh id and name: sharing either would make the copy overwrite the original on submission.
    const copy: FormField = {
      ...structuredCopy(source),
      id: null,
      name: suggestFieldName(source.label || source.name, taken),
    };
    fields.splice(index + 1, 0, copy);
    this.commit(fields);
    this.selectedIndex.set(index + 1);
  }

  protected remove(index: number): void {
    const fields = [...this.orderedFields()];
    if (!fields[index]) {
      return;
    }
    this.pushUndo();
    fields.splice(index, 1);
    this.commit(fields);
    this.selectedIndex.set(null);
  }

  protected patchForm(patch: Partial<FormDefinition>): void {
    this.form.update((current) => ({ ...current, ...patch }));
    this.dirty.set(true);
  }

  protected patchField(patch: Partial<FormField>): void {
    const index = this.selectedIndex();
    if (index == null) {
      return;
    }
    const fields = [...this.orderedFields()];
    fields[index] = { ...fields[index], ...patch };
    this.commit(fields);
  }

  protected patchValidation(patch: Partial<FormField['validation']>): void {
    const index = this.selectedIndex();
    if (index == null) {
      return;
    }
    const fields = [...this.orderedFields()];
    fields[index] = {
      ...fields[index],
      validation: { ...fields[index].validation, ...patch },
    };
    this.commit(fields);
  }

  protected addOption(): void {
    const field = this.selected();
    if (!field) {
      return;
    }
    const next = field.options.length + 1;
    this.patchField({
      options: [...field.options, { value: `option${next}`, label: `Option ${next}` }],
    });
  }

  protected patchOption(index: number, patch: Partial<{ value: string; label: string }>): void {
    const field = this.selected();
    if (!field) {
      return;
    }
    const options = field.options.map((option, position) =>
      position === index ? { ...option, ...patch } : option,
    );
    this.patchField({ options });
  }

  protected removeOption(index: number): void {
    const field = this.selected();
    if (!field) {
      return;
    }
    this.patchField({ options: field.options.filter((_, position) => position !== index) });
  }

  /** Writes the field list back, renumbering order so it always matches display position. */
  private commit(fields: FormField[]): void {
    this.form.update((current) => ({
      ...current,
      fields: fields.map((field, index) => ({ ...field, order: index })),
    }));
    this.dirty.set(true);
  }

  // --------------------------------------------------------------- undo/redo

  private pushUndo(): void {
    this.undoStack.update((stack) => [...stack.slice(-19), structuredCopy(this.form())]);
    this.redoStack.set([]);
  }

  protected undo(): void {
    const stack = this.undoStack();
    const previous = stack[stack.length - 1];
    if (!previous) {
      return;
    }
    this.redoStack.update((redo) => [...redo, structuredCopy(this.form())]);
    this.undoStack.set(stack.slice(0, -1));
    this.form.set(previous);
    this.selectedIndex.set(null);
    this.dirty.set(true);
  }

  protected redo(): void {
    const stack = this.redoStack();
    const next = stack[stack.length - 1];
    if (!next) {
      return;
    }
    this.undoStack.update((undo) => [...undo, structuredCopy(this.form())]);
    this.redoStack.set(stack.slice(0, -1));
    this.form.set(next);
    this.selectedIndex.set(null);
    this.dirty.set(true);
  }

  // ------------------------------------------------------------------ actions

  protected save(): void {
    const current = this.form();
    if (!current.name?.trim()) {
      this.notifications.error('The form needs a name before it can be saved.');
      return;
    }
    this.busy.set(true);
    const call = current.id
      ? this.api.update(current.id, current)
      : this.api.create(current);

    call.subscribe({
      next: (saved) => {
        this.busy.set(false);
        this.form.set({ ...saved, fields: saved.fields ?? [] });
        this.dirty.set(false);
        this.notifications.success(`Saved "${saved.name}"`);
        if (!current.id && saved.id) {
          // Replace the URL so a reload returns to the saved form rather than a blank draft.
          this.router.navigate(['/forms', saved.id], { replaceUrl: true });
        }
      },
      error: () => this.busy.set(false),
    });
  }

  protected publish(): void {
    const current = this.form();
    if (!current.id) {
      return;
    }
    if (this.dirty()) {
      this.notifications.info('Saving before publishing');
      this.save();
    }
    this.busy.set(true);
    this.api.publish(current.id).subscribe({
      next: (version) => {
        this.busy.set(false);
        this.serverErrors.set([]);
        this.form.update((form) => ({
          ...form,
          status: 'PUBLISHED',
          publishedVersion: version.version,
        }));
        this.notifications.success(
          `Published version ${version.version}`,
          'Workflow form nodes can now reference this version. Editing the draft will not change it.',
        );
      },
      error: (failure) => {
        this.busy.set(false);
        const details = failure?.error?.details;
        this.serverErrors.set(Array.isArray(details) ? details : []);
      },
    });
  }

  protected leave(): void {
    if (this.dirty()) {
      this.pendingConfirm.set({
        heading: 'Leave without saving?',
        message: 'You have unsaved changes to this form. If you leave now, they will not be kept.',
        confirmLabel: 'Leave without saving',
        danger: true,
        onConfirm: () => this.router.navigate(['/forms']),
      });
      return;
    }
    this.router.navigate(['/forms']);
  }

  /** Runs the pending confirmed action, then clears the dialog. */
  protected runConfirmed(): void {
    const request = this.pendingConfirm();
    this.pendingConfirm.set(null);
    request?.onConfirm();
  }

  protected asText(value: unknown): string {
    return value == null ? '' : String(value);
  }

  protected toNumber(value: string): number | null {
    if (!value?.trim()) {
      return null;
    }
    const parsed = Number(value);
    return Number.isFinite(parsed) ? parsed : null;
  }
}

/** structuredClone is not available in every supported browser; a JSON round-trip is enough here. */
function structuredCopy<T>(value: T): T {
  return JSON.parse(JSON.stringify(value)) as T;
}
