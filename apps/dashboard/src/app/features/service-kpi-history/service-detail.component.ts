import { Component, DestroyRef, inject, signal, computed } from '@angular/core';
import { DatePipe } from '@angular/common';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { TelecomClient, type ServiceSummary } from '../../core/api/telecom-client';
import { dataSource } from '../../core/api/data-source';
import { IncidentStream, mergeIncidentPage } from '../../core/state/incident-stream';
import { KpiChartComponent } from './kpi-chart.component';
import { IncidentListComponent } from '../incident-investigation/incident-list.component';
import { type Incident, type KpiWindow } from './voice-model';
import { SmsQualityComponent } from './sms-quality.component';
import { SmsHistoryComponent } from './sms-history.component';
import { HistoryRangeComponent, type HistoryRange } from './history-range.component';

import { KpiCardsComponent } from './kpi-cards.component';
import { MetricChartComponent } from './metric-chart.component';
import { ServicePathComponent } from './service-path.component';
import { serviceHealth } from './assurance-model';
import { SessionStore } from '../login-and-session/session.store';

import { IconComponent } from '../../shared/icon.component';
import { MetricExplanationComponent } from '../../shared/metric-explanation.component';
import { primaryMetric, supportedValue } from '../../shared/metric-presentation';

const PAGE_SIZE = 20;
const MAX_WINDOWS = 1440;

