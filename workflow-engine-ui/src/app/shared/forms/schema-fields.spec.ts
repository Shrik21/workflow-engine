import {
  applyDefaults,
  coerceScalar,
  controlFor,
  humanise,
  isEmpty,
  missingRequired,
  toFields,
  visibleFields,
} from './schema-fields';
import { ConfigSchema } from '../../core/models/node.models';

/**
 * These rules decide what an operator sees for a plugin the front end has never met, so a mistake here
 * shows up as a control that silently drops input rather than as an error.
 */
describe('schema fields', () => {
  describe('controlFor', () => {
    it('renders a secret reference as a secret picker, whatever its type says', () => {
      expect(controlFor({ type: 'string', format: 'secret-ref' })).toBe('secret');
    });

    it('prefers an enum over the declared type, because a choice is a dropdown', () => {
      expect(controlFor({ type: 'string', enum: ['GET', 'POST'] })).toBe('select');
    });

    it('honours the textarea hint', () => {
      expect(controlFor({ type: 'string', format: 'textarea' })).toBe('textarea');
    });

    it('maps numeric types to a number control', () => {
      expect(controlFor({ type: 'integer' })).toBe('number');
      expect(controlFor({ type: 'number' })).toBe('number');
    });

    it('maps booleans to a checkbox', () => {
      expect(controlFor({ type: 'boolean' })).toBe('boolean');
    });

    it('distinguishes a free-form map from an arbitrary structure', () => {
      expect(controlFor({ type: 'object', additionalProperties: { type: 'string' } })).toBe('map');
      expect(controlFor({ type: 'object' })).toBe('json');
    });

    it('falls back to text for unknown or missing types', () => {
      expect(controlFor({})).toBe('text');
      expect(controlFor(null)).toBe('text');
      expect(controlFor({ type: 'something-new' })).toBe('text');
    });
  });

  describe('toFields', () => {
    const schema: ConfigSchema = {
      type: 'object',
      properties: {
        apiKeySecret: { type: 'string', format: 'secret-ref', title: 'API key secret name' },
        to: { type: 'string', title: 'Recipient' },
        body: { type: 'string', format: 'textarea', title: 'Body', description: 'Message body' },
        contentType: { type: 'string', enum: ['text/plain', 'text/html'], default: 'text/plain' },
      },
      required: ['apiKeySecret', 'to', 'body'],
    };

    it('preserves the order the plugin declared', () => {
      expect(toFields(schema).map((field) => field.name)).toEqual([
        'apiKeySecret',
        'to',
        'body',
        'contentType',
      ]);
    });

    it('marks required fields and carries titles, descriptions and defaults', () => {
      const fields = toFields(schema);
      expect(fields[0].required).toBeTrue();
      expect(fields[0].label).toBe('API key secret name');
      expect(fields[2].description).toBe('Message body');
      expect(fields[3].required).toBeFalse();
      expect(fields[3].defaultValue).toBe('text/plain');
      expect(fields[3].options).toEqual(['text/plain', 'text/html']);
    });

    it('derives a label when the schema omits a title', () => {
      const fields = toFields({ properties: { botTokenSecret: { type: 'string' } } });
      expect(fields[0].label).toBe('Bot token secret');
    });

    it('still renders a required property that has no description, so it can be satisfied', () => {
      const fields = toFields({ properties: {}, required: ['mystery'] });
      expect(fields.length).toBe(1);
      expect(fields[0].name).toBe('mystery');
      expect(fields[0].required).toBeTrue();
    });

    it('returns nothing for an absent schema', () => {
      expect(toFields(null)).toEqual([]);
      expect(toFields({})).toEqual([]);
    });
  });

  describe('applyDefaults', () => {
    it('fills only absent keys', () => {
      const fields = toFields({
        properties: { method: { type: 'string', default: 'GET' }, url: { type: 'string' } },
      });
      expect(applyDefaults(fields, {})).toEqual({ method: 'GET' });
      expect(applyDefaults(fields, { method: 'POST' })).toEqual({ method: 'POST' });
    });

    it('does not overwrite a value the operator deliberately cleared', () => {
      const fields = toFields({ properties: { method: { type: 'string', default: 'GET' } } });
      expect(applyDefaults(fields, { method: '' })).toEqual({ method: '' });
    });
  });

  describe('missingRequired', () => {
    const fields = toFields({
      properties: { to: { type: 'string', title: 'Recipient' }, cc: { type: 'string' } },
      required: ['to'],
    });

    it('reports an empty required field by its label', () => {
      expect(missingRequired(fields, {})).toEqual(['Recipient']);
      expect(missingRequired(fields, { to: '   ' })).toEqual(['Recipient']);
    });

    it('accepts any non-empty value, including an expression', () => {
      expect(missingRequired(fields, { to: '${recipientEmail}' })).toEqual([]);
    });

    it('ignores optional fields', () => {
      expect(missingRequired(fields, { to: 'a@b.com' })).toEqual([]);
    });
  });

  describe('isEmpty', () => {
    it('treats blank strings, empty collections, null and undefined as empty', () => {
      expect(isEmpty(null)).toBeTrue();
      expect(isEmpty(undefined)).toBeTrue();
      expect(isEmpty('')).toBeTrue();
      expect(isEmpty('  ')).toBeTrue();
      expect(isEmpty([])).toBeTrue();
      expect(isEmpty({})).toBeTrue();
    });

    it('does not treat false or zero as empty', () => {
      expect(isEmpty(false)).toBeFalse();
      expect(isEmpty(0)).toBeFalse();
    });
  });

  describe('coerceScalar', () => {
    it('produces numbers and booleans so a plugin does not have to parse them', () => {
      expect(coerceScalar('5000')).toBe(5000);
      expect(coerceScalar('-2.5')).toBe(-2.5);
      expect(coerceScalar('true')).toBeTrue();
      expect(coerceScalar('false')).toBeFalse();
      expect(coerceScalar('null')).toBeNull();
    });

    it('leaves variable placeholders and prose alone', () => {
      expect(coerceScalar('${amount}')).toBe('${amount}');
      expect(coerceScalar('Approval Required')).toBe('Approval Required');
      expect(coerceScalar('user@example.com')).toBe('user@example.com');
    });

    it('parses a value that clearly intends to be a structure', () => {
      expect(coerceScalar('{"a":1}')).toEqual({ a: 1 });
      expect(coerceScalar('[1,2]')).toEqual([1, 2]);
    });

    it('keeps malformed structures as text rather than throwing', () => {
      expect(coerceScalar('{not json')).toBe('{not json');
    });

    it('returns an empty string for blank input', () => {
      expect(coerceScalar('   ')).toBe('');
    });
  });

  describe('humanise', () => {
    it('turns camelCase and snake_case into readable labels', () => {
      expect(humanise('apiKeySecret')).toBe('Api key secret');
      expect(humanise('bot_token')).toBe('Bot token');
      expect(humanise('url')).toBe('Url');
    });
  });

  describe('visibleFields', () => {
    /** A node with one operation selector and fields that apply to some operations only. */
    const schema: ConfigSchema = {
      type: 'object',
      properties: {
        operation: { type: 'string', enum: ['FIND_MANY', 'AGGREGATE'] },
        collection: { type: 'string' },
        filter: { type: 'object', visibleWhen: { operation: ['FIND_MANY'] } },
        pipeline: { type: 'array', visibleWhen: { operation: ['AGGREGATE'] } },
      },
    };

    const names = (value: Record<string, unknown>) =>
      visibleFields(toFields(schema), value).map((field) => field.name);

    it('shows only the fields that apply to the chosen operation', () => {
      expect(names({ operation: 'FIND_MANY' })).toEqual(['operation', 'collection', 'filter']);
      expect(names({ operation: 'AGGREGATE' })).toEqual(['operation', 'collection', 'pipeline']);
    });

    it('shows everything while the selector is unset', () => {
      // A form that appears empty until something is chosen gives no clue that choosing is what is needed.
      expect(names({})).toEqual(['operation', 'collection', 'filter', 'pipeline']);
      expect(names({ operation: '' })).toEqual(['operation', 'collection', 'filter', 'pipeline']);
    });

    it('keeps hidden values rather than discarding them', () => {
      // Switching from Find Many to Aggregate and back must not lose the filter that was typed. Hiding is
      // rendering; the value is untouched and still submitted.
      const value = { operation: 'AGGREGATE', filter: { status: 'ACTIVE' } };
      expect(names(value)).not.toContain('filter');
      expect(value.filter).toEqual({ status: 'ACTIVE' });
    });

    it('reports a required field as missing even when it is hidden', () => {
      const required: ConfigSchema = {
        type: 'object',
        required: ['pipeline'],
        properties: {
          operation: { type: 'string', enum: ['FIND_MANY', 'AGGREGATE'] },
          pipeline: { type: 'array', visibleWhen: { operation: ['AGGREGATE'] } },
        },
      };
      // The engine refuses the node without it whether or not this panel is showing it.
      expect(missingRequired(toFields(required), { operation: 'FIND_MANY' })).toEqual(['Pipeline']);
    });

    it('requires every named field to match when a condition names more than one', () => {
      const both: ConfigSchema = {
        type: 'object',
        properties: {
          operation: { type: 'string' },
          mode: { type: 'string' },
          advanced: { type: 'string', visibleWhen: { operation: ['FIND_MANY'], mode: ['EXPERT'] } },
        },
      };
      const shown = (value: Record<string, unknown>) =>
        visibleFields(toFields(both), value).map((field) => field.name);

      expect(shown({ operation: 'FIND_MANY', mode: 'EXPERT' })).toContain('advanced');
      expect(shown({ operation: 'FIND_MANY', mode: 'SIMPLE' })).not.toContain('advanced');
    });

    it('ignores a malformed condition instead of hiding the field', () => {
      const malformed: ConfigSchema = {
        type: 'object',
        properties: {
          // An empty list would hide the field for every value, which is never what was meant.
          broken: { type: 'string', visibleWhen: { operation: [] } },
        },
      };
      expect(visibleFields(toFields(malformed), { operation: 'ANYTHING' }).map((f) => f.name)).toEqual([
        'broken',
      ]);
    });
  });

  describe('advanced', () => {
    it('marks a property that declares it, and leaves the rest everyday', () => {
      const fields = toFields({
        type: 'object',
        properties: {
          fileId: { type: 'string' },
          maxRows: { type: 'integer', advanced: true },
        },
        required: ['fileId'],
      } as never);

      expect(fields.find((f) => f.name === 'fileId')!.advanced).toBe(false);
      expect(fields.find((f) => f.name === 'maxRows')!.advanced).toBe(true);
    });

    it('accepts the x- prefixed spelling, which is the JSON Schema convention', () => {
      const fields = toFields({
        type: 'object',
        properties: { timeout: { type: 'integer', 'x-advanced': true } },
      } as never);

      expect(fields[0].advanced).toBe(true);
    });

    it('never hides a required field, whatever the schema claims', () => {
      // Hiding something the node cannot run without is how an author ends up staring at a validation
      // error for a control they were never shown.
      const fields = toFields({
        type: 'object',
        properties: { connection: { type: 'string', advanced: true } },
        required: ['connection'],
      } as never);

      expect(fields[0].advanced).toBe(false);
    });

    it('defaults to everyday when nothing is declared, so existing plugins are unaffected', () => {
      const fields = toFields({
        type: 'object',
        properties: { url: { type: 'string' } },
      } as never);

      expect(fields[0].advanced).toBe(false);
    });
  });

  describe('requiredWhen', () => {
    const schema = {
      type: 'object',
      required: ['operation'],
      properties: {
        operation: { type: 'string', enum: ['FIND_MANY', 'AGGREGATE'] },
        pipeline: { type: 'array', requiredWhen: { operation: ['AGGREGATE'] } },
      },
    } as never;

    it('requires the field only for the matching value', () => {
      // The gap it fills: 'pipeline' cannot go in required[] without refusing every Find node.
      expect(missingRequired(toFields(schema), { operation: 'AGGREGATE' })).toEqual(['Pipeline']);
      expect(missingRequired(toFields(schema), { operation: 'FIND_MANY' })).toEqual([]);
    });

    it('does not require it while the deciding field is unset', () => {
      // 'Operation' is separately in required[] and empty here, so it is legitimately reported. The claim
      // under test is only that a conditional field is not demanded before anything has been chosen.
      expect(missingRequired(toFields(schema), {})).not.toContain('Pipeline');
    });

    it('is satisfied once a value is supplied', () => {
      expect(missingRequired(toFields(schema), { operation: 'AGGREGATE', pipeline: [{}] })).toEqual([]);
    });

    it('leaves the unconditional required list working exactly as before', () => {
      expect(missingRequired(toFields(schema), { operation: '' })).toEqual(['Operation']);
    });

    it('needs every named field to match when a condition names two', () => {
      const both = {
        type: 'object',
        properties: {
          mode: { type: 'string' },
          target: { type: 'string' },
          extra: { type: 'string', requiredWhen: { mode: ['A'], target: ['X'] } },
        },
      } as never;

      expect(missingRequired(toFields(both), { mode: 'A', target: 'X' })).toEqual(['Extra']);
      expect(missingRequired(toFields(both), { mode: 'A', target: 'Y' })).toEqual([]);
    });
  });

  describe('enumDescriptions', () => {
    it('reads a map of option to help text', () => {
      const fields = toFields({
        type: 'object',
        properties: {
          operation: {
            type: 'string',
            enum: ['FIND_ONE', 'AGGREGATE'],
            enumDescriptions: { AGGREGATE: 'Runs an aggregation pipeline.' },
          },
        },
      } as never);

      expect(fields[0].optionDescriptions['AGGREGATE']).toBe('Runs an aggregation pipeline.');
      expect(fields[0].optionDescriptions['FIND_ONE']).toBeUndefined();
    });

    it('reads a parallel array, matching enum by position', () => {
      const fields = toFields({
        type: 'object',
        properties: {
          mode: { type: 'string', enum: ['A', 'B'], enumDescriptions: ['First.', 'Second.'] },
        },
      } as never);

      expect(fields[0].optionDescriptions).toEqual({ A: 'First.', B: 'Second.' });
    });

    it('is empty when nothing is declared, so existing plugins are unaffected', () => {
      const fields = toFields({
        type: 'object',
        properties: { mode: { type: 'string', enum: ['A'] } },
      } as never);

      expect(fields[0].optionDescriptions).toEqual({});
    });
  });
});
