import { allPages, metricValue, phaseAt, serviceHealth } from './assurance-model';
import { voiceWindows, voiceIncidents } from '../../../fixtures/voice';
import services from '../../../fixtures/services.json';
import type { ServiceSummary } from '../../core/api/telecom-client';

describe('Assurance evidence semantics', () => {
  it('keeps missing, absent and zero-sample KPIs distinct from measured zero', () => {
    expect(metricValue(voiceWindows[4], 'cssrPct')).toBeNull();
    expect(metricValue(voiceWindows[8], 'cssrPct')).toBeNull();
    expect(metricValue(voiceWindows[0], 'imsCpuPct')).toBeNull();
    const zero = structuredClone(voiceWindows[0]); zero.kpis[0].observed = 0;
    expect(metricValue(zero, 'cssrPct')).toBe(0);
  });
  it('uses only persisted episode transitions for degradation and recovery', () => {
    const base = voiceIncidents[0].latestDetection;
    const opening = { ...base, phase: 'OPEN' as const, sequence: 1, windowStart: voiceWindows[2].windowStart, windowEnd: voiceWindows[2].windowEnd };
    const recovery = { ...base, phase: 'RECOVERY' as const, sequence: 2, windowStart: voiceWindows[7].windowStart, windowEnd: voiceWindows[7].windowEnd };
    expect(phaseAt(voiceWindows[0], [opening, recovery])).toBe('UNKNOWN');
    expect(phaseAt(voiceWindows[6], [opening, recovery])).toBe('DEGRADED');
    expect(phaseAt(voiceWindows[7], [opening, recovery])).toBe('RECOVERY');
    expect(phaseAt(voiceWindows[9], [opening, recovery])).toBe('NORMAL');
    expect(phaseAt(voiceWindows[4], [opening, recovery])).toBe('UNKNOWN');
    expect(phaseAt(voiceWindows[9], [{ ...opening, phase: 'UNKNOWN', sequence: 3 }])).toBe('UNKNOWN');
  });
  it('does not equate a healthy point or an unresolved workflow count with recovery', () => {
    const service = { ...services[0], latestWindow: voiceWindows[0], openIncidents: 1 } as ServiceSummary;
    expect(serviceHealth(service, [{ ...voiceIncidents[0], technicalState: 'ONGOING' }])).toBe('DEGRADED');
    expect(serviceHealth(service, voiceIncidents)).toBe('NORMAL');
    expect(serviceHealth({ ...service, latestWindow: voiceWindows[4] })).toBe('UNKNOWN');
    expect(serviceHealth({ ...service, freshness: 'STALE' })).toBe('STALE');
  });
  it('bounds overview incident reads and rejects shifting totals', async () => {
    await expect(allPages(async () => ({ items: [], total: 10001 }))).rejects.toThrow('limit');
    let calls = 0;
    await expect(allPages(async () => ({ items: [1], total: ++calls === 1 ? 2 : 3 }))).rejects.toThrow('changed');
  });
});
