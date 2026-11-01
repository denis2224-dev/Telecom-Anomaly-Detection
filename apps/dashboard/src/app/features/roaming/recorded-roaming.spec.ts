import { loadRecordedRoaming, roamingRate } from './recorded-roaming';
import type { TelecomClient, TraceMetricSeries } from '../../core/api/telecom-client';

type Point = TraceMetricSeries['points'][number];
const from = '2026-10-06T00:00:00Z', to = '2026-10-06T01:00:00Z';
function apiWith(points: Point[]) {
  return {
    traceDatasets: async () => [{datasetId:'archive',from,to,archiveReady:true}],
    tracePartners: async () => ({partners:[{id:'RO-01',country:'RO',direction:'out',active:true}]}),
    traceMetricSeries: async (query: any) => ({...query,unit:query.metric.includes('.active')?'COUNT':'PERCENT',aggregation:query.metric.includes('.active')?'SNAPSHOT_LAST':'RATIO_OF_SUMS',requestedFrom:query.from,requestedTo:query.to,points}),
  } as unknown as TelecomClient;
}
const point = (numerator: number | null, denominator: number | null, quality = 'VALID') =>
  ({ value: denominator ? numerator! / denominator * 100 : null, numerator, denominator, quality, coverage:1, snapshotAt:'2026-10-06T00:55:00Z' } as Point);

