import { ConfigProperty, ConfigSchema } from '../../core/models/node.models';

/**
 * Translates a plugin's published configuration schema into a list of controls to render.
 *
 * Pure functions, deliberately separated from the component that renders them, so the mapping rules
 * are unit-testable without a DOM. They are the load-bearing piece of the "no front-end release when
 * a plugin is added" claim, and a subtle mistake here shows up as a control that silently drops the
 * operator's input.
 */

export type ControlKind =
  | 'text'
  | 'textarea'
  | 'number'
  | 'boolean'
  | 'select'
  | 'map'
  | 'json'
  | 'secret';

export interface SchemaField {
  name: string;
  label: string;
  description: string | null;
  control: ControlKind;
  options: string[];
  required: boolean;
  defaultValue: unknown;
  /** `{ operation: ['FIND_MANY'] }`: show this field only for those values of `operation`. */
  visibleWhen: Record<string, string[]> | null;
  /**
   * `{ operation: ['AGGREGATE'] }`: required only for those values, rather than always.
   *
   * Separate from the schema's `required` list, which the engine enforces unconditionally. A field that only
   * applies to one operation cannot go in `required` — the engine would refuse every node that chose a
   * different operation — so before this existed a plugin had to declare nothing required and check at
   * execution instead, which moves the error from publish time to run time.
   */
  requiredWhen: Record<string, string[]> | null;
  /**
   * Per-option help, keyed by option value.
   *
   * An operation selector with forty-five entries is a list of names an author has to already know. Carrying
   * a sentence per option is what turns it into something they can choose from.
   */
  optionDescriptions: Record<string, string>;
  /**
   * Rarely changed, so it renders behind a toggle rather than in the main form.
   *
   * Different from `visibleWhen` and doing a different job. `visibleWhen` says a field *does not apply* to
   * what was chosen; `advanced` says it applies but has a sensible default and almost nobody touches it —
   * row limits, timeouts, protocol overrides. Both are needed: a Read Excel Sheet node has three fields an
   * author sets and eight that are correct as they stand, and showing all eleven flat is what makes the form
   * tiring to use.
   */
  advanced: boolean;
}

/**
 * Chooses a control for one property.
 *
 * Order matters. `enum` wins over `type`, because an enumerated string is a dropdown rather than a
 * text box. `format` wins over the generic string mapping, because that is exactly what the SDK's
 * `secretRef` and textarea hints exist to express. An object with `additionalProperties` is a
 * free-form map and gets a key/value editor; an object without one is an arbitrary structure and gets
 * a JSON editor, which is honest about the fact that the schema does not describe its shape.
 */
export function controlFor(property: ConfigProperty | null | undefined): ControlKind {
  if (!property) {
    return 'text';
  }
  if (property.format === 'secret-ref') {
    return 'secret';
  }
  if (Array.isArray(property.enum) && property.enum.length > 0) {
    return 'select';
  }
  if (property.format === 'textarea') {
    return 'textarea';
  }
  switch (property.type) {
    case 'integer':
    case 'number':
      return 'number';
    case 'boolean':
      return 'boolean';
    case 'object':
      return property.additionalProperties ? 'map' : 'json';
    case 'array':
      return 'json';
    default:
      return 'text';
  }
}

/**
 * Turns a schema into an ordered field list.
 *
 * Property order is preserved from the schema, because the plugin author chose it and it is usually
 * the order the fields are meant to be filled in. Required fields are not hoisted to the top for the
 * same reason.
 *
 * A property named in `required` but absent from `properties` is still rendered, as a text field. The
 * engine will reject the node without it, so hiding it would leave the operator unable to satisfy a
 * validation error they cannot see.
 */
