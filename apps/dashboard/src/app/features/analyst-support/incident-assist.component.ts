import { Component, computed, effect, inject, input, signal, untracked } from '@angular/core';
import { RouterLink } from '@angular/router';
import { DatePipe } from '@angular/common';
import { TelecomClient } from '../../core/api/telecom-client';
import { SessionStore } from '../login-and-session/session.store';
import { incidentRoute, legacyAnalystIncident, nativeAnalystIncident, relatedReason, suggestedChecks, type AnalystIncident } from './analyst-support.model';

@Component({
  selector:'app-incident-assist',imports:[RouterLink,DatePipe],
  styles:[`:host{display:block;margin:14px 0;}section,details{padding:12px;background:var(--field);border-radius:var(--radius-control);margin:8px 0;}h3,summary{font-size:14px;margin:0;}p,li{font-size:12px;overflow-wrap:anywhere;}ul{padding-left:18px;}small{color:var(--text-muted);}a{font-weight:600;}button{font-size:12px;min-height:36px;}`],
  template:`<section aria-label="Suggested next checks"><h3>Suggested next checks</h3><p><small>Verify findings before confirming a cause.</small></p><ul>@for(check of checks();track check){<li>{{check}}</li>}</ul></section>
    <details (toggle)="opened.set($any($event.target).open)"><summary>Related incidents</summary>
      <p><small>Matches in recorded scope and time; a shared cause is unconfirmed.</small></p>
      @if(loading()){<p role="status">Checking incidents…</p>}
      @if(error()){<p role="alert">{{error()}}</p><button type="button" (click)="retry.update(next)">Retry related incidents</button>}
      <ul>@for(match of matches();track match.item.id){<li><span class="badge" [attr.data-state]="match.item.severity">{{match.item.severity}}</span> <a [routerLink]="route(match.item)" queryParamsHandling="preserve">{{match.item.domain}} · {{match.item.scope}}</a><p>{{match.reason}} · {{match.item.technical}}</p><small>{{match.item.from|date:'dd MMM HH:mm':'UTC'}} UTC</small></li>}</ul>
      @if(!loading()&&!error()&&!matches().length){<p>No matches in the checked records.</p>}
      @if(checked()){<p><small>Checked {{checked()}} of {{total()}} episodes.</small></p>}
      @if(hasMore()&&!error()){<button type="button" [disabled]="loading()" (click)="more()">Check more incidents</button>}
    </details>`,
})
export class IncidentAssistComponent {
  readonly incident=input.required<AnalystIncident>();
  readonly checks=computed(()=>suggestedChecks(this.incident()));
  readonly route=incidentRoute;
  readonly opened=signal(false);readonly retry=signal(0);readonly next=(value:number)=>value+1;
  readonly loading=signal(false);readonly error=signal('');readonly checked=signal(0);readonly total=signal(0);readonly hasMore=signal(false);
  readonly matches=signal<{item:AnalystIncident;reason:string}[]>([]);
  private readonly api=inject(TelecomClient);private readonly session=inject(SessionStore);
  private controller?:AbortController;private generation=0;private page=0;
  constructor(){effect(cleanup=>{
    const item=this.incident(),opened=this.opened();this.retry();this.session.actor();const revision=this.session.revision;
    const controller=this.controller=new AbortController(),generation=++this.generation;cleanup(()=>controller.abort());
    this.matches.set([]);this.checked.set(0);this.total.set(0);this.hasMore.set(false);this.error.set('');this.loading.set(false);this.page=0;
    if(opened&&this.session.phase()==='authenticated')untracked(()=>void this.load(item,controller,generation,revision));
  });}
  more(){if(!this.loading()&&this.hasMore()&&this.controller)void this.load(this.incident(),this.controller,this.generation,this.session.revision);}
  private async load(item:AnalystIncident,controller:AbortController,generation:number,revision:number){
    this.loading.set(true);this.error.set('');
    try{
      let rows:AnalystIncident[],total:number,limit:number;
      if(item.source==='legacy'){
        const result=await this.api.listIncidents({page:this.page,size:100},controller.signal);
        if(result.items.length>100||result.page!==this.page)throw new Error('Incident page changed. Retry.');
        rows=result.items.map(legacyAnalystIncident);total=result.total;limit=100;
      }else{
        if(!item.dataset)throw new Error('Incident archive unavailable.');
        const result=await this.api.traceIncidents({datasetId:item.dataset,from:item.from,to:item.to,limit:50,offset:this.page*50},controller.signal);
        if(result.items.length>50||result.offset!==this.page*50)throw new Error('Incident page changed. Retry.');
        rows=result.items.map(row=>nativeAnalystIncident(row));total=result.total;limit=50;
        if(rows.some(row=>row.dataset!==item.dataset))throw new Error('Incident archive changed. Retry.');
      }
      if(controller.signal.aborted||generation!==this.generation||revision!==this.session.revision)return;
      if(!Number.isInteger(total)||total<this.page*limit+rows.length||(rows.length<limit&&this.page*limit+rows.length<total))throw new Error('Incident history changed. Retry.');
      const matches=new Map(this.matches().map(match=>[match.item.id,match]));
      for(const row of rows){const reason=relatedReason(item,row);if(reason)matches.set(row.id,{item:row,reason});}
      this.matches.set([...matches.values()]);this.checked.set(this.page*limit+rows.length);this.total.set(total);this.page++;
      this.hasMore.set(this.page*limit<total);
    }catch(error){if(!controller.signal.aborted&&generation===this.generation&&revision===this.session.revision)this.error.set(error instanceof Error?error.message:'Related incidents unavailable.');}
    finally{if(!controller.signal.aborted&&generation===this.generation)this.loading.set(false);}
  }
}
