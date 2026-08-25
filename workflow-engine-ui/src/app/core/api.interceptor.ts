import { HttpErrorResponse, HttpInterceptorFn } from '@angular/common/http';
import { inject } from '@angular/core';
import { catchError, throwError } from 'rxjs';
import { NotificationService } from './notification.service';
import { AuthStateService } from './auth/auth-state.service';
import { ApiError } from './models/api.models';

/**
 * Adds the acting username for the engine's own audit trail.
 *
 * <p>Attribution only, and the server does not trust it: since authentication landed, the engine derives the
 * actor from the validated token and ignores this header for anything that matters. It is retained because
 * the engine's workflow audit records read better with a username attached, and because a machine client
 * without a token can still identify itself for the log.
 *
 * <p>The {@code X-Admin-Api-Key} header this interceptor used to send is gone. A single shared key with no
 * identity, no expiry and no audit trail is exactly what the authentication system replaced.
 */
export const identityInterceptor: HttpInterceptorFn = (request, next) => {
  const state = inject(AuthStateService);
  const username = state.user()?.username;
  return username ? next(request.clone({ setHeaders: { 'X-Actor': username } })) : next(request);
};

/**
 * Turns transport and engine errors into a single notification, then rethrows.
 *
 * <p>Rethrowing matters: a component may still need to react, for example by keeping a dialog open on a
 * validation failure. This interceptor owns telling the operator what happened; the component owns what to
 * do next.
 *
 * <p>401 is handled quietly. By the time an error reaches here, the auth interceptor has already tried to
 * refresh and given up, and it has already told the user their session ended. A second toast saying
 * "unauthorised" would be noise on top of an explanation.
 */
export const errorInterceptor: HttpInterceptorFn = (request, next) => {
  const notifications = inject(NotificationService);

  return next(request).pipe(
    catchError((error: HttpErrorResponse) => {
      if (error.status !== 401) {
        notifications.error(...describe(error));
      }
      return throwError(() => error);
    }),
  );
};

/**
 * Produces a title and detail lines for an error.
 *
 * The engine returns a consistent shape with a `details` array for multi-problem failures, so those are
 * surfaced verbatim. The special cases exist because the generic message would leave nothing actionable.
 */
function describe(error: HttpErrorResponse): [string, ...string[]] {
  if (error.status === 0) {
    return [
      'Cannot reach the workflow engine',
      'The request did not complete. Check that the engine is running and reachable.',
    ];
  }

  if (error.status === 403) {
    return [
      'You do not have permission to do that',
      'The server refused the request. Your role may have changed since you signed in; reload to pick up '
        + 'new permissions.',
    ];
  }

  const body = error.error as ApiError | string | null;
  if (body && typeof body === 'object' && 'code' in body) {
    const details = Array.isArray(body.details) ? body.details : [];
    return [body.message || body.code, ...details];
  }

  if (typeof body === 'string' && body.trim().length > 0) {
    return [`Request failed (${error.status})`, body];
  }

  return [`Request failed (${error.status} ${error.statusText || 'error'})`];
}
