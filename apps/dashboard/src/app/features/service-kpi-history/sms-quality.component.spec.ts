import { TestBed } from '@angular/core/testing';
import { SmsQualityComponent } from './sms-quality.component';
import type { KpiWindow } from './voice-model';
import services from '../../../fixtures/services.json';

function windowFor(
  samples: number | null,
  depth: number | null,
  age: number | null,
): KpiWindow {
  return {
    schemaVersion: 2,
    featureVersion: 2,
    windowId: 'sms-day07',
    scopeId: 'SMS-MD-ROUTE-A',
    service: 'SMS',
    windowStart: '2026-09-23T08:00:00Z',
    windowEnd: '2026-09-23T08:01:00Z',
    quality: 'COMPLETE',
    baselineVersion: 'test-v2',
    topologyVersion: 'test-v2',
    featureNames: [],
    featureValues: [],
    mlEligible: false,
    sourceEventIds: [],
    kpis: [
      {
        name: 'p95DeliveryMs',
        observed: 45000,
        baseline: 2000,
        unit: 'MILLISECONDS',
        numerator: null,
        denominator: null,
      },
      {
        name: 'deliveredMessages',
        observed: samples,
        baseline: null,
        unit: 'COUNT',
        numerator: null,
        denominator: null,
      },
      {
        name: 'queueDepth',
        observed: depth,
        baseline: null,
        unit: 'COUNT',
        numerator: null,
        denominator: null,
      },
      {
        name: 'oldestPendingAgeSec',
        observed: age,
        baseline: null,
        unit: 'SECONDS',
        numerator: null,
        denominator: null,
      },
    ],
  };
}

function render(
  window: KpiWindow | null,
  freshness: 'FRESH' | 'STALE' | 'MISSING' = 'FRESH',
) {
  const fixture = TestBed.createComponent(SmsQualityComponent);
  fixture.componentRef.setInput('window', window);
  fixture.componentRef.setInput('freshness', freshness);
  fixture.detectChanges();

  return {
    component: fixture.componentInstance,
    text: (fixture.nativeElement.textContent as string).replace(/\s+/g, ' ').trim(),
  };
}

describe('SMS evidence', () => {
  it('shows slow delivery beside its completed samples and queue', () => {
    const { text } = render(windowFor(100, 250, 90));

    expect(text).toContain('45000 ms');
    expect(text).toContain('Completed-message samples: 100');
    expect(text).toContain('250 messages');
    expect(text).toContain('90 s');
  });

  it('renders the actual SMS demo fixture with delivery, samples and queue metrics', () => {
    const service = services.find(item => item.scope.scopeId === 'SMS-MD-ROUTE-A')!;
    const { component, text } = render(service.latestWindow as KpiWindow);

    expect(component.p95()).toBe(45000);
    expect(component.baseline()).toBe(2000);
    expect(component.samples()).toBe(100);
    expect(component.depth()).toBe(250);
    expect(component.age()).toBe(90);
    expect(text).not.toContain('Unavailable');
    expect(text).not.toContain('An empty queue cannot be confirmed');
  });

  it('warns about low sample volume below 30, without treating unknown counts as zero', () => {
    expect(render(windowFor(29, 0, 0)).text).toContain('Low sample volume');
    expect(render(windowFor(30, 0, 0)).text).not.toContain('Low sample volume');

    const { text } = render(windowFor(null, 250, 90));
    expect(text).toContain('Completed-message samples: Unavailable');
    expect(text).not.toContain('Low sample volume');
    expect(text).not.toContain('No completed messages in this window');
  });

  it('keeps a stalled backlog visible with zero completed samples', () => {
    const { component, text } = render(windowFor(0, 250, 90));

    expect(component.p95()).toBeNull();
    expect(component.depth()).toBe(250);
    expect(text).toContain(
      'No messages completed while a backlog remains',
    );
  });

  it('distinguishes missing queue data from a measured empty queue', () => {
    const missing = windowFor(100, null, null);
    missing.kpis = missing.kpis.filter(
      kpi => kpi.name !== 'queueDepth',
    );

    const unknown = render(missing);

    expect(unknown.component.depth()).toBeNull();
    expect(unknown.text).toContain(
      'An empty queue cannot be confirmed',
    );

    const empty = render(windowFor(0, 0, 0));

    expect(empty.component.depth()).toBe(0);
    expect(empty.text).toContain('0 messages');
    expect(empty.text).not.toContain(
      'An empty queue cannot be confirmed',
    );
  });

  it('does not invent samples from p95 or hide an independently observed queue', () => {
    const window = windowFor(null, 250, 90);
    window.quality = 'INCOMPLETE';

    const { component } = render(window);

    expect(component.samples()).toBeNull();
    expect(component.p95()).toBeNull();
    expect(component.depth()).toBe(250);
  });

  it('marks stale values as historical and hides missing-window observations', () => {
    expect(
      render(windowFor(100, 250, 90), 'STALE').text,
    ).toContain('historical evidence');

    const missing = windowFor(100, 250, 90);
    missing.quality = 'MISSING';

    expect(render(missing).component.depth()).toBeNull();
    expect(render(null).text).toContain(
      'No SMS observation available',
    );
  });
});
