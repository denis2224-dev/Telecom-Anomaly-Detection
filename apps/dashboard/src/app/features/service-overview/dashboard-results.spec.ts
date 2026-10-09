import { describe, expect, it } from 'vitest';
import { callResults } from './dashboard-results';
import { voiceWindows } from '../../../fixtures/voice';

const row = () => ({ ...voiceWindows[0], kpis: [{ name: 'cssrPct', unit: 'PERCENT' as const, observed: 98.7, baseline: 99.3, numerator: 987, denominator: 1000 }] });
describe('Observed call results', () => {
  it('conserves measured attempts without attributing failures to a cause', () => {
    expect(callResults(row())).toEqual({ total: 1000, successful: 987, failed: 13, rate: 98.7 });
  });
  it('keeps missing, partial, zero-volume and inconsistent observations unavailable', () => {
    expect(callResults(undefined)).toBeNull();
    expect(callResults({ ...row(), quality: 'INCOMPLETE' })).toBeNull();
    for (const change of [{ denominator: 0 }, { numerator: null }, { numerator: 1001 }, { numerator: 987.5 }, { observed: 50 }, { observed: Number.NaN }]) {
      const window = row();
      expect(callResults({ ...window, kpis: [{ ...window.kpis[0], ...change }] })).toBeNull();
    }
  });
});
