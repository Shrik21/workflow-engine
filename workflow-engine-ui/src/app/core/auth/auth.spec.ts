import {
  HttpClient,
  HttpErrorResponse,
  provideHttpClient,
  withInterceptors,
} from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { AuthStateService } from './auth-state.service';
import { authInterceptor } from './auth.interceptor';
import {
  DEFAULT_PASSWORD_POLICY,
  UserProfile,
  policyViolations,
  scorePassword,
} from './auth.models';

/**
 * Authentication state, the interceptor's refresh behaviour, and the password helpers.
 *
 * The interceptor tests are the ones that matter. Its failure modes are an infinite refresh loop and
 * parallel refreshes that trip the server's token-reuse detection and sign everyone out, and neither shows
 * up in manual testing until it is in production under load.
 */

function profile(overrides: Partial<UserProfile> = {}): UserProfile {
  return {
    id: 'user-1',
    username: 'vivek',
    email: 'vivek@example.com',
    firstName: 'Vivek',
    lastName: 'User',
    roles: ['USER'],
    permissions: ['WORKFLOW_VIEW', 'WORKFLOW_CREATE', 'WORKFLOW_EXECUTE', 'EXECUTION_VIEW'],
    enabled: true,
    accountLocked: false,
    createdAt: null,
    updatedAt: null,
    lastLoginAt: null,
    ...overrides,
  };
}

describe('AuthStateService', () => {
  let state: AuthStateService;

  beforeEach(() => {
    TestBed.configureTestingModule({ providers: [AuthStateService] });
    state = TestBed.inject(AuthStateService);
  });

  it('starts unauthenticated with no token', () => {
    expect(state.isAuthenticated()).toBeFalse();
    expect(state.token()).toBeNull();
    expect(state.permissions()).toEqual([]);
  });

  it('records a session and derives roles and permissions', () => {
    state.setSession('token-abc', 900, profile());

    expect(state.isAuthenticated()).toBeTrue();
    expect(state.token()).toBe('token-abc');
    expect(state.has('WORKFLOW_CREATE')).toBeTrue();
    expect(state.has('PLUGIN_UPLOAD')).toBeFalse();
    expect(state.hasRole('USER')).toBeTrue();
    expect(state.isAdmin()).toBeFalse();
    expect(state.displayName()).toBe('Vivek User');
  });

  it('recognises an administrator', () => {
    state.setSession('token', 900, profile({ roles: ['ADMIN'], permissions: ['PLUGIN_UPLOAD'] }));

    expect(state.isAdmin()).toBeTrue();
    expect(state.has('PLUGIN_UPLOAD')).toBeTrue();
  });

  it('never persists the token to browser storage', () => {
    state.setSession('token-abc', 900, profile());

    // The reason the access token lives in memory: an XSS flaw must not be able to steal a durable
    // credential. If this ever fails, that property is gone.
    const stored = [
      ...Object.values(localStorage),
      ...Object.values(sessionStorage),
    ].join(' ');
    expect(stored).not.toContain('token-abc');
  });

  it('treats a token as expiring shortly before it actually does', () => {
    state.setSession('token', 20, profile());
    // Refreshed 30 seconds early, so a request is never sent with a token that expires in flight.
    expect(state.isTokenExpiring()).toBeTrue();

    state.setSession('token', 900, profile());
    expect(state.isTokenExpiring()).toBeFalse();
  });

  it('clears everything on sign-out', () => {
    state.setSession('token', 900, profile());
    state.clear();

    expect(state.isAuthenticated()).toBeFalse();
    expect(state.token()).toBeNull();
    expect(state.user()).toBeNull();
  });
});

