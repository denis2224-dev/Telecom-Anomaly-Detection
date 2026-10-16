import { loadRecordedRoaming, roamingRate } from './recorded-roaming';
import type { TelecomClient, TraceMetricSeries } from '../../core/api/telecom-client';

type Point = TraceMetricSeries['points'][number];
const point = (numerator: number | null, denominator: number | null, quality = 'VALID') =>
  ({ value: denominator ? numerator! / denominator * 100 : null, numerator, denominator, quality } as Point);

describe('Recorded roaming in the original presentation', () => {
  it('weights rates by source attempts and keeps missing or stale samples out', () => {
    expect(roamingRate([point(1, 1), point(1, 9)])).toBe(20);
    expect(roamingRate([point(9, 10, 'STALE'), point(0, 0), point(null, null)])).toBeNull();
    expect(roamingRate([point(95, 100, 'INSUFFICIENT_EVIDENCE')])).toBe(95);
  });
  it('uses recorded bounds, one direction and no invented SMS or baseline', async () => {
    const requests: any[] = [];
    const api = {
      traceDatasets: async () => [{ datasetId: 'archive', from: '2026-10-06T00:00:00Z', to: '2026-10-08T00:00:00Z', archiveReady: true, synthetic: true, catalogueVersion: 'recorded' }],
      tracePartners: async (_: unknown, version: string) => {
        expect(version).toBe('recorded');
        return { catalogueVersion: 'recorded', partners: ['in','out'].map(direction => ({ id: 'RO-01', country: 'RO', direction, active: true })) };
      },
      traceMetricSeries: async (query: any) => { requests.push(query); return { ...query,
        requestedFrom: query.from, requestedTo: query.to, points: [{ ...point(95, 100), from: query.from, to: query.to }] }; },
    } as unknown as TelecomClient;
    const result = await loadRecordedRoaming(api, '2026-10-16T00:00:00Z', '2026-10-16T01:00:00Z', 'out', new AbortController().signal);
    expect(requests).toHaveLength(4);
    expect(requests.every(query => query.scopeId === 'RO-01:out' && query.to === '2026-10-08T00:00:00Z')).toBe(true);
    expect(result).toMatchObject({datasetId:'archive',direction:'out',resolution:'1h'});
    expect(result.rows[0]).toMatchObject({ country: 'Romania', registration: 95, sms: null });
    expect(result.windows[0].kpis[0].baseline).toBeNull();
    expect(result.note).toContain('Synthetic recorded outbound');
    expect(result.note).toContain('2026-10-06 00:00');
  });
});

 it('does not read partner data after a cancelled dataset request returns late',async()=>{
  const controller=new AbortController();let release!:(value:unknown)=>void;
  const tracePartners=vi.fn();
  const api={traceDatasets:()=>new Promise(resolve=>{release=resolve;}),tracePartners} as unknown as TelecomClient;
  const request=loadRecordedRoaming(api,'2026-10-06T00:00:00Z','2026-10-08T00:00:00Z','out',controller.signal);
  controller.abort();release([{datasetId:'archive',from:'2026-10-06T00:00:00Z',to:'2026-10-08T00:00:00Z',archiveReady:true}]);
  await expect(request).rejects.toThrow();expect(tracePartners).not.toHaveBeenCalled();
 });
