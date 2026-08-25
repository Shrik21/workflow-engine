import { HttpClient } from '@angular/common/http';
import { Injectable, computed, inject, signal } from '@angular/core';
import { Observable, catchError, map, of, tap } from 'rxjs';
import { environment } from '../../../environments/environment';

/** A signed-in account, as the registry describes it. */
export interface Identity {
  id: string;
  username: string;
  email: string;
  displayName: string;
  roles: string[];
  permissions: string[];
  mustChangePassword: boolean;
  lastLoginAt: string | null;
}

/** What `/api/auth/login` and `/api/auth/refresh` return. The refresh token is in a cookie, not here. */
export interface SessionResponse {
  accessToken: string;
  refreshToken: string | null;
  tokenType: string;
  expiresIn: number;
  mustChangePassword: boolean;
  user: {
    id: string;
    username: string;
    email: string;
    displayName: string;
    roles: string[];
    permissions: string[];
    mustChangePassword: boolean;
    lastLoginAt: string | null;
  };
}

/**
 * Who is signed in, and the token the registry is called with.
 *
 * <h2>This session is the registry's alone</h2>
 *
 * The refresh token lives in an {@code HttpOnly} cookie named `plugin_registry_refresh`, issued by the
 * registry and readable by nothing in this application. That name is what keeps this session separate from
 * the workflow platform's: cookies are scoped by host and path and **not by port**, so a console on
 * `localhost:4300` and one on `localhost:4200` share a single cookie jar. Two services using the same cookie
 * name would overwrite each other's sessions, and signing in to one would sign the operator out of the other.
 * Distinct names mean neither can see or clear the other's.
 *
 * <h2>A reload restores the session rather than ending it</h2>
 *
 * The access token is held in memory and is lost on reload, which is intended: it is the credential that can
 * publish executable code, and it should not be sitting in storage any script can read. The cookie survives,
 * so {@link restoreSession} exchanges it for a fresh access token before the router runs. The operator sees a
 * page that was already signed in; nothing is signed out by a refresh, by opening another application, or by
 * closing and reopening the tab.
 *
 * <h2>Permissions come from the server</h2>
 *
 * {@link hasPermission} reads the list the registry put in the session. The UI uses it to hide controls that
 * would only produce a 403. It is presentation, not protection: the registry authorises every request on its
 * own.
 */
@Injectable({ providedIn: 'root' })
export class AuthService {
  private readonly http = inject(HttpClient);
  private readonly base = `${environment.registryBaseUrl}/api/auth`;

  private readonly accessToken = signal<string | null>(null);
  private readonly identityState = signal<Identity | null>(null);

  /**
   * Whether a renewal has been tried since the application loaded.
   *
   * <p>Without this, a reload with no cookie would leave the guard unable to tell "not signed in" from "not
   * asked yet", and the login page would flash before the session was restored.
   */
  private readonly restored = signal(false);

  readonly identity = this.identityState.asReadonly();
  readonly isAuthenticated = computed(() => this.accessToken() !== null);
  readonly sessionRestored = this.restored.asReadonly();

  /** True while the account still has to replace the password it was given. */
  readonly mustChangePassword = computed(() => this.identityState()?.mustChangePassword ?? false);

  /** @returns the bearer token, or null when nobody is signed in */
  getAccessToken(): string | null {
    return this.accessToken();
  }

  /**
   * @param permission the permission to test
   * @returns whether the signed-in account holds it
   */
  hasPermission(permission: string): boolean {
    return this.identityState()?.permissions.includes(permission) ?? false;
  }

  /**
   * @param permissions the permissions to test
   * @returns whether the account holds any of them
   */
  hasAnyPermission(...permissions: string[]): boolean {
    return permissions.some((permission) => this.hasPermission(permission));
  }

  /**
   * @param role the role to test
   * @returns whether the account holds it
   */
  hasRole(role: string): boolean {
    return this.identityState()?.roles.includes(role) ?? false;
  }

  /**
   * Signs in.
   *
   * @param username the account
   * @param password its password, held only for the duration of this request
   * @returns the identity
   */
  login(username: string, password: string): Observable<Identity> {
    return this.http
      .post<SessionResponse>(
        `${this.base}/login`,
        { username, password },
        // The registry answers with Set-Cookie; the browser stores it only if credentials are allowed.
        { withCredentials: true },
      )
      .pipe(map((session) => this.adopt(session)));
  }

  /**
   * Restores a session from the registry's cookie, if there is one.
   *
   * <p>Run once before the router starts. A failure is the ordinary case — it simply means nobody is signed
   * in — so it never raises and never reports anything to the operator.
   *
   * @returns whether a session was restored
   */
  restoreSession(): Observable<boolean> {
    return this.http
      .post<SessionResponse>(`${this.base}/refresh`, {}, { withCredentials: true })
      .pipe(
        map((session) => {
          this.adopt(session);
          return true;
        }),
        catchError(() => of(false)),
        tap(() => this.restored.set(true)),
      );
  }

  /**
   * Renews the session.
   *
   * <p>The registry rotates on every use: the cookie it sends back replaces the one presented. Nothing here
   * has to manage that, which is the advantage of the token never being in this application's hands.
   *
   * @returns the refreshed identity
   */
  refresh(): Observable<Identity> {
    return this.http
      .post<SessionResponse>(`${this.base}/refresh`, {}, { withCredentials: true })
      .pipe(map((session) => this.adopt(session)));
  }

  /** Re-reads the account, so a role changed since sign-in is reflected without signing out. */
  reloadIdentity(): Observable<Identity | null> {
    return this.http.get<SessionResponse['user']>(`${this.base}/me`).pipe(
      tap((user) => this.identityState.set(toIdentity(user))),
      map(() => this.identityState()),
      catchError(() => of(this.identityState())),
    );
  }

  /**
   * Signs out of the registry, and only the registry.
   *
   * <p>The endpoint revokes the refresh token and clears this registry's cookie by name. The workflow
   * platform's cookie shares the host and path but not the name, so it is untouched: signing out here leaves
   * a workflow session exactly as it was.
   */
  logout(): void {
    this.http
      .post(`${this.base}/logout`, {}, { withCredentials: true })
      .pipe(catchError(() => of(null)))
      .subscribe(() => this.clear());
    // Cleared locally straight away as well. A sign-out that waited on the network would leave the operator
    // looking at a signed-in page if the request were slow, and a sign-out must never appear to have failed.
    this.clear();
  }

  /** Forgets the session locally. Used when renewal has already failed and there is nothing to revoke. */
  clear(): void {
    this.accessToken.set(null);
    this.identityState.set(null);
  }

  /** Records that the password was changed, so the console stops insisting on it. */
  markPasswordChanged(): void {
    const current = this.identityState();
    if (current) {
      this.identityState.set({ ...current, mustChangePassword: false });
    }
  }

  private adopt(session: SessionResponse): Identity {
    this.accessToken.set(session.accessToken);
    const identity = toIdentity(session.user, session.mustChangePassword);
    this.identityState.set(identity);
    this.restored.set(true);
    return identity;
  }
}

function toIdentity(user: SessionResponse['user'], mustChangePassword?: boolean): Identity {
  return {
    id: user.id,
    username: user.username,
    email: user.email,
    displayName: user.displayName || user.username,
    roles: user.roles ?? [],
    permissions: user.permissions ?? [],
    mustChangePassword: mustChangePassword ?? user.mustChangePassword ?? false,
    lastLoginAt: user.lastLoginAt ?? null,
  };
}
