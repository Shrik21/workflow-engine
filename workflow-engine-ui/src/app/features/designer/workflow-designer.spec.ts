import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { By } from '@angular/platform-browser';
import { API_BASE_URL } from '../../core/api/api-base';
import { AuthStateService } from '../../core/auth/auth-state.service';
import { DesignerStore } from './designer.store';
import { WorkflowDesigner } from './workflow-designer';

describe('WorkflowDesigner Phase 3 chrome', () => {
  let fixture: ComponentFixture<WorkflowDesigner>;
  let http: HttpTestingController;
  let store: DesignerStore;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [WorkflowDesigner],
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        provideRouter([]),
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
      permissions: ['WORKFLOW_VIEW', 'WORKFLOW_CREATE', 'WORKFLOW_EDIT', 'WORKFLOW_EXECUTE'],
      enabled: true,
      accountLocked: false,
      createdAt: null,
      updatedAt: null,
      lastLoginAt: null,
    });

    fixture = TestBed.createComponent(WorkflowDesigner);
    http = TestBed.inject(HttpTestingController);
    fixture.componentRef.setInput('id', 'new');
    fixture.detectChanges();

    // Catalogue / secrets / marketplace background calls.
    http.match(() => true).forEach((request) => {
      if (request.request.url.includes('/nodes')) {
        request.flush([]);
      } else if (request.request.url.includes('/secrets')) {
        request.flush([]);
      } else {
        request.flush([]);
      }
    });
    fixture.detectChanges();

    store = fixture.debugElement.injector.get(DesignerStore);
  });

  afterEach(() => {
    http.match(() => true).forEach((request) => request.flush([]));
  });

  it('groups identity, tools, and Save draft / Publish / Run actions', () => {
    const bar = fixture.debugElement.query(By.css('.designer__bar'));
    expect(bar).toBeTruthy();
    expect(fixture.nativeElement.querySelector('.designer__identity')).toBeTruthy();
    expect(fixture.nativeElement.querySelector('.designer__tools')).toBeTruthy();
    expect(fixture.nativeElement.querySelector('.designer__actions')).toBeTruthy();

    const labels = Array.from(
      fixture.nativeElement.querySelectorAll('.designer__actions button') as NodeListOf<HTMLElement>,
    ).map((button) => button.textContent?.trim());
    expect(labels).toEqual(['Validate', 'Save draft', 'Publish', 'Run']);
  });

  it('shows Unsaved changes when the draft is dirty', () => {
    store.setName('Phase3 Dirty Draft');
    fixture.detectChanges();
    expect(fixture.nativeElement.textContent).toContain('Unsaved changes');
  });

  it('opens the leave confirmation when Back is pressed while dirty', () => {
    store.setName('Phase3 Leave Guard');
    fixture.detectChanges();

    const back = fixture.debugElement.query(By.css('.designer__identity .btn--quiet'));
    back.nativeElement.click();
    fixture.detectChanges();

    expect(fixture.nativeElement.textContent).toMatch(/unsaved|leave/i);
  });
});
