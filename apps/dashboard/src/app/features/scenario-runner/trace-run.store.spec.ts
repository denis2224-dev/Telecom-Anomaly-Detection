import { TestBed } from '@angular/core/testing';
import { signal } from '@angular/core';
import { Subject } from 'rxjs';
import { TelecomClient, type TraceScenarioRun, type TraceScenarioStart } from '../../core/api/telecom-client';
import { ApiFailure } from '../../core/api/api-errors';
import { SessionStore } from '../login-and-session/session.store';
import { TraceRunStore } from './trace-run.store';
import { percentageBasisPoints, missingMinuteIndices, plannedTraceStage } from './trace-scenario-runner.component';

const command: TraceScenarioStart = {requestId:'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa',type:'MOBILE_DATA_DNS_FAILURE',version:'trace-link-scenarios-v1',
  seed:42,scope:{domain:'MOBILE_DATA',id:'chi',direction:null,country:null,procedure:'DNS',topologyVersion:'trace-link-links-v1'}};
const run: TraceScenarioRun = {runId:'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb',fingerprint:'f'.repeat(64),command:{...command,start:'2026-10-12T09:00:00Z',parameters:{normalUtilizationBp:5000,degradedUtilizationBp:9800,capacityGbps:20,attempts:1000,healthySuccessBp:9900,degradedSuccessBp:7000,missingMinutes:[]}},
  status:'SCHEDULED',completedWindows:0,generatedObservations:0,deliveryStatus:'DISABLED_PENDING_REVIEW',offered:0,acknowledged:0,persisted:null,accepted:null,history:[]};

function setup(api: object) {
  const ended = new Subject<void>(), phase = signal('authenticated'); let revision = 1;
  const session = {phase,actor:signal({analystId:'trace-test'}),ended$:ended.asObservable(),get revision(){return revision;}};
  sessionStorage.removeItem('trace-link.pending-command:trace-test');
  TestBed.configureTestingModule({providers:[{provide:TelecomClient,useValue:api},{provide:SessionStore,useValue:session}]});
  return {store:TestBed.inject(TraceRunStore),end(){phase.set('expired');revision++;ended.next();}};
}
describe('Trace Link command and session boundaries',()=>{
  it('ignores history requested before a newly accepted command',async()=>{
    let resolve!: (runs:TraceScenarioRun[])=>void;
    const {store}=setup({traceScenarioCatalogue:async()=>({enabled:true}),traceScenarioHistory:()=>new Promise<TraceScenarioRun[]>(r=>resolve=r),startTraceScenario:async()=>run});
    const load=store.load();await Promise.resolve();await store.start(command);resolve([]);await load;
    expect(store.runs()).toEqual([run]);
  });
  it('stops polling terminal interrupted history and permits an independent command',async()=>{
    const traceScenarioRun=vi.fn(),startTraceScenario=vi.fn().mockResolvedValue({...run,runId:'second'});
    const {store}=setup({traceScenarioRun,startTraceScenario});
    store.runs.set([{...run,status:'INTERRUPTED'}]);await store.refresh();expect(traceScenarioRun).not.toHaveBeenCalled();
    await store.start(command);expect(store.runs().length).toBe(2);expect(store.runs().find(item=>item.status==='INTERRUPTED')).toBeTruthy();
  });
  it('retries an uncertain start with identical identity and parameters',async()=>{
    const startTraceScenario=vi.fn().mockRejectedValueOnce(new ApiFailure(503)).mockResolvedValueOnce(run);
    const {store}=setup({startTraceScenario});await store.start(command);
    expect(store.pending()?.requestId).toBe(command.requestId);expect(store.rejected()).toBe(false);
    await store.retry();expect(startTraceScenario.mock.calls[0]).toEqual(startTraceScenario.mock.calls[1]);expect(store.pending()).toBeNull();
  });
  it('definite rejection allows a new command and preserves existing history',async()=>{
    const {store}=setup({startTraceScenario:vi.fn().mockRejectedValue(new ApiFailure(409))});store.runs.set([run]);
    await store.start(command);expect(store.rejected()).toBe(true);store.abandonRejected();expect(store.pending()).toBeNull();expect(store.runs()).toEqual([run]);
  });
  it('clears private state and ignores a late accepted response after logout',async()=>{
    let resolve!: (run:TraceScenarioRun)=>void;
    const {store,end}=setup({startTraceScenario:()=>new Promise<TraceScenarioRun>(r=>resolve=r)});
    const pending=store.start(command);end();resolve(run);await pending;
    expect(store.runs()).toEqual([]);expect(store.pending()).toBeNull();expect(store.error()).toBe('');expect(store.busy()).toBe(false);
  });
  it('does not let a status response overwrite a completed stop',async()=>{
    let resolve!: (run:TraceScenarioRun)=>void;
    const {store}=setup({traceScenarioRun:()=>new Promise<TraceScenarioRun>(r=>resolve=r),stopTraceScenario:async()=>({...run,status:'STOPPED'})});
    store.runs.set([run]);const refresh=store.refresh();await store.stop(run.runId);resolve({...run,status:'RUNNING'});await refresh;
    expect(store.runs()[0].status).toBe('STOPPED');
  });
  it('loads authoritative history and retains multiple independent active scopes',async()=>{
    const other={...run,runId:'other',command:{...run.command,scope:{...run.command.scope,id:'bal'}}};
    const {store}=setup({traceScenarioCatalogue:async()=>({enabled:true}),traceScenarioHistory:async()=>[run,other],traceScenarioRun:async(id:string)=>id===run.runId?{...run,status:'RUNNING'}:other});
    await store.load();await store.refresh();expect(store.runs().length).toBe(2);expect(store.runs().find(item=>item.runId===run.runId)?.status).toBe('RUNNING');
  });
  it('feature off does not query history',async()=>{
    const history=vi.fn();const {store}=setup({traceScenarioCatalogue:async()=>({enabled:false}),traceScenarioHistory:history});
    await store.load();expect(history).not.toHaveBeenCalled();expect(store.catalogue()?.enabled).toBe(false);
  });
});
describe('Trace Link exact parameter presentation',()=>{
  it('converts at most two decimal places explicitly without rounding',()=>{
    expect(percentageBasisPoints('99.01')).toBe(9901);expect(percentageBasisPoints('0')).toBe(0);expect(percentageBasisPoints('100.00')).toBe(10000);
    for(const raw of ['99.001','100.01','-1','NaN','1e2','']) expect(percentageBasisPoints(raw)).toBeNull();
  });
  it('retains missing recovery indices and rejects duplicates or invalid indices',()=>{
    expect(missingMinuteIndices('5, 6,7')).toEqual([5,6,7]);expect(missingMinuteIndices('')).toEqual([]);
    for(const raw of ['7,7','8','-1','1.5','1,']) expect(missingMinuteIndices(raw)).toBeNull();
  });
  it('describes each planned phase without implying observed recovery',()=>{
    expect(plannedTraceStage('TRANSPORT_LINK_OVERLOAD',3)).toBe('High measured utilization');
    expect(plannedTraceStage('MOBILE_DATA_DNS_FAILURE',3)).toContain('independent');
    expect(plannedTraceStage('ROAMING_REGISTRATION_FAILURE',6)).toBe('Restored registration success');
    expect(plannedTraceStage('TELEMETRY_GAP',3)).toBe('Missing telemetry');
    expect(plannedTraceStage('NORMAL_CONTROL',4)).toBe('Healthy measured control planned');
  });
});
