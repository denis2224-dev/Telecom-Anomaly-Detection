import { voiceIncidents, voiceWindows } from '../../fixtures/voice';
import { deviation, rankValue, supportedValue, type Kpi } from './metric-presentation';

describe('Nullable metric presentation', () => {
  const rate: Kpi = { ...voiceWindows[0].kpis[0], observed: 96, baseline: 98 };
  const p95: Kpi = { name: 'p95DeliveryMs', observed: 45000, baseline: 2000,
    unit: 'MILLISECONDS', numerator: null, denominator: null };
  const count = (observed: number | null): Kpi => ({ ...p95,
    name: 'deliveredMessages', unit: 'COUNT', observed, baseline: null });

  it('uses percentage points and preserves a real zero baseline', () => {
    expect(deviation(rate, 96)).toBe('-2 pp');
    expect(deviation({ ...rate, baseline: null }, 96)).toBe('Comparison unavailable');
    expect(deviation({ ...rate, baseline: 0 }, 0)).toBe('0 pp');
    expect(supportedValue({ ...rate, observed: 0 }, [rate])).toBe(0);
    expect(supportedValue({ ...rate, denominator: 0 }, [rate])).toBeNull();
  });

  it('supports p95 only with completed samples and retains independent queue evidence', () => {
    expect(supportedValue(p95, [p95, count(0)])).toBeNull();
    expect(supportedValue(p95, [p95, count(null)])).toBeNull();
    expect(supportedValue(p95, [p95, count(5)])).toBe(45000);
    expect(supportedValue(p95, [p95, count(5)], 'MISSING')).toBeNull();
    const queue: Kpi = { ...count(0), name: 'queueDepth' };
    expect(supportedValue(queue, [queue], 'INCOMPLETE')).toBe(0);
  });

  it('keeps zero model rank distinct from missing or failed model results', () => {
    const record = voiceIncidents[0].latestDetection;
    expect(rankValue({ ...record, mlStatus: 'OK', anomalyRank: 0 })).toBe('0');
    expect(rankValue({ ...record, mlStatus: 'OK', anomalyRank: null })).toBe('Unavailable');
    expect(rankValue({ ...record, mlStatus: 'TIMEOUT', anomalyRank: 0.82 })).toBe('Unavailable');
  });
});
