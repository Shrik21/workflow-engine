import { Injectable, signal } from '@angular/core';

export type NotificationKind = 'success' | 'error' | 'info' | 'warning';

export interface Notification {
  id: number;
  kind: NotificationKind;
  title: string;
  /** Extra lines, used for the engine's validation detail lists. */
  details: string[];
  /** Errors stay until dismissed; everything else auto-dismisses. */
  sticky: boolean;
}

/**
 * Transient messages.
 *
 * Errors are sticky and carry their detail list. That matters for this application specifically:
 * publishing a workflow or uploading a plugin can fail with a dozen individual problems, and a toast
 * that auto-dismissed after three seconds would throw away the only copy of them.
 */
@Injectable({ providedIn: 'root' })
export class NotificationService {
  private static readonly AUTO_DISMISS_MS = 4500;

  private nextId = 1;
  private readonly items = signal<Notification[]>([]);

  readonly notifications = this.items.asReadonly();

  success(title: string, ...details: string[]): void {
    this.push('success', title, details, false);
  }

  info(title: string, ...details: string[]): void {
    this.push('info', title, details, false);
  }

  warning(title: string, ...details: string[]): void {
    this.push('warning', title, details, true);
  }

  error(title: string, ...details: string[]): void {
    this.push('error', title, details, true);
  }

  dismiss(id: number): void {
    this.items.update((current) => current.filter((item) => item.id !== id));
  }

  dismissAll(): void {
    this.items.set([]);
  }

  private push(kind: NotificationKind, title: string, details: string[], sticky: boolean): void {
    const id = this.nextId++;
    const cleaned = details.filter((line) => !!line && line.trim().length > 0);
    this.items.update((current) => [...current, { id, kind, title, details: cleaned, sticky }]);
    if (!sticky) {
      setTimeout(() => this.dismiss(id), NotificationService.AUTO_DISMISS_MS);
    }
  }
}
