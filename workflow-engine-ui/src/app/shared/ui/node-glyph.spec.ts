import { ComponentFixture, TestBed } from '@angular/core/testing';
import { By } from '@angular/platform-browser';
import { NodeGlyph } from './node-glyph';

/**
 * Which mark an icon hint resolves to.
 *
 * <p>Hints are free text published by plugins, so the mapping is a series of substring rules and the order
 * they are tried in is load-bearing. Most of these tests exist because a plausible reordering would silently
 * put the wrong logo on a node — which nothing else would catch, since every branch renders *something*.
 */
describe('NodeGlyph', () => {
  let fixture: ComponentFixture<NodeGlyph>;

  beforeEach(() => {
    TestBed.configureTestingModule({ imports: [NodeGlyph] });
    fixture = TestBed.createComponent(NodeGlyph);
  });

  function shapeFor(hint: string | null): string {
    fixture.componentRef.setInput('icon', hint);
    fixture.detectChanges();
    return (fixture.componentInstance as unknown as { shape: () => string }).shape();
  }

  function isBrand(hint: string | null): boolean {
    fixture.componentRef.setInput('icon', hint);
    fixture.detectChanges();
    return (fixture.componentInstance as unknown as { isBrand: () => boolean }).isBrand();
  }

  // ------------------------------------------------------------------ brand marks

  it('resolves brand marks from an icon hint', () => {
    expect(shapeFor('slack')).toBe('brand-slack');
    expect(shapeFor('github')).toBe('brand-github');
    expect(shapeFor('mongodb')).toBe('brand-mongodb');
    expect(shapeFor('jira')).toBe('brand-jira');
    expect(shapeFor('docker')).toBe('brand-docker');
    expect(shapeFor('excel')).toBe('brand-excel');
  });

  it('resolves brand marks from a plugin id', () => {
    // Plugins publish an id when they publish no icon, so this is the common path in practice.
    expect(shapeFor('orchpilot-slack-plugin')).toBe('brand-slack');
    expect(shapeFor('orchpilot-github')).toBe('brand-github');
    expect(shapeFor('orchpilot-jira-plugin')).toBe('brand-jira');
    expect(shapeFor('orchpilot-docker-registry-plugin')).toBe('brand-docker');
  });

  it('prefers the more specific brand when a hint contains two', () => {
    // The trap: this id contains both "gcp" and "kubernetes". Reversing the table's order would put a
    // Google logo on every GKE node, and nothing would fail except the look of it.
    expect(shapeFor('orchpilot-gcp-kubernetes')).toBe('brand-kubernetes');
    expect(shapeFor('orchpilot-gcp-network')).toBe('brand-google');
    expect(shapeFor('orchpilot-gcp-compute-instance')).toBe('brand-google');
  });

  it('prefers a brand over the generic rule that would otherwise catch it', () => {
    // "slack" would have matched the message glyph, "gcp" the cloud one.
    expect(shapeFor('slack')).not.toBe('message');
    expect(shapeFor('gcp')).not.toBe('cloud');
    expect(shapeFor('mongodb')).not.toBe('database');
  });

  it('reports whether a mark is a brand, so callers can skip the monochrome tint', () => {
    expect(isBrand('slack')).toBeTrue();
    expect(isBrand('start')).toBeFalse();
    expect(isBrand(null)).toBeFalse();
  });

  // ------------------------------------------------------------------ monochrome glyphs

  it('still resolves the built-in shapes', () => {
    expect(shapeFor('start')).toBe('play');
    expect(shapeFor('end')).toBe('stop');
    expect(shapeFor('decision')).toBe('branch');
    expect(shapeFor('form')).toBe('form');
    expect(shapeFor('email')).toBe('email');
    expect(shapeFor('ai-agent')).toBe('spark');
  });

  it('falls back to a neutral shape for anything unrecognised', () => {
    // A plugin uploaded next year cannot ship an asset into this bundle, so an unknown hint has to
    // degrade rather than break.
    expect(shapeFor('some-brand-nobody-has-heard-of')).toBe('default');
    expect(shapeFor('')).toBe('default');
    expect(shapeFor(null)).toBe('default');
  });

  // ------------------------------------------------------------------ rendering

  it('renders an svg at the requested size', () => {
    fixture.componentRef.setInput('icon', 'slack');
    fixture.componentRef.setInput('size', 40);
    fixture.detectChanges();

    const svg = fixture.debugElement.query(By.css('svg')).nativeElement as SVGElement;
    expect(svg.getAttribute('width')).toBe('40');
    expect(svg.getAttribute('height')).toBe('40');
    // Decoration only; the node's own label carries the accessible name.
    expect(svg.getAttribute('aria-hidden')).toBe('true');
  });

  it('a brand mark paints its own colours rather than inheriting currentColor', () => {
    fixture.componentRef.setInput('icon', 'slack');
    fixture.detectChanges();

    const fills = fixture.debugElement
      .queryAll(By.css('svg [fill]'))
      .map((el) => (el.nativeElement as SVGElement).getAttribute('fill'));

    expect(fills).toContain('#E01E5A');
    expect(fills.some((f) => f !== 'currentColor' && f !== 'none')).toBeTrue();
  });
});
