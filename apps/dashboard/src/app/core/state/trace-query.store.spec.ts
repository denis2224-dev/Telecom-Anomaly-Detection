import { TestBed } from '@angular/core/testing';
import { Subject } from 'rxjs';
import { SessionStore } from '../../features/login-and-session/session.store';
import { TraceQueryStore, traceContextParameters } from './trace-query.store';

describe('Shared Trace Link query context',()=>{
  let store:TraceQueryStore,ended:Subject<void>;
  beforeEach(()=>{ended=new Subject<void>();TestBed.configureTestingModule({providers:[{provide:SessionStore,useValue:{ended$:ended}}]});store=TestBed.inject(TraceQueryStore);store.update({datasetId:'recorded',from:'2026-09-10T09:00:00Z',to:'2026-10-10T09:00:00Z'});});
  it('keeps period and resolution independent',()=>{
    store.update({resolution:'1h'});store.preset(24);expect(store.context().resolution).toBe('1h');
    const from=store.context().from;store.update({resolution:'5m'});expect(store.context().from).toBe(from);
  });
  it('zooms only visible bounds, resets to the selected period and rejects out-of-period zoom',()=>{
    const before={...store.context()};expect(store.zoom('2026-10-09T09:00:00Z','2026-10-10T09:00:00Z')).toBe(true);
    expect(store.context().from).toBe(before.from);expect(store.context().to).toBe(before.to);
    expect(store.zoom('2026-09-09T09:00:00Z',before.to)).toBe(false);store.resetZoom();expect(store.context()).toEqual(before);
  });
  it('clears incompatible link focus without changing country or domestic context for roaming filters',()=>{
    store.update({linkDirectionId:'chi-orh:a-to-b'});store.update({cityId:'chi'},[{id:'chi-orh',endpointA:'chi',endpointB:'orh'}]);
    expect(store.context().linkDirectionId).toBe('chi-orh:a-to-b');store.update({cityId:'hin'},[{id:'chi-orh',endpointA:'chi',endpointB:'orh'}]);expect(store.context().linkDirectionId).toBeUndefined();
    store.update({countryId:'RO',roamingDirection:'in'});expect(store.context().cityId).toBe('hin');expect(store.context().domain).toBe('volte');
  });
  it('rejects invalid UTC bounds, unsupported resolution and periods beyond 30 days',()=>{
    const before=store.context();expect(store.update({from:'2026-09-10T12:00:00+03:00'})).toBe(false);
    expect(store.update({from:'2026-09-01T09:00:00Z'})).toBe(false);expect(store.update({to:before.from})).toBe(false);
    expect(store.context()).toBe(before);
  });
  it('round-trips a stable secret-free URL context including visible bounds',()=>{
    store.zoom('2026-10-09T09:00:00Z','2026-10-10T09:00:00Z');const parameters=traceContextParameters(store.context());
    expect(store.restore({...parameters,token:'not-a-context-field'})).toBe(true);expect(traceContextParameters(store.context())).toEqual(parameters);
    expect(Object.keys(traceContextParameters(store.context()))).not.toContain('token');
  });
  it('rejects impossible calendar dates and URL-like dataset identifiers',()=>{
    expect(store.update({from:'2026-02-30T09:00:00Z'})).toBe(false);
    expect(store.update({datasetId:'data?token=secret'})).toBe(false);
    expect(store.update({from:'2026-10-09T09:00:00.000Z'})).toBe(true);
  });
  it('removes zoom and link parameters when restoring a URL without them',()=>{
    store.update({linkDirectionId:'chi-orh:a-to-b'});store.zoom('2026-10-09T09:00:00Z','2026-10-10T09:00:00Z');
    store.restore({datasetId:'recorded',from:'2026-09-10T09:00:00Z',to:'2026-10-10T09:00:00Z'});
    expect(store.context().linkDirectionId).toBeUndefined();expect(store.context().visibleFrom).toBeUndefined();
  });
  it('restores a city and directional link together before inventory is loaded',()=>{
    expect(store.restore({...traceContextParameters(store.context()),cityId:'chi',linkDirectionId:'chi-orh:a-to-b'})).toBe(true);
    expect(store.context().linkDirectionId).toBe('chi-orh:a-to-b');
  });
  it('rejects ambiguous repeated query parameters',()=>{
    expect(store.restore({from:['2026-10-09T09:00:00Z'] as unknown as string})).toBe(false);
  });
  it('clears partner focus when its roaming filters change without changing the city',()=>{
    store.update({countryId:'RO',partnerDirectionId:'RO-DEMO-01:out',cityId:'chi'});
    store.update({partnerDirectionId:'RO-DEMO-01:out'});
    store.update({countryId:'IT'});expect(store.context().partnerDirectionId).toBeUndefined();expect(store.context().cityId).toBe('chi');
    store.update({partnerDirectionId:'IT-DEMO-01:out'});store.update({roamingDirection:'in'});expect(store.context().partnerDirectionId).toBeUndefined();
  });
  it('accepts an atomic country, direction and partner focus from an incident',()=>{
    expect(store.update({countryId:'IT',roamingDirection:'in',partnerDirectionId:'IT-DEMO-01:in'})).toBe(true);
    expect(store.context().partnerDirectionId).toBe('IT-DEMO-01:in');
  });
  it('clears the query on session end and changes revision for cancellation guards',()=>{
    const revision=store.revision();ended.next();expect(store.context().datasetId).toBe('');expect(store.revision()).toBeGreaterThan(revision);
  });
});