@Component({
  selector: 'app-service-detail',
  imports: [RouterLink, DatePipe, HistoryRangeComponent, KpiChartComponent,
    IncidentListComponent, SmsQualityComponent, SmsHistoryComponent, IconComponent, MetricExplanationComponent,
    KpiCardsComponent, MetricChartComponent, ServicePathComponent],
  template: `
    <a class="back-link" routerLink="/dashboard"><app-icon name="left" />Service overview</a>
    <div class="page-heading"><div class="heading-copy"><p class="eyebrow">Service investigation</p><h1>{{ service()?.scope?.service === 'SMS' ? 'SMS delivery assurance' : 'VoLTE setup assurance' }}</h1><p class="mono">{{ scopeId() }}</p></div>
      @if (service(); as item) { <span class="badge" [attr.data-state]="item.freshness"><span class="status-dot"></span>Source: {{ item.freshness }}</span> }
    </div>
    @if (loading()) { <section class="state-panel skeleton-panel" role="status"><span class="spinner"></span> Loading service evidence…<div class="skeleton"></div><div class="skeleton chart"></div></section> }
    @if (error()) { <section class="state-panel" role="alert"><h2>Evidence unavailable</h2><p>{{ error() }}</p><button (click)="load()"><app-icon name="refresh" />Retry</button></section> }
    @if (!loading() && !error() && service(); as item) {
      <app-history-range [from]="from()" [to]="to()" (changed)="applyRange($event)" (refresh)="load()" (latest)="latestHour()" />
      <div class="range-caption"><span>{{ from() | date:'dd MMM yyyy HH:mm':'UTC' }} – {{ to() | date:'dd MMM yyyy HH:mm':'UTC' }} UTC</span><span>{{ item.scope.region }} · {{ item.scope.route }}</span></div>
      @if (!item.latestWindow || item.latestWindow.quality === 'MISSING' || item.freshness === 'MISSING') {
        <app-metric-explanation topic="missing-evidence" mode="state" />
      } @else if (item.freshness === 'STALE') {
        <app-metric-explanation topic="stale-evidence" mode="state" />
      }
      @if (item.scope.service === 'VOLTE') {
        <app-metric-explanation topic="percentage-points" />
      }
      <p class="helper">Current health: {{ health() }} · Technical recovery and analyst resolution are separate.</p>
      <app-kpi-cards [service]="item" />
      <app-service-path [service]="item" [detections]="detections()" />
      <div class="stat-grid">
        <section class="stat-card"><div class="stat-label">{{ item.scope.service === 'VOLTE' ? 'Last success rate in selected range' : 'Last delivery p95 in selected range' }}<app-icon name="activity" /></div><strong class="metric-value">{{ summary().latest === null ? 'Unavailable' : summary().latest }} <small>{{ summary().latest === null ? '' : item.scope.service === 'VOLTE' ? '%' : 'ms' }}</small></strong>
          @if (summary().delta !== null) { <p class="stat-trend" [class.negative]="summary().worsened"><app-icon name="trend" />{{ summary().delta! > 0 ? '+' : '' }}{{ summary().delta }} {{ item.scope.service === 'VOLTE' ? 'pp' : 'ms' }} over range</p> } @else { <p class="helper">Trend requires comparable observations</p> }
          <svg class="stat-sparkline" viewBox="0 0 100 32" aria-hidden="true"><path [attr.d]="summary().sparkline" /></svg>
        </section>
        <section class="stat-card"><div class="stat-label">Observation windows<app-icon name="calendar" /></div><strong class="metric-value">{{ windows().length }} <small>windows</small></strong><p class="helper">{{ summary().available }} with a measured {{ item.scope.service === 'VOLTE' ? 'success rate' : 'delivery p95' }}</p><div class="stat-meter"><span [style.width.%]="windows().length ? summary().available / windows().length * 100 : 0"></span></div></section>
        <section class="stat-card"><div class="stat-label">Open incidents<app-icon name="alert" /></div><strong class="metric-value">{{ item.openIncidents }}</strong><p class="helper">Unresolved work across this service scope</p></section>
      </div>
      @if (streamError()) { <div class="notice" role="status">{{ streamError() }}<div class="button-row"><button (click)="refreshIncidents()"><app-icon name="refresh" />Retry incident refresh</button><button (click)="refreshIncidents(0)" [disabled]="incidentLoading()">Show first incident page</button></div></div> }
      @if (item.scope.service === 'VOLTE') {
        <app-kpi-chart [windows]="windows()" [incidents]="incidents()" [from]="from()" [to]="to()" />
        <p class="chart-disclaimer"><app-icon name="info" />Incident shading uses only incident page {{ incidentPage() + 1 }}. Unshaded time may contain incidents on other pages.</p>
      } @else {
        <app-metric-chart name="p95DeliveryMs" title="P95 delivery delay · actual versus baseline" unit="MILLISECONDS" [windows]="windows()" [detections]="detections()" [from]="from()" [to]="to()" />
        <app-sms-quality [window]="item.latestWindow" [freshness]="item.freshness" />
        <app-sms-history [windows]="windows()" />
      }
      <div class="supporting-charts">@for (chart of charts(); track chart.name) {
        <app-metric-chart [name]="chart.name" [title]="chart.title" [unit]="chart.unit" [windows]="windows()" [detections]="detections()" [from]="from()" [to]="to()" />
      }</div>
      <p class="helper">Phase bands use the latest persisted detection on incident page {{ incidentPage() + 1 }}. Earlier phases and other pages require incident investigation; missing evidence does not prove normal health.</p>
      <div class="section-heading"><div><p class="eyebrow">Incident workspace</p><p>Incidents are paged for this scope independently of the KPI time range.</p></div>@if (incidentLoading()) { <span class="helper" role="status"><span class="spinner"></span> Refreshing incident page…</span> }</div>
      <app-incident-list [incidents]="incidents()" />
      <nav class="pagination" aria-label="Incident pages">
        <button (click)="refreshIncidents(incidentPage() - 1)" [disabled]="incidentLoading() || incidentPage() === 0"><app-icon name="left" />Previous incidents</button>
        <span>Page {{ incidentPage() + 1 }} · {{ incidents().length }} shown · {{ incidentTotal() }} total</span>
        <button (click)="refreshIncidents(incidentPage() + 1)" [disabled]="incidentLoading() || (incidentPage() + 1) * pageSize >= incidentTotal()">Next incidents<app-icon name="right" /></button>
      </nav>
      <p class="evidence-asof">Server evidence as of {{ observedAt() | date:'dd MMM yyyy HH:mm:ss':'UTC' }} UTC{{ fixture ? ' · Synthetic sample' : '' }}</p>
    }
  `,
})
export class ServiceDetailComponent {
  private readonly api = inject(TelecomClient);
  private readonly stream = inject(IncidentStream);
  private generation = 0;
  private loadController?: AbortController;
  private refreshController?: AbortController;
  private closeStream?: () => void;
  private refreshTimer?: ReturnType<typeof setTimeout>;
  private refreshAgain = false;
  private currentTimer?: ReturnType<typeof setInterval>;
  readonly detections = computed(() => this.incidents().map(item => item.latestDetection));
  readonly health = computed(() => {
    if (!this.service()) return 'UNKNOWN';
    const health = serviceHealth(this.service()!, this.incidents());
    return health === 'NORMAL' && this.incidentTotal() > this.incidents().length ? 'UNKNOWN' : health;
  });
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
  readonly pageSize = PAGE_SIZE;
  readonly fixture = dataSource.fixture;
  readonly scopeId = signal('');
  readonly service = signal<ServiceSummary | null>(null);
  readonly loading = signal(true);
  readonly error = signal('');
  readonly streamError = signal('');
  readonly from = signal('');
  readonly to = signal('');
  readonly observedAt = signal('');
  readonly windows = signal<KpiWindow[]>([]);
  readonly incidents = signal<Incident[]>([]);
  readonly incidentPage = signal(0);
  readonly incidentTotal = signal(0);
  readonly incidentLoading = signal(false);

