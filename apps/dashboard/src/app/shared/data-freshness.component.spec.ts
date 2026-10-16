import {freshnessLabel} from './data-freshness.component';
const now=Date.parse('2026-10-17T12:00:00Z');
describe('Measurement freshness',()=>{
  it('uses measurement time, distinguishes historical coverage and does not fill missing tails',()=>{
    expect(freshnessLabel('2026-10-17T11:58:00Z','2026-10-17T12:00:00Z',now)).toEqual({state:'RECENT',text:'Last measurement 2m ago'});
    expect(freshnessLabel('2026-10-15T12:00:00Z','2026-10-15T12:00:00Z',now).state).toBe('HISTORICAL');
    const gap=freshnessLabel('2026-10-17T11:30:00Z','2026-10-17T12:00:00Z',now);
    expect(gap.state).toBe('GAP');expect(gap.text).toContain('30m before selected end');
    expect(freshnessLabel('2026-10-17T11:00:00Z','2026-10-17T12:00:00Z',now,false,false,3_600_000).state).toBe('RECENT');
  });
  it('keeps sample, loading, unavailable and invalid clock states explicit',()=>{
    expect(freshnessLabel(null,'',now,true).state).toBe('SAMPLE');
    expect(freshnessLabel(null,'',now,false,true).state).toBe('LOADING');
    expect(freshnessLabel(null,'2026-10-17T12:00:00Z',now).state).toBe('MISSING');
    expect(freshnessLabel('bad','bad',now).state).toBe('MISSING');
    expect(freshnessLabel('2026-10-18T12:00:00Z','2026-10-18T12:00:00Z',now).state).toBe('UNKNOWN');
  });
});
