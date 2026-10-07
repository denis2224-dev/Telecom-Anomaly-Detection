import { TestBed } from '@angular/core/testing';
import { EvidenceTimelineComponent } from './evidence-timeline.component';
import { voiceIncidents } from '../../../fixtures/voice';
import type { components } from '../../core/api/schema';

type Detection = components['schemas']['ServiceDetection'];

function detection(sequence: number, phase: Detection['phase']): Detection {
  const record = structuredClone(voiceIncidents[0].latestDetection);
  return {
    ...record,
    detectionId: String(sequence).padStart(64, '0'), sequence, phase,
    technicalState: phase === 'RECOVERY' ? 'RECOVERED' : phase === 'UNKNOWN' ? 'UNKNOWN' : 'ONGOING',
  };
}

function render(records: Detection[]) {
  const fixture = TestBed.createComponent(EvidenceTimelineComponent);
  fixture.componentRef.setInput('detections', records);
  fixture.detectChanges();
  return fixture;
}

describe('Historical detection evidence', () => {
  it('orders bad, unknown and recovery updates without mutating records or their input order', () => {
    const records = [detection(4, 'RECOVERY'), detection(1, 'OPEN'), detection(3, 'UNKNOWN'), detection(2, 'UPDATE')];
    const before = structuredClone(records);
    const fixture = render(records);
    const headings = Array.from<HTMLElement>(fixture.nativeElement.querySelectorAll('h3'));

    expect(headings.map(item => item.textContent?.trim())).toEqual([
      'Update 1 · OPEN', 'Update 2 · UPDATE', 'Update 3 · UNKNOWN', 'Update 4 · RECOVERY',
    ]);
    const events = fixture.nativeElement.querySelectorAll('.evidence-entry');
    expect(events).toHaveLength(4);
    expect(events[2].getAttribute('data-phase')).toBe('UNKNOWN');
    expect(events[3].querySelector('time')?.getAttribute('datetime')).toBe(records[0].detectedAt);
    expect(records).toEqual(before);
    expect(fixture.componentInstance.ordered()[0]).toBe(records[1]);
  });

  it('keeps each window’s measurements, sources and hypothesis separate, preserving zero and null', () => {
    const bad = detection(1, 'OPEN');
    bad.kpis = [{ name: 'cssrPct', observed: 0, baseline: 99.3, unit: 'PERCENT', numerator: 0, denominator: 100 }];
    bad.impact.extraFailedAttempts = 100;
    bad.probableCause = 'Earlier capacity hypothesis';
    bad.causeConfidence = 'LOW';
    bad.mlStatus = 'UNAVAILABLE';
    bad.recommendedChecks = ['Inspect IMS load'];
    bad.evidence = [{ code: 'EARLIER_SOURCE', summary: 'Earlier observed CPU', nodeId: 'IMS-A', sourceEventIds: ['earlier-event'] }];
    const unknown = detection(2, 'UNKNOWN');
    unknown.kpis = [{ name: 'cssrPct', observed: null, baseline: null, unit: 'PERCENT', numerator: null, denominator: null }];
    unknown.impact.extraFailedAttempts = 0;
    unknown.probableCause = 'Insufficient evidence';
    unknown.recommendedChecks = [];
    unknown.evidence = [];
    const fixture = render([unknown, bad]);
    const articles = fixture.nativeElement.querySelectorAll('article');
    const cells = (article: HTMLElement) => Array.from(article.querySelectorAll('[data-kpi] td')).map(cell => cell.textContent?.trim());

    expect(cells(articles[0])).toEqual(['0', '99.3', 'PERCENT', '-99.3 pp', '-100%', '0', '100']);
    expect(cells(articles[1])).toEqual(['Unavailable', 'Unavailable', 'PERCENT', 'Comparison unavailable', 'Unavailable', 'Unavailable', 'Unavailable']);
    expect(articles[0].textContent).toContain(`Scope: ${bad.scopeId}`);
    expect(articles[0].textContent).toContain('EARLIER_SOURCE');
    expect(articles[0].textContent).toContain('IMS-A');
    const source = Array.from<HTMLDetailsElement>(articles[0].querySelectorAll('details'))
      .find(details => details.querySelector('summary')?.textContent === 'Troubleshooting');
    expect(source?.open).toBe(false);
    expect(source?.textContent).toContain('earlier-event');
    const hypothesis = articles[0].querySelector('[aria-label="Cause hypothesis"]');
    expect(hypothesis.textContent).toContain('Earlier capacity hypothesis');
    expect(hypothesis.textContent).toContain('Cause confidence: LOW');
    expect(hypothesis.textContent).toContain('ML result: Model service unavailable');
    expect(hypothesis.textContent).toContain('not a confirmed root cause');
    expect(hypothesis.textContent).toContain('Inspect IMS load');
    expect(articles[1].textContent).not.toContain('Earlier capacity hypothesis');
    expect(articles[1].textContent).toContain('Estimated extra failed attempts: 0');
    expect(articles[1].textContent).toContain('Unique customers: Unavailable');
    expect(articles[1].textContent).toContain('does not prove recovery');
    expect(articles[1].textContent).toContain('No checks supplied for this update');
    expect(articles[1].textContent).toContain('No source evidence supplied');
  });

  it('shows SMS message impact separately from unique customers and missing source IDs', () => {
    const sms = detection(1, 'OPEN');
    sms.service = 'SMS';
    sms.impact = { extraFailedAttempts: 0, affectedDeliveredMessages: 0, pendingMessages: 250, uniqueSubscribers: null };
    sms.evidence = [{ code: 'QUEUE', summary: 'Queue snapshot', nodeId: null, sourceEventIds: [] }];
    const text = render([sms]).nativeElement.textContent as string;
    expect(text).toContain('Affected delivered messages: 0');
    expect(text).toContain('Pending messages: 250');
    expect(text).toContain('Unique customers: Unavailable');
    expect(text).not.toContain('Estimated extra failed attempts');
    expect(text).toContain('Node: Unavailable');
    expect(text).toContain('No source event IDs supplied');
  });

  it('explains absent baseline and refuses unsupported p95 even if a value was supplied', () => {
    const record = detection(1, 'OPEN');
    record.service = 'SMS';
    record.kpis = [
      { name: 'p95DeliveryMs', observed: 45000, baseline: null, unit: 'MILLISECONDS', numerator: null, denominator: null },
      { name: 'deliveredMessages', observed: 0, baseline: null, unit: 'COUNT', numerator: null, denominator: null },
      { name: 'queueDepth', observed: 250, baseline: null, unit: 'COUNT', numerator: null, denominator: null },
    ];
    const fixture = render([record]);
    const article: HTMLElement = fixture.nativeElement.querySelector('article');
    expect(article.querySelector('[data-kpi="p95DeliveryMs"] td')?.textContent?.trim()).toBe('Unavailable');
    expect(article.querySelector('[data-kpi="queueDepth"] td')?.textContent?.trim()).toBe('250');
    expect(article.textContent).toContain('Expected value unavailable');
    expect(article.textContent).toContain('No completed messages in this window');
  });

  it('makes an empty detection history explicit', () => {
    const fixture = render([]);
    expect(fixture.nativeElement.querySelector('[role="status"]').textContent).toContain('No evidence updates available');
    expect(fixture.nativeElement.querySelectorAll('article')).toHaveLength(0);
  });

  it('withholds unsupported causes and never promotes a ping failure to confirmed power loss', () => {
    for (const evidence of [[], [{ code: 'PING_RESULT', summary: 'Ping failed', nodeId: 'SITE-ORH', sourceEventIds: [] }]]) {
      const record = detection(1, 'OPEN');
      record.probableCause = 'Confirmed power loss';
      record.evidence = evidence;
      const fixture = render([record]);
      const hypothesis = fixture.nativeElement.querySelector('[aria-label="Cause hypothesis"]');
      expect(hypothesis.textContent).toContain('Cause undetermined');
      expect(hypothesis.textContent).not.toContain('Confirmed power loss');
      expect(hypothesis.textContent).toContain('classification: Unavailable');
      expect(hypothesis.textContent).toContain('Affected paths: Unavailable');
    }
  });

  it('does not invent relative change against a zero baseline', () => {
    const record = detection(1, 'OPEN');
    record.kpis = [{ name: 'sip503Count', observed: 5, baseline: 0, unit: 'COUNT', numerator: null, denominator: null }];
    const cells = render([record]).nativeElement.querySelectorAll('[data-kpi] td');
    expect(cells[3].textContent).toBe('+5 count');
    expect(cells[4].textContent).toBe('Unavailable');
  });

  it('keeps a retained hypothesis out of the current cause when measurements are unavailable', () => {
    const record = detection(1, 'UPDATE');
    record.kpis = record.kpis.map(kpi => ({ ...kpi, observed: null }));
    record.probableCause = 'Retained IMS capacity hypothesis';
    const hypothesis = render([record]).nativeElement.querySelector('[aria-label="Cause hypothesis"]');
    expect(hypothesis.textContent).toContain('Cause undetermined');
    expect(hypothesis.textContent).not.toContain('Retained IMS capacity hypothesis');
  });

  it('distinguishes calibrated rank from severity and cause confidence and preserves a real zero rank', () => {
    const record = detection(1, 'OPEN');
    record.mlStatus = 'OK'; record.modelVersion = 'test-model'; record.anomalyRank = 0;
    record.severity = 'HIGH'; record.causeConfidence = 'MEDIUM';
    const text = render([record]).nativeElement.textContent as string;
    expect(text).toContain('Severity: HIGH');
    expect(text).toContain('Cause confidence: MEDIUM');
    expect(text).toContain('Model anomaly rank: 0');
    expect(text).toContain('not a failure probability');
    const timeout: Detection = { ...record, mlStatus: 'TIMEOUT', modelVersion: null, anomalyRank: null };
    expect(render([timeout]).nativeElement.textContent).toContain('Model anomaly rank: Unavailable');
  });
});
