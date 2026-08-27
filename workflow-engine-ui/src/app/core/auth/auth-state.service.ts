import { Injectable, computed, signal } from '@angular/core';
import { Permission, Role, UserProfile } from './auth.models';

/**
 * Authentication state, as signals.
 *
 * <h2>Where the access token lives, and why</h2>
 *
 * In a private field of this service, which means in memory only. It is never written to
 * `localStorage` or `sessionStorage`, and there is deliberately no method that returns it to arbitrary
 * callers except the interceptor that attaches it.
 *
 * The cost is real and worth naming: a page refresh loses the token, so the application has to obtain a new
 * one on startup. It can, because the refresh token is an HttpOnly cookie the browser still holds, so a
 * silent re-authentication restores the session without the user noticing.
 *
 * The benefit is that a cross-site scripting flaw cannot exfiltrate a durable credential. Script running on
 * the page can still call the API as the user for as long as the tab is open, which is bad; but it cannot
 * copy a token out and use it later from elsewhere, which is much worse. Storing tokens in `localStorage`
 * gives up that distinction for the convenience of surviving a refresh, and the refresh cookie already
 * solves that.
 */
@Injectable({ providedIn: 'root' })
export class AuthStateService {
  /**
   * The access token. Private, in memory, never persisted.
   *
   * A plain field rather than a signal: nothing should re-render because a token rotated, and making it
   * reactive would invite components to read it.
   */
  private accessToken: string | null = null;

  /** When the current access token expires, so it can be refreshed before it is rejected. */
  private expiresAt = 0;

  private readonly userState = signal<UserProfile | null>(null);
  private readonly initialisedState = signal(false);

  /** The signed-in user, or null. */
  readonly user = this.userState.asReadonly();

  /**
   * Whether the initial silent refresh has completed.
   *
   * Guards exist because of this: on a cold load the application does not yet know whether there is a
   * session, and redirecting to the login page before finding out would sign the user out on every refresh.
   */
  readonly initialised = this.initialisedState.asReadonly();

  readonly isAuthenticated = computed(() => this.userState() !== null);

  readonly roles = computed<Role[]>(() => this.userState()?.roles ?? []);

  readonly permissions = computed<Permission[]>(() => this.userState()?.permissions ?? []);

  readonly isAdmin = computed(() => this.roles().includes('ADMIN'));

  readonly displayName = computed(() => {
    const user = this.userState();
    if (!user) {
      return '';
    }
    const full = `${user.firstName ?? ''} ${user.lastName ?? ''}`.trim();
    return full.length > 0 ? full : user.username;
  });

  /** Records a successful sign-in or refresh. */
  setSession(accessToken: string, expiresInSeconds: number, user: UserProfile): void {
    this.accessToken = accessToken;
    // Refresh 30 seconds early, so a request is never sent with a token that expires in flight.
    this.expiresAt = Date.now() + Math.max(0, expiresInSeconds - 30) * 1000;
    this.userState.set(user);
    this.initialisedState.set(true);
  }

  /** Updates the profile without touching the token, after a role change or a profile edit. */
  setUser(user: UserProfile): void {
    this.userState.set(user);
    this.initialisedState.set(true);
  }

  /** Marks startup as finished with no session, so guards may act. */
  markInitialised(): void {
    this.initialisedState.set(true);
  }

  /** Discards everything. Called on sign-out and whenever a refresh fails. */
  clear(): void {
    this.accessToken = null;
    this.expiresAt = 0;
    this.userState.set(null);
    this.initialisedState.set(true);
  }

  /** @returns the access token for the interceptor to attach, or null */
  token(): string | null {
    return this.accessToken;
  }

  /** @returns whether the token is absent or within 30 seconds of expiring */
  isTokenExpiring(): boolean {
    return this.accessToken === null || Date.now() >= this.expiresAt;
  }

  /**
   * @param permission the permission to test
   * @returns whether the signed-in user holds it
   */
  has(permission: Permission): boolean {
    return this.permissions().includes(permission);
  }

  /**
   * @param permissions permissions to test
   * @returns whether the user holds at least one
   */
  hasAny(...permissions: Permission[]): boolean {
    return permissions.some((permission) => this.has(permission));
  }

  /**
   * @param role the role to test
   * @returns whether the user holds it
   */
  hasRole(role: Role): boolean {
    return this.roles().includes(role);
  }
}
