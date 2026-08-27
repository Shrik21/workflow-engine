import { Injectable, computed, effect, signal } from '@angular/core';

export type ThemePreference = 'light' | 'dark' | 'system';
export type ResolvedTheme = 'light' | 'dark';

const STORAGE_KEY = 'orchpilot.theme';

/**
 * Light / dark appearance for the console.
 *
 * Preference is local to the browser (localStorage). It never leaves the machine and does not touch
 * accounts, permissions or API contracts. `system` follows the OS colour scheme until the operator
 * picks light or dark explicitly. When nothing is stored yet, the console opens in dark mode.
 */
@Injectable({ providedIn: 'root' })
export class ThemeService {
  private readonly preferenceState = signal<ThemePreference>(readStoredPreference());
  private readonly systemDarkState = signal(systemPrefersDark());

  readonly preference = this.preferenceState.asReadonly();

  readonly resolved = computed<ResolvedTheme>(() => {
    const preference = this.preferenceState();
    if (preference === 'system') {
      return this.systemDarkState() ? 'dark' : 'light';
    }
    return preference;
  });

  readonly label = computed(() => {
    switch (this.preferenceState()) {
      case 'light':
        return 'Light';
      case 'dark':
        return 'Dark';
      default:
        return 'System';
    }
  });

  readonly nextLabel = computed(() => {
    switch (this.preferenceState()) {
      case 'light':
        return 'Switch to dark mode';
      case 'dark':
        return 'Switch to system theme';
      default:
        return 'Switch to light mode';
    }
  });

  constructor() {
    if (typeof window !== 'undefined' && typeof window.matchMedia === 'function') {
      const media = window.matchMedia('(prefers-color-scheme: dark)');
      const sync = () => this.systemDarkState.set(media.matches);
      sync();
      if (typeof media.addEventListener === 'function') {
        media.addEventListener('change', sync);
      } else {
        // Safari < 14
        media.addListener(sync);
      }
    }

    effect(() => {
      this.apply(this.resolved());
    });

    // Sync the first paint immediately; the effect covers later preference / OS changes.
    this.apply(this.resolved());
  }

  /** Cycles system → light → dark → system. */
  cycle(): void {
    const order: ThemePreference[] = ['system', 'light', 'dark'];
    const current = this.preferenceState();
    const next = order[(order.indexOf(current) + 1) % order.length];
    this.setPreference(next);
  }

  setPreference(preference: ThemePreference): void {
    this.preferenceState.set(preference);
    try {
      localStorage.setItem(STORAGE_KEY, preference);
    } catch {
      // Private mode may refuse storage; the in-memory preference still works for this session.
    }
    this.apply(this.resolved());
  }

  private apply(theme: ResolvedTheme): void {
    if (typeof document === 'undefined') {
      return;
    }
    const root = document.documentElement;
    if (theme === 'dark') {
      root.setAttribute('data-theme', 'dark');
    } else {
      root.removeAttribute('data-theme');
    }
    root.style.colorScheme = theme;
  }
}

function readStoredPreference(): ThemePreference {
  try {
    const value = localStorage.getItem(STORAGE_KEY);
    if (value === 'light' || value === 'dark' || value === 'system') {
      return value;
    }
  } catch {
    // Ignore.
  }
  return 'dark';
}

function systemPrefersDark(): boolean {
  try {
    return window.matchMedia('(prefers-color-scheme: dark)').matches;
  } catch {
    return false;
  }
}
