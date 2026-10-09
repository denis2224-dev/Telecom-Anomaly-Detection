import { countryPreview, previewScenarios, scenarioPreview } from './monitoring-preview';
const start = '2026-10-09T10:00:00Z';
describe('Bounded local monitoring previews', () => {
  it('repeats each seeded eight-minute profile, scopes evidence and recovers after disruption', () => {
    for (const scenario of previewScenarios) {
      const data = scenarioPreview(scenario.id, 42, 'VOLTE-MD-ORH', 'Romania', start);
      expect(data).toEqual(scenarioPreview(scenario.id, 42, 'VOLTE-MD-ORH', 'Romania', start));
      expect(data.rows).toHaveLength(8);
      expect(data.rows.every(row => row.scopeId === 'VOLTE-MD-ORH' && row.windowStart.endsWith('Z'))).toBe(true);
      expect(Date.parse(data.rows.at(-1)!.windowEnd) - Date.parse(start)).toBe(480000);
      const values = data.rows.map(row => row.kpis.find(kpi => kpi.name === scenario.metric)!.observed!);
      const increases = ['imsCpuPct', 'linkUtilizationPct', 'p95DeliveryMs'].includes(scenario.metric);
      expect(increases ? values[3] > values[0] : values[3] < values[0]).toBe(true);
      expect(data.timeline[0].phase).toBe('OPEN'); expect(data.timeline.at(-1)!.phase).toBe('RECOVERY');
      expect(data.timeline.every(event => event.target === (scenario.id.includes('ROAMING') ? 'Romania' : 'VOLTE-MD-ORH'))).toBe(true);
      expect(data.rows).not.toEqual(scenarioPreview(scenario.id, 43, 'VOLTE-MD-ORH', 'Romania', start).rows);
      for (const row of data.rows) {
        const success = row.kpis.find(kpi => kpi.name === 'cssrPct')!, failed = row.kpis.find(kpi => kpi.name === 'failedCallSharePct')!;
        expect(success.observed! + failed.observed!).toBeCloseTo(100);
        expect(success.numerator! + failed.numerator!).toBe(success.denominator);
      }
    }
  });
  it('conserves queued SMS messages and drains the backlog without loss', () => {
    const data = scenarioPreview('SMS_DELAYED_DELIVERY', 0, 'SMS-MD-CHI', '', start);
    let pending = 0;
    for (const row of data.rows) {
      const value = (name: string) => row.kpis.find(kpi => kpi.name === name)!.observed!;
      pending += value('receivedMessages') - value('deliveredMessages');
      expect(value('queueDepth')).toBe(pending);
      expect(value('lostMessages')).toBe(0);
      expect(value('deliveredLatePct')).toBeGreaterThanOrEqual(0); expect(value('deliveredLatePct')).toBeLessThanOrEqual(100);
    }
    expect(data.cumulativeReceived).toBe(data.cumulativeDelivered + data.queued);
    expect(data.queued).toBe(0);
  });
  it('only changes the affected country and rejects invalid preview inputs', () => {
    const normal = countryPreview(42), affected = countryPreview(42, 'Romania');
    expect(affected.slice(1)).toEqual(normal.slice(1)); expect(affected[0].voice).toBeLessThan(normal[0].voice);
    expect(() => scenarioPreview('VOLTE_CALL_SUCCESS_DROP', -1, 'scope', '', start)).toThrow();
    expect(() => scenarioPreview('VOLTE_CALL_SUCCESS_DROP', 42, 'scope', '', 'invalid')).toThrow();
  });
});
