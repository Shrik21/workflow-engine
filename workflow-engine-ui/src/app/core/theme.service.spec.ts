import { TestBed } from '@angular/core/testing';
import { ThemeService } from './theme.service';

describe('ThemeService', () => {
  beforeEach(() => {
    localStorage.removeItem('orchpilot.theme');
    document.documentElement.removeAttribute('data-theme');
    TestBed.configureTestingModule({});
  });

  afterEach(() => {
    localStorage.removeItem('orchpilot.theme');
    document.documentElement.removeAttribute('data-theme');
    document.documentElement.style.colorScheme = '';
  });

  it('defaults to dark and cycles system → light → dark', () => {
    const theme = TestBed.inject(ThemeService);
    expect(theme.preference()).toBe('dark');
    expect(document.documentElement.getAttribute('data-theme')).toBe('dark');

    theme.cycle();
    expect(theme.preference()).toBe('system');
    expect(localStorage.getItem('orchpilot.theme')).toBe('system');

    theme.cycle();
    expect(theme.preference()).toBe('light');
    expect(document.documentElement.getAttribute('data-theme')).toBeNull();

    theme.cycle();
    expect(theme.preference()).toBe('dark');
    expect(document.documentElement.getAttribute('data-theme')).toBe('dark');
  });

  it('restores a stored preference', () => {
    localStorage.setItem('orchpilot.theme', 'dark');
    const theme = TestBed.inject(ThemeService);
    expect(theme.preference()).toBe('dark');
    expect(theme.resolved()).toBe('dark');
    expect(document.documentElement.getAttribute('data-theme')).toBe('dark');
  });
});
