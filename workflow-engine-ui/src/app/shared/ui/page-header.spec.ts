import { ComponentFixture, TestBed } from '@angular/core/testing';
import { PageHeader } from './page-header';

describe('PageHeader', () => {
  let fixture: ComponentFixture<PageHeader>;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [PageHeader],
    }).compileComponents();
    fixture = TestBed.createComponent(PageHeader);
    fixture.componentRef.setInput('title', 'Workflows');
    fixture.componentRef.setInput('description', 'Build and run graphs.');
    fixture.detectChanges();
  });

  it('renders the title and description', () => {
    const text = fixture.nativeElement.textContent as string;
    expect(text).toContain('Workflows');
    expect(text).toContain('Build and run graphs.');
    expect(fixture.nativeElement.querySelector('h1')).toBeTruthy();
  });
});
