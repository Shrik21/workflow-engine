import { HttpErrorResponse, HttpInterceptorFn, HttpRequest } from '@angular/common/http';
import { inject } from '@angular/core';
import { Router } from '@angular/router';
import { Observable, catchError, switchMap, throwError } from 'rxjs';
import { AuthService } from './auth/auth.service';
import { NotificationService } from './notification.service';

/** Endpoints that must never carry a token or trigger a renewal: they are how a session begins or ends. */
const SESSION_ENDPOINTS = ['/api/auth/login', '/api/auth/refresh', '/api/auth/password-policy'];

/**
 * Attaches the bearer token, and renews it once when the registry says it has expired.
 *
 * <h2>Exactly one retry</h2>
 *
 * A 401 triggers a single renewal and a single replay of the original request. If the replay fails, or the
 * renewal does, the session is cleared and the operator is sent to sign in. The `retried` flag on the cloned
 * request is what makes that bound real: without it, a replay that 401s would renew again, and a registry
 * that answers 401 to everything would keep a browser looping until somebody closed the tab.
 *
 * <h2>The refresh endpoint is excluded</h2>
 *
 * A 401 from `/api/auth/refresh` means the session is over, so treating it as "renew and retry" would ask the
 * dead session to renew itself. It is excluded by path rather than by inspecting the failure, because the
 * path is the fact and the failure is an interpretation.
 */
export const authInterceptor: HttpInterceptorFn = (request, next) => {
  const auth = inject(AuthService);
  const router = inject(Router);
  const token = auth.getAccessToken();

  const isSessionEndpoint = SESSION_ENDPOINTS.some((path) => request.url.includes(path));
  const authorised = token && !isSessionEndpoint ? withToken(request, token) : request;

  return next(authorised).pipe(
    catchError((error: HttpErrorResponse) => {
      const alreadyRetried = request.headers.has('X-Retried');
      if (error.status !== 401 || isSessionEndpoint || alreadyRetried) {
        if (error.status === 401 && !isSessionEndpoint) {
          auth.clear();
          void router.navigate(['/login']);
        }
        return throwError(() => error);
      }

      return auth.refresh().pipe(
        switchMap(() => {
          const renewed = auth.getAccessToken();
          // Marked as retried, so a second 401 falls through to signing out rather than renewing again.
          const replay = withToken(request, renewed ?? '').clone({
            setHeaders: { 'X-Retried': 'true' },
          });
          return next(replay);
        }),
        catchError((renewalFailure) => {
          auth.clear();
          void router.navigate(['/login']);
          return throwError(() => renewalFailure);
        }),
      );
    }),
  );
};

function withToken(request: HttpRequest<unknown>, token: string): HttpRequest<unknown> {
  return request.clone({ setHeaders: { Authorization: `Bearer ${token}` } });
}

/**
 * Turns a failed request into a sentence somebody can act on.
 *
 * <h2>Why the status codes are named individually</h2>
 *
 * Each means something specific here, and collapsing them into "something went wrong" throws away the only
 * useful part. A 403 means this account lacks a permission, which is a conversation with an administrator. A
 * 409 on upload means that version already exists, which means changing the version rather than retrying. A
 * 503 means the registry is down, which is nobody's fault and not worth retrying immediately.
 */
export const errorInterceptor: HttpInterceptorFn = (request, next) => {
  const notifications = inject(NotificationService);

  return next(request).pipe(
    catchError((error: HttpErrorResponse) => {
      // Some callers render the failure themselves, in a place with room to explain it and a control to fix
      // it. A toast on top of that would say the same thing twice.
      if (request.headers.has('X-Suppress-Error-Toast')) {
        return throwError(() => error);
      }
      // A 401 is handled by the auth interceptor, which either renews silently or signs the operator out.
      // Reporting it here as well would put "session expired" on screen during a renewal that worked.
      if (error.status !== 401) {
        const message = messageFor(error);
        notifications.error(message.title, message.detail);
      }
      return throwError(() => error);
    }),
  );
};

function messageFor(error: HttpErrorResponse): { title: string; detail: string } {
  const fromServer = typeof error.error?.message === 'string' ? error.error.message : null;
  // The registry lists each broken rule separately, which matters most for a rejected password.
  const details: string[] = Array.isArray(error.error?.details) ? error.error.details : [];
  const detail = [fromServer, ...details].filter(Boolean).join(' ');

  switch (error.status) {
    case 0:
      return {
        title: 'The plugin registry could not be reached',
        detail: 'Check that the service is running and reachable from this console.',
      };
    case 400:
      return { title: 'The request was rejected', detail: detail || 'Check the values and try again.' };
    case 403:
      return {
        title: 'You do not have permission to do that',
        detail: detail || 'Ask an administrator of this registry for the permission you need.',
      };
    case 404:
      return { title: 'Not found', detail: detail || 'The registry has no record of it.' };
    case 409:
      return { title: 'That conflicts with something that already exists', detail };
    case 413:
      return { title: 'The archive is too large', detail };
    case 415:
      return { title: 'Only Java .jar archives are supported', detail };
    case 422:
      return { title: 'That was refused', detail: detail || 'It did not pass validation.' };
    case 503:
      return { title: 'The plugin registry is unavailable', detail: detail || 'Nothing was changed.' };
    default:
      return error.status >= 500
        ? { title: 'The plugin registry failed to handle the request', detail }
        : { title: 'The request failed', detail: detail || `HTTP ${error.status}` };
  }
}
