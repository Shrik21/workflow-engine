import { ComponentFixture, TestBed } from '@angular/core/testing';
import { By } from '@angular/platform-browser';
import { Modal } from './modal';

describe('Modal accessibility', () => {
  let fixture: ComponentFixture<Modal>;
  let opener: HTMLButtonElement;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [Modal],
    }).compileComponents();

    opener = document.createElement('button');
    opener.textContent = 'Open';
    document.body.appendChild(opener);
    opener.focus();

    fixture = TestBed.createComponent(Modal);
    fixture.componentRef.setInput('heading', 'Confirm action');
    fixture.detectChanges();
  });

  afterEach(() => {
    fixture.destroy();
    opener.remove();
  });

  it('moves focus into the dialog and restores it on close', async () => {
    await fixture.whenStable();
    await Promise.resolve();
    fixture.detectChanges();

    const close = fixture.debugElement.query(By.css('button[aria-label="Close dialog"]'))
      .nativeElement as HTMLButtonElement;
    expect(document.activeElement).toBe(close);

    fixture.destroy();
    expect(document.activeElement).toBe(opener);
  });

  it('cycles Tab within the dialog', async () => {
    await Promise.resolve();
    fixture.detectChanges();

    const close = fixture.debugElement.query(By.css('button[aria-label="Close dialog"]'))
      .nativeElement as HTMLButtonElement;
    close.focus();

    const dialog = fixture.debugElement.query(By.css('[role="dialog"]')).nativeElement as HTMLElement;
    dialog.dispatchEvent(new KeyboardEvent('keydown', { key: 'Tab', bubbles: true }));
    // Only one focusable control in the bare modal, so Tab wraps to itself.
    expect(document.activeElement).toBe(close);
  });
});
