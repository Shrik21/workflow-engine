import { provideHttpClient, withFetch, withInterceptors, withXsrfConfiguration } from '@angular/common/http';
import {
  ApplicationConfig,
  inject,
  provideAppInitializer,
  provideBrowserGlobalErrorListeners,
  provideZoneChangeDetection,
} from '@angular/core';
import { provideRouter, withComponentInputBinding, withInMemoryScrolling } from '@angular/router';
import { errorInterceptor, identityInterceptor } from './core/api.interceptor';
import { authInterceptor } from './core/auth/auth.interceptor';
import { AuthService } from './core/auth/auth.service';
import { routes } from './app.routes';

export const appConfig: ApplicationConfig = {
  providers: [
    provideBrowserGlobalErrorListeners(),
    provideZoneChangeDetection({ eventCoalescing: true }),
    provideRouter(
      routes,
      withComponentInputBinding(),
      withInMemoryScrolling({ scrollPositionRestoration: 'top' }),
    ),
    provideHttpClient(
      withFetch(),
      // Angular's XSRF handling is disabled: the API authenticates with a bearer token in a header, which
      // a cross-site request cannot set, and the one cookie the platform issues is SameSite=Strict and
      // path-scoped to the refresh endpoint. Leaving the default on would have Angular look for a
      // XSRF-TOKEN cookie the server never sets.
      withXsrfConfiguration({ cookieName: '', headerName: '' }),
      /*
       * Order is significant. The auth interceptor is outermost so that it sees a 401 from anything
       * inside it and can refresh and retry; the error interceptor then reports whatever survives. The
       * identity interceptor only adds the actor header for audit attribution.
       */
      withInterceptors([authInterceptor, identityInterceptor, errorInterceptor]),
    ),

    /*
     * Restore the session before the router runs.
     *
     * The access token lives in memory, so a page refresh loses it while the HttpOnly refresh cookie
     * survives. Without this, every reload would bounce a signed-in user to the login screen: the guard
     * would run before the application had any chance to discover there was a session. Blocking startup on
     * one request is a fair price for a refresh that keeps you where you were.
     *
     * A failure is expected and silent. It simply means nobody is signed in.
     */
    provideAppInitializer(() => inject(AuthService).restoreSession()),
  ],
};
