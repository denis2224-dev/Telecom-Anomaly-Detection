import { Component, DestroyRef, computed, inject, signal } from '@angular/core';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { TelecomClient } from '../../core/api/telecom-client';
import type { components } from '../../core/api/schema';
import { IncidentStream } from '../../core/state/incident-stream';
import { TraceQueryStore, traceContextParameters, type TraceQueryContext } from '../../core/state/trace-query.store';
import { SessionStore } from '../login-and-session/session.store';

type NativeIncident=components['schemas']['TraceLinkIncidentView'];
type Workflow=components['schemas']['TraceIncidentWorkflow'];
const object=(value:unknown):Record<string,unknown>=>value!==null&&typeof value==='object'&&!Array.isArray(value)?value as Record<string,unknown>:{};
@Component({
 selector:'app-trace-incident',imports:[RouterLink],
 styles:[`section{margin:16px 0;}pre{white-space:pre-wrap;overflow-wrap:anywhere;}label{display:grid;gap:6px;margin:12px 0;}textarea,select{max-width:100%;min-height:44px;}button{min-height:44px;} .actions{display:flex;flex-wrap:wrap;gap:12px;}`],
 template:`<a routerLink="/trace-link" [queryParams]="contextParameters()">Back to shared investigation</a>
 <h1>Trace Link incident investigation</h1>
 @if(busy()){<p role="status">Loading committed incident evidence…</p>}
 @if(error()){<p role="alert">{{error()}}</p><button type="button" (click)="load()">Retry incident</button>}
 @if(incident();as item){<section aria-label="Native technical incident"><h2>{{domain()}} · {{scope()}}</h2><p>{{item.technicalState}} · {{item.severity}} · synthetic</p>
 <p>First observed {{time(item.firstObservedAt)}} · detected {{time(item.detectedAt)}} · latest window end {{time(item.latestWindowEnd)}} · {{query.context().timezone}}</p>
 <p>{{item.technicalState==='RECOVERED'?'Technical recovery does not resolve analyst work.':item.technicalState==='UNKNOWN'?'Incomplete evidence does not establish recovery.':'The incident remains ongoing.'}}</p>
 <p>{{cause()}}</p><button type="button" (click)="focusContext()">Use this incident scope and interval</button></section>}
 @if(workflow();as state){<section aria-label="Native analyst workflow"><h2>Analyst workflow · {{state.status}}</h2>
 <p>{{state.assigneeId ? 'Assigned for investigation' : 'Unassigned'}} · version {{state.version}}</p>
 <div class="actions"><button type="button" [disabled]="changing() || !!state.assigneeId" (click)="assign(session.actor()?.analystId ?? '')">Assign to me</button>
 <button type="button" [disabled]="changing() || state.status!=='OPEN' || !state.assigneeId" (click)="changeStatus('INVESTIGATING')">Start investigation</button></div>
 @if(privileged()){<label>Assign to enabled analyst<select [value]="target()" (change)="target.set($any($event.target).value)"><option value="">Choose an analyst</option>@for(actor of analysts();track actor.id){<option [value]="actor.id">{{actor.displayName}}</option>}</select></label><button type="button" [disabled]="changing() || !target()" (click)="assign(target())">Assign selected analyst</button>}
 <label>Investigation comment<textarea maxlength="2000" [value]="comment()" (input)="comment.set($any($event.target).value)"></textarea></label><button type="button" [disabled]="changing() || !comment().trim()" (click)="addComment()">Add comment</button>
 <label>Resolution note<textarea maxlength="2000" [value]="note()" (input)="note.set($any($event.target).value)"></textarea></label><button type="button" [disabled]="changing() || state.status!=='INVESTIGATING' || incident()?.technicalState!=='RECOVERED' || !note().trim()" (click)="changeStatus('RESOLVED')">Resolve reviewed incident</button>
 </section>}
 <section aria-label="Immutable native evidence"><h2>Original committed evidence</h2><p>Page {{evidencePage()+1}} · {{evidenceTotal()}} records</p>@for(item of evidence();track item.lifecycle.detectionId){<details><summary>{{item.lifecycle.phase}} · sequence {{item.lifecycle.sequence}}</summary><pre>{{serialize(item)}}</pre></details>}
 <button type="button" [disabled]="busy() || evidencePage()===0" (click)="evidencePage.update(value=>value-1);load()">Previous evidence</button><button type="button" [disabled]="busy() || (evidencePage()+1)*50>=evidenceTotal()" (click)="evidencePage.update(value=>value+1);load()">Next evidence</button></section>
 <section aria-label="Append-only native audit"><h2>Analyst audit</h2>@for(action of audit();track action.version){<p>{{action.action}} · {{time(action.occurredAt)}} · {{action.note ?? ''}}</p>}<button type="button" [disabled]="busy() || auditPage()===0" (click)="auditPage.update(value=>value-1);load()">Previous audit</button><button type="button" [disabled]="busy() || audit().length<50" (click)="auditPage.update(value=>value+1);load()">Next audit</button></section>`,
})
export class TraceIncidentComponent {
 readonly session=inject(SessionStore);readonly query=inject(TraceQueryStore);private readonly api=inject(TelecomClient);private readonly stream=inject(IncidentStream);
 readonly contextParameters=computed(()=>traceContextParameters(this.query.context()));
 readonly incident=signal<NativeIncident|null>(null);readonly workflow=signal<Workflow|null>(null);readonly evidence=signal<components['schemas']['TraceLinkDetectionEnvelope'][]>([]);
 readonly evidenceTotal=signal(0);readonly evidencePage=signal(0);readonly auditPage=signal(0);readonly audit=signal<components['schemas']['TraceIncidentAudit'][]>([]);
 readonly analysts=signal<components['schemas']['AnalystSummary'][]>([]);readonly target=signal('');readonly comment=signal('');readonly note=signal('');readonly busy=signal(false);readonly changing=signal(false);readonly error=signal('');
 readonly privileged=computed(()=>this.session.actor()?.roles.some(role=>role==='SUPERVISOR'||role==='ADMIN')??false);
 readonly context=computed(()=>object(this.incident()?.latestDetection.context));
 readonly domain=computed(()=>String(this.context()['domain']??'Unavailable'));readonly scope=computed(()=>String(object(this.context()['scope'])['id']??'Unavailable'));
 readonly cause=computed(()=>{const cause=object(this.incident()?.latestDetection.cause);return cause['status']==='HYPOTHESIS'&&cause['causeConfidence']?`Possible cause: ${cause['probableCause']} · ${cause['causeConfidence']}`:'Cause undetermined';});
 private episode='';private generation=0;private request?:AbortController;private stop?:()=>void;private timer?:ReturnType<typeof setTimeout>;
 constructor(){
  const destroy=inject(DestroyRef),route=inject(ActivatedRoute);
  route.queryParams?.pipe(takeUntilDestroyed(destroy)).subscribe(params=>{if(Object.keys(params).length && !this.query.restore(params))this.error.set('Invalid investigation query context.');});
  route.paramMap.pipe(takeUntilDestroyed(destroy)).subscribe(params=>{this.episode=params.get('episodeId')??'';this.evidencePage.set(0);this.auditPage.set(0);void this.load();});
  const ended=this.session.ended$.subscribe(()=>{this.request?.abort();this.stop?.();this.incident.set(null);this.workflow.set(null);this.evidence.set([]);this.audit.set([]);this.analysts.set([]);this.comment.set('');this.note.set('');this.target.set('');this.busy.set(false);this.changing.set(false);clearTimeout(this.timer);this.generation++;});
  destroy.onDestroy(()=>{this.request?.abort();this.stop?.();clearTimeout(this.timer);ended.unsubscribe();this.generation++;});
 }
 async load():Promise<void>{
  if(this.session.phase()!=='authenticated')return;
  this.request?.abort();const request=this.request=new AbortController(),generation=++this.generation,revision=this.session.revision,episode=this.episode;this.busy.set(true);this.error.set('');
  try{const [incident,workflow,evidence,audit,analysts]=await Promise.all([this.api.traceIncident(episode,request.signal),this.api.traceIncidentWorkflow(episode,request.signal),this.api.traceIncidentEvidence(episode,this.evidencePage()*50,request.signal),this.api.traceIncidentAudit(episode,this.auditPage()*50,request.signal),this.api.listAnalysts()]);
   if(request.signal.aborted||generation!==this.generation||revision!==this.session.revision)return;
   if(incident.episodeId!==episode||workflow.episodeId!==episode||evidence.items.length>50||audit.length>50)throw new Error('Incident response does not match the selected bounded investigation.');
   this.incident.set(incident);this.workflow.set(workflow);this.evidence.set(evidence.items);this.evidenceTotal.set(evidence.total);this.audit.set(audit);this.analysts.set(analysts.filter(actor=>actor.enabled));
   if(!this.stop)this.stop=this.stream.connect(()=>this.refresh(),()=>this.error.set('Live connection interrupted. Retry refresh to reconcile committed evidence.'),()=>this.refresh());
  }catch(error){if(!request.signal.aborted&&generation===this.generation)this.error.set(error instanceof Error?error.message:'Incident unavailable.');}
  finally{if(generation===this.generation)this.busy.set(false);}
 }
 private refresh():void{clearTimeout(this.timer);this.timer=setTimeout(()=>{if(this.changing()||this.busy())this.refresh();else void this.load();},150);}
 private async action(run:(state:Workflow,signal:AbortSignal)=>Promise<Workflow>):Promise<void>{
  const state=this.workflow();if(!state||this.changing())return;const revision=this.session.revision,generation=this.generation,episode=this.episode;const request=this.request=new AbortController();this.changing.set(true);this.error.set('');
  try{await run(state,request.signal);if(request.signal.aborted||revision!==this.session.revision||episode!==this.episode||generation!==this.generation)return;await this.load();}
  catch(error){if(!request.signal.aborted&&revision===this.session.revision)this.error.set(error instanceof Error?error.message:'Workflow update failed. Refresh and retry.');}
  finally{if(revision===this.session.revision)this.changing.set(false);}
 }
 assign(id:string):void{if(id)void this.action((state,signal)=>this.api.assignTraceIncident(this.episode,{assigneeId:id,version:state.version},signal));}
 changeStatus(status:'INVESTIGATING'|'RESOLVED'):void{void this.action((state,signal)=>this.api.updateTraceIncident(this.episode,{status,version:state.version,resolutionNote:status==='RESOLVED'?this.note():null},signal));}
 addComment():void{void this.action((state,signal)=>this.api.commentTraceIncident(this.episode,{version:state.version,note:this.comment()},signal));}
 time(value:string):string{return new Intl.DateTimeFormat('en-GB',{timeZone:this.query.context().timezone,dateStyle:'medium',timeStyle:'long'}).format(new Date(value));}
 serialize(value:unknown):string{return JSON.stringify(value,null,2);}
 focusContext():void{const item=this.incident();if(!item)return;const context=this.context(),scope=object(context['scope']);const domain=String(context['domain']).toLowerCase().replace('_','-');
  if(!['volte','transport','roaming','sms','mobile-data'].includes(domain))return;
  const patch:Partial<TraceQueryContext>={datasetId:String(context['datasetId']),from:item.firstObservedAt,to:item.latestWindowEnd,domain:domain as TraceQueryContext['domain'],visibleFrom:undefined,visibleTo:undefined};
  if(domain==='transport'){patch.cityId='all';patch.linkDirectionId=String(scope['id']);}else if(domain==='roaming'){patch.countryId=String(scope['partnerId']).split('-')[0];patch.roamingDirection=String(scope['directionId']) as 'in'|'out';patch.partnerDirectionId=String(scope['partnerId'])+':'+String(scope['directionId']);}else patch.cityId=String(scope['id']).toLowerCase();
  this.query.update(patch);
 }
}
