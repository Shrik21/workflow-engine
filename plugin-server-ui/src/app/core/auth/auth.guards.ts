import { inject } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';
import { AuthService } from './auth.service';
import { NotificationService } from '../notification.service';

/** Requires a session. Sends anonymous visitors to sign in, remembering where they were going. */
export const authGuard: CanActivateFn = (_route, state) => {
  const auth = inject(AuthService);
  const router = inject(Router);

  if (!auth.isAuthenticated()) {
    return router.createUrlTree(['/login'], { queryParams: { returnUrl: state.url } });
  }
  // An account holding a password somebody else chose goes nowhere until it has chosen its own. The
  // registry marks this at sign-in; the console is what makes it unavoidable.
  if (auth.mustChangePassword() && !state.url.startsWith('/profile')) {
    return router.createUrlTree(['/profile'], { queryParams: { mustChangePassword: '1' } });
  }
  return true;
};

/** Keeps a signed-in operator off the sign-in page. */
export const guestGuard: CanActivateFn = () => {
  const auth = inject(AuthService);
  const router = inject(Router);
  return auth.isAuthenticated() ? router.createUrlTree(['/plugins']) : true;
};

/**
 * Requires one of the named permissions.
 *
 * <h2>Convenience, not protection</h2>
 *
 * This stops somebody navigating to a page whose every control would answer 403, which is a courtesy. The
 * registry authorises each request independently and is the only thing that decides anything: a guard that
 * was bypassed would show a page that immediately fills with refusals.
 *
 * @param permissions any one of which admits
 * @returns the guard
 */
export function permissionGuard(...permissions: string[]): CanActivateFn {
  return () => {
    const auth = inject(AuthService);
    const router = inject(Router);

    if (auth.hasAnyPermission(...permissions)) {
      return true;
    }
    inject(NotificationService).warning(
      'You do not have permission to open that',
      `It needs ${permissions.join(' or ')}. Ask an administrator of this registry.`,
    );
    return router.createUrlTree(['/plugins']);
  };
}
