import { Component, DestroyRef, computed, inject, signal } from '@angular/core';
import { DatePipe } from '@angular/common';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { TelecomClient, type ServiceSummary } from '../../core/api/telecom-client';
import { dataSource } from '../../core/api/data-source';
import { LiveUpdates } from '../../core/api/live-updates';
import { KpiChartComponent } from './kpi-chart.component';
import { IncidentListComponent } from '../incident-investigation/incident-list.component';
import { IncidentStoryComponent } from '../incident-investigation/incident-story.component';
import { episodes, type Incident, type KpiWindow } from './voice-model';
import { SmsQualityComponent } from './sms-quality.component';
import { SmsHistoryComponent } from './sms-history.component';
import { KpiCardsComponent } from './kpi-cards.component';
import { MetricChartComponent } from './metric-chart.component';
import { ServicePathComponent } from './service-path.component';
import { Detection, allPages, historySlices, mergeWindows, serviceHealth } from './assurance-model';

@Component({
  selector: 'app-service-detail',
  host: { '[attr.data-service]': 'service()?.scope?.service' },
  imports: [RouterLink, DatePipe, KpiChartComponent, IncidentListComponent, SmsQualityComponent,
    SmsHistoryComponent, KpiCardsComponent, MetricChartComponent, ServicePathComponent, IncidentStoryComponent],
  templateUrl: './service-detail.component.html',
})
export class ServiceDetailComponent {
  private readonly api = inject(TelecomClient);
  private generation = 0;
  private refreshing = false;
  private cache = new Map<string, { version: number; detections: Detection[] }>();
  readonly fixture = dataSource.fixture;
  readonly scopeId = signal(''); readonly service = signal<ServiceSummary | null>(null);
  readonly loading = signal(true); readonly error = signal(''); readonly rangeError = signal('');
  readonly from = signal(''); readonly to = signal(''); readonly observedAt = signal('');
  readonly hours = signal(1); readonly rolling = signal(true);
  readonly windows = signal<KpiWindow[]>([]); readonly incidents = signal<Incident[]>([]);
  private readonly currentIncidents = signal<Incident[]>([]);
  readonly detections = signal<Detection[]>([]);
  readonly health = computed(() => this.service() ? serviceHealth(this.service()!, this.currentIncidents()) : 'UNKNOWN');
  readonly charts = computed(() => this.service()?.scope.service === 'SMS' ? [
    { name: 'deliverySrPct', title: 'Delivery success rate', unit: 'PERCENT' },
    { name: 'queueDepth', title: 'Queue depth', unit: 'COUNT' },
    { name: 'oldestPendingAgeSec', title: 'Oldest pending message age', unit: 'SECONDS' },
    { name: 'deliveredMessages', title: 'Delivered sample volume', unit: 'COUNT' },
  ] : [
    { name: 'rrcSrPct', title: 'Radio / access · RRC SR', unit: 'PERCENT' },
    { name: 'bearerSrPct', title: 'Radio / access · Bearer SR', unit: 'PERCENT' },
    { name: 'imsCpuPct', title: 'IMS / core · CPU', unit: 'PERCENT' },
    { name: 'sip503Ratio', title: 'IMS / core · SIP 503 rate', unit: 'RATIO' },
    { name: 'packetLossRatio', title: 'Transport · packet loss', unit: 'RATIO' },
    { name: 'eligibleAttempts', title: 'Eligible attempt volume', unit: 'COUNT' },
  ]);
  constructor() {
    const destroy = inject(DestroyRef);
    const injectRoute = inject(ActivatedRoute);
    destroy.onDestroy(() => { this.generation++; this.cache.clear(); });
    inject(LiveUpdates).refresh$.pipe(takeUntilDestroyed(destroy)).subscribe(() => {
      if (!this.fixture && !this.loading() && !this.refreshing) void this.load(true);
    });
    injectRoute.paramMap.pipe(takeUntilDestroyed(destroy)).subscribe(params => {
      const requested = Number(injectRoute.snapshot.queryParamMap.get('hours'));
      if ([1, 6, 24, 48].includes(requested)) this.hours.set(requested);
      this.scopeId.set(params.get('scopeId') ?? ''); this.from.set(''); this.to.set('');
      this.cache.clear(); this.windows.set([]); this.service.set(null); this.currentIncidents.set([]); this.rolling.set(true);
      void this.load();
    });
  }
  selectHours(value: string) {
    const hours = Number(value); if (![1, 6, 24, 48].includes(hours)) return;
    this.hours.set(hours); this.rolling.set(true); this.from.set(''); this.to.set(''); this.rangeError.set('');
    void this.load();
  }
  applyRange(event: Event, start: string, end: string) {
    event.preventDefault();
    try {
      const from = new Date(start + 'Z').toISOString(), to = new Date(end + 'Z').toISOString();
      historySlices(from, to);
      this.rangeError.set(''); this.rolling.set(false); this.from.set(from); this.to.set(to); void this.load();
    } catch { this.rangeError.set('Choose an end after the start, with a range of at most 48 hours.'); }
  }
  async load(incremental = false) {
    const generation = ++this.generation, scopeId = this.scopeId(), valid = () => generation === this.generation;
    this.refreshing = true;
    if (!incremental) { this.loading.set(true); this.windows.set([]); this.incidents.set([]); this.detections.set([]); }
    this.error.set('');
    try {
      const services = await this.api.listServices(); if (!valid()) return;
      const service = services.find(item => item.scope.scopeId === scopeId);
      if (!service) throw new Error('This service could not be found. Return to the service overview.');
      let from = this.from(), to = this.to();
      if (this.rolling() || !from) {
        const end = Math.floor(Date.parse(service.observedAt) / 60000) * 60000;
        const range = this.fixture ? await dataSource.loadRange(scopeId) : { from: new Date(end - this.hours() * 3600000).toISOString(), to: new Date(end).toISOString() };
        if (!valid()) return; from = range.from; to = range.to;
      }
      const old = incremental ? this.windows() : [];
      const tail = incremental && old.length ? Math.max(Date.parse(from), Date.parse(old.at(-1)!.windowStart) - 120000) : Date.parse(from);
      const fetchFrom = new Date(Math.min(tail, Date.parse(to) - 60000)).toISOString();
      const history = (!incremental || this.rolling()) ? await Promise.all(historySlices(fetchFrom, to).map(range =>
        allPages(page => this.api.getServiceKpis(scopeId, { ...range, page, size: 100 }), valid))) : [];
      const incidentPage = await allPages(page => this.api.listIncidents({ scopeId, service: service.scope.service, page, size: 100 }), valid);
      if (!valid()) return;
      const currentIncidents = episodes(incidentPage.items);
      const incidents = currentIncidents.filter(item => Date.parse(item.firstObservedAt) < Date.parse(to)
        && (Date.parse(item.lastObservedAt) > Date.parse(from) || item.technicalState !== 'RECOVERED'));
      const evidence: Detection[] = [];
      for (const incident of incidents) {
        let cached = this.cache.get(incident.id);
        if (!cached || cached.version !== incident.version) {
          const result = await allPages(page => this.api.getDetections(incident.id, page), valid);
          cached = { version: incident.version, detections: result.items };
          if (!valid()) return; this.cache.set(incident.id, cached);
        }
        evidence.push(...cached.detections);
      }
      if (!valid()) return;
      const activeIds = new Set(incidents.map(item => item.id));
      for (const id of this.cache.keys()) if (!activeIds.has(id)) this.cache.delete(id);
      this.service.set(service); this.from.set(from); this.to.set(to);
      this.currentIncidents.set(currentIncidents);
      this.windows.set(mergeWindows(old, ...history.map(item => item.items)).filter(row => Date.parse(row.windowStart) >= Date.parse(from) && Date.parse(row.windowStart) < Date.parse(to)));
      this.observedAt.set(service.observedAt); this.incidents.set(incidents); this.detections.set(evidence);
    } catch (error) {
      if (valid()) this.error.set(error instanceof Error ? error.message : 'Could not load evidence. Try again.');
    } finally { if (valid()) { this.loading.set(false); this.refreshing = false; } }
  }
}
