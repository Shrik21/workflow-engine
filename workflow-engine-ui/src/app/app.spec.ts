import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed, fakeAsync, tick } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { By } from '@angular/platform-browser';
import { API_BASE_URL } from './core/api/api-base';
import { AuthStateService } from './core/auth/auth-state.service';
import { App } from './app';

describe('App mobile navigation', () => {
  let fixture: ComponentFixture<App>;
  let http: HttpTestingController;

  function flushBackground(): void {
    http.match(() => true).forEach((request) => {
      if (request.request.url.includes('/counts')) {
        request.flush({ mine: 0, available: 0, overdue: 0 });
      } else {
        request.flush([]);
      }
    });
  }

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [App],
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        provideRouter([{ path: '**', children: [] }]),
        { provide: API_BASE_URL, useValue: '' },
      ],
    }).compileComponents();

    const state = TestBed.inject(AuthStateService);
    state.setSession('test-token', 3600, {
      id: 'u1',
      username: 'admin',
      email: 'admin@example.com',
      firstName: 'Admin',
      lastName: 'User',
      roles: ['ADMIN'],
      permissions: [
        'WORKFLOW_VIEW',
        'EXECUTION_VIEW',
        'TASK_VIEW',
        'PLUGIN_VIEW',
        'SECRET_VIEW',
        'USER_VIEW',
        'EVENT_EMIT',
        'AI_PROVIDER_VIEW',
        'AI_CLI_VIEW',
        'WORKFLOW_STORAGE_SETTINGS_VIEW',
      ],
      enabled: true,
      accountLocked: false,
      createdAt: null,
      updatedAt: null,
      lastLoginAt: null,
    });

    fixture = TestBed.createComponent(App);
    http = TestBed.inject(HttpTestingController);
    (
      fixture.componentInstance as unknown as { mobileCompact: { set(v: boolean): void } }
    ).mobileCompact.set(true);
    fixture.detectChanges();
    flushBackground();
    fixture.detectChanges();
  });

  afterEach(() => {
    flushBackground();
  });

  it('marks the closed drawer as inert and aria-hidden', () => {
    const sidebar = fixture.debugElement.query(By.css('#app-sidebar')).nativeElement as HTMLElement;
    const toggle = fixture.debugElement.query(By.css('.nav-toggle')).nativeElement as HTMLButtonElement;
    expect(toggle.getAttribute('aria-expanded')).toBe('false');
    expect(sidebar.hasAttribute('inert')).toBeTrue();
    expect(sidebar.getAttribute('aria-hidden')).toBe('true');
  });

  it('opens the drawer, removes inert, and closes on Escape with focus restore', fakeAsync(() => {
    const toggle = fixture.debugElement.query(By.css('.nav-toggle')).nativeElement as HTMLButtonElement;
    toggle.focus();
    toggle.click();
    fixture.detectChanges();
    tick();
    fixture.detectChanges();

    const sidebar = fixture.debugElement.query(By.css('#app-sidebar')).nativeElement as HTMLElement;
    expect(toggle.getAttribute('aria-expanded')).toBe('true');
    expect(sidebar.hasAttribute('inert')).toBeFalse();

    (
      fixture.componentInstance as unknown as {
        onDocumentKeydown(event: { key: string; preventDefault(): void }): void;
      }
    ).onDocumentKeydown({
      key: 'Escape',
      preventDefault(): void {
        /* no-op */
      },
    });
    fixture.detectChanges();
    tick();
    fixture.detectChanges();

    expect(toggle.getAttribute('aria-expanded')).toBe('false');
    expect(document.activeElement).toBe(toggle);
  }));

  it('makes main content inert while the drawer is open', () => {
    const toggle = fixture.debugElement.query(By.css('.nav-toggle')).nativeElement as HTMLButtonElement;
    const main = fixture.debugElement.query(By.css('#main-content')).nativeElement as HTMLElement;

    toggle.click();
    fixture.detectChanges();
    expect(main.hasAttribute('inert')).toBeTrue();

    toggle.click();
    fixture.detectChanges();
    expect(main.hasAttribute('inert')).toBeFalse();
  });
});
