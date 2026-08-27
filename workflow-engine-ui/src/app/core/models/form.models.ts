/**
 * Form definitions, as read from and written to the engine.
 *
 * The field catalogue is fetched from `GET /api/forms/field-types` rather than hardcoded. The server owns
 * which types exist, what they are called, whether they need options, and which variable types they may map
 * to; the designer renders its palette and its type check from that. A field type added to the backend enum
 * appears here with no change to this file, which is the whole point of the registry.
 */

/** A field type name. A string because the catalogue is server-driven. */
export type FormFieldTypeName = string;

/** Variable data types a field may map to. */
export type VariableDataType =
  | 'STRING'
  | 'INTEGER'
  | 'LONG'
  | 'DOUBLE'
  | 'BOOLEAN'
  | 'DATE'
  | 'DATETIME'
  | 'LIST'
  | 'OBJECT';

/** One field type as published by the server. */
export interface FieldTypeOption {
  name: FormFieldTypeName;
  label: string;
  /** False for presentational types such as SECTION, which are never validated or mapped. */
  collectsValue: boolean;
  /** True for dropdowns and similar, which are invalid without options. */
  hasOptions: boolean;
  compatibleTypes: VariableDataType[];
}

/** The catalogue, keyed by category: `Basic`, `Date and time`, `Choice`, `File`, `Layout`. */
export type FieldTypeCatalogue = Record<string, FieldTypeOption[]>;

export interface FormValidationRule {
  required?: boolean | null;
  minLength?: number | null;
  maxLength?: number | null;
  min?: number | null;
  max?: number | null;
  pattern?: string | null;
  patternMessage?: string | null;
}

export interface FormFieldOption {
  value: string;
  label: string;
}

export interface FormField {
  id?: string | null;
  type: FormFieldTypeName;
  /** Payload key, unique within the form. */
  name: string;
  label?: string | null;
  placeholder?: string | null;
  description?: string | null;
  /** Dotted workflow variable path this field writes, for example `employee.name`. */
  variable?: string | null;
  variableType?: VariableDataType | null;
  defaultValue?: unknown;
  /** Shown but not editable; its value comes from the workflow. */
  readOnly?: boolean | null;
  validation: FormValidationRule;
  options: FormFieldOption[];
  visibilityExpression?: string | null;
  order?: number;
  width?: number;
}

export type FormStatus = 'DRAFT' | 'PUBLISHED' | 'ARCHIVED';

/** The editable head of a form. */
export interface FormDefinition {
  id?: string | null;
  name: string;
  description?: string | null;
  title?: string | null;
  fields: FormField[];
  status?: FormStatus;
  publishedVersion?: number | null;
  columns: number;
  submitButtonText?: string | null;
  saveButtonText?: string | null;
  successMessage?: string | null;
  createdBy?: string | null;
  createdAt?: string | null;
  updatedAt?: string | null;
}

/** A form in the list, without its fields. */
export interface FormSummary {
  id: string;
  name: string;
  description: string | null;
  status: FormStatus;
  publishedVersion: number | null;
  fieldCount: number;
  createdBy: string | null;
  createdAt: string | null;
  updatedAt: string | null;
}

/**
 * One selectable form, from `GET /api/forms/available`.
 *
 * Distinct from {@link FormSummary}, which is the inventory row and carries authorship, timestamps and a
 * field count that a dropdown has no use for. `version` here is the published version a node would pin —
 * the server has already excluded forms that have none, so it is never null in practice, but it is typed
 * nullable because the wire format allows it and a picker that crashed on one bad row would be worse than
 * one that renders it without a version.
 */
export interface AvailableForm {
  id: string;
  name: string;
  description: string | null;
  version: number | null;
  status: FormStatus;
}

/** `Employee Approval Form (v3)`, or just the name when there is no version to show. */
export function formLabel(form: AvailableForm): string {
  return form.version == null ? form.name : `${form.name} (v${form.version})`;
}

/**
 * An immutable published snapshot.
 *
 * This is what a runtime renders. The same shape as a definition minus the editing metadata, which is why
 * the dynamic renderer accepts either.
 */
