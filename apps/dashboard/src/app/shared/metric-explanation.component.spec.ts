import { TestBed } from '@angular/core/testing';
import { MetricExplanationComponent } from './metric-explanation.component';

describe('Contextual metric help', () => {
  it('uses native expandable help for optional explanations', () => {
    const fixture = TestBed.createComponent(MetricExplanationComponent);
    fixture.componentRef.setInput('topic', 'rank');
    fixture.detectChanges();
    const details: HTMLDetailsElement = fixture.nativeElement.querySelector('details');
    expect(details.open).toBe(false);
    expect(details.querySelector('summary')?.textContent).toContain('model anomaly rank');
    expect(details.textContent).toContain('not a failure probability');
  });

  it('keeps exceptional states and next actions visible', () => {
    const fixture = TestBed.createComponent(MetricExplanationComponent);
    fixture.componentRef.setInput('topic', 'baseline-missing');
    fixture.componentRef.setInput('mode', 'state');
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('details')).toBeNull();
    const note = fixture.nativeElement.querySelector('[role="note"]');
    expect(note.textContent).toContain('Expected value unavailable');
    expect(note.textContent).toContain('Ask the baseline owner');
  });
});
