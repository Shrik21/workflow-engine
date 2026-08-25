import { HttpClient } from '@angular/common/http';
import { Injectable, inject, signal } from '@angular/core';
import { Router } from '@angular/router';
import { Observable, catchError, firstValueFrom, map, of, shareReplay, tap, throwError } from 'rxjs';
import { API_BASE_URL } from '../api/api-base';
import { NotificationService } from '../notification.service';
import { AuthStateService } from './auth-state.service';
import {
  ChangePasswordRequest,
  DEFAULT_PASSWORD_POLICY,
  LoginRequest,
  LoginResponse,
  PasswordPolicy,
  RegisterRequest,
  UserProfile,
} from './auth.models';

/**
 * Sign-in, sign-out, registration and token refresh.
 *
 * The refresh method is the interesting one. Several requests can fail with 401 at the same moment, and each
 * must not start its own refresh: the first would rotate the token and the rest would then present a revoked
 * one, which the server treats as theft and answers by revoking the whole family. So the in-flight refresh
 * is shared, and every caller waits on the same request.
 */
@Injectable({ providedIn: 'root' })
export class AuthService {
  private readonly http = inject(HttpClient);
  private readonly state = inject(AuthStateService);
  private readonly notifications = inject(NotificationService);
  private readonly router = inject(Router);
  private readonly base = inject(API_BASE_URL) + '/api/auth';

  /**
   * The refresh currently in flight, shared by every caller.
   *
   * Without this, concurrent 401s each rotate the token and all but one end up holding a revoked token,
   * which trips the server's reuse detection and logs everyone out.
   */
  private refreshInFlight: Observable<string> | null = null;

  private readonly policyState = signal<PasswordPolicy>(DEFAULT_PASSWORD_POLICY);

  /** The server's password rules, for the registration and change-password forms. */
  readonly passwordPolicy = this.policyState.asReadonly();

  /**
   * Restores a session on application start.
   *
   * The access token lives in memory, so a page refresh loses it. The refresh cookie survives, so this asks
   * the server for a new pair. A failure is expected and silent: it simply means nobody is signed in.
   */
  async restoreSession(): Promise<void> {
    try {
      await firstValueFrom(this.refreshTokens());
    } catch {
      this.state.markInitialised();
    }
  }

  login(request: LoginRequest): Observable<UserProfile> {
    return this.http.post<LoginResponse>(`${this.base}/login`, request).pipe(
      tap((response) => this.state.setSession(response.accessToken, response.expiresIn, response.user)),
      map((response) => response.user),
    );
  }

  register(request: RegisterRequest): Observable<UserProfile> {
    return this.http.post<UserProfile>(`${this.base}/register`, request);
  }

  /**
   * Signs out.
   *
   * State is cleared regardless of what the server says. A failed logout must still end the local session,
   * or a network blip would leave the user apparently signed in with a token they cannot renew.
   */
  logout(options: { navigate?: boolean; message?: string } = {}): void {
    const finish = () => {
      this.state.clear();
      this.refreshInFlight = null;
      if (options.navigate !== false) {
        this.router.navigate(['/login']);
      }
      if (options.message) {
        this.notifications.info(options.message);
      }
    };

    this.http.post<void>(`${this.base}/logout`, {}).subscribe({ next: finish, error: finish });
  }

  /** Clears local state without calling the server, for when the session is already known to be dead. */
  discardSession(message?: string): void {
    this.state.clear();
    this.refreshInFlight = null;
    if (message) {
      this.notifications.warning(message);
    }
  }

  /**
   * Exchanges the refresh cookie for a new token pair.
   *
   * @returns the new access token, shared between concurrent callers
   */
  refreshTokens(): Observable<string> {
    if (this.refreshInFlight) {
      return this.refreshInFlight;
    }

    this.refreshInFlight = this.http.post<LoginResponse>(`${this.base}/refresh`, {}).pipe(
      tap((response) => this.state.setSession(response.accessToken, response.expiresIn, response.user)),
      map((response) => response.accessToken),
      // Cleared on both paths so a later 401 can start a fresh attempt rather than replaying a dead one.
      tap({
        next: () => (this.refreshInFlight = null),
        error: () => (this.refreshInFlight = null),
      }),
      // Every concurrent caller gets the same result from the same request.
      shareReplay({ bufferSize: 1, refCount: false }),
    );

    return this.refreshInFlight;
  }

  /** Reads the current user from the server, picking up a role change made by an administrator. */
  loadCurrentUser(): Observable<UserProfile> {
    return this.http
      .get<UserProfile>(`${this.base}/me`)
      .pipe(tap((user) => this.state.setUser(user)));
  }

  /**
   * Changes the password.
   *
   * The server revokes every session afterwards, this one included, so the local session is discarded and
   * the user is sent back to sign in. Pretending to stay signed in would fail at the next refresh.
   */
  changePassword(request: ChangePasswordRequest): Observable<void> {
    return this.http.post<void>(`${this.base}/change-password`, request).pipe(
      tap(() => {
        this.state.clear();
        this.refreshInFlight = null;
        this.router.navigate(['/login']);
        this.notifications.success('Password changed', 'Sign in again with your new password.');
      }),
    );
  }

  /** Loads the server's password rules. Falls back to the built-in defaults if unavailable. */
  loadPasswordPolicy(): Observable<PasswordPolicy> {
    return this.http.get<PasswordPolicy>(`${this.base}/password-policy`).pipe(
      tap((policy) => this.policyState.set(policy)),
      catchError(() => of(this.policyState())),
    );
  }

  /** Extracts the message and detail lines from a failed request, for display on a form. */
  describeError(error: unknown): { message: string; details: string[] } {
    const body = (error as { error?: { message?: string; details?: string[] } })?.error;
    return {
      message: body?.message ?? 'Something went wrong. Please try again.',
      details: Array.isArray(body?.details) ? body.details : [],
    };
  }

  /** @returns an observable that fails, for guards that need to reject */
  fail(reason: string): Observable<never> {
    return throwError(() => new Error(reason));
  }
}
