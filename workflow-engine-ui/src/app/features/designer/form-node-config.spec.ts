import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { Component, signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { By } from '@angular/platform-browser';
import { API_BASE_URL } from '../../core/api/api-base';
import { AvailableForm } from '../../core/models/form.models';
import { WorkflowNode } from '../../core/models/workflow.models';
import { FormNodeConfig } from './form-node-config';

/**
 * The form picker, in every state the list can be in.
 *
 * <p>These are the states that were previously indistinguishable, because there was no dropdown at all — the
 * author typed a form id into a text box and found out at run time whether it was real.
 */

/** A host, so the `node` input can be changed the way the property panel changes it. */
@Component({
  standalone: true,
  imports: [FormNodeConfig],
  template: `<wf-form-node-config [node]="node()" (nodeChange)="patches.push($event)" />`,
})
class Host {
  readonly node = signal<WorkflowNode>({ id: 'approve', type: 'FORM' });
  readonly patches: Partial<WorkflowNode>[] = [];
}

function form(overrides: Partial<AvailableForm> = {}): AvailableForm {
  return {
    id: 'form-001',
    name: 'Employee Approval Form',
    description: 'Employee approval request form',
    version: 3,
    status: 'PUBLISHED',
    ...overrides,
  };
}

describe('FormNodeConfig', () => {
  let fixture: ComponentFixture<Host>;
  let host: Host;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      imports: [Host],
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        provideRouter([]),
        { provide: API_BASE_URL, useValue: '' },
      ],
    });
    fixture = TestBed.createComponent(Host);
    host = fixture.componentInstance;
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  /** The catalogue request the renderer makes on construction; irrelevant to these tests. */
  function flushCatalogue(): void {
    http.match('/api/forms/field-types').forEach((request) => request.flush({}));
  }

  function text(): string {
    return fixture.nativeElement.textContent as string;
  }

  /**
   * Options of the form picker only. The Assignment select (#assign-type) is a separate control and must
   * not be mixed into assertions about form names vs ids.
   */
  function options(): string[] {
    return Array.from(fixture.nativeElement.querySelectorAll('#form-select option')).map((option) =>
      (option as HTMLOptionElement).textContent!.trim(),
    );
  }

  it('loads the available forms and shows their names, not their ids', () => {
    fixture.detectChanges();
    flushCatalogue();

    http
      .expectOne('/api/forms/available')
      .flush([form(), form({ id: 'form-002', name: 'Leave Request Form', version: 1 })]);
    fixture.detectChanges();

    expect(options()).toEqual([
      'Select a form…',
      'Employee Approval Form (v3)',
      'Leave Request Form (v1)',
    ]);
  });

  it('selects the form the node already references, without rewriting it', () => {
    host.node.set({ id: 'approve', type: 'FORM', formId: 'form-001', formVersion: 3 });
    fixture.detectChanges();
    flushCatalogue();
    http.expectOne('/api/forms/available').flush([form()]);
    fixture.detectChanges();

    const select: HTMLSelectElement = fixture.nativeElement.querySelector('#form-select');
    expect(select.value).toBe('form-001');
    expect(host.patches).toEqual([], 'opening a node must not modify it');
    expect(text()).toContain('v3');
  });

  it('stores the id and pins the published version when a form is chosen', () => {
    fixture.detectChanges();
    flushCatalogue();
    http.expectOne('/api/forms/available').flush([form()]);
    fixture.detectChanges();

    const select: HTMLSelectElement = fixture.nativeElement.querySelector('#form-select');
    select.value = 'form-001';
    select.dispatchEvent(new Event('change'));

    expect(host.patches).toEqual([{ formId: 'form-001', formVersion: 3 }]);
  });

  it('offers a Retry when the list cannot be loaded', () => {
    fixture.detectChanges();
    flushCatalogue();
    http.expectOne('/api/forms/available').flush('nope', { status: 500, statusText: 'Server Error' });
    fixture.detectChanges();

    expect(text()).toContain('Unable to load forms');
    const retry = Array.from(fixture.nativeElement.querySelectorAll('button')).find(
      (button) => (button as HTMLButtonElement).textContent!.trim() === 'Retry',
    ) as HTMLButtonElement;
    expect(retry).toBeTruthy();

    retry.click();
    http.expectOne('/api/forms/available').flush([form()]);
    fixture.detectChanges();

    expect(text()).not.toContain('Unable to load forms');
  });

  it('says so when the user may not list forms, rather than showing an empty dropdown', () => {
    fixture.detectChanges();
    flushCatalogue();
    http.expectOne('/api/forms/available').flush('', { status: 403, statusText: 'Forbidden' });
    fixture.detectChanges();

    expect(text()).toContain('You do not have permission to list forms');
  });

  it('explains an empty list instead of rendering an empty dropdown', () => {
    fixture.detectChanges();
    flushCatalogue();
    http.expectOne('/api/forms/available').flush([]);
    fixture.detectChanges();

    expect(text()).toContain('No forms available');
    expect(fixture.nativeElement.querySelector('#form-select')).toBeNull();
  });

  it('names a referenced form that is no longer offered, and keeps the value', () => {
    host.node.set({ id: 'approve', type: 'FORM', formId: 'form-archived', formVersion: 2 });
    fixture.detectChanges();
    flushCatalogue();
    http.expectOne('/api/forms/available').flush([form()]);
    fixture.detectChanges();

    // Not in the list, so the component asks what it is.
    http.expectOne('/api/forms/form-archived').flush({ id: 'form-archived', name: 'Retired Form' });
    fixture.detectChanges();

    expect(text()).toContain('Retired Form');
    expect(text()).toContain('no longer offered for new nodes');
    const select: HTMLSelectElement = fixture.nativeElement.querySelector('#form-select');
    expect(select.value).toBe('form-archived', 'the deliberate choice must not be silently cleared');
  });

  it('reports a referenced form that has genuinely gone', () => {
    host.node.set({ id: 'approve', type: 'FORM', formId: 'form-gone' });
    fixture.detectChanges();
    flushCatalogue();
    http.expectOne('/api/forms/available').flush([form()]);
    fixture.detectChanges();

    http.expectOne('/api/forms/form-gone').flush('', { status: 404, statusText: 'Not Found' });
    fixture.detectChanges();

    expect(text()).toContain('no longer exists');
  });

  it('asks before changing a form that has field mappings, and does nothing if refused', () => {
    host.node.set({
      id: 'approve',
      type: 'FORM',
      formId: 'form-001',
      formVersion: 3,
      outputMapping: { approved: 'workflow.approved' },
    });
    fixture.detectChanges();
    flushCatalogue();
    http
      .expectOne('/api/forms/available')
      .flush([form(), form({ id: 'form-002', name: 'Leave Request Form', version: 1 })]);
    fixture.detectChanges();

    const select: HTMLSelectElement = fixture.nativeElement.querySelector('#form-select');
    select.value = 'form-002';
    select.dispatchEvent(new Event('change'));
    fixture.detectChanges();

    // A confirmation dialog is shown, and nothing is changed until it is accepted.
    expect(fixture.nativeElement.querySelector('wf-confirm-dialog')).toBeTruthy();
    expect(host.patches).toEqual([], 'no change until the confirmation is accepted');
  });

  it('changes the form and its version once the change is confirmed', () => {
    host.node.set({
      id: 'approve',
      type: 'FORM',
      formId: 'form-001',
      formVersion: 3,
      outputMapping: { approved: 'workflow.approved' },
    });
    fixture.detectChanges();
    flushCatalogue();
    http
      .expectOne('/api/forms/available')
      .flush([form(), form({ id: 'form-002', name: 'Leave Request Form', version: 1 })]);
    fixture.detectChanges();

    const select: HTMLSelectElement = fixture.nativeElement.querySelector('#form-select');
    select.value = 'form-002';
    select.dispatchEvent(new Event('change'));
    fixture.detectChanges();

    // Accept the confirmation, as clicking the dialog's confirm button would.
    const config = fixture.debugElement.query(By.directive(FormNodeConfig)).componentInstance as {
      runConfirmed(): void;
    };
    config.runConfirmed();

    expect(host.patches).toEqual([{ formId: 'form-002', formVersion: 1 }]);
  });

  it('does not ask when the node has no mappings to invalidate', () => {
    host.node.set({ id: 'approve', type: 'FORM', formId: 'form-001', formVersion: 3 });
    fixture.detectChanges();
    flushCatalogue();
    http
      .expectOne('/api/forms/available')
      .flush([form(), form({ id: 'form-002', name: 'Leave Request Form', version: 1 })]);
    fixture.detectChanges();

    const select: HTMLSelectElement = fixture.nativeElement.querySelector('#form-select');
    select.value = 'form-002';
    select.dispatchEvent(new Event('change'));
    fixture.detectChanges();

    // No confirmation is shown, and the change applies immediately.
    expect(fixture.nativeElement.querySelector('wf-confirm-dialog')).toBeFalsy();
    expect(host.patches).toEqual([{ formId: 'form-002', formVersion: 1 }]);
  });

  it('offers to move a node pinned to an older version', () => {
    host.node.set({ id: 'approve', type: 'FORM', formId: 'form-001', formVersion: 2 });
    fixture.detectChanges();
    flushCatalogue();
    http.expectOne('/api/forms/available').flush([form({ version: 5 })]);
    fixture.detectChanges();

    expect(text()).toContain('v5 is available');

    const move = Array.from(fixture.nativeElement.querySelectorAll('button')).find((button) =>
      (button as HTMLButtonElement).textContent!.includes('v5 is available'),
    ) as HTMLButtonElement;
    move.click();

    expect(host.patches).toEqual([{ formVersion: 5 }]);
  });

  it('loads the pinned version to preview it', () => {
    host.node.set({ id: 'approve', type: 'FORM', formId: 'form-001', formVersion: 3 });
    fixture.detectChanges();
    flushCatalogue();
    http.expectOne('/api/forms/available').flush([form()]);
    fixture.detectChanges();

    const preview = Array.from(fixture.nativeElement.querySelectorAll('button')).find(
      (button) => (button as HTMLButtonElement).textContent!.trim() === 'Preview form',
    ) as HTMLButtonElement;
    preview.click();
    fixture.detectChanges();

    // The pinned version, not the newest: previewing something other than what the node runs is worthless.
    http.expectOne('/api/forms/form-001/versions/3').flush({
      id: 'v3',
      formDefinitionId: 'form-001',
      version: 3,
      name: 'Employee Approval Form',
      title: 'Employee Approval',
      fields: [],
      columns: 1,
      submitButtonText: null,
      saveButtonText: null,
      successMessage: null,
      description: null,
      publishedBy: null,
      publishedAt: null,
    });
    fixture.detectChanges();

    expect(text()).toContain('Version 3 as a person will see it');
  });
});