export function toFields(schema: ConfigSchema | null | undefined): SchemaField[] {
  const properties = schema?.properties ?? {};
  const required = new Set(schema?.required ?? []);
  const fields: SchemaField[] = [];

  for (const [name, property] of Object.entries(properties)) {
    fields.push({
      name,
      label: property?.title?.trim() || humanise(name),
      description: property?.description?.trim() || null,
      control: controlFor(property),
      options: Array.isArray(property?.enum) ? [...property.enum] : [],
      required: required.has(name),
      defaultValue: property?.default,
      visibleWhen: conditionOf(property, 'visibleWhen'),
      requiredWhen: conditionOf(property, 'requiredWhen'),
      optionDescriptions: optionDescriptionsOf(property),
      // A required field is never advanced, whatever the schema claims: hiding something the node cannot
      // run without behind a toggle is how an author ends up staring at a validation error for a control
      // they were never shown.
      advanced: !required.has(name) && isAdvanced(property),
    });
  }

  for (const name of required) {
    if (!(name in properties)) {
      fields.push({
        name,
        label: humanise(name),
        description: 'Required by the node type but not described in its schema.',
        control: 'text',
        options: [],
        required: true,
        defaultValue: undefined,
        visibleWhen: null,
        requiredWhen: null,
        optionDescriptions: {},
        advanced: false,
      });
    }
  }

  return fields;
}

/**
 * Whether a property asks to be tucked behind the advanced toggle.
 *
 * Accepts both `advanced` and the JSON Schema convention of prefixing a non-standard keyword with `x-`, so a
 * plugin author writing either gets what they meant. A schema that declares neither renders exactly as it
 * always has, which is what keeps this change invisible to the eleven plugins that predate it.
 */
function isAdvanced(property: ConfigProperty | null | undefined): boolean {
  if (!property) {
    return false;
  }
  const raw = property as unknown as Record<string, unknown>;
  return raw['advanced'] === true || raw['x-advanced'] === true;
}

/** Reads `visibleWhen`, ignoring anything that is not a map of field name to a non-empty list of values. */
function conditionOf(
  property: ConfigProperty | null | undefined,
  keyword: 'visibleWhen' | 'requiredWhen',
): Record<string, string[]> | null {
  const raw = property ? (property as unknown as Record<string, unknown>)[keyword] : undefined;
  if (!raw || typeof raw !== 'object') {
    return null;
  }
  const condition: Record<string, string[]> = {};
  for (const [name, values] of Object.entries(raw)) {
    if (Array.isArray(values) && values.length > 0) {
      condition[name] = values.map((value) => String(value));
    }
  }
  return Object.keys(condition).length > 0 ? condition : null;
}

/**
 * Reads per-option help.
 *
 * Two shapes are accepted, because both are natural to write and neither is standard JSON Schema: a map of
 * option value to text, and a parallel array matching `enum` by position.
 */
function optionDescriptionsOf(property: ConfigProperty | null | undefined): Record<string, string> {
  if (!property) {
    return {};
  }
  const raw = (property as unknown as Record<string, unknown>)['enumDescriptions'];
  const options = Array.isArray(property.enum) ? property.enum : [];

  if (Array.isArray(raw)) {
    const byPosition: Record<string, string> = {};
    options.forEach((option, index) => {
      const text = raw[index];
      if (typeof text === 'string' && text.trim()) {
        byPosition[String(option)] = text.trim();
      }
    });
    return byPosition;
  }
  if (raw && typeof raw === 'object') {
    const byKey: Record<string, string> = {};
    for (const [option, text] of Object.entries(raw as Record<string, unknown>)) {
      if (typeof text === 'string' && text.trim()) {
        byKey[option] = text.trim();
      }
    }
    return byKey;
  }
  return {};
}

/**
 * Whether a condition holds for the values currently entered.
 *
 * Every named field must hold one of its listed values, so a condition can depend on two selectors at once.
 */
export function conditionHolds(
  condition: Record<string, string[]> | null,
  value: Record<string, unknown> | null | undefined,
): boolean {
  if (!condition) {
    return false;
  }
  const current = value ?? {};
  return Object.entries(condition).every(([name, allowed]) => {
    const held = current[name];
    return held != null && held !== '' && allowed.includes(String(held));
  });
}

/**
 * Whether a field must be filled in, for the values currently entered.
 *
 * The schema's `required` list wins outright — the engine enforces it unconditionally, so the panel must not
 * suggest otherwise. `requiredWhen` is the conditional layer on top.
 */
export function isRequired(
  field: SchemaField,
  value: Record<string, unknown> | null | undefined,
): boolean {
  return field.required || conditionHolds(field.requiredWhen, value);
}