  readonly summary = computed(() => {
    const rows = [...this.windows()].sort((a, b) => Date.parse(a.windowStart) - Date.parse(b.windowStart));
    const sms = this.service()?.scope.service === 'SMS';
    const values = rows.map(row => supportedValue(
      primaryMetric(sms ? 'SMS' : 'VOLTE', row.kpis), row.kpis, row.quality,
    ));
    const latest = values.at(-1) ?? null, first = values[0] ?? null;
    const delta = latest === null || first === null || rows.length < 2 ? null : Math.round((latest - first) * 100) / 100;
    const valid = values.filter((value): value is number => value !== null);
    const min = Math.min(...valid), max = Math.max(...valid);
    let connected = false;
    const sparkline = values.map((value, index) => {
      if (value === null) { connected = false; return ''; }
      const contiguous = index > 0 && rows[index - 1].windowEnd === rows[index].windowStart;
      const point = `${connected && contiguous ? 'L' : 'M'}${index / Math.max(1, rows.length - 1) * 100},${28 - (value - min) / (max - min || 1) * 24}`;
      connected = true;
      return point;
    }).join(' ');
    return { latest, delta, available: valid.length, worsened: delta !== null && (sms ? delta > 0 : delta < 0), sparkline };
  });

  constructor() {
    const destroy = inject(DestroyRef);
    const stop = () => {
      ++this.generation;
      this.cancelReads();
      this.closeStream?.();
      this.closeStream = undefined;
      clearInterval(this.currentTimer);
    };
    inject(SessionStore).ended$.pipe(takeUntilDestroyed(destroy)).subscribe(stop);
    destroy.onDestroy(stop);
    inject(ActivatedRoute).paramMap.pipe(takeUntilDestroyed(destroy)).subscribe(params => {
      this.closeStream?.();
      this.closeStream = undefined;
      this.scopeId.set(params.get('scopeId') ?? '');
      this.from.set('');
      this.to.set('');
      this.incidents.set([]);
      this.incidentPage.set(0);
      void this.load();
    });
    if (!this.fixture) this.currentTimer = setInterval(() => void this.refreshIncidents(), 30_000);
  }

  applyRange(range: HistoryRange): void {
    this.from.set(range.from);
    this.to.set(range.to);
    void this.load();
  }

  latestHour(): void {
    this.from.set('');
    this.to.set('');
    void this.load();
  }

  private cancelReads(): void {
    clearTimeout(this.refreshTimer);
    this.loadController?.abort();
    this.refreshController?.abort();
    this.refreshController = undefined;
    this.refreshAgain = false;
    this.incidentLoading.set(false);
  }

  async load(): Promise<void> {
    const generation = ++this.generation;
    this.cancelReads();
    const controller = this.loadController = new AbortController();
    const scopeId = this.scopeId();
    const previous = this.incidents();
    this.loading.set(true);
    this.error.set('');
    this.streamError.set('');
    this.windows.set([]);
    try {
      const services = await this.api.listServices(controller.signal);
      if (generation !== this.generation) return;
      const service = services.find(item => item.scope.scopeId === scopeId);
      if (!service) throw new Error('This service could not be found. Return to the overview.');
      this.service.set(service);
      if (!this.from()) {
        const end = Date.parse(service.latestWindow?.windowEnd ?? service.observedAt);
        if (!Number.isFinite(end)) throw new Error('The service has no valid evidence time.');
        const range = this.fixture && service.scope.service === 'VOLTE'
          ? (await dataSource.loadVoice()).voiceRange
          : { from: new Date(end - 3_600_000).toISOString(), to: new Date(end).toISOString() };
        if (generation !== this.generation) return;
        this.from.set(range.from);
        this.to.set(range.to);
      }
      const from = this.from(), to = this.to();
      const duration = Date.parse(to) - Date.parse(from);
      if (!Number.isFinite(duration) || duration <= 0 || duration > 86_400_000) {
        throw new Error('Choose a valid time range of at most 24 hours.');
      }
      const [history, incidents] = await Promise.all([
        this.history(scopeId, from, to, controller.signal),
        this.api.listIncidents({ scopeId, service: service.scope.service,
          page: 0, size: PAGE_SIZE }, controller.signal),
      ]);
      if (generation !== this.generation) return;
      this.checkIncidentPage(incidents);
      this.windows.set(history.items);
      this.observedAt.set(history.observedAt ?? service.observedAt);
      this.incidents.set(mergeIncidentPage(previous, incidents.items));
      this.incidentPage.set(0);
      this.incidentTotal.set(incidents.total);
      if (!this.fixture && !this.closeStream) {
        this.closeStream = this.stream.connect(
          () => this.queueIncidentRefresh(),
          () => this.streamError.set(
            'Live connection interrupted. Existing evidence is still shown; reconnecting…',
          ),
        );
      }
    } catch (error) {
      controller.abort(); // Also cancel the other Promise.all request on failure.
      if (generation === this.generation) {
        this.error.set(error instanceof Error ? error.message : 'Could not load evidence.');
      }
    } finally {
      if (generation === this.generation) this.loading.set(false);
    }
  }

