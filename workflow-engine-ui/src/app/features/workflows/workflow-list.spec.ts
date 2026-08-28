import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { By } from '@angular/platform-browser';
import { API_BASE_URL } from '../../core/api/api-base';
import { AuthStateService } from '../../core/auth/auth-state.service';
import { emptyPage } from '../../core/models/api.models';
import { WorkflowResponse } from '../../core/models/workflow.models';
import { WorkflowList } from './workflow-list';

function sampleWorkflow(overrides: Partial<WorkflowResponse> = {}): WorkflowResponse {
  return {
    id: 'wf-1',
    name: 'Phase3 Sample',
    description: 'A sample draft',
    status: 'DRAFT',
    version: 1,
    publishedVersion: null,
    nodes: [],
    connections: [],
    variables: {},
    triggers: [],
    metadata: {},
    createdBy: 'admin',
    updatedBy: 'admin',
    createdAt: '2026-01-01T00:00:00Z',
    updatedAt: '2026-01-02T00:00:00Z',
    publishedAt: null,
    ...overrides,
  };
}

describe('WorkflowList Phase 3 chrome', () => {
  let fixture: ComponentFixture<WorkflowList>;
  let http: HttpTestingController;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [WorkflowList],
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
      permissions: ['WORKFLOW_VIEW', 'WORKFLOW_CREATE', 'WORKFLOW_EXECUTE', 'WORKFLOW_DELETE'],
      enabled: true,
      accountLocked: false,
      createdAt: null,
      updatedAt: null,
      lastLoginAt: null,
    });

    fixture = TestBed.createComponent(WorkflowList);
    http = TestBed.inject(HttpTestingController);
    fixture.detectChanges();

    const request = http.expectOne((req) => req.url.includes('/workflows'));
    request.flush({
      ...emptyPage<WorkflowResponse>(),
      content: [sampleWorkflow()],
      totalElements: 1,
      totalPages: 1,
      number: 0,
      size: 20,
      first: true,
      last: true,
    });
    fixture.detectChanges();
  });

  afterEach(() => {
    http.verify();
  });

  it('keeps search, status filters, and labelled primary actions', () => {
    expect(fixture.nativeElement.querySelector('input[type="search"]')).toBeTruthy();
    const filters = fixture.debugElement.queryAll(By.css('.btn-group button'));
    expect(filters.length).toBe(4);
    expect(filters[0].nativeElement.getAttribute('aria-pressed')).toBe('true');

    const open = fixture.debugElement.query(By.css('a[aria-label="Open Phase3 Sample"]'));
    expect(open).toBeTruthy();
    expect((open.nativeElement as HTMLElement).textContent).toContain('Open');

    const tableScroll = fixture.nativeElement.querySelector('.table-scroll');
    expect(tableScroll).toBeTruthy();
  });

  it('marks a pressed status filter when Draft is selected', () => {
    const draft = fixture.debugElement
      .queryAll(By.css('.btn-group button'))
      .find((button) => (button.nativeElement.textContent as string).includes('Draft'));
    expect(draft).toBeTruthy();
    draft!.nativeElement.click();
    fixture.detectChanges();

    const request = http.expectOne((req) => req.url.includes('/workflows'));
    expect(request.request.params.get('status')).toBe('DRAFT');
    request.flush({
      ...emptyPage<WorkflowResponse>(),
      content: [sampleWorkflow()],
      totalElements: 1,
      totalPages: 1,
      number: 0,
      size: 20,
      first: true,
      last: true,
    });
    fixture.detectChanges();

    expect(draft!.nativeElement.getAttribute('aria-pressed')).toBe('true');
  });
});
