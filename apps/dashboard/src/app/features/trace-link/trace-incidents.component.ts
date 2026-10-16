import { ShiftHandoverComponent } from '../analyst-support/shift-handover.component';
import { nativeAnalystIncident } from '../analyst-support/analyst-support.model';
import { TraceIncidentCardActionsComponent } from './trace-incident-card-actions.component';
import { Component, DestroyRef, computed, effect, inject, signal, untracked } from '@angular/core';
import { DatePipe } from '@angular/common';
import { RouterLink } from '@angular/router';
import { TelecomClient } from '../../core/api/telecom-client';
import { ApiFailure } from '../../core/api/api-errors';
import type { components } from '../../core/api/schema';
import { IncidentStream } from '../../core/state/incident-stream';
import { TraceQueryStore, traceContextParameters } from '../../core/state/trace-query.store';
import { SessionStore } from '../login-and-session/session.store';

@Component({
 styles:[`header{display:flex;flex-wrap:wrap;align-items:center;gap:8px;}header time{margin-left:auto;color:var(--text-muted);}p{margin:0;}ul{list-style:none;padding:0;display:grid;gap:8px;}li{display:grid;gap:8px;padding:18px;background:var(--surface-raised);border-radius:12px;box-shadow:inset 4px 0 var(--warning);font-size:13px;}`],
 selector:'app-trace-incidents',imports:[ShiftHandoverComponent,RouterLink,DatePipe,TraceIncidentCardActionsComponent],
 template:`<section class="detail-panel" aria-label="Scoped persisted incidents"><h2>Incidents in this context</h2>

 @if(busy()){<p role="status">Loading incident history…</p>}@if(error()){<p role="alert">{{error()}}</p><button type="button" (click)="load()">Retry incidents</button>}
 @if(notEnabled()){<p role="status">Incident history is not enabled on this installation. Recorded measurements remain available.</p><button type="button" (click)="load()">Retry incidents</button>}
 @if(!notEnabled()){
 @if(!busy() && !error() && !items().length){<p>No committed incidents match this context.</p>}
 <app-shift-handover [items]="handoverItems()" [total]="total()" [scope]="'Recorded context · ' + queryLabel()" [from]="rangeFrom()" [to]="rangeTo()" [loading]="busy()" [error]="error()" />
 <ul>@for(item of items();track item.episodeId){<li><header><span class="badge" [attr.data-state]="item.severity">{{item.severity}}</span><strong>{{title(item)}}</strong><time>since {{item.firstObservedAt | date:'HH:mm':'UTC'}}</time></header><p>{{item.technicalState}} · {{description(item)}}</p><app-trace-incident-card-actions [incident]="item" (workflowChanged)="acceptWorkflow($event)"><a incident-leading class="button incident-evidence-action" [routerLink]="['/trace-link/incidents',item.episodeId]" [queryParams]="parameters()">Episode evidence</a></app-trace-incident-card-actions></li>}</ul>
 <p>{{total()}} matching episodes · page {{page()+1}}</p><button type="button" [disabled]="busy() || page()===0" (click)="move(-1)">Previous incidents</button><button type="button" [disabled]="busy() || (page()+1)*50>=total()" (click)="move(1)">Next incidents</button>}</section>`,
})
export class TraceIncidentsComponent {
 title(item:components['schemas']['TraceLinkIncidentView']):string { const domain=String(item.latestDetection.context['domain']??'Service');return domain==='VOLTE'?'VoLTE anomaly':domain==='SMS'?'SMS anomaly':domain.replaceAll('_',' ')+' anomaly'; }
 description(item:components['schemas']['TraceLinkIncidentView']):string { const cause=item.latestDetection.cause;return cause['status']==='HYPOTHESIS'&&typeof cause['probableCause']==='string'?'Possible cause: '+cause['probableCause']:'Cause undetermined'; }

 private readonly api=inject(TelecomClient);private readonly session=inject(SessionStore);private readonly query=inject(TraceQueryStore);private readonly stream=inject(IncidentStream);
 private readonly workflows=signal(new Map<string,components['schemas']['TraceIncidentWorkflow']>());
 acceptWorkflow(state:components['schemas']['TraceIncidentWorkflow']):void {
  if(!this.items().some(item=>item.episodeId===state.episodeId))return;
  this.workflows.update(rows=>{if((rows.get(state.episodeId)?.version??-1)>state.version)return rows;return new Map(rows).set(state.episodeId,state);});
 }
 readonly handoverItems=computed(()=>this.items().map(item=>nativeAnalystIncident(item,this.workflows().get(item.episodeId)?.status??null)));
 readonly queryLabel=computed(()=>{const context=this.query.context();return ['Archive '+context.datasetId,context.domain,context.cityId==='all'?'All cities':context.cityId,context.countryId!=='all'?context.countryId:null,context.domain==='roaming'?'Direction '+context.roamingDirection:null,context.linkDirectionId,context.partnerDirectionId].filter(Boolean).join(' · ');});
 readonly rangeFrom=computed(()=>this.query.context().visibleFrom??this.query.context().from);readonly rangeTo=computed(()=>this.query.context().visibleTo??this.query.context().to);
 readonly items=signal<components['schemas']['TraceLinkIncidentView'][]>([]);readonly total=signal(0);readonly page=signal(0);readonly busy=signal(false);readonly error=signal('');
 readonly notEnabled=signal(false);
 private request?:AbortController;private timer?:ReturnType<typeof setTimeout>;private generation=0;
 parameters(){return traceContextParameters(this.query.context());}
 constructor(){
  effect(cleanup=>{this.query.revision();this.session.phase();this.session.actor();untracked(()=>{this.page.set(0);void this.load();});
   if(this.session.phase()==='authenticated')cleanup(this.stream.connect(()=>this.refresh(),()=>this.error.set('Live connection interrupted. Refresh to reconcile committed incidents.'),()=>this.refresh()));
  });
  inject(DestroyRef).onDestroy(()=>{this.generation++;this.request?.abort();clearTimeout(this.timer);});
 }
 private refresh(){clearTimeout(this.timer);this.timer=setTimeout(()=>void this.load(),150);}
 move(delta:number){this.page.update(value=>Math.max(0,value+delta));void this.load();}
 async load():Promise<void>{
  this.request?.abort();const generation=++this.generation,revision=this.session.revision;this.items.set([]);this.workflows.set(new Map());this.total.set(0);this.error.set('');this.notEnabled.set(false);this.busy.set(false);
  if(this.session.phase()!=='authenticated'||!this.query.context().datasetId)return;
  const context=this.query.context(),queryRevision=this.query.revision(),request=this.request=new AbortController();this.busy.set(true);
  try{const result=await this.api.traceIncidents({datasetId:context.datasetId,domain:context.domain,cityId:context.cityId,linkDirectionId:context.linkDirectionId,countryId:context.countryId,roamingDirection:context.roamingDirection,from:context.visibleFrom??context.from,to:context.visibleTo??context.to,limit:50,offset:this.page()*50},request.signal);
   if(request.signal.aborted||revision!==this.session.revision||queryRevision!==this.query.revision()||generation!==this.generation)return;
   if(result.items.length>50||result.offset!==this.page()*50)throw new Error('Incident history does not match this bounded page.');
   this.items.set(result.items);this.total.set(result.total);
  }catch(error){if(!request.signal.aborted&&generation===this.generation){
    if(error instanceof ApiFailure && error.status===404)this.notEnabled.set(true);
    else this.error.set(error instanceof Error?error.message:'Incident history unavailable.');
  }}
  finally{if(generation===this.generation)this.busy.set(false);}
 }
}