describe('Recorded roaming in the original presentation', () => {
  it('sorts recorded buckets before selecting the latest user snapshot', async () => {
    const points = [
      {...point(98,100),value:200,from:'2026-10-06T00:30:00Z',to},
      {...point(98,100),value:100,from,to:'2026-10-06T00:30:00Z'},
    ];
    const result = await loadRecordedRoaming(apiWith(points),from,to,'out',new AbortController().signal);
    expect(result.rows[0].users).toBe(200);
    expect(result.windows.map(window => Date.parse(window.windowStart))).toEqual([Date.parse(from),Date.parse('2026-10-06T00:30:00Z')]);
    expect(result.rates.registration).toBe(98);
  });
  it('rejects invalid, duplicate, overlapping and out-of-range source buckets', async () => {
    for (const points of [
      [{...point(98,100),from:'invalid',to}],
      [{...point(98,100),from:to,to:from}],
      [{...point(98,100),from:'2026-10-05T23:55:00Z',to}],
      [{...point(98,100),from,to:'2026-10-06T01:05:00Z'}],
      [{...point(98,100),from,to},{...point(98,100),from,to}],
    ]) await expect(loadRecordedRoaming(apiWith(points),from,to,'out',new AbortController().signal)).rejects.toThrow('buckets');
  });
  it('does not substitute another dataset or interval for an explicit selection', async () => {
    const api = apiWith([]);
    await expect(loadRecordedRoaming(api,from,to,'out',new AbortController().signal,{datasetId:'missing'})).rejects.toThrow('selected recorded roaming dataset');
    await expect(loadRecordedRoaming(api,from,'2026-10-07T00:00:00Z','out',new AbortController().signal,{strictRange:true})).rejects.toThrow('selected interval');
  });
  it('joins equal instants across partners and rejects incompatible bucket ends', async () => {
    let incompatible = false;
    const api = apiWith([]);
    api.tracePartners = async () => ({catalogueVersion:'',partners:['RO-01','RO-02'].map(id => ({id,country:'RO',direction:'out',active:true,synthetic:true,validFrom:from,validTo:null}))});
    api.traceMetricSeries = async (query: any) => ({...query,unit:query.metric.includes('.active')?'COUNT':'PERCENT',aggregation:query.metric.includes('.active')?'SNAPSHOT_LAST':'RATIO_OF_SUMS',requestedFrom:query.from,requestedTo:query.to,points:[{
      ...point(98,100), from:query.scopeId.startsWith('RO-02') ? '2026-10-06T00:00:00.000Z' : from,
      to:incompatible && query.scopeId.startsWith('RO-02') ? '2026-10-06T00:30:00Z' : to,
    }]} as TraceMetricSeries);
    const result = await loadRecordedRoaming(api,from,to,'out',new AbortController().signal);
    expect(result.windows).toHaveLength(1);
    expect(result.windows[0].quality).toBe('COMPLETE');
    expect(result.rates.registration).toBe(98);
    incompatible = true;
    await expect(loadRecordedRoaming(api,from,to,'out',new AbortController().signal)).rejects.toThrow('incompatible bucket bounds');
  });
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
      traceMetricSeries: async (query: any) => { requests.push(query); return { ...query,unit:query.metric.includes('.active')?'COUNT':'PERCENT',aggregation:query.metric.includes('.active')?'SNAPSHOT_LAST':'RATIO_OF_SUMS',
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
  it('prefers an available non-synthetic source and retains source uncertainty', async () => {
    const api=apiWith([]);
    api.traceDatasets=async()=>[
      {datasetId:'synthetic',from,to,archiveReady:true,synthetic:true,manifestSha256:''},
      {datasetId:'source',from,to,archiveReady:true,synthetic:false,manifestSha256:''},
    ];
    const result=await loadRecordedRoaming(api,from,to,'out',new AbortController().signal);
    expect(result.datasetId).toBe('source');
    expect(result.note).toContain('source unverified');
    expect(result.users).toBeNull();
  });
  it('does not hide synthetic series behind a non-synthetic dataset flag', async () => {
    const api=apiWith([{...point(95,100),from,to}]), original=api.traceMetricSeries;
    api.traceMetricSeries=async(...args)=>({...await original(...args),synthetic:true});
    const result=await loadRecordedRoaming(api,from,to,'out',new AbortController().signal);
    expect(result.synthetic).toBe(true);
    expect(result.note).toContain('Synthetic recorded');
  });
  it('keeps user totals unavailable for missing, stale, negative or differently timed snapshots', async () => {
    for(const change of [{value:null},{quality:'STALE'},{value:-1},{snapshotAt:null},{snapshotAt:from},{coverage:0}] satisfies Partial<Point>[]){
      const api=apiWith([{...point(95,100),from,to,...change}]);
      expect((await loadRecordedRoaming(api,from,to,'out',new AbortController().signal)).users).toBeNull();
    }
    const api=apiWith([{...point(95,100),from,to}]);
    api.tracePartners=async()=>({catalogueVersion:'',partners:['RO-01','RO-02'].map(id=>({id,country:'RO',direction:'out',active:true,synthetic:false,validFrom:from,validTo:null}))});
    const original=api.traceMetricSeries;
    api.traceMetricSeries=async(query,signal)=>{const result=await original(query,signal);return {...result,points:result.points.map(row=>({...row,snapshotAt:query.scopeId==='RO-01:out'?'2026-10-06T00:55:00Z':'2026-10-06T00:50:00Z'}))};};
    expect((await loadRecordedRoaming(api,from,to,'out',new AbortController().signal)).users).toBeNull();
  });
  it('filters country, partner and direction before reading measurements', async () => {
    const api=apiWith([]), read=vi.spyOn(api,'traceMetricSeries');
    api.tracePartners=async()=>({catalogueVersion:'',partners:[['RO-01','RO','out'],['RO-02','RO','out'],['IT-01','IT','out'],['RO-01','RO','in']].map(([id,country,direction])=>({id,country,direction,active:true,synthetic:false,validFrom:from,validTo:null}))} as any);
    await loadRecordedRoaming(api,from,to,'out',new AbortController().signal,{countryId:'RO',partnerDirectionId:'RO-02:out'});
    expect(read).toHaveBeenCalledTimes(4);
    expect(read.mock.calls.every(([query])=>query.scopeId==='RO-02:out')).toBe(true);
    await expect(loadRecordedRoaming(api,from,to,'out',new AbortController().signal,{countryId:'IT',partnerDirectionId:'RO-02:out'})).rejects.toThrow('catalogue');
  });
  it('rejects incompatible units, aggregation and truncated series', async () => {
    for(const change of [{unit:'ERLANG'},{aggregation:'COUNT_SUM'},{nextCursor:'next'}]){
      const api=apiWith([]),original=api.traceMetricSeries;
      api.traceMetricSeries=async(...args)=>({...await original(...args),...change});
      await expect(loadRecordedRoaming(api,from,to,'out',new AbortController().signal)).rejects.toThrow('incompatible');
    }
  });
  it('accepts declared partial edge buckets without expanding the selected interval',async()=>{
    const api=apiWith([{...point(90,100),from,to}]);
    const original=api.traceMetricSeries;
    api.traceMetricSeries=async(...args)=>({...await original(...args),partialStart:true,partialEnd:true,effectiveFrom:from,effectiveTo:to});
    const result=await loadRecordedRoaming(api,'2026-10-06T00:01:00Z','2026-10-06T00:59:00Z','out',new AbortController().signal,{resolution:'1h',strictRange:true});
    expect(result.from).toBe('2026-10-06T00:01:00Z');expect(result.to).toBe('2026-10-06T00:59:00Z');
    expect(result.windows[0].windowStart).toBe('2026-10-06T00:01:00.000Z');expect(result.windows[0].windowEnd).toBe('2026-10-06T00:59:00.000Z');
    expect(result.windows[0].kpis[0]).toMatchObject({observed:90,numerator:90,denominator:100});
  });
  it('charts each recorded procedure from its own counters',async()=>{
    const api=apiWith([]),original=api.traceMetricSeries;
    api.traceMetricSeries=async(query,signal)=>({...await original(query,signal),points:[{...point(query.metric==='roaming.voice.success'?20:query.metric==='roaming.data.success'?50:90,100),from,to}]});
    const result=await loadRecordedRoaming(api,from,to,'out',new AbortController().signal);
    expect(result.windowsByMetric['roaming.voice.success'][0].kpis[0]).toMatchObject({name:'roamingVoiceSrPct',observed:20});
    expect(result.windowsByMetric['roaming.data.success'][0].kpis[0]).toMatchObject({name:'roamingDataSrPct',observed:50});
    expect(result.rates).toMatchObject({registration:90,data:50,voice:20});
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

it('uses an explicit source baseline and retains a usable snapshot before an empty partial tail',async()=>{
  const api=apiWith([
    {...point(98,100),from,to:'2026-10-06T00:55:00Z',snapshotAt:'2026-10-06T00:54:59Z',baseline:99.5,baselineVersion:'demo-static'},
    {...point(null,null,'MISSING'),coverage:0,from:'2026-10-06T00:55:00Z',to,snapshotAt:null},
  ]);
  const result=await loadRecordedRoaming(api,from,to,'out',new AbortController().signal);
  expect(result.windows[0].kpis[0].baseline).toBe(99.5);
  expect(result.windows[0].baselineVersion).toBe('demo-static');
  expect(result.windows[1].kpis[0].baseline).toBeNull();
  expect(result.users).toBe(98);
});
