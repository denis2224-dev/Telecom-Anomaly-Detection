import { Component, DestroyRef, effect, inject, signal, untracked } from '@angular/core';
import { RouterLink } from '@angular/router';
import { TelecomClient } from '../../core/api/telecom-client';
import type { components } from '../../core/api/schema';
import { IncidentStream } from '../../core/state/incident-stream';
import { TraceQueryStore, traceContextParameters } from '../../core/state/trace-query.store';
import { SessionStore } from '../login-and-session/session.store';

@Component({
 selector:'app-trace-incidents',imports:[RouterLink],
 template:`<section class="detail-panel" aria-label="Scoped persisted incidents"><h2>Incidents in this context</h2>
 <p>Committed technical episodes for the visible period and selected scope. Recovery and analyst resolution are separate states.</p>
 @if(busy()){<p role="status">Loading incident history…</p>}@if(error()){<p role="alert">{{error()}}</p><button type="button" (click)="load()">Retry incidents</button>}
 @if(!busy() && !error() && !items().length){<p>No committed incidents match this context.</p>}
 <ul>@for(item of items();track item.episodeId){<li><a [routerLink]="['/trace-link/incidents',item.episodeId]" [queryParams]="parameters()">{{item.severity}} · {{item.technicalState}} · {{item.episodeId}}</a> · {{item.firstObservedAt}} — {{item.latestWindowEnd}} UTC</li>}</ul>
 <p>{{total()}} matching episodes · page {{page()+1}}</p><button type="button" [disabled]="busy() || page()===0" (click)="move(-1)">Previous incidents</button><button type="button" [disabled]="busy() || (page()+1)*50>=total()" (click)="move(1)">Next incidents</button></section>`,
})
export class TraceIncidentsComponent {
 private readonly api=inject(TelecomClient);private readonly session=inject(SessionStore);private readonly query=inject(TraceQueryStore);private readonly stream=inject(IncidentStream);
 readonly items=signal<components['schemas']['TraceLinkIncidentView'][]>([]);readonly total=signal(0);readonly page=signal(0);readonly busy=signal(false);readonly error=signal('');
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
  this.request?.abort();const generation=++this.generation,revision=this.session.revision;this.items.set([]);this.total.set(0);this.error.set('');this.busy.set(false);
  if(this.session.phase()!=='authenticated'||!this.query.context().datasetId)return;
  const context=this.query.context(),queryRevision=this.query.revision(),request=this.request=new AbortController();this.busy.set(true);
  try{const result=await this.api.traceIncidents({datasetId:context.datasetId,domain:context.domain,cityId:context.cityId,linkDirectionId:context.linkDirectionId,countryId:context.countryId,roamingDirection:context.roamingDirection,from:context.visibleFrom??context.from,to:context.visibleTo??context.to,limit:50,offset:this.page()*50},request.signal);
   if(request.signal.aborted||revision!==this.session.revision||queryRevision!==this.query.revision()||generation!==this.generation)return;
   if(result.items.length>50||result.offset!==this.page()*50)throw new Error('Incident history does not match this bounded page.');
   this.items.set(result.items);this.total.set(result.total);
  }catch(error){if(!request.signal.aborted&&generation===this.generation)this.error.set(error instanceof Error?error.message:'Incident history unavailable.');}
  finally{if(generation===this.generation)this.busy.set(false);}
 }
}
