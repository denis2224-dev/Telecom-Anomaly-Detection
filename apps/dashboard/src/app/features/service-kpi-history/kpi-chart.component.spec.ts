import { TestBed } from '@angular/core/testing';
import { KpiChartComponent } from './kpi-chart.component';
import { voiceWindows, voiceRange, voiceIncidents } from '../../../fixtures/voice';
import { episodes, observed } from './voice-model';

describe('Voice evidence semantics', () => {
  it('never presents zero attempts or missing telemetry as measured zero percent', () => {
    expect(observed(voiceWindows[4])).toBeNull();
    expect(observed(voiceWindows[8])).toBeNull();
    const measuredZero = structuredClone(voiceWindows[0]);
    measuredZero.kpis[0].observed = 0;
    expect(observed(measuredZero)).toBe(0);
  });
  it('keeps newest episode version without coupling technical and workflow state', () => {
    const newest = voiceIncidents[0];
    expect(episodes([{ ...newest, version: 1, technicalState: 'ONGOING' }, newest, newest])).toEqual([newest]);
    expect(newest.technicalState).toBe('RECOVERED');
    expect(newest.status).toBe('OPEN');
  });
  it('breaks plotted lines for null values and absent time windows', async () => {
    const fixture = TestBed.createComponent(KpiChartComponent);
    fixture.componentRef.setInput('from', voiceRange.from);
    fixture.componentRef.setInput('to', voiceRange.to);
    fixture.componentRef.setInput('windows', voiceWindows);
    await fixture.whenStable();
    expect(fixture.componentInstance.path(false).match(/M/g)).toHaveLength(3);
    expect(fixture.componentInstance.path(true).match(/M/g)).toHaveLength(1);
    expect(fixture.componentInstance.attempts()).toBe(8000);
    fixture.componentRef.setInput('windows', [voiceWindows[0], voiceWindows[2]]);
    await fixture.whenStable();
    expect(fixture.componentInstance.path(false).match(/M/g)).toHaveLength(2);
  });
});
