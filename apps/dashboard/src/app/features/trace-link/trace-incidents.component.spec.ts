import {TestBed} from '@angular/core/testing';
import {signal} from '@angular/core';
import {TelecomClient} from '../../core/api/telecom-client';
import {TraceQueryStore,type TraceQueryContext} from '../../core/state/trace-query.store';
import {IncidentStream} from '../../core/state/incident-stream';
import {SessionStore} from '../login-and-session/session.store';
import {TraceIncidentsComponent} from './trace-incidents.component';

describe('Incident population filters',()=>{
  for(const domain of ['volte','roaming'] as const)it(`applies a retained partner filter only to roaming, selected domain ${domain}`,async()=>{
    const context:TraceQueryContext={datasetId:'archive',from:'2026-10-06T00:00:00Z',to:'2026-10-06T01:00:00Z',resolution:'5m',timezone:'UTC',cityId:'all',countryId:'all',roamingDirection:'out',domain,partnerDirectionId:'partner-1:out'};
    const items=[1,2].map(id=>({episodeId:String(id),latestDetection:{context:{scope:{partnerId:`partner-${id}`,directionId:'out'}}}}));
    TestBed.configureTestingModule({providers:[{provide:TelecomClient,useValue:{traceIncidents:vi.fn().mockResolvedValue({items,total:2,limit:50,offset:0})}},
      {provide:TraceQueryStore,useValue:{context:signal(context)}},{provide:IncidentStream,useValue:{connect:()=>()=>{}}},
      {provide:SessionStore,useValue:{phase:signal('authenticated'),actor:signal({}),revision:1}}]});
    TestBed.overrideComponent(TraceIncidentsComponent,{set:{template:''}});
    const fixture=TestBed.createComponent(TraceIncidentsComponent);fixture.detectChanges();
    await vi.waitFor(()=>expect(fixture.componentInstance.busy()).toBe(false));
    expect(fixture.componentInstance.items().map(item=>item.episodeId)).toEqual(domain==='roaming'?['1']:['1','2']);
    expect(fixture.componentInstance.total()).toBe(2);fixture.destroy();
  });
});