describe('authInterceptor', () => {
  let http: HttpTestingController;
  let client: HttpClient;
  let state: AuthStateService;

  /** A login response body, as the refresh endpoint returns. */
  function tokenResponse(accessToken: string) {
    return {
      accessToken,
      refreshToken: null,
      tokenType: 'Bearer',
      expiresIn: 900,
      user: profile(),
    };
  }

  const unauthorised = { status: 401, statusText: 'Unauthorized' };

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [
        provideRouter([]),
        provideHttpClient(withInterceptors([authInterceptor])),
        provideHttpClientTesting(),
      ],
    });
    http = TestBed.inject(HttpTestingController);
    client = TestBed.inject(HttpClient);
    state = TestBed.inject(AuthStateService);
  });

  afterEach(() => http.verify());

  it('attaches the bearer token to an authenticated request', () => {
    state.setSession('token-abc', 900, profile());

    client.get('/api/workflows').subscribe();
    const request = http.expectOne('/api/workflows');

    expect(request.request.headers.get('Authorization')).toBe('Bearer token-abc');
    request.flush({});
  });

  it('sends no Authorization header when signed out', () => {
    client.get('/api/nodes').subscribe({ error: () => undefined });
    const request = http.expectOne('/api/nodes');

    expect(request.request.headers.has('Authorization')).toBeFalse();
    request.flush({});
  });

  it('never attaches a token to the sign-in endpoint', () => {
    state.setSession('token-abc', 900, profile());

    client.post('/api/auth/login', {}).subscribe();
    const request = http.expectOne('/api/auth/login');

    expect(request.request.headers.has('Authorization')).toBeFalse();
    request.flush(tokenResponse('fresh'));
  });

  it('refreshes on 401 and retries the original request with the new token', () => {
    state.setSession('stale-token', 900, profile());

    let succeeded = false;
    client.get('/api/workflows').subscribe(() => (succeeded = true));

    http.expectOne('/api/workflows').flush(null, unauthorised);
    http.expectOne('/api/auth/refresh').flush(tokenResponse('fresh-token'));

    const retry = http.expectOne('/api/workflows');
    expect(retry.request.headers.get('Authorization')).toBe('Bearer fresh-token');
    retry.flush({ content: [] });

    expect(succeeded).toBeTrue();
    expect(state.token()).toBe('fresh-token');
  });

  it('gives up after one retry rather than looping', () => {
    state.setSession('stale-token', 900, profile());

    let failed: HttpErrorResponse | null = null;
    client.get('/api/workflows').subscribe({ error: (error) => (failed = error) });

    http.expectOne('/api/workflows').flush(null, unauthorised);
    http.expectOne('/api/auth/refresh').flush(tokenResponse('fresh-token'));
    // The retry is refused too. A second refresh here would be an infinite loop against our own API.
    http.expectOne('/api/workflows').flush(null, unauthorised);

    // http.verify() in afterEach is what proves no further request was made.
    expect(failed).not.toBeNull();
    expect(state.isAuthenticated()).toBeFalse();
  });

  it('shares one refresh between concurrent 401s', () => {
    state.setSession('stale-token', 900, profile());

    client.get('/api/workflows').subscribe({ error: () => undefined });
    client.get('/api/executions').subscribe({ error: () => undefined });

    http.expectOne('/api/workflows').flush(null, unauthorised);
    http.expectOne('/api/executions').flush(null, unauthorised);

    // The decisive assertion. Two refreshes would rotate the token twice, leaving one caller holding a
    // revoked token; the server treats that as theft and revokes the whole family, signing the user out.
    const refreshes = http.match('/api/auth/refresh');
    expect(refreshes.length).toBe(1);
    refreshes[0].flush(tokenResponse('fresh-token'));

    const retries = http.match(
      (request) => request.url === '/api/workflows' || request.url === '/api/executions',
    );
    expect(retries.length).toBe(2);
    retries.forEach((retry) => {
      expect(retry.request.headers.get('Authorization')).toBe('Bearer fresh-token');
      retry.flush({});
    });
  });

  it('clears the session when the refresh itself fails', () => {
    state.setSession('stale-token', 900, profile());

    client.get('/api/workflows').subscribe({ error: () => undefined });
    http.expectOne('/api/workflows').flush(null, unauthorised);
    http.expectOne('/api/auth/refresh').flush(null, unauthorised);

    // The refresh token is expired or revoked. There is nothing left to try, so the session ends once.
    expect(state.isAuthenticated()).toBeFalse();
    expect(state.token()).toBeNull();
  });

  it('does not attempt a refresh for a 403', () => {
    state.setSession('token-abc', 900, profile());

    let failed: HttpErrorResponse | null = null;
    client.post('/api/plugins/upload', {}).subscribe({ error: (error) => (failed = error) });
    http.expectOne('/api/plugins/upload').flush(null, { status: 403, statusText: 'Forbidden' });

    // 403 means authenticated but not permitted. A new token would change nothing, and the session stands.
    expect(failed).not.toBeNull();
    expect(state.isAuthenticated()).toBeTrue();
  });
});

describe('password helpers', () => {
  const policy = DEFAULT_PASSWORD_POLICY;

  it('reports which rules a password fails', () => {
    expect(policyViolations('short', policy)).toContain('At least 12 characters');
    expect(policyViolations('alllowercase1!', policy)).toContain('An upper-case letter');
    expect(policyViolations('ALLUPPERCASE1!', policy)).toContain('A lower-case letter');
    expect(policyViolations('NoDigitsHere!!', policy)).toContain('A digit');
    expect(policyViolations('NoSymbolsHere1', policy)).toContain('A special character');
    expect(policyViolations('Tr0ubador-Zebra!x', policy)).toEqual([]);
  });

  it('scores longer and more varied passwords higher', () => {
    expect(scorePassword('', policy)).toBe('weak');
    expect(scorePassword('abcdefghijkl', policy)).toBe('weak');
    expect(scorePassword('Tr0ubador!x12', policy)).not.toBe('weak');
    expect(scorePassword('Tr0ubador-Zebra!x-Longer', policy)).toBe('very-strong');
  });

  it('does not reward repetition or keyboard runs', () => {
    // Length without entropy should not score as strength.
    const repeated = scorePassword('Aaaaaaaaaaaa1!', policy);
    const varied = scorePassword('Kp7$wmVzqXr2', policy);
    expect(['weak', 'medium']).toContain(repeated);
    expect(varied).not.toBe('weak');
  });
});
