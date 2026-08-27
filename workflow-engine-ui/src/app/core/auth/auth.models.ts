/**
 * Authentication types, mirroring the engine's DTOs.
 *
 * Note what is absent: there is no type here with a password field on a *response*, because the API has
 * none. Requests carry passwords; nothing that comes back does.
 */

/** Roles the engine ships with. Extra roles added server-side still arrive as strings. */
export type Role =
  | 'ADMIN'
  | 'USER'
  | 'WORKFLOW_ADMIN'
  | 'WORKFLOW_EDITOR'
  | 'WORKFLOW_VIEWER'
  | 'PLUGIN_ADMIN'
  | 'EXECUTION_ADMIN';

/**
 * Permissions the engine grants.
 *
 * The console gates what it *renders* on these. It never treats them as authorization: the server checks
 * every request independently, so hiding a button is a courtesy, not a control.
 */
export type Permission =
  | 'WORKFLOW_VIEW'
  | 'WORKFLOW_CREATE'
  | 'WORKFLOW_EDIT'
  | 'WORKFLOW_DELETE'
  | 'WORKFLOW_EXECUTE'
  | 'WORKFLOW_PUBLISH'
  | 'PLUGIN_VIEW'
  | 'PLUGIN_UPLOAD'
  | 'PLUGIN_ACTIVATE'
  | 'PLUGIN_DEACTIVATE'
  | 'PLUGIN_DELETE'
  | 'USER_VIEW'
  | 'USER_CREATE'
  | 'USER_EDIT'
  | 'USER_DELETE'
  | 'EXECUTION_VIEW'
  | 'EXECUTION_CANCEL'
  | 'WORKFLOW_INSTANCE_PAUSE'
  | 'WORKFLOW_INSTANCE_RESUME'
  | 'WORKFLOW_INSTANCE_TERMINATE'
  | 'AI_PROVIDER_VIEW'
  | 'AI_PROVIDER_MANAGE'
  // AI command-line tools. Separate from AI_PROVIDER_* because configuring one names an executable the engine
  // host will run, which is a stronger capability than naming an HTTP endpoint. Execute is split from
  // configure so an operator can be trusted to point at a vetted binary without also being able to drive it.
  | 'AI_CLI_VIEW'
  | 'AI_CLI_CREATE'
  | 'AI_CLI_UPDATE'
  | 'AI_CLI_DELETE'
  | 'AI_CLI_EXECUTE'
  | 'AI_ERROR_ANALYSIS'
  // Where uploaded workflow files are physically stored. Attaching a file to a workflow needs neither of
  // these — that follows the workflow's own access control.
  | 'WORKFLOW_STORAGE_SETTINGS_VIEW'
  | 'WORKFLOW_STORAGE_SETTINGS_EDIT'
  | 'EXTERNAL_FORM_CREATE_LINK'
  | 'EXTERNAL_FORM_REVOKE_LINK'
  | 'EXTERNAL_FORM_SUBMIT'
  | 'SCHEDULE_VIEW'
  | 'SCHEDULE_CREATE'
  | 'SCHEDULE_EDIT'
  | 'SCHEDULE_DELETE'
  | 'SECRET_VIEW'
  | 'SECRET_MANAGE'
  | 'EVENT_EMIT'
  | 'TASK_VIEW'
  | 'TASK_VIEW_ALL'
  | 'TASK_CLAIM'
  | 'TASK_COMPLETE'
  | 'TASK_CANCEL'
  | 'TASK_REASSIGN'
  | 'TASK_ADMIN';

export interface LoginRequest {
  username: string;
  password: string;
}

export interface RegisterRequest {
  username: string;
  email: string;
  password: string;
  firstName?: string | null;
  lastName?: string | null;
}

export interface ChangePasswordRequest {
  currentPassword: string;
  newPassword: string;
  confirmPassword: string;
}

