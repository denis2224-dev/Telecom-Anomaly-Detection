import { TelecomClient } from '../../core/api/telecom-client';
import { TraceQueryStore, traceContextParameters } from '../../core/state/trace-query.store';
import { SessionStore } from '../login-and-session/session.store';
import { loadRecordedRoaming, type RecordedRoaming, type RoamingRow, type RoamingSelection } from './recorded-roaming';
import { dataSource } from '../../core/api/data-source';
import { Component, computed, effect, inject, input, output, signal } from '@angular/core';
import { DatePipe } from '@angular/common';
import { IconComponent } from '../../shared/icon.component';
import type { components } from '../../core/api/schema';
import { nativeIncidentMetric } from '../trace-link/trace-incident-card-actions.component';
import { RouterLink } from '@angular/router';
import { MetricChartComponent } from '../service-kpi-history/metric-chart.component';
import { HistoryRangeComponent } from '../service-kpi-history/history-range.component';
import { countryPreview, previewMetric, previewWindow, randomSource, roamingSample } from '../scenario-runner/monitoring-preview';
@Component({
  selector: 'app-roaming-overview', imports: [MetricChartComponent, RouterLink, HistoryRangeComponent, IconComponent, DatePipe],
  templateUrl: './roaming-overview.component.html',
  styleUrl: './roaming-overview.component.css',
})
export class RoamingOverviewComponent {
  private readonly query=inject(TraceQueryStore);
  readonly roamingContext=computed(()=>{
    return {...traceContextParameters(this.investigationContext()),metric:this.chartMetric()};
  });
  readonly investigationContext=computed(()=>{
    const context=this.query.context(),recorded=this.recorded();
    const partner=recorded?.partners.find(partner=>partner.id+':'+partner.direction===this.partnerDirectionId());
    return {...context,domain:'roaming' as const,datasetId:recorded?.datasetId??this.datasetId(),from:recorded?.from??this.from(),to:recorded?.to??this.to(),resolution:recorded?.resolution??this.resolution()??context.resolution,roamingDirection:recorded?.direction??this.direction()??'out',cityId:'all',countryId:partner?.country??this.countryId(),partnerDirectionId:this.partnerDirectionId() || undefined,linkDirectionId:undefined,visibleFrom:undefined,visibleTo:undefined};
  });
  readonly incidents = input<components['schemas']['TraceLinkIncidentView'][]>([]);
  readonly incidentIntervals = computed(()=>this.incidents().filter(item=>nativeIncidentMetric(item)===this.chartMetric()
    && item.latestDetection.context['datasetId']===this.recorded()?.datasetId).map(item=>({from:item.firstObservedAt,to:item.latestWindowEnd,
      recovered:item.technicalState==='RECOVERED',label:this.chart().title+' incident'})));
  readonly metrics = [
    {id:'roaming.registration.success',name:'registrationSrPct',label:'Registration success'},
    {id:'roaming.data.success',name:'roamingDataSrPct',label:'Data session success'},
    {id:'roaming.voice.success',name:'roamingVoiceSrPct',label:'Voice call success'},
  ];
  readonly latestMetrics = computed(()=>this.metrics.map(metric=>{
    const window=this.recorded()?.windowsByMetric[metric.id]?.at(-1),kpi=window?.kpis[0];
    return {...metric,value:kpi?.observed??null,baseline:kpi?.baseline??null,at:window?.windowEnd,
      change:kpi?.observed!=null&&kpi.baseline!=null?kpi.observed-kpi.baseline:null,quality:window?.quality??'MISSING'};
  }));
  readonly countryName = (country:string)=>new Intl.DisplayNames(['en'],{type:'region'}).of(country)??country;
  selectRange(range:{from:string;to:string}):void {this.select({...range,follow:'fixed'});}
  readonly followLatest=input(false);
  readonly countryId = input('all');
  readonly chartMetric = input('roaming.registration.success');
  readonly chart = computed(()=>this.chartMetric()==='roaming.voice.success'?{name:'roamingVoiceSrPct',title:'Roaming voice call success rate'}
    :this.chartMetric()==='roaming.data.success'?{name:'roamingDataSrPct',title:'Roaming data session success rate'}:{name:'registrationSrPct',title:'Registration success rate'});
  readonly partnerDirectionId = input('');
  readonly selectionChanged = output<Record<string,string>>();
  readonly incidentHighlight = input<{from:string;to:string;label:string}|null>(null);
  readonly countries = computed(()=>[...new Set(this.recorded()?.partners.map(partner=>partner.country) ?? [])].sort());
  readonly partners = computed(()=>this.recorded()?.partners.filter(partner=>this.countryId()==='all'||partner.country===this.countryId()) ?? []);
  readonly hasMeasurement = (window: RecordedRoaming['windows'][number]) => window.kpis[0]?.observed !== null;
  select(patch:Record<string,string>):void {this.selectionChanged.emit({...this.roamingContext(),follow:this.followLatest()&&this.recorded()?.datasetId==='roaming-demo-v1'?'latest':'fixed',...patch});}
  lastHour():void {const demo=this.recorded()?.datasetId==='roaming-demo-v1',to=demo?new Date(Math.floor(Date.now()/300_000)*300_000).toISOString():this.recorded()?.to??this.to();this.select({from:new Date(Date.parse(to)-3_600_000).toISOString(),to,follow:demo?'latest':'fixed'});}
  readonly datasetId = input('');
  readonly resolution = input<RoamingSelection['resolution']>();
  readonly direction = input<'in' | 'out'>();
  readonly strictRange = input(false);
  readonly preview = input(dataSource.fixture);
  readonly sample = computed(() => this.preview());
  readonly compact = input(false);
  readonly showCountries = input(false);
  readonly from = input(new Date(Date.now()-48*3_600_000).toISOString());
  readonly to = input(new Date().toISOString());
  readonly seed = input<number>(roamingSample.seed);
  readonly affectedCountry = input('');
  readonly affectedMetric = input('voice');
  readonly sort = signal('users');
  readonly refresh = signal(0);
  readonly recorded = signal<RecordedRoaming | null>(null);
  readonly loading = signal(false);
  readonly error = signal('');
  readonly rows = computed<RoamingRow[]>(() => this.sample() ? countryPreview(this.seed(), this.affectedCountry(), this.affectedMetric()) : this.recorded()?.rows ?? []);
  readonly totalUsers = computed(() => this.sample() ? this.rows().reduce((sum, row) => sum + (row.users ?? 0), 0) : this.recorded()?.users ?? null);
  readonly maxUsers = computed(() => Math.max(1, ...this.rows().map(row => row.users ?? 0)));
  readonly sorted = computed(() => [...this.rows()].sort((a,b) => this.sort() === 'country' ? a.country.localeCompare(b.country)
    : this.sort() === 'voice' ? (a.voice ?? Infinity) - (b.voice ?? Infinity) : this.sort() === 'registration' ? (a.registration ?? Infinity) - (b.registration ?? Infinity) : (b.users ?? -1) - (a.users ?? -1)));
  readonly summary = computed(() => {
    const rows = this.rows(), users = rows.reduce((sum, row) => sum + (row.users ?? 0), 0);
    const mean = (name: 'registration'|'data'|'voice'|'sms') => this.sample()
      ? (rows.reduce((sum,row) => sum + (row[name] ?? 0) * (row.users ?? 0), 0) / users).toFixed(1) + '%'
      : this.recorded()?.rates[name] == null ? '—' : this.recorded()!.rates[name]!.toFixed(1) + '%';
    return [{ label: 'Registration success', value: mean('registration') }, { label: 'Partner user estimates', value: this.totalUsers()?.toLocaleString('en') ?? '—' },
      { label: 'Data session success', value: mean('data') }, { label: 'Voice call success', value: mean('voice') }, { label: 'SMS delivery success', value: mean('sms') }];
  });
  readonly windows = computed(() => {
    if (!this.sample()) return this.recorded()?.windowsByMetric[this.chartMetric()] ?? [];
    const base = roamingSample.registrationSuccess;
    const random = randomSource(this.seed()), start = Date.parse(this.from()), step = (Date.parse(this.to()) - start) / 48;
    return Array.from({ length: 48 }, (_, index) => previewWindow('roaming-preview', start + index * step, start + (index + 1) * step,
      [previewMetric('registrationSrPct', +(base + (index === 47 ? 0 : (random() - .5) * .4) - (index === 25 ? 1.6 : 0)).toFixed(2), base, 'PERCENT')]));
  });
  count(value: number | null): string { return value === null ? '—' : value.toLocaleString('en', { maximumFractionDigits: 0 }); }
  percent(value: number | null): string { return value === null ? '—' : String(+value.toFixed(2)); }
  share(value: number | null): string { return value === null || !this.totalUsers() ? '—' : (value / this.totalUsers()! * 100).toFixed(1) + '%'; }
  constructor() {
    const api = inject(TelecomClient), session = inject(SessionStore), query = inject(TraceQueryStore);
    effect(cleanup => {
      this.refresh();
      const preview = this.preview(), from = this.from(), to = this.to();
      const direction = this.direction() ?? (query.context().roamingDirection === 'in' ? 'in' : 'out');
      const selection = {datasetId:this.datasetId(), resolution:this.resolution(), strictRange:this.strictRange(), countryId:this.countryId(), partnerDirectionId:this.partnerDirectionId()};
      session.actor();
      const revision=session.revision, authenticated = session.phase() === 'authenticated';
      this.recorded.set(null); this.error.set(''); this.loading.set(false);
      if (preview || !authenticated) return;
      const request = new AbortController();
      cleanup(() => request.abort());
      this.loading.set(true);
      void loadRecordedRoaming(api, from, to, direction, request.signal, selection).then(result => {
        if (!request.signal.aborted && revision === session.revision) this.recorded.set(result);
      }).catch(error => {
        if (!request.signal.aborted && revision === session.revision) { request.abort(); this.error.set(error instanceof Error ? error.message : 'Recorded roaming measurements could not be loaded.'); this.loading.set(false); }
      }).finally(() => { if (!request.signal.aborted && revision === session.revision) this.loading.set(false); });
    });
  }

}
