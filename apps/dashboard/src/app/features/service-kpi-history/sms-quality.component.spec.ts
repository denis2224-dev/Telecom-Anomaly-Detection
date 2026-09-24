import { TestBed } from '@angular/core/testing';
import type { components } from '../../core/api/schema';
import services from '../../../fixtures/services.json';
import { SmsQualityComponent } from './sms-quality.component';

type KpiWindow = components['schemas']['ServiceKpiWindow'];

describe('SMS delivery and queue evidence', () => {
  it('shows delay, completed samples, and a measured backlog', async () => {
    const fixture = TestBed.createComponent(SmsQualityComponent);
    fixture.componentRef.setInput('window', services[1].latestWindow as KpiWindow);
    await fixture.whenStable();

    const text = fixture.nativeElement.textContent as string;
    expect(text).toContain('45000 ms');
    expect(text).toContain('2000 ms');
    expect(text).toContain('Completed messages: 100');
    expect(text).toContain('Queue depth: 250');
    expect(text).toContain('90 seconds');
  });

  it('keeps a zero-sample backlog distinct from missing and empty queue data', async () => {
    const window = structuredClone(services[1].latestWindow) as KpiWindow;
    window.kpis.find(kpi => kpi.name === 'p95DeliveryMs')!.observed = null;
    window.kpis.find(kpi => kpi.name === 'deliveredMessages')!.observed = 0;

    const fixture = TestBed.createComponent(SmsQualityComponent);
    fixture.componentRef.setInput('window', window);
    await fixture.whenStable();
    expect(fixture.nativeElement.textContent).toContain('No messages completed, but a backlog remains.');
    expect(fixture.nativeElement.textContent).toContain('No completed-message delay samples');

    const missing = structuredClone(window);
    missing.kpis.find(kpi => kpi.name === 'queueDepth')!.observed = null;
    missing.kpis.find(kpi => kpi.name === 'oldestPendingAgeSec')!.observed = null;
    fixture.componentRef.setInput('window', missing);
    await fixture.whenStable();
    expect(fixture.nativeElement.textContent).toContain('Queue health is unknown because queue data is unavailable.');
    expect(fixture.nativeElement.textContent).not.toContain('Measured empty queue.');

    const empty = structuredClone(missing);
    empty.kpis.find(kpi => kpi.name === 'queueDepth')!.observed = 0;
    empty.kpis.find(kpi => kpi.name === 'oldestPendingAgeSec')!.observed = 0;
    fixture.componentRef.setInput('window', empty);
    await fixture.whenStable();
    expect(fixture.nativeElement.textContent).toContain('Measured empty queue.');
  });
});
