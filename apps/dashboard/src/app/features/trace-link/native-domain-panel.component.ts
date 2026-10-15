import { Component, computed, effect, inject, input, signal } from '@angular/core';
import { TelecomClient, type TraceCity, type TraceLink, type TracePartner, type TraceNativeSeries } from '../../core/api/telecom-client';
import { TraceQueryStore, type TraceQueryContext } from '../../core/state/trace-query.store';
import { SessionStore } from '../login-and-session/session.store';
import { TraceMetricChartComponent, type MetricChartSeries } from './trace-metric-chart.component';

export const nativeMetrics:Record<string,readonly {id:string;label:string}[]> = {
  transport:[{id:'transport.traffic',label:'Directional carried traffic'},{id:'transport.effectiveCapacity',label:'Effective capacity'},{id:'transport.link.utilization',label:'Capacity utilization'},{id:'transport.rtt.snapshot',label:'RTT snapshot'},{id:'transport.jitter',label:'Jitter'}],
  roaming:[{id:'roaming.registration.success',label:'Registration success'},{id:'roaming.data.success',label:'Data procedure success'},{id:'roaming.voice.success',label:'Voice procedure success'}],
  'mobile-data':[{id:'mobileData.activation.success',label:'EPS bearer activation success'},{id:'mobileData.dns.success',label:'DNS response success'},{id:'mobileData.throughput',label:'Modeled throughput'}],
};
// Field order matches the versioned receiving scope identity. No metric or scope aliases.
export function nativeScope(context:TraceQueryContext,metric:string,partner?:TracePartner):Record<string,string>|null {
  const common={scopeNamespace:'trace-link-reference-v1'};
  if(context.domain==='transport' && context.linkDirectionId){
    const [link,direction]=context.linkDirectionId.split(':');
    return {...common,domain:'TRANSPORT',scopeType:'LINK_DIRECTION',scopeId:link,linkId:link,directionId:direction};
  }
  if(context.domain==='roaming' && partner){
    const procedure=metric==='roaming.registration.success'?'REGISTRATION':metric==='roaming.data.success'?'DATA':'VOICE';
    return {...common,domain:'ROAMING',scopeType:'ROAMING_PROCEDURE',scopeId:partner.id,partnerId:partner.id,countryId:partner.country,direction:partner.direction,procedure};
  }
  if(context.domain==='mobile-data' && context.cityId!=='all'){
    const procedure=metric==='mobileData.activation.success'?'EPS_BEARER_ACTIVATION':metric==='mobileData.dns.success'?'DNS':'USER_THROUGHPUT';
    return {...common,domain:'MOBILE_DATA',scopeType:'CITY_SERVICE',scopeId:context.cityId,contractCityId:context.cityId.toUpperCase(),procedure,accessTechnology:'LTE'};
  }
  return null;
}
export async function nativeScopeKey(scope:Record<string,string>):Promise<string> {
  const digest=await crypto.subtle.digest('SHA-256',new TextEncoder().encode(JSON.stringify(scope)));
  return Array.from(new Uint8Array(digest),byte=>byte.toString(16).padStart(2,'0')).join('');
}
export function nativeChartSeries(series:TraceNativeSeries):MetricChartSeries {
  return {datasetId:series.datasetId,domain:series.domain,metric:series.metricId,scopeType:series.scope.scopeType,scopeId:series.scope.scopeId,
    resolution:'5m',requestedFrom:series.from,requestedTo:series.to,effectiveFrom:series.from,effectiveTo:series.to,partialStart:false,partialEnd:false,
    policyVersion:series.policyVersion,nextCursor:null,sourceResolutionSeconds:series.sourceResolutionSeconds,unit:series.unit,aggregation:series.aggregationType,synthetic:series.synthetic,topologyVersion:series.topologyVersion,
    points:series.points.map(point=>({from:point.from,to:point.to,value:point.value,quality:point.quality,coverage:point.sourceCoverage,
      numerator:typeof point.primitives?.['numerator']==='number'?point.primitives['numerator']:null,
      denominator:typeof point.primitives?.['denominator']==='number'?point.primitives['denominator']:null,
      sumValue:typeof point.primitives?.['sum']==='number'?point.primitives['sum']:null,
      sampleCount:typeof point.primitives?.['count']==='number'?point.primitives['count']:null,
      nullReason:point.nullReason,qualityReason:point.qualityReason}))};
}
@Component({
  selector:'app-native-domain-panel',imports:[TraceMetricChartComponent],
  styles:[`dialog{width:min(1200px,95vw);max-height:90vh;overflow:auto;background:var(--surface);color:var(--text);border:1px solid var(--text-muted);border-radius:12px;}label{display:grid;gap:6px;margin:12px 0;}select{min-height:44px;max-width:100%;}details{margin:12px 0;}pre{white-space:pre-wrap;overflow-wrap:anywhere;}button{min-height:44px;}`],
  template:`<section aria-label="Native domain measurements">
    <label>Domain measurement<select [value]="selectedMetric()" (change)="metric.set($any($event.target).value)">@for(item of metrics();track item.id){<option [value]="item.id" [selected]="item.id===selectedMetric()">{{item.label}}</option>}</select></label>
    @if(query.context().domain==='roaming'){<label>Partner and direction<select [value]="partnerKey()" (change)="query.update({partnerDirectionId:$any($event.target).value})">@for(item of availablePartners();track item.id+item.direction){<option [value]="item.id+':'+item.direction" [selected]="item.id+':'+item.direction===partnerKey()">{{item.country}} · {{item.id}} · {{item.direction}}</option>}</select></label><p>Separate partner, direction and procedure metrics. Active users and finalized SMS unavailable.</p>}
    @if(query.context().domain==='transport'){<p>Select a link direction. Utilization alone is not an outage. Loss, error, drop and state history unavailable.</p>}
    @if(query.context().domain==='mobile-data'){<p>Activation, DNS and throughput are independent. Normal activation does not prove DNS health.</p>}
    <p>Aligned five-minute windows only. Other resolutions unavailable.</p>
    @if(!ready()){<p role="status">Choose a dataset, scope and aligned 5m range.</p>}
    @if(busy()){<p role="status">Loading native domain measurements…</p>}
    @if(error()){<p role="alert">{{error()}}</p><button type="button" (click)="refresh.update(value=>value+1)">Retry domain measurements</button>}
    @if(result();as series){<p>{{series.population}} · {{series.unit}} · {{series.aggregationType}} · {{series.synthetic?'Synthetic':'Source marked non-synthetic'}} · source {{series.sourceId}} · catalogue {{series.catalogueVersion}} · topology {{series.topologyVersion}}</p>
      <app-trace-metric-chart [series]="chart()!" [timezone]="query.context().timezone" (zoomRequested)="query.zoom($event.from,$event.to)" />
      <p>Source quality and coverage may be unavailable independently of arithmetic. Retained snapshots are not fresh observations.</p>
      <details><summary>Exact source primitives and qualifications for the visible bucket page</summary>@for(point of sourceRows();track point.from){<article><h4>{{point.from}} · {{point.quality}}</h4><p>{{point.qualityReason ?? point.nullReason ?? 'No quality reason'}} · coverage {{point.sourceCoverage ?? 'Unavailable'}} · revision {{point.revision ?? 'Unavailable'}} · finalized {{point.finalized ?? 'Unavailable'}}</p><pre>{{details(point)}}</pre></article>}<button type="button" [disabled]="sourcePage()===0" (click)="sourcePage.update(value=>value-1)">Previous source records</button><button type="button" [disabled]="(sourcePage()+1)*50>=series.points.length" (click)="sourcePage.update(value=>value+1)">Next source records</button></details>
      <p>CSV unavailable for five-minute measurements. Registered series support CSV.</p>
    }
    <button #expandButton type="button" [disabled]="!ready()" (click)="openExpanded(expanded,expandButton)">Expand domain measurement</button>
    <dialog #expanded aria-labelledby="expanded-domain-title"><h2 id="expanded-domain-title">Expanded domain measurement</h2><button type="button" (click)="expanded.close()">Close expanded domain measurement</button><button type="button" (click)="query.resetZoom()">Reset domain zoom</button>@if(chart();as series){<app-trace-metric-chart [series]="series" [timezone]="query.context().timezone" (zoomRequested)="query.zoom($event.from,$event.to)" />}@else{<p role="status">{{error() || 'Loading domain measurements…'}}</p>}</dialog>
  </section>`,
})
export class NativeDomainPanelComponent {
  readonly query=inject(TraceQueryStore);private readonly api=inject(TelecomClient);private readonly session=inject(SessionStore);
  readonly cities=input.required<TraceCity[]>();readonly links=input.required<TraceLink[]>();readonly partners=input.required<TracePartner[]>();
  readonly metric=signal('');readonly refresh=signal(0);readonly busy=signal(false);readonly error=signal('');readonly result=signal<TraceNativeSeries|null>(null);
  readonly sourcePage=signal(0);readonly sourceRows=computed(()=>this.result()?.points.slice(this.sourcePage()*50,(this.sourcePage()+1)*50)??[]);
  readonly metrics=computed(()=>nativeMetrics[this.query.context().domain]??[]);
  readonly selectedMetric=computed(()=>this.metrics().find(item=>item.id===this.metric())?.id??this.metrics()[0]?.id??'');
  readonly availablePartners=computed(()=>this.partners().filter(p=>(this.query.context().countryId==='all'||p.country===this.query.context().countryId)&&(this.query.context().roamingDirection==='both'||p.direction===this.query.context().roamingDirection)));
  readonly partner=computed(()=>this.availablePartners().find(p=>p.id+':'+p.direction===this.query.context().partnerDirectionId)??this.availablePartners()[0]);
  readonly partnerKey=computed(()=>this.partner()?this.partner()!.id+':'+this.partner()!.direction:'');
  readonly scope=computed(()=>{
    const context=this.query.context();
    if(context.domain==='mobile-data'&&!this.cities().some(city=>city.id===context.cityId))return null;
    if(context.domain==='transport'&&!this.links().some(link=>link.directions.some(direction=>link.id+':'+direction.id===context.linkDirectionId)))return null;
    return nativeScope(context,this.selectedMetric(),this.partner());
  });
  readonly ready=computed(()=>{const c=this.query.context();return !!c.datasetId&&!!this.scope()&&c.resolution==='5m'&&[c.visibleFrom??c.from,c.visibleTo??c.to].every(value=>Date.parse(value)%300000===0);});
  readonly chart=computed(()=>this.result()?nativeChartSeries(this.result()!):null);
  constructor(){
    effect(()=>{const context=this.query.context(),partner=this.partner();if(context.domain==='roaming'&&partner&&context.partnerDirectionId!==partner.id+':'+partner.direction)this.query.update({partnerDirectionId:partner.id+':'+partner.direction});});
    effect(cleanup=>{
    const context=this.query.context(),revision=this.query.revision(),sessionRevision=this.session.revision,scope=this.scope(),metric=this.selectedMetric();this.refresh();
    this.result.set(null);this.error.set('');this.busy.set(false);this.sourcePage.set(0);
    if(!this.ready()||!scope||this.session.phase()!=='authenticated')return;
    const request=new AbortController();cleanup(()=>request.abort());this.busy.set(true);
    void (async()=>{
      const scopeKey=await nativeScopeKey(scope);if(request.signal.aborted)return;
      const series=await this.api.traceNativeSeries({datasetId:context.datasetId,sourceId:'trace-link-native-primitives-v1',scopeKey,metric,from:context.visibleFrom??context.from,to:context.visibleTo??context.to},request.signal);
      if(request.signal.aborted||revision!==this.query.revision()||sessionRevision!==this.session.revision)return;
      if(series.datasetId!==context.datasetId||series.sourceId!=='trace-link-native-primitives-v1'||series.scopeKey!==scopeKey||series.metricId!==metric||series.domain!==context.domain||series.sourceResolutionSeconds!==300
        ||Date.parse(series.from)!==Date.parse(context.visibleFrom??context.from)||Date.parse(series.to)!==Date.parse(context.visibleTo??context.to)||series.points.length>8640
        ||Object.entries(scope).some(([key,value])=>series.scope[key as keyof typeof series.scope]!==value))throw new Error('Native measurements do not match the selected complete scope.');
      if(series.points.some(point=>!Number.isFinite(Date.parse(point.from))||!Number.isFinite(Date.parse(point.to))||(point.value!==null&&!Number.isFinite(point.value))||point.quality==='MISSING'&&point.value!==null))throw new Error('Native measurements contain invalid observations.');
      this.result.set(series);
    })().catch(error=>{if(!request.signal.aborted)this.error.set(error instanceof Error?error.message:'Native measurements unavailable.');})
      .finally(()=>{if(!request.signal.aborted)this.busy.set(false);});
  });}
  openExpanded(dialog:HTMLDialogElement,opener:HTMLButtonElement):void{dialog.showModal();dialog.addEventListener('close',()=>opener.focus(),{once:true});}
  details(point:TraceNativeSeries['points'][number]):string{return JSON.stringify({primitives:point.primitives,provenance:point.provenance,sourcePayloadSha256:point.sourcePayloadSha256},null,2);}
}