export interface FormVersion {
  id: string;
  formDefinitionId: string;
  version: number;
  name: string;
  description: string | null;
  title: string | null;
  fields: FormField[];
  columns: number;
  submitButtonText: string | null;
  saveButtonText: string | null;
  successMessage: string | null;
  publishedBy: string | null;
  publishedAt: string | null;
}

/** What the dynamic renderer needs, satisfied by both a draft and a published version. */
export type RenderableForm = Pick<
  FormDefinition,
  'name' | 'title' | 'fields' | 'columns' | 'submitButtonText' | 'saveButtonText'
>;

export function emptyForm(): FormDefinition {
  return {
    name: 'Untitled form',
    description: null,
    title: null,
    fields: [],
    status: 'DRAFT',
    columns: 1,
    submitButtonText: 'Submit',
    saveButtonText: 'Save draft',
    successMessage: 'Submitted successfully',
  };
}

export function emptyValidation(): FormValidationRule {
  return {};
}

/**
 * Derives a payload key from a label.
 *
 * A convenience, not a rule: the designer suggests `employeeName` for "Employee Name" and the author may
 * change it. Suggesting rather than enforcing matters because the name is the submission contract, and
 * silently regenerating it when a label is edited would break any saved draft.
 */
export function suggestFieldName(label: string, taken: string[]): string {
  const base =
    label
      .trim()
      .replace(/[^A-Za-z0-9 ]/g, '')
      .split(/\s+/)
      .filter((part) => part.length > 0)
      .map((part, index) =>
        index === 0
          ? part.charAt(0).toLowerCase() + part.slice(1)
          : part.charAt(0).toUpperCase() + part.slice(1),
      )
      .join('') || 'field';

  const start = /^[A-Za-z]/.test(base) ? base : `field${base}`;
  if (!taken.includes(start)) {
    return start;
  }
  let index = 2;
  while (taken.includes(`${start}${index}`)) {
    index++;
  }
  return `${start}${index}`;
}

/**
 * Problems the designer can see without asking the server.
 *
 * A subset of what publishing checks, and never a replacement for it: the server validates the same rules and
 * is the authority. These are the mistakes worth flagging while the author is still working, because each one
 * is invisible on the canvas.
 */
export function localFormIssues(form: FormDefinition, catalogue: FieldTypeCatalogue): string[] {
  const issues: string[] = [];
  const types = new Map<string, FieldTypeOption>();
  Object.values(catalogue)
    .flat()
    .forEach((option) => types.set(option.name, option));

  if (!form.name?.trim()) {
    issues.push('The form needs a name.');
  }

  const valueFields = form.fields.filter((field) => types.get(field.type)?.collectsValue !== false);
  if (valueFields.length === 0) {
    issues.push('Add at least one field that collects a value.');
  }

  const seen = new Set<string>();
  for (const field of form.fields) {
    const meta = types.get(field.type);
    if (meta && !meta.collectsValue) {
      continue;
    }
    const label = field.label?.trim() || field.name || 'a field';

    if (!field.name?.trim()) {
      issues.push(`"${label}" has no field name.`);
    } else {
      if (!/^[A-Za-z][A-Za-z0-9_]*$/.test(field.name)) {
        issues.push(
          `Field name "${field.name}" must start with a letter and use only letters, digits and underscores.`,
        );
      }
      if (seen.has(field.name)) {
        issues.push(`Field name "${field.name}" is used more than once.`);
      }
      seen.add(field.name);
    }

    if (meta?.hasOptions && field.options.length === 0) {
      issues.push(`"${label}" is a ${meta.label.toLowerCase()} but has no options.`);
    }
    if (!field.variable?.trim()) {
      issues.push(`"${label}" is not mapped to a variable, so its value would be discarded.`);
    } else if (
      field.variableType &&
      meta &&
      meta.compatibleTypes.length > 0 &&
      !meta.compatibleTypes.includes(field.variableType)
    ) {
      issues.push(`"${label}" is a ${meta.label} and cannot write a ${field.variableType} variable.`);
    }
    if (field.readOnly && field.validation.required) {
      issues.push(`"${label}" is read-only and required, so nobody could ever satisfy it.`);
    }
  }
  return issues;
}
