import { Component, DestroyRef, computed, effect, inject, signal } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { ActivatedRoute, Router } from '@angular/router';
import type { components } from '../../core/api/schema';
import { TelecomClient } from '../../core/api/telecom-client';
import { SessionStore } from '../login-and-session/session.store';
import { TraceIncidentsComponent } from '../trace-link/trace-incidents.component';
import { TraceQueryStore, validTraceContext, type TraceQueryContext } from '../../core/state/trace-query.store';
import { RoamingOverviewComponent } from './roaming-overview.component';

@Component({
  selector: 'app-roaming-page',
  imports: [RoamingOverviewComponent,TraceIncidentsComponent],
  template: `@if (selection(); as selection) {
    @if (selection.invalid) { <p role="alert">Invalid roaming context. Use inbound/outbound and a UTC interval up to 30 days.</p> }
    @else {
      @if(focusError()){<p role="alert">{{focusError()}}</p>}
      @if(highlight()){<p role="status">Showing roaming incident <button type="button" (click)="dismiss()">Dismiss incident highlight</button></p>}
      <app-roaming-overview #roaming [from]="selection.context.from" [to]="selection.context.to" [datasetId]="selection.context.datasetId" [resolution]="selection.resolution" [direction]="selection.context.roamingDirection === 'in' ? 'in' : 'out'" [countryId]="selection.context.countryId" [partnerDirectionId]="selection.context.partnerDirectionId ?? ''" [strictRange]="selection.strictRange" [followLatest]="selection.following" [chartMetric]="selection.metric" [incidentHighlight]="highlight()" [incidents]="incidents()" (selectionChanged)="choose($event)" />
      @if(roaming.recorded();as source){<app-trace-incidents [context]="roaming.investigationContext()" [partners]="source.partners" (incidentsChanged)="incidents.set($event)" />}
    }
  }`,
})
export class RoamingPageComponent {
  private readonly route=inject(ActivatedRoute);
  private readonly router=inject(Router);
  private readonly parameters = toSignal(this.route.queryParamMap,{initialValue:this.route.snapshot.queryParamMap});
  private readonly query = inject(TraceQueryStore);
  readonly incidents=signal<components['schemas']['TraceLinkIncidentView'][]>([]);
  private readonly currentEnd=signal(new Date(Math.floor(Date.now()/300_000)*300_000).toISOString());
  readonly selection = computed(() => {
    const params = this.parameters();
    const keys = ['datasetId', 'from', 'to', 'resolution', 'roamingDirection','countryId','partnerDirectionId','timezone'];
    const context = { ...this.query.context(), ...Object.fromEntries(keys.filter(key => params.has(key)).map(key => [key, params.get(key)!])),
      domain:'roaming' as const, roamingDirection:(params.get('roamingDirection') ?? 'out') as TraceQueryContext['roamingDirection'], cityId:'all',
      countryId:params.get('countryId')??'all',partnerDirectionId:params.get('partnerDirectionId')??undefined,
      datasetId:params.get('datasetId')??'',visibleFrom: undefined, visibleTo: undefined };
    if (!params.has('from') || params.get('follow')==='latest') {
      const span=params.has('from')&&params.has('to')?Date.parse(params.get('to')!)-Date.parse(params.get('from')!):86400000;
      if(Number.isFinite(span) && span>0 && span<=30*86400000){context.to=this.currentEnd();context.from=new Date(Date.parse(context.to)-span).toISOString();}
    }
    const strictRange = params.has('from') || params.has('to');
    const metric=params.get('metric')??'roaming.registration.success';
    return { context, strictRange, following:!strictRange || params.get('follow')==='latest', metric, resolution: params.has('resolution') ? context.resolution : undefined,
      invalid: [...keys,'metric','incidentId'].some(key => params.getAll(key).length > 1) || !['roaming.registration.success','roaming.data.success','roaming.voice.success'].includes(metric) || params.get('roamingDirection') === 'both' || params.has('from') !== params.has('to') || !validTraceContext(context) };
  });
  readonly highlight=signal<{from:string;to:string;label:string}|null>(null);
  readonly focusError=signal('');
  choose(parameters:Record<string,string>):void{void this.router.navigate([],{relativeTo:this.route,queryParams:{...parameters,incidentId:null},replaceUrl:true});}
  dismiss():void{void this.router.navigate([],{relativeTo:this.route,queryParams:{incidentId:null},queryParamsHandling:'merge',replaceUrl:true});}
  constructor(){
    const timer=setInterval(()=>this.currentEnd.set(new Date(Math.floor(Date.now()/300_000)*300_000).toISOString()),60_000);
    inject(DestroyRef).onDestroy(()=>clearInterval(timer));
    const api=inject(TelecomClient),session=inject(SessionStore);
    effect(cleanup=>{
      const params=this.parameters(),selection=this.selection(),id=params.get('incidentId'),revision=session.revision;
      this.highlight.set(null);this.focusError.set('');
      if(!id || selection.invalid || session.phase()!=='authenticated')return;
      const request=new AbortController();cleanup(()=>request.abort());
      void api.traceIncident(id,request.signal).then(item=>{
        if(request.signal.aborted || revision!==session.revision)return;
        const context=item.latestDetection.context,scope=context['scope'] as Record<string,unknown>|undefined;
        if(item.episodeId!==id || context['domain']!=='ROAMING' || context['datasetId']!==selection.context.datasetId
          || `${scope?.['partnerId']}:${scope?.['directionId']}`!==selection.context.partnerDirectionId
          || scope?.['directionId']!==selection.context.roamingDirection)throw new Error('Incident does not match the selected roaming population.');
        this.highlight.set({from:item.firstObservedAt,to:item.latestWindowEnd,label:item.severity+' roaming incident'});
      }).catch(error=>{if(!request.signal.aborted && revision===session.revision)this.focusError.set(error instanceof Error?error.message:'Incident highlight unavailable.');});
    });
  }
}
