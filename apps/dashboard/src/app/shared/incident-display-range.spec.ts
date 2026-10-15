import { incidentDisplayRange } from './incident-display-range';

describe('Incident display range',()=>{
  it('marks an in-range incident without narrowing the selected period',()=>{
    const current={from:'2026-10-01T00:00:00Z',to:'2026-10-02T00:00:00Z'};
    expect(incidentDisplayRange('2026-10-01T08:00:00Z','2026-10-01T08:05:00Z',current)).toEqual({from:'2026-10-01T00:00:00.000Z',to:'2026-10-02T00:00:00.000Z'});
  });
  it('keeps the same scale when a recorded incident lies outside the selected period',()=>{
    const result=incidentDisplayRange('2026-09-01T08:00:00Z','2026-09-01T08:05:00Z',{from:'2026-10-01T00:00:00Z',to:'2026-10-02T00:00:00Z'});
    expect(Date.parse(result.to)-Date.parse(result.from)).toBe(86_400_000);
    expect(Date.parse(result.from)).toBeLessThan(Date.parse('2026-09-01T08:00:00Z'));
    expect(Date.parse(result.to)).toBeGreaterThan(Date.parse('2026-09-01T08:05:00Z'));
  });
  it('provides a full context interval for links opened without a period',()=>{
    const result=incidentDisplayRange('2026-10-01T08:00:00Z','2026-10-01T08:05:00Z');
    expect(Date.parse(result.to)-Date.parse(result.from)).toBe(86_400_000);
    expect(()=>incidentDisplayRange('invalid','invalid')).toThrow('Incident interval unavailable');
  });
});
