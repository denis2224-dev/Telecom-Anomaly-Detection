import { historySlices, mergeWindows, metricValue, phaseAt, serviceHealth } from './assurance-model';
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
    expect(phaseAt(voiceWindows[0], [opening, recovery])).toBe('NORMAL');
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
  it('splits 48 hours into two legal half-open 24 hour requests', () => {
    const ranges = historySlices('2026-09-13T10:00:00Z', '2026-09-15T10:00:00Z');
    expect(ranges).toHaveLength(2);
    expect(ranges[0].to).toBe(ranges[1].from);
    for (const range of ranges) expect(Date.parse(range.to) - Date.parse(range.from)).toBe(86400000);
    expect(() => historySlices('2026-09-12T10:00:00Z', '2026-09-15T10:00:00Z')).toThrow();
  });
  it('merges overlapping pages by window id/start, retaining latest and sorting', () => {
    const changed = { ...voiceWindows[0], quality: 'INCOMPLETE' as const };
    expect(mergeWindows([voiceWindows[2], voiceWindows[0]], [voiceWindows[1], changed, voiceWindows[2]]))
      .toEqual([changed, voiceWindows[1], voiceWindows[2]]);
  });
});