/** The signed-in user. */
export interface UserProfile {
  id: string;
  username: string;
  email: string;
  firstName: string | null;
  lastName: string | null;
  roles: Role[];
  permissions: Permission[];
  enabled: boolean;
  accountLocked: boolean;
  createdAt: string | null;
  updatedAt: string | null;
  lastLoginAt: string | null;
}

/**
 * A successful sign-in.
 *
 * `refreshToken` is null under the default cookie transport: the token was set as an HttpOnly cookie the
 * browser will send back automatically, and this application has no way to read it. That is the point.
 */
export interface LoginResponse {
  accessToken: string;
  refreshToken: string | null;
  tokenType: string;
  expiresIn: number;
  user: UserProfile;
}

/** The server's password rules, fetched so the form shows what is actually enforced. */
export interface PasswordPolicy {
  minLength: number;
  maxLength: number;
  requireUppercase: boolean;
  requireLowercase: boolean;
  requireDigit: boolean;
  requireSpecial: boolean;
  registrationEnabled: boolean;
  rules: string[];
}

export const DEFAULT_PASSWORD_POLICY: PasswordPolicy = {
  minLength: 12,
  maxLength: 128,
  requireUppercase: true,
  requireLowercase: true,
  requireDigit: true,
  requireSpecial: true,
  registrationEnabled: true,
  rules: [
    'At least 12 characters',
    'An upper-case letter',
    'A lower-case letter',
    'A digit',
    'A special character',
    'Not a commonly used password',
  ],
};

export type PasswordStrength = 'weak' | 'medium' | 'strong' | 'very-strong';

/**
 * Scores a password for the strength meter.
 *
 * Advisory only, and deliberately not the same thing as the policy check. The policy is a pass or fail
 * decided by the server; this is feedback while typing. A password can satisfy every rule and still score
 * only medium, which is useful information rather than a contradiction.
 */
export function scorePassword(password: string, policy: PasswordPolicy): PasswordStrength {
  const value = password ?? '';
  if (value.length === 0) {
    return 'weak';
  }

  let score = 0;
  if (value.length >= policy.minLength) {
    score += 1;
  }
  if (value.length >= policy.minLength + 4) {
    score += 1;
  }
  if (value.length >= policy.minLength + 10) {
    score += 1;
  }

  const classes = [/[a-z]/, /[A-Z]/, /[0-9]/, /[^A-Za-z0-9]/].filter((pattern) =>
    pattern.test(value),
  ).length;
  score += classes - 1;

  // Repetition and sequences add length without adding entropy, so they should not add score.
  if (/(.)\1{2,}/.test(value)) {
    score -= 1;
  }
  if (/(0123|1234|2345|3456|abcd|qwer|asdf)/i.test(value)) {
    score -= 1;
  }

  if (score <= 2) {
    return 'weak';
  }
  if (score <= 4) {
    return 'medium';
  }
  if (score <= 5) {
    return 'strong';
  }
  return 'very-strong';
}

/**
 * Which policy rules a candidate password fails.
 *
 * Duplicates the server's rules on purpose, for immediate feedback. The server validates independently and
 * is the authority; if the two ever disagree, the server wins and the form is wrong.
 */
export function policyViolations(password: string, policy: PasswordPolicy): string[] {
  const value = password ?? '';
  const violations: string[] = [];
  if (value.length < policy.minLength) {
    violations.push(`At least ${policy.minLength} characters`);
  }
  if (value.length > policy.maxLength) {
    violations.push(`No more than ${policy.maxLength} characters`);
  }
  if (policy.requireUppercase && !/[A-Z]/.test(value)) {
    violations.push('An upper-case letter');
  }
  if (policy.requireLowercase && !/[a-z]/.test(value)) {
    violations.push('A lower-case letter');
  }
  if (policy.requireDigit && !/[0-9]/.test(value)) {
    violations.push('A digit');
  }
  if (policy.requireSpecial && !/[^A-Za-z0-9]/.test(value)) {
    violations.push('A special character');
  }
  return violations;
}
