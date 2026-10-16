import { TestBed } from '@angular/core/testing';
import { Subject } from 'rxjs';
import { IncidentAcknowledgements, incidentDashboardParameters } from './incident-dashboard-actions.component';
import { TelecomClient } from '../../core/api/telecom-client';
import { SessionStore } from '../login-and-session/session.store';
import { voiceIncidents } from '../../../fixtures/voice';

describe('Persisted incident acknowledgement', () => {
  function setup() {
    const item = {...structuredClone(voiceIncidents[0]),status:'OPEN' as const,assigneeId:null,version:3};
    const assigned = {...item,assigneeId:'analyst-1',version:4};
    const acknowledged = {...assigned,status:'INVESTIGATING' as const,version:5};
    const api = {
      getIncident:vi.fn().mockResolvedValue(item),
      listAnalysts:vi.fn().mockResolvedValue([{id:'analyst-1',enabled:true}]),
      assignIncident:vi.fn().mockResolvedValue(assigned),
      changeStatus:vi.fn().mockResolvedValue(acknowledged),
    };
    const ended$ = new Subject<void>();
    const session = {revision:1,ended$,actor:()=>({analystId:'analyst-1',roles:['ANALYST']})};
    TestBed.configureTestingModule({providers:[{provide:TelecomClient,useValue:api},{provide:SessionStore,useValue:session}]});
    return {item,assigned,acknowledged,api,session,store:TestBed.inject(IncidentAcknowledgements)};
  }
  it('deduplicates concurrent actions and uses the assigned version for the transition',async()=>{
    const {item,api,acknowledged,store}=setup();
    const [a,b]=await Promise.all([store.acknowledge(item.id),store.acknowledge(item.id)]);
    expect(a).toEqual(acknowledged);expect(b).toEqual(a);
    expect(api.assignIncident).toHaveBeenCalledTimes(1);
    expect(api.changeStatus).toHaveBeenCalledExactlyOnceWith(item.id,{status:'INVESTIGATING',version:4});
    expect(store.current(item)).toEqual(acknowledged);
  });
  it('reconciles a partial assignment before retrying the status change',async()=>{
    const {item,assigned,api,store}=setup();
    api.changeStatus.mockRejectedValueOnce(new Error('Connection lost'));
    await expect(store.acknowledge(item.id)).rejects.toThrow('Connection lost');
    expect(store.current(item)).toEqual(assigned);
    api.getIncident.mockResolvedValue(assigned);
    await store.acknowledge(item.id);
    expect(api.assignIncident).toHaveBeenCalledTimes(1);
    expect(api.changeStatus).toHaveBeenCalledTimes(2);
  });
  it('rejects a response from an ended session before any write',async()=>{
    const {item,api,session,store}=setup();
    api.getIncident.mockImplementation(async()=>{session.revision++;return item;});
    await expect(store.acknowledge(item.id)).rejects.toThrow('Session changed');
    expect(api.assignIncident).not.toHaveBeenCalled();expect(api.changeStatus).not.toHaveBeenCalled();
  });
  it('does not write an already acknowledged episode and clears cached identity on signout',async()=>{
    const {item,acknowledged,api,session,store}=setup();
    api.getIncident.mockResolvedValue(acknowledged);
    await store.acknowledge(item.id);
    expect(api.changeStatus).not.toHaveBeenCalled();
    session.ended$.next();expect(store.saved().size).toBe(0);
  });
  it('carries incident identity, service, scope and an exclusive interval in dashboard links',()=>{
    const {item}=setup();const params=incidentDashboardParameters(item);
    expect(params.incidentId).toBe(item.id);expect(params.scopeId).toBe(item.scopeId);
    expect(params.service).toBe(item.service);expect(Date.parse(params.to)).toBeGreaterThan(Date.parse(item.lastObservedAt));
  });
});
