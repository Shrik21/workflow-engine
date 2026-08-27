import { provideHttpClient, withFetch, withInterceptors, withXsrfConfiguration } from '@angular/common/http';
import {
  ApplicationConfig,
  inject,
  provideAppInitializer,
  provideBrowserGlobalErrorListeners,
  provideZoneChangeDetection,
} from '@angular/core';
import { provideRouter, withComponentInputBinding, withInMemoryScrolling } from '@angular/router';
import { authInterceptor, errorInterceptor } from './core/http.interceptors';
import { AuthService } from './core/auth/auth.service';
import { routes } from './app.routes';

export const appConfig: ApplicationConfig = {
  providers: [
    provideBrowserGlobalErrorListeners(),
    provideZoneChangeDetection({ eventCoalescing: true }),
    provideRouter(
      routes,
      // Route parameters arrive as component inputs, so a details page reacts to the id changing rather
      // than reading a snapshot once and showing the wrong plugin on the second navigation.
      withComponentInputBinding(),
      withInMemoryScrolling({ scrollPositionRestoration: 'top' }),
    ),
    provideHttpClient(
      withFetch(),
      // Angular's XSRF handling is off because it would look for a XSRF-TOKEN cookie the registry never
      // sets. It is not needed: every authenticated request carries a bearer token in a header, which a
      // cross-site request cannot set, and the one cookie in play is SameSite=Strict and path-scoped to the
      // refresh endpoint, so a cross-site request cannot cause it to be sent either.
      withXsrfConfiguration({ cookieName: '', headerName: '' }),
      // Order matters: the token goes on first, so the error interceptor sees the request as it was sent.
      withInterceptors([authInterceptor, errorInterceptor]),
    ),

    /*
     * Restore the session before the router runs.
     *
     * The access token lives in memory, so a reload loses it while the registry's HttpOnly refresh cookie
     * survives. Without this, every reload would bounce a signed-in operator to the login page: the guard
     * would run before the application had any chance to discover there was a session.
     *
     * A failure is expected and silent. It means nobody is signed in.
     */
    provideAppInitializer(() => inject(AuthService).restoreSession()),
  ],
};
