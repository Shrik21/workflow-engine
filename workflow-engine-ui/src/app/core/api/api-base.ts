import { InjectionToken } from '@angular/core';
import { environment } from '../../../environments/environment';

/**
 * The base URL every API client prefixes its paths with.
 *
 * An injection token rather than each service reading `environment` directly, for two reasons: a test can
 * override it without touching a global, and there is exactly one place to look when asking which backend
 * the console is talking to.
 *
 * Empty means same-origin, which is what both supported deployments use. See
 * `src/environments/environment.ts` for how to point it elsewhere.
 */
export const API_BASE_URL = new InjectionToken<string>('WORKFLOW_API_BASE_URL', {
  providedIn: 'root',
  factory: () => environment.apiBaseUrl,
});
