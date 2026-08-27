import { inject } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';
import { NotificationService } from '../notification.service';
import { AuthStateService } from './auth-state.service';
import { Permission, Role } from './auth.models';

/**
 * Route guards.
 *
 * All three are conveniences for the person using the console, not security controls. The server checks every
 * request independently; a guard that was bypassed would produce a screen that renders and then fills with
 * 403s, which is untidy rather than dangerous. Writing them as if they were the control is how a front end
 * ends up being the only thing enforcing a rule.
 *
 * Every guard relies on the session having been restored before routing begins. That happens once at
 * bootstrap, before the router is given control, so `initialised` is always true by the time a guard runs and
 * a page refresh does not bounce a signed-in user to the login screen.
 */

/** Requires a signed-in user. Remembers where they were going so sign-in can return them there. */
export const authGuard: CanActivateFn = (_route, routerState) => {
  const state = inject(AuthStateService);
  const router = inject(Router);

  if (state.isAuthenticated()) {
    return true;
  }
  return router.createUrlTree(['/login'], { queryParams: { returnUrl: routerState.url } });
};

/** Keeps a signed-in user off the sign-in and registration pages. */
export const guestGuard: CanActivateFn = () => {
  const state = inject(AuthStateService);
  const router = inject(Router);

  return state.isAuthenticated() ? router.createUrlTree(['/workflows']) : true;
};

/**
 * Requires one of the given roles.
 *
 * @param roles roles that grant access
 * @returns a guard function
 */
export function roleGuard(...roles: Role[]): CanActivateFn {
  return (_route, routerState) => {
    const state = inject(AuthStateService);
    const router = inject(Router);
    const notifications = inject(NotificationService);

    if (!state.isAuthenticated()) {
      return router.createUrlTree(['/login'], { queryParams: { returnUrl: routerState.url } });
    }
    if (roles.some((role) => state.hasRole(role))) {
      return true;
    }

    // Told, not silently redirected: a page that vanishes without explanation reads as a bug.
    notifications.warning(
      'That area is restricted',
      `It requires the ${roles.join(' or ')} role. You are signed in as ${state.displayName()}.`,
    );
    return router.createUrlTree(['/workflows']);
  };
}

/**
 * Requires at least one of the given permissions.
 *
 * Preferred over {@link roleGuard}: a route guarded by permission keeps working when a new role is
 * introduced that happens to grant it, whereas one guarded by role has to be revisited.
 *
 * @param permissions permissions that grant access
 * @returns a guard function
 */
export function permissionGuard(...permissions: Permission[]): CanActivateFn {
  return (_route, routerState) => {
    const state = inject(AuthStateService);
    const router = inject(Router);
    const notifications = inject(NotificationService);

    if (!state.isAuthenticated()) {
      return router.createUrlTree(['/login'], { queryParams: { returnUrl: routerState.url } });
    }
    if (state.hasAny(...permissions)) {
      return true;
    }

    notifications.warning(
      'You do not have access to that page',
      `It requires ${permissions.join(' or ')}.`,
    );
    return router.createUrlTree(['/workflows']);
  };
}
