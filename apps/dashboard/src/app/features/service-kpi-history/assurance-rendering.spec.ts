import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { KpiCardsComponent } from './kpi-cards.component';
import { MetricChartComponent } from './metric-chart.component';
import { ServicePathComponent } from './service-path.component';
import { IncidentStoryComponent } from '../incident-investigation/incident-story.component';
import { voiceWindows, voiceIncidents } from '../../../fixtures/voice';
import { smsWindows, smsIncidents, smsDetections } from '../../../fixtures/sms';
import services from '../../../fixtures/services.json';

describe('VoLTE and SMS assurance rendering', () => {
  beforeEach(() => TestBed.configureTestingModule({ providers: [provideRouter([])] }));
  it('uses the captured geographic source and roles without inventing dependency measurements', async () => {
    const fixture = TestBed.createComponent(ServicePathComponent);
    const service = { ...services[0], scope: { ...services[0].scope, scopeId: 'VOLTE-MD-CHI', dependencyIds: ['IMS-MD-CHI-01', 'TRANSPORT-MD-CHI-01'] },
      latestWindow: { ...voiceWindows[0], scopeId: 'VOLTE-MD-CHI', topologyVersion: '2-geography-g1' } };
    fixture.componentRef.setInput('service', service);
    await fixture.whenStable();
    expect(fixture.nativeElement.textContent).toContain('VOLTE-SRC-CHI');
    expect(fixture.componentInstance.metrics('IMS-MD-CHI-01')).toEqual(['imsCpuPct']);
    expect(fixture.componentInstance.metrics('TRANSPORT-MD-CHI-01')).toEqual(['packetLossRatio']);
    expect(fixture.componentInstance.measured('IMS-MD-CHI-01')).toBe(false);
  });
  it('renders observed VoLTE KPIs and CSSR baseline/delta without inventing absent metrics', async () => {
    const fixture = TestBed.createComponent(KpiCardsComponent);
    fixture.componentRef.setInput('service', { ...services[0], latestWindow: voiceWindows[0] });
    await fixture.whenStable();
    const cssr = fixture.nativeElement.querySelector('[data-kpi-card="cssrPct"]');
    expect(cssr.textContent).toContain('99.6 %'); expect(cssr.textContent).toContain('99.3 %');
    expect(cssr.textContent).toContain('+0.3 pp');
    expect(fixture.nativeElement.querySelector('[data-kpi-card="imsCpuPct"]').textContent).toContain('Unavailable');
  });
  it('renders every canonical SMS card with actual values and units', async () => {
    const fixture = TestBed.createComponent(KpiCardsComponent);
    fixture.componentRef.setInput('service', { ...services[1], latestWindow: smsWindows[0] });
    await fixture.whenStable();
    for (const name of ['p95DeliveryMs', 'deliverySrPct', 'deliveredMessages', 'queueDepth', 'oldestPendingAgeSec']) {
      const card = fixture.nativeElement.querySelector(`[data-kpi-card="${name}"]`);
      expect(card).not.toBeNull(); expect(card.textContent).not.toContain('UNKNOWN / MISSING');
    }
  });
  it('renders missing KPI values as unavailable even when a payload contains zeros', async () => {
    const fixture = TestBed.createComponent(KpiCardsComponent);
    const missing = { ...voiceWindows[0], quality: 'MISSING', kpis: voiceWindows[0].kpis.map(item => ({ ...item, observed: 0 })) };
    fixture.componentRef.setInput('service', { ...services[0], latestWindow: missing }); await fixture.whenStable();
    expect(fixture.nativeElement.querySelector('[data-kpi-card="cssrPct"] .kpi-value').textContent).toBe('Unavailable');
    expect(fixture.nativeElement.textContent).toContain('UNKNOWN / MISSING');
  });
  it('draws real SMS P95 baseline and phase bands from persisted detections', async () => {
    const fixture = TestBed.createComponent(MetricChartComponent);
    fixture.componentRef.setInput('name', 'p95DeliveryMs'); fixture.componentRef.setInput('title', 'SMS P95');
    fixture.componentRef.setInput('windows', smsWindows); fixture.componentRef.setInput('detections', smsDetections);
    fixture.componentRef.setInput('from', smsWindows[0].windowStart); fixture.componentRef.setInput('to', smsWindows.at(-1)!.windowEnd);
    await fixture.whenStable();
    expect(fixture.nativeElement.querySelector('.expected-line').getAttribute('d')).not.toBe('');
    expect(fixture.nativeElement.querySelector('[data-phase="DEGRADED"]')).not.toBeNull();
    expect(fixture.nativeElement.querySelector('[data-phase="RECOVERY"]')).not.toBeNull();
    fixture.componentRef.setInput('name', 'queueDepth'); await fixture.whenStable();
    expect(fixture.nativeElement.querySelector('.expected-line').getAttribute('d')).toBe('');
    expect(fixture.nativeElement.textContent).toContain('No baseline available');
  });
  it('exposes exact backend cause/confidence/checks/evidence and aggregate impact', async () => {
    const fixture = TestBed.createComponent(IncidentStoryComponent);
    fixture.componentRef.setInput('incident', voiceIncidents[0]); await fixture.whenStable();
    expect(fixture.nativeElement.textContent).toContain(voiceIncidents[0].latestDetection.probableCause);
    expect(fixture.nativeElement.textContent).toContain(`Cause confidence: ${voiceIncidents[0].latestDetection.causeConfidence}`);
    expect(fixture.nativeElement.textContent).toContain(`Estimated extra failed attempts: ${voiceIncidents[0].latestDetection.impact.extraFailedAttempts}`);
    fixture.componentRef.setInput('incident', smsIncidents[0]); await fixture.whenStable();
    const detection = smsIncidents[0].latestDetection;
    for (const item of detection.evidence) expect(fixture.nativeElement.textContent).toContain(item.summary);
    for (const check of detection.recommendedChecks) expect(fixture.nativeElement.textContent).toContain(check);
    expect(fixture.nativeElement.textContent).toContain(`Affected delivered messages: ${detection.impact.affectedDeliveredMessages}`);
    expect(fixture.nativeElement.textContent).toContain(`Pending messages: ${detection.impact.pendingMessages}`);
    expect(fixture.nativeElement.textContent).toContain('Unique subscribers: not available in aggregate demo');
    expect(fixture.nativeElement.getAttribute('data-phase')).toBeNull();
    expect(fixture.nativeElement.querySelector('[data-phase="RECOVERY"]')).not.toBeNull();
  });
  it('uses actual dependency IDs and never turns unmeasured SMS transport green', async () => {
    const fixture = TestBed.createComponent(ServicePathComponent);
    fixture.componentRef.setInput('service', { ...services[1], latestWindow: smsWindows[0] }); await fixture.whenStable();
    expect(fixture.nativeElement.textContent).toContain('SMS-ADAPTER');
    expect(fixture.nativeElement.querySelector('[data-node-id="SMSC-A"]').textContent).toContain('OBSERVED CONTEXT');
    expect(fixture.nativeElement.querySelector('[data-node-id="TRANSPORT-A"]').textContent).toContain('UNKNOWN / NO CURRENT MEASUREMENT');
  });
});
