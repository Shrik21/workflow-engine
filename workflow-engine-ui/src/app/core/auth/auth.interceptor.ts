import { HttpErrorResponse, HttpInterceptorFn, HttpRequest } from '@angular/common/http';
import { inject } from '@angular/core';
import { catchError, switchMap, throwError } from 'rxjs';
import { AuthService } from './auth.service';
import { AuthStateService } from './auth-state.service';

/**
 * Endpoints that must never carry a bearer token or trigger a refresh.
 *
 * Sign-in and registration have no token to send. Refresh and logout must not be retried on 401: refresh
 * failing with 401 *is* the answer that the session is over, and retrying it would recurse.
 */
const UNAUTHENTICATED_PATHS = [
  '/api/auth/login',
  '/api/auth/register',
  '/api/auth/refresh',
  '/api/auth/logout',
  '/api/auth/password-policy',
];

/** Marks a request as already retried, so one failure cannot become an endless loop. */
const RETRY_HEADER = 'X-Auth-Retry';

/**
 * Attaches the access token, and recovers from an expired one.
 *
 * <pre>
 * request ──► attach Authorization: Bearer &lt;token&gt;
 *               │
 *          401 from the server
 *               │
 *          already retried? ── yes ──► sign out, go to /login
 *               │ no
 *          refresh (shared with any concurrent 401)
 *            ┌──┴──┐
 *        succeeded  failed ──► sign out, go to /login
 *            │
 *      retry once with the new token
 * </pre>
 *
 * Three properties matter, and all three are easy to get wrong:
 *
 * 1. **One retry only.** The retry carries a marker header, so a request that fails twice gives up instead of
 *    looping. A refresh loop against a server returning 401 is indistinguishable from a denial-of-service
 *    attempt on your own API.
 * 2. **One refresh at a time.** {@link AuthService.refreshTokens} shares the in-flight request. Since the
 *    server rotates refresh tokens and treats a reused one as theft, parallel refreshes would revoke the
 *    whole token family and sign the user out.
 * 3. **Refresh failures are terminal.** If the refresh token has expired or been revoked, there is nothing
 *    left to try, so the session is cleared once rather than retried per request.
 */
export const authInterceptor: HttpInterceptorFn = (request, next) => {
  const auth = inject(AuthService);
  const state = inject(AuthStateService);

  if (isUnauthenticatedPath(request.url)) {
    return next(request);
  }

  const token = state.token();
  const authorised = token ? withBearer(request, token) : request;

  return next(authorised).pipe(
    catchError((error: HttpErrorResponse) => {
      if (error.status !== 401) {
        return throwError(() => error);
      }

      // Already retried once with a fresh token and still refused: the problem is not the token.
      if (request.headers.has(RETRY_HEADER)) {
        auth.discardSession('Your session has ended. Please sign in again.');
        return throwError(() => error);
      }

      return auth.refreshTokens().pipe(
        switchMap((refreshed) =>
          next(withBearer(request, refreshed).clone({ setHeaders: { [RETRY_HEADER]: '1' } })),
        ),
        catchError((refreshError) => {
          // The refresh token is gone, expired or revoked. Nothing further to attempt.
          auth.discardSession('Your session has expired. Please sign in again.');
          return throwError(() => refreshError);
        }),
      );
    }),
  );
};

function withBearer(request: HttpRequest<unknown>, token: string): HttpRequest<unknown> {
  return request.clone({ setHeaders: { Authorization: `Bearer ${token}` } });
}

function isUnauthenticatedPath(url: string): boolean {
  return UNAUTHENTICATED_PATHS.some((path) => url.includes(path));
}
