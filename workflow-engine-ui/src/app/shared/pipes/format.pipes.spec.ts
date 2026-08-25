import { AgoPipe } from './format.pipes';

/**
 * The age pipe, in both directions.
 *
 * Future timestamps are the part worth testing. They used to render "just now", which was harmless while every
 * value on screen was something that had already happened, and became wrong the moment tasks introduced due
 * dates: a form due tomorrow read as though it were due at that instant.
 */
describe('AgoPipe', () => {
  const pipe = new AgoPipe();

  function offset(seconds: number): string {
    return new Date(Date.now() + seconds * 1000).toISOString();
  }

  it('renders a past timestamp as an age', () => {
    expect(pipe.transform(offset(-120))).toBe('2m ago');
    expect(pipe.transform(offset(-7200))).toBe('2h ago');
  });

  it('renders a future timestamp as a countdown, not as "just now"', () => {
    expect(pipe.transform(offset(300))).toBe('in 5m');
    expect(pipe.transform(offset(86400))).toBe('in 1d');
  });

  it('treats anything inside a minute as now, whichever side of now it is', () => {
    expect(pipe.transform(offset(-5))).toBe('just now');
    expect(pipe.transform(offset(5))).toBe('just now');
  });

  it('passes through what it cannot parse rather than showing NaN', () => {
    expect(pipe.transform('not a date')).toBe('not a date');
    expect(pipe.transform(null)).toBe('');
  });
});
