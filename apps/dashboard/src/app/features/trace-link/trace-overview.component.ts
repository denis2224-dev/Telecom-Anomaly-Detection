import { NativeDomainPanelComponent } from './native-domain-panel.component';
import { GrafanaLinkService } from './grafana-link.service';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { Component, DestroyRef, computed, effect, inject, signal, untracked } from '@angular/core';
import { ActivatedRoute, Router } from '@angular/router';
import { TelecomClient, type TraceCity, type TraceLink, type TracePartner, type TraceKpiDefinition, type TraceMetricSeries, type TraceMetricQuery } from '../../core/api/telecom-client';
import { TraceQueryStore, traceContextParameters, type TraceQueryContext } from '../../core/state/trace-query.store';
import { SessionStore } from '../login-and-session/session.store';
import { TraceMetricChartComponent } from './trace-metric-chart.component';

@Component({
  selector:'app-trace-overview',imports:[TraceMetricChartComponent,NativeDomainPanelComponent],
  styles:[`
    dialog {width:min(1400px,95vw);max-height:90vh;overflow:auto;background:var(--surface,#fff);color:var(--text);border:1px solid var(--border,#bbb);border-radius:12px;padding:20px;} dialog::backdrop {background:#0006;}
    .controls {display:grid;grid-template-columns:repeat(auto-fit,minmax(170px,1fr));gap:12px;} label {display:grid;gap:6px;} input,select,button {min-height:44px;max-width:100%;} .actions {display:flex;flex-wrap:wrap;gap:10px;margin:12px 0;}
    .topology {width:100%;height:350px;background:var(--surface,#fff);} .topology line {stroke:#64748b;stroke-width:2;} .topology circle {fill:var(--brand,#ff7900);} .topology text {fill:currentColor;font-size:12px;} .topology g:focus circle {stroke:currentColor;stroke-width:4;} .table-scroll {overflow:auto;} table {min-width:600px;} code {overflow-wrap:anywhere;} .detail-panel {margin:16px 0;}
  `],
  template:`
    <div class="page-heading"><div><h1>Trace Link history</h1><p>Synthetic persisted measurements and versioned inventory. No preview values.</p></div></div>
    <section class="detail-panel" aria-label="Shared history query">
      <div class="controls">
        <label>Dataset ID<input #dataset [value]="query.context().datasetId" maxlength="128" placeholder="Recorded dataset ID" /></label>
        <label>From · UTC inclusive<input #from [value]="query.context().from" aria-describedby="trace-time-help" /></label>
        <label>To · UTC exclusive<input #to [value]="query.context().to" /></label>
        <label>Resolution<select [value]="query.context().resolution" (change)="change({resolution:$any($event.target).value})"><option>5m</option><option>15m</option><option>1h</option><option>6h</option></select></label>
        <label>Timezone<select [value]="query.context().timezone" (change)="change({timezone:$any($event.target).value})"><option>UTC</option><option>Europe/Chisinau</option></select></label>
        <label>City<select [value]="query.context().cityId" (change)="chooseCity($any($event.target).value)"><option value="all">Choose a city for series</option>@for(city of cities();track city.id){<option [value]="city.id" [selected]="city.id===query.context().cityId">{{city.name}}</option>}</select></label>
      </div>
      <p id="trace-time-help">Use UTC timestamps ending in Z. Maximum selected period is 30 days. Native history is five minutes; period and resolution are independent.</p>
      <div class="actions"><button type="button" (click)="apply(dataset.value,from.value,to.value)">Apply history query</button>@for(hours of periods;track hours){<button type="button" (click)="query.preset(hours)">{{ hours<72 ? hours+'h' : hours/24+'d' }}</button>}</div>
      @if(inputError()){<p role="alert">{{inputError()}}</p>}
      <p>Selected [{{query.context().from}}, {{query.context().to}}). Visible [{{query.context().visibleFrom ?? query.context().from}}, {{query.context().visibleTo ?? query.context().to}}).</p>
      <div class="actions"><button type="button" (click)="zoom()">Zoom in</button><button type="button" (click)="zoomOut()">Zoom out</button><button type="button" (click)="pan(-1)">Pan earlier</button><button type="button" (click)="pan(1)">Pan later</button><button type="button" (click)="query.resetZoom()">Reset zoom</button></div>
    </section>
    @if(inventoryBusy()){<p role="status">Loading versioned inventory…</p>}
    @if(inventoryError()){<p role="alert">{{inventoryError()}}</p><button type="button" (click)="loadInventory()">Retry inventory</button>}
    @if(cities().length){<section class="detail-panel" aria-label="Approved topology">
      <h2>Active reference topology</h2><p>Catalogue {{catalogueVersion()}} · {{cities().length}} active cities · {{links().length}} active links. Declared directions and capacity are inventory, not measured traffic or source authority.</p>
      <div class="table-scroll" tabindex="0" aria-label="Scrollable reference topology"><svg class="topology" style="min-width:650px" viewBox="0 0 650 350" role="group" aria-label="Versioned city and link topology; equivalent selection table follows">
        @for(link of links();track link.id){@if(cityById(link.endpointA);as a){@if(cityById(link.endpointB);as b){<g tabindex="0" role="button" [attr.aria-label]="'Select directional link '+link.id" (click)="chooseLink(link.id)" (keydown.enter)="chooseLink(link.id)" (keydown.space)="$event.preventDefault();chooseLink(link.id)"><line [attr.x1]="x(a)" [attr.y1]="y(a)" [attr.x2]="x(b)" [attr.y2]="y(b)"><title>{{link.id}} · {{link.endpointA}} to {{link.endpointB}} · declared inventory direction</title></line></g>}}}
        @for(city of cities();track city.id){<g tabindex="0" role="button" [attr.aria-label]="'Select city '+city.name" (click)="chooseCity(city.id)" (keydown.enter)="chooseCity(city.id)" (keydown.space)="$event.preventDefault();chooseCity(city.id)"><circle [attr.cx]="x(city)" [attr.cy]="y(city)" r="6"/><text [attr.x]="x(city)+10" [attr.y]="y(city)+4">{{city.name}}</text></g>}
      </svg></div>
      <details><summary>City selection table</summary><div class="table-scroll"><table><thead><tr><th>City</th><th>Latitude</th><th>Longitude</th><th>Provenance</th></tr></thead><tbody>@for(city of cities();track city.id){<tr><th><button type="button" (click)="chooseCity(city.id)">{{city.name}}</button></th><td>{{city.latitude}}</td><td>{{city.longitude}}</td><td>{{city.synthetic?'Synthetic':'Source marked non-synthetic'}}</td></tr>}</tbody></table></div></details>
      <details open><summary>Directional transport inventory</summary><div class="table-scroll"><table><thead><tr><th>Link</th><th>Endpoints</th><th>Direction</th><th>Effective Gbps</th><th>Measurement source</th></tr></thead><tbody>@for(link of links();track link.id){@for(direction of link.directions;track direction.id){<tr><th>{{link.id}}</th><td>{{direction.id==='a-to-b'?link.endpointA:link.endpointB}} → {{direction.id==='a-to-b'?link.endpointB:link.endpointA}}</td><td><button type="button" [attr.aria-pressed]="query.context().linkDirectionId===link.id+':'+direction.id" (click)="change({linkDirectionId:link.id+':'+direction.id})">{{link.id}} {{direction.id}}</button></td><td>{{direction.effectiveCapacityGbps}}</td><td>{{direction.measurementSource}}</td></tr>}}</tbody></table></div></details>
      @if(query.context().linkDirectionId){<p>Selected {{query.context().linkDirectionId}}. Open Transport for enabled native measurements. Each declared direction is queried independently; no reverse-direction value is inferred.</p>}
    </section>}
    <section class="detail-panel" aria-label="Persisted domain panels"><h2>Persisted domain metrics</h2><div class="actions">@for(domain of domains;track domain.id){<button type="button" [attr.aria-pressed]="query.context().domain===domain.id" (click)="change({domain:domain.id})">{{domain.label}}</button>}</div>
      @if(query.context().domain==='roaming'){<div class="controls"><label>Country<select [value]="query.context().countryId" (change)="change({countryId:$any($event.target).value})"><option value="all">All countries</option>@for(country of countries();track country){<option [value]="country">{{country}}</option>}</select></label><label>Roaming direction<select [value]="query.context().roamingDirection" (change)="change({roamingDirection:$any($event.target).value})"><option value="both">Both, separate populations</option><option value="out">out</option><option value="in">in</option></select></label></div><ul>@for(partner of visiblePartners();track partner.id+partner.direction){<li>{{partner.country}} · {{partner.id}} · {{partner.direction}} · {{partner.synthetic?'Synthetic reference partner':'Source marked non-synthetic'}} · inventory context</li>}</ul>}
      @if(query.context().domain==='transport' || query.context().domain==='roaming' || query.context().domain==='mobile-data'){<app-native-domain-panel [cities]="cities()" [links]="links()" [partners]="partners()" />}
      @if(!availableDefinitions().length && ['transport','roaming','mobile-data'].includes(query.context().domain)){<p>Registered rollups are unavailable for {{query.context().domain}}. Native source measurements are listed separately.</p>}
      @else if(!availableDefinitions().length){<p role="status">No approved metric definition is registered for {{query.context().domain}}. Measurements, baseline and incidents remain unavailable.</p>}
      @else{<label>Metric<select [value]="definition()?.metric ?? ''" (change)="metric.set($any($event.target).value)">@for(item of availableDefinitions();track item.metric){<option [value]="item.metric" [selected]="definition()?.metric===item.metric">{{item.metric}}</option>}</select></label><p>{{definition()?.population}} · {{definition()?.aggregation}} · policy {{definition()?.policyVersion}}</p>}
      @if(!metricQuery() && availableDefinitions().length){<p>Enter the recorded dataset, choose a city and apply valid history bounds.</p>}
      @if(seriesBusy()){<p role="status">Loading persisted metric buckets…</p>}
      @if(seriesError()){<p role="alert">{{seriesError()}}</p><button type="button" (click)="refresh.update(value=>value+1)">Retry metric history</button>}
      @if(series();as result){<p>{{result.synthetic?'Synthetic':'Source marked non-synthetic'}} · dataset <code>{{result.datasetId}}</code> · {{result.scopeType}} {{result.scopeId}} · topology {{result.topologyVersion ?? 'Unavailable'}} · source resolution {{result.sourceResolutionSeconds}} seconds · {{result.partialStart || result.partialEnd ? 'Partial edge buckets' : 'Full edge buckets'}}</p><app-trace-metric-chart [series]="result" [timezone]="query.context().timezone" (zoomRequested)="query.zoom($event.from,$event.to)"/><button type="button" [disabled]="csvBusy() || seriesBusy()" (click)="downloadCsv()">Download exact server CSV</button>}
      @if(metricQuery()){<button #expandBtn type="button" (click)="openExpanded(expanded,expandBtn)">Expand metric</button>}
      <p>@if(grafana.url(query.context());as url){<a [href]="url" target="_blank" rel="noopener noreferrer">Open this context in Grafana</a>}@else{Grafana is not configured. Metric history and exact CSV remain available.}</p>
    </section>
    <dialog #expanded aria-labelledby="expanded-trace-title"><h2 id="expanded-trace-title">Expanded persisted metric</h2><button type="button" (click)="expanded.close()">Close expanded metric</button><p>Visible [{{query.context().visibleFrom ?? query.context().from}}, {{query.context().visibleTo ?? query.context().to}}) · {{query.context().resolution}} · {{query.context().timezone}}</p><button type="button" (click)="query.resetZoom()">Reset zoom</button>@if(series();as result){<app-trace-metric-chart [series]="result" [timezone]="query.context().timezone" (zoomRequested)="query.zoom($event.from,$event.to)"/>}@else{<p role="status">{{seriesError() || 'Loading selected history…'}}</p>}</dialog>
  `,
})
export class TraceOverviewComponent {
  readonly grafana=inject(GrafanaLinkService);
  readonly query=inject(TraceQueryStore);private readonly api=inject(TelecomClient);private readonly session=inject(SessionStore);
  private readonly router=inject(Router);private readonly route=inject(ActivatedRoute);
  readonly cities=signal<TraceCity[]>([]);readonly links=signal<TraceLink[]>([]);readonly partners=signal<TracePartner[]>([]);
  readonly definitions=signal<TraceKpiDefinition[]>([]);readonly catalogueVersion=signal('');readonly metric=signal('');
  readonly inventoryBusy=signal(false);readonly inventoryError=signal('');readonly inputError=signal('');
  readonly series=signal<TraceMetricSeries|null>(null);readonly seriesBusy=signal(false);readonly seriesError=signal('');readonly csvBusy=signal(false);readonly refresh=signal(0);
  readonly periods=[6,24,48,168,336,720];
  readonly domains: {id:TraceQueryContext['domain'];label:string}[]=[{id:'volte',label:'VoLTE'},{id:'transport',label:'Transport'},{id:'roaming',label:'Roaming'},{id:'sms',label:'SMS'},{id:'mobile-data',label:'LTE mobile data'}];
  readonly countries=computed(()=>[...new Set(this.partners().map(partner=>partner.country))].sort());
  readonly visiblePartners=computed(()=>this.partners().filter(partner=>(this.query.context().countryId==='all'||partner.country===this.query.context().countryId) && (this.query.context().roamingDirection==='both'||partner.direction===this.query.context().roamingDirection)));
  readonly availableDefinitions=computed(()=>this.definitions().filter(item=>item.domain===this.query.context().domain));
  readonly definition=computed<TraceKpiDefinition|undefined>(()=>this.availableDefinitions().find(item=>item.metric===this.metric()) ?? this.availableDefinitions()[0]);
  readonly metricQuery=computed<TraceMetricQuery|null>(()=>{
    const context=this.query.context(),definition=this.definition();
    if(!definition || context.domain==='all' || !context.datasetId.trim() || context.cityId==='all' || context.domain==='transport'||context.domain==='roaming')return null;
    return {datasetId:context.datasetId,domain:context.domain,metric:definition.metric,scopeType:'CITY',scopeId:context.cityId,
      from:context.visibleFrom??context.from,to:context.visibleTo??context.to,resolution:context.resolution};
  });
  private inventoryRequest?:AbortController;private csvRequest?:AbortController;private inventoryGeneration=0;
  constructor(){
    this.route.queryParams?.pipe(takeUntilDestroyed()).subscribe(params=>{if(!this.query.restore(params))this.inputError.set('Invalid URL history context. Review the period, resolution and scope.');});
    if(!this.query.restore(this.route.snapshot.queryParams))this.inputError.set('Invalid URL history context. Review the period, resolution and scope.');
    effect(cleanup=>{this.metricQuery();cleanup(()=>{this.csvRequest?.abort();this.csvBusy.set(false);});});
    effect(()=>{const context=this.query.context();void this.router.navigate([],{relativeTo:this.route,queryParams:traceContextParameters(context),replaceUrl:true});});
    effect(cleanup=>{
      if(this.session.phase()!=='authenticated')return;
      untracked(()=>{void this.loadInventory();});
      cleanup(()=>{this.inventoryRequest?.abort();this.csvRequest?.abort();this.cities.set([]);this.links.set([]);this.partners.set([]);this.definitions.set([]);this.series.set(null);});
    });
    effect(cleanup=>{
      const query=this.metricQuery(),revision=this.query.revision(),sessionRevision=this.session.revision;this.refresh();
      const authenticated=this.session.phase()==='authenticated';
      this.series.set(null);this.seriesError.set('');this.seriesBusy.set(false);
      if(!query||!authenticated)return;
      const request=new AbortController();cleanup(()=>request.abort());this.seriesBusy.set(true);
      void this.api.traceMetricSeries(query,request.signal).then(result=>{
        if(request.signal.aborted||this.query.revision()!==revision||this.session.revision!==sessionRevision)return;
        if(result.datasetId!==query.datasetId||result.domain!==query.domain||result.metric!==query.metric||result.scopeId!==query.scopeId
          ||result.scopeType!==query.scopeType||result.resolution!==query.resolution||Date.parse(result.requestedFrom)!==Date.parse(query.from)
          ||Date.parse(result.requestedTo)!==Date.parse(query.to)||result.points.length>8640)throw new Error('Metric response does not match the selected query.');
        if(result.points.some(point=>!['VALID','PARTIAL','MISSING','STALE','INSUFFICIENT_EVIDENCE'].includes(point.quality)
          || !Number.isFinite(point.coverage)||point.coverage<0||point.coverage>1
          || (point.value!==null && (!Number.isFinite(point.value)||point.quality==='MISSING'||(result.aggregation==='RATIO_OF_SUMS'&&point.denominator===0)))))
          throw new Error('Metric response contains incompatible quality or primitives.');
        this.series.set(result);
      }).catch(error=>{if(!request.signal.aborted)this.seriesError.set(error instanceof Error?error.message:'History unavailable.');})
        .finally(()=>{if(!request.signal.aborted)this.seriesBusy.set(false);});
    });
    inject(DestroyRef).onDestroy(()=>{this.inventoryRequest?.abort();this.csvRequest?.abort();});
  }
  async loadInventory():Promise<void>{
    if(this.session.phase()!=='authenticated')return;
    const revision=this.session.revision,generation=++this.inventoryGeneration;this.inventoryRequest?.abort();const request=this.inventoryRequest=new AbortController();
    this.inventoryBusy.set(true);this.inventoryError.set('');
    try{
      const [cities,links,partners,definitions]=await Promise.all([this.api.traceCities(request.signal),this.api.traceLinks(request.signal),this.api.tracePartners(request.signal),this.api.traceDefinitions(request.signal)]);
      if(request.signal.aborted||revision!==this.session.revision||generation!==this.inventoryGeneration)return;
      if(cities.catalogueVersion!==links.catalogueVersion||cities.catalogueVersion!==partners.catalogueVersion)throw new Error('Inventory versions disagree. Retry a consistent catalogue.');
      this.cities.set(cities.cities.filter(city=>city.active));this.links.set(links.links.filter(link=>link.active));this.partners.set(partners.partners.filter(partner=>partner.active));this.catalogueVersion.set(cities.catalogueVersion);this.definitions.set(definitions);
    }catch(error){if(!request.signal.aborted)this.inventoryError.set(error instanceof Error?error.message:'Inventory unavailable.');}
    finally{if(!request.signal.aborted)this.inventoryBusy.set(false);}
  }
  change(patch:Partial<TraceQueryContext>):void {this.inputError.set(this.query.update(patch,this.links())?'':'Invalid query context. Use a UTC period of at most 30 days.');}
  chooseLink(id:string):void{const link=this.links().find(link=>link.id===id);if(!link?.directions.length)return;const city=this.query.context().cityId;this.change({domain:'transport',linkDirectionId:id+':'+link.directions[0].id,...(city!=='all'&&![link.endpointA,link.endpointB].includes(city)?{cityId:link.endpointA}:{})});}
  chooseCity(id:string):void{this.change({cityId:id});}
  apply(datasetId:string,from:string,to:string):void{this.change({datasetId:datasetId.trim(),from,to});}
  cityById(id:string):TraceCity|undefined{return this.cities().find(city=>city.id===id);}
  x(city:TraceCity):number{const values=this.cities().map(city=>city.longitude);return 40+(city.longitude-Math.min(...values))/Math.max(.01,Math.max(...values)-Math.min(...values))*480;}
  y(city:TraceCity):number{const values=this.cities().map(city=>city.latitude);return 310-(city.latitude-Math.min(...values))/Math.max(.01,Math.max(...values)-Math.min(...values))*270;}
  zoom():void{const c=this.query.context(),from=Date.parse(c.visibleFrom??c.from),to=Date.parse(c.visibleTo??c.to),quarter=(to-from)/4;if(to-from>600000)this.query.zoom(new Date(from+quarter).toISOString(),new Date(to-quarter).toISOString());}
  zoomOut():void{const c=this.query.context(),from=Date.parse(c.visibleFrom??c.from),to=Date.parse(c.visibleTo??c.to),half=(to-from)/2;this.query.zoom(new Date(Math.max(Date.parse(c.from),from-half)).toISOString(),new Date(Math.min(Date.parse(c.to),to+half)).toISOString());}
  pan(direction:number):void{const c=this.query.context(),from=Date.parse(c.visibleFrom??c.from),to=Date.parse(c.visibleTo??c.to),shift=(to-from)/2*direction;this.query.zoom(new Date(from+shift).toISOString(),new Date(to+shift).toISOString());}
  openExpanded(dialog: HTMLDialogElement, opener: HTMLElement): void {
    dialog.showModal();
    dialog.addEventListener('close', () => opener.focus(), { once: true });
  }
  async downloadCsv():Promise<void>{
    const query=this.metricQuery();if(!query||!this.series()||this.csvBusy())return;
    const revision=this.query.revision(),sessionRevision=this.session.revision;this.csvRequest?.abort();const request=this.csvRequest=new AbortController();this.csvBusy.set(true);
    try{const csv=await this.api.traceMetricCsv(query,request.signal);if(request.signal.aborted||revision!==this.query.revision()||sessionRevision!==this.session.revision)return;
      const url=URL.createObjectURL(new Blob([csv],{type:'text/csv;charset=utf-8'}));const anchor=document.createElement('a');anchor.href=url;anchor.download='trace-link-metrics.csv';anchor.click();setTimeout(()=>URL.revokeObjectURL(url),0);
    }catch(error){if(!request.signal.aborted)this.seriesError.set(error instanceof Error?error.message:'CSV unavailable.');}finally{if(!request.signal.aborted)this.csvBusy.set(false);}
  }
}