/**
 * The fields to render for the values currently entered.
 *
 * A field with no condition is always shown. A field with one is shown when every named field holds one of
 * the listed values — so a condition can depend on two selectors at once, and the common case of one reads
 * the obvious way.
 *
 * Filtering happens at render time rather than by deleting values, deliberately: switching a MongoDB node
 * from Find Many to Aggregate and back must not silently discard the filter that was typed. Hidden values
 * are kept, sent, and still read by the plugin — which is also why this cannot be relied on to prevent
 * anything. The plugin validates and authorizes on the server.
 */
export function visibleFields(
  fields: SchemaField[],
  value: Record<string, unknown> | null | undefined,
): SchemaField[] {
  const current = value ?? {};
  return fields.filter((field) => {
    if (!field.visibleWhen) {
      return true;
    }
    return Object.entries(field.visibleWhen).every(([name, allowed]) => {
      const held = current[name];
      // An unset selector shows everything conditioned on it: the alternative is a form that appears
      // empty until something is chosen, with no clue that choosing is what is needed.
      if (held == null || held === '') {
        return true;
      }
      return allowed.includes(String(held));
    });
  });
}

/**
 * Fills in defaults for values the operator has not set.
 *
 * Only absent keys are touched. An explicit empty string is a decision and must not be overwritten by
 * a default, or clearing a field would be impossible.
 */
export function applyDefaults(
  fields: SchemaField[],
  value: Record<string, unknown> | null | undefined,
): Record<string, unknown> {
  const result: Record<string, unknown> = { ...(value ?? {}) };
  for (const field of fields) {
    if (field.defaultValue !== undefined && !(field.name in result)) {
      result[field.name] = field.defaultValue;
    }
  }
  return result;
}

/**
 * Reports required fields that are still empty.
 *
 * Client-side validation here is a convenience, not the authority: the engine validates the same
 * requirements at publish time from the same schema. Duplicating it saves a round trip; disagreeing
 * with it would be a bug.
 */
export function missingRequired(
  fields: SchemaField[],
  value: Record<string, unknown> | null | undefined,
): string[] {
  const current = value ?? {};
  return fields
    .filter((field) => isRequired(field, current) && isEmpty(current[field.name]))
    .map((field) => field.label);
}

export function isEmpty(value: unknown): boolean {
  if (value == null) {
    return true;
  }
  if (typeof value === 'string') {
    return value.trim().length === 0;
  }
  if (Array.isArray(value)) {
    return value.length === 0;
  }
  if (typeof value === 'object') {
    return Object.keys(value as Record<string, unknown>).length === 0;
  }
  return false;
}

/**
 * Coerces text entered in a key/value or JSON control into a usable type.
 *
 * A workflow variable of `5000` should reach a plugin as a number, not the string `"5000"`, because
 * the plugin's typed accessor would then have to parse it. Anything that is not valid JSON stays a
 * string, which is what makes free text such as an email subject work without quoting.
 */
export function coerceScalar(text: string): unknown {
  const trimmed = text.trim();
  if (trimmed.length === 0) {
    return '';
  }
  if (trimmed === 'true') {
    return true;
  }
  if (trimmed === 'false') {
    return false;
  }
  if (trimmed === 'null') {
    return null;
  }
  if (/^-?\d+(\.\d+)?([eE][+-]?\d+)?$/.test(trimmed)) {
    const parsed = Number(trimmed);
    if (Number.isFinite(parsed)) {
      return parsed;
    }
  }
  // A leading brace or bracket signals intent to write a structure; anything else is left as text so
  // that "${amount}" and "Approval Required" survive untouched.
  if (trimmed.startsWith('{') || trimmed.startsWith('[')) {
    try {
      return JSON.parse(trimmed);
    } catch {
      return text;
    }
  }
  return text;
}

/**
 * Turns a property name into a label, for schemas that omit `title`.
 *
 * Produces sentence case rather than title case, so a generated label reads the same way as the
 * hand-written titles the SDK's schema builder emits ("Bot token secret name"), instead of sitting
 * beside them in a different style.
 */
export function humanise(name: string): string {
  return name
    .replace(/[_-]+/g, ' ')
    .replace(/([a-z0-9])([A-Z])/g, '$1 $2')
    .trim()
    .toLowerCase()
    .replace(/^./, (first) => first.toUpperCase());
}