  private async history(scopeId: string, from: string, to: string, signal: AbortSignal) {
    const items: KpiWindow[] = [];
    const ids = new Set<string>();
    let total: number | undefined;
    let observedAt: string | undefined;
    for (let page = 0; page < 15; page++) {
      signal.throwIfAborted();
      const result = await this.api.getServiceKpis(scopeId, { from, to, page, size: 100 }, signal);
      signal.throwIfAborted();
      if (!Number.isInteger(result.total) || result.total < 0 || result.total > MAX_WINDOWS
        || result.items.length > 100 || (total !== undefined && total !== result.total)) {
        throw new Error('History exceeded its limit or changed while loading. Retry the selected range.');
      }
      total = result.total;
      observedAt ??= result.observedAt;
      for (const item of result.items) {
        const start = Date.parse(item.windowStart);
        if (ids.has(item.windowId) || item.scopeId !== scopeId || !Number.isFinite(start)
          || start < Date.parse(from) || start >= Date.parse(to)) {
          throw new Error('History contains duplicate or unexpected windows. Retry the selected range.');
        }
        ids.add(item.windowId);
        items.push(item);
      }
      if (items.length > total || items.length > MAX_WINDOWS) {
        throw new Error('History exceeded its reported limit. Retry the selected range.');
      }
      if (items.length === total) return { items, observedAt };
      if (result.items.length !== 100) {
        throw new Error('History changed while loading. Retry to get a complete range.');
      }
    }
    throw new Error('History exceeded 15 pages. Choose a shorter range.');
  }

  private checkIncidentPage(result: { items: Incident[]; total: number }): void {
    if (!Number.isInteger(result.total) || result.total < 0 || result.items.length > PAGE_SIZE
      || result.items.length > result.total || (result.total > 0 && !result.items.length)) {
      throw new Error('This incident page changed or exceeded its limit. Return to the first page.');
    }
  }

  private queueIncidentRefresh(): void {
    clearTimeout(this.refreshTimer);
    this.refreshTimer = setTimeout(() => {
      if (this.loading()) { this.queueIncidentRefresh(); return; }
      if (!this.error()) void this.refreshIncidents();
    }, 150);
  }

  async refreshIncidents(page = this.incidentPage()): Promise<void> {
    const service = this.service();
    if (this.loading() || this.error() || !service || page < 0) return;
    if (this.refreshController) { this.refreshAgain = true; return; }
    const controller = this.refreshController = new AbortController();
    const generation = this.generation;
    this.incidentLoading.set(true);
    try {
      const [result, services] = await Promise.all([
        this.api.listIncidents({ scopeId: this.scopeId(), service: service.scope.service, page, size: PAGE_SIZE }, controller.signal),
        this.api.listServices(controller.signal),
      ]);
      if (generation !== this.generation) return;
      this.checkIncidentPage(result);
      const current = services.find(item => item.scope.scopeId === this.scopeId());
      if (!current) throw new Error('This service could not be found. Return to the overview.');
      this.service.set(current);
      this.incidents.set(mergeIncidentPage(this.incidents(), result.items));
      this.incidentPage.set(page);
      this.incidentTotal.set(result.total);
      this.streamError.set('');
    } catch (error) {
      controller.abort();
      if (generation === this.generation) {
        this.streamError.set(error instanceof Error ? error.message
          : 'Live incident refresh failed. Retry to catch up.');
      }
    } finally {
      if (this.refreshController === controller) {
        this.refreshController = undefined;
        this.incidentLoading.set(false);
        if (this.refreshAgain) {
          this.refreshAgain = false;
          this.queueIncidentRefresh();
        }
      }
    }
  }
}
