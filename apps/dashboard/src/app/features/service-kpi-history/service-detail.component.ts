import { SmsShadowComponent } from '../../shared/sms-shadow.component';
import { Component, DestroyRef, inject, signal, computed } from '@angular/core';
import { DatePipe } from '@angular/common';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { TelecomClient, type ServiceSummary } from '../../core/api/telecom-client';
import { dataSource } from '../../core/api/data-source';
import { mergeIncidentPage } from '../../core/state/incident-stream';
import { LiveUpdates } from '../../core/state/live-updates';
import { KpiChartComponent } from './kpi-chart.component';
import { IncidentListComponent } from '../incident-investigation/incident-list.component';
import { type Incident, type KpiWindow } from './voice-model';
import { SmsQualityComponent } from './sms-quality.component';
import { SmsHistoryComponent } from './sms-history.component';
import { HistoryRangeComponent, type HistoryRange } from './history-range.component';

import { KpiCardsComponent } from './kpi-cards.component';
import { MetricChartComponent } from './metric-chart.component';
import { ServicePathComponent } from './service-path.component';
import { serviceHealth, VOLTE_METRICS, SMS_METRICS } from './assurance-model';
import { SessionStore } from '../login-and-session/session.store';

import { IconComponent } from '../../shared/icon.component';
import { MetricExplanationComponent } from '../../shared/metric-explanation.component';
import { bindGeography, type City } from '../service-overview/dashboard-geography';

const PAGE_SIZE = 20;
const MAX_WINDOWS = 1440;

@Component({
  selector: 'app-service-detail',
  imports: [SmsShadowComponent, RouterLink, DatePipe, HistoryRangeComponent, KpiChartComponent,
    IncidentListComponent, SmsQualityComponent, SmsHistoryComponent, IconComponent, MetricExplanationComponent, KpiCardsComponent, MetricChartComponent, ServicePathComponent],
  template: `
    <div class="page-heading"><a class="back-link" routerLink="/dashboard"><app-icon name="left" />Service overview</a><div class="heading-copy"><h1>{{ service()?.scope?.service === 'SMS' ? 'SMS delivery assurance' : 'VoLTE setup assurance' }}</h1><p class="mono">{{ scopeId() }}</p></div>
    </div>
    @if (loading() && !displayedService()) { <section class="state-panel skeleton-panel service-loading" role="status"><span class="spinner"></span> Loading service evidence…<div class="skeleton"></div><div class="skeleton chart"></div></section> }
    @if (error()) { <section class="state-panel" role="alert"><h2>Evidence unavailable</h2><p>{{ error() }}</p><button (click)="load()"><app-icon name="refresh" />Retry</button></section> }
    @if (displayedService(); as item) {
      <div class="service-toolbar">
        <label class="field service-city">City<select aria-label="City" [value]="scopeId()" [disabled]="cityLoading() || loading() || !cityOptions().length" (change)="selectCity($any($event.target).value)">
          @if (!selectedCity()) { <option [value]="scopeId()" [selected]="true">{{ item.scope.region }} · current scope</option> }
          @for (city of cityOptions(); track city.id) { <option [value]="city.scopeId" [selected]="city.scopeId === scopeId()">{{ city.name }}</option> }
        </select></label>
        <app-history-range [compact]="true" [from]="from()" [to]="to()" (changed)="applyRange($event)" (refresh)="load()" (latest)="latestHour()" />
      </div>
      @if (cityError()) { <p class="city-error" role="status">City list unavailable <button type="button" (click)="loadCities()">Retry cities</button></p> }
    }
    @if (!error() && displayedService(); as item) {
      <section class="service-hero" aria-label="Service KPI history">
        <div class="kpi-switcher" role="group" aria-label="Graph KPI">
          @for (metric of exactMetrics(); track metric.name) {
            <button type="button" [attr.aria-pressed]="selectedMetric().name === metric.name" (click)="selectedKpi.set(metric.name)">{{ kpiLabel(metric.name) }}</button>
          }
        </div>
        <app-metric-chart view="chart" [hero]="true" [loading]="loading()" [name]="selectedMetric().name" [title]="selectedMetric().title" [unit]="selectedMetric().unit" [windows]="windows()" [incidents]="incidents()" [detections]="detections()" [from]="from()" [to]="to()" (windowSelected)="hoveredWindow.set($event)" />
        <div class="chart-legend" aria-label="Chart legend"><span class="actual-key">Observed</span><span class="expected-key">Baseline</span><span class="state-degraded">Incident</span><span class="state-recovery">Recovery</span><span class="state-unknown">Unavailable</span></div>
      </section>
      <app-kpi-cards [service]="item" />
      <div class="service-alerts" aria-live="polite">
        @if (streamError() || connectionInterrupted()) { <div class="service-connection"><span [title]="streamError() || connectionInterrupted()">{{ streamError() || connectionInterrupted() }}</span><button type="button" (click)="streamError() ? refreshIncidents() : load()" [disabled]="incidentLoading() || loading()"><app-icon name="refresh" />Retry</button>@if (streamError() && incidentPage() > 0) { <button type="button" (click)="refreshIncidents(0)" [disabled]="incidentLoading()"><app-icon name="left" />First incident page</button> }</div> }
      </div>
      <app-incident-list [incidents]="incidents()" [total]="incidentTotal()" [highlighted]="highlightedEpisodes()" [loading]="incidentLoading()" [cities]="cityCatalogue()" [scope]="item.scope" />
      <nav class="pagination" aria-label="Incident pages">
        <button (click)="refreshIncidents(incidentPage() - 1)" [disabled]="incidentLoading() || incidentPage() === 0"><app-icon name="left" />Previous incidents</button>
        <span>Page {{ incidentPage() + 1 }} · {{ incidents().length }} shown · {{ incidentTotal() }} total</span>
        <button (click)="refreshIncidents(incidentPage() + 1)" [disabled]="incidentLoading() || (incidentPage() + 1) * pageSize >= incidentTotal()">Next incidents<app-icon name="right" /></button>
      </nav>
      <details class="source-inventory"><summary>Service dependencies and source quality</summary>
        <p>{{ item.scope.region }} · {{ item.scope.route }} · Current health: {{ health() }}</p>
        <p>Server evidence as of {{ observedAt() | date:'dd MMM yyyy HH:mm:ss':'UTC' }} UTC{{ fixture ? ' · Synthetic sample' : '' }}</p>
        <p>Incident shading and chart highlights use episodes on the current incident page. Technical recovery and analyst resolution are separate; the last observation of an ongoing episode is not a recovery time.</p>
      @if (!item.latestWindow || item.latestWindow.quality === 'MISSING' || item.freshness === 'MISSING') {
        <app-metric-explanation topic="missing-evidence" mode="state" />
      } @else if (item.freshness === 'STALE') {
        <app-metric-explanation topic="stale-evidence" mode="state" />
      }
      @if (item.latestWindow && !hasBaseline(selectedMetric().name)) {
        <app-metric-explanation topic="baseline-missing" mode="state" />
      }

        <app-service-path [service]="item" [detections]="detections()" />
        @if (item.scope.service === 'SMS') { <app-sms-quality [window]="item.latestWindow" [freshness]="item.freshness" /> }
      </details>
      @if (!fixture && item.scope.service === 'SMS') { <app-sms-shadow [scopeId]="scopeId()" [from]="from()" [to]="to()" [revision]="observedAt()" /> }
      <details class="exact-values"><summary>Exact values ({{ windows().length }} windows)</summary>
        @if (item.scope.service === 'VOLTE') {
          <app-kpi-chart view="table" [windows]="windows()" [incidents]="incidents()" [from]="from()" [to]="to()" />
        } @else { <app-sms-history [windows]="windows()" /> }
        @for (chart of exactMetrics(); track chart.name) {
          @if (chart.name !== 'cssrPct') { <app-metric-chart view="table" [name]="chart.name" [title]="chart.title" [unit]="chart.unit" [windows]="windows()" [detections]="detections()" [from]="from()" [to]="to()" /> }
        }
      </details>
    }
  `,
})
export class ServiceDetailComponent {
  private readonly api = inject(TelecomClient);
  private readonly router = inject(Router);
  private cityController?: AbortController;
  private readonly live = inject(LiveUpdates);
  private generation = 0;
  private loadController?: AbortController;
  private refreshController?: AbortController;
  private closeStream?: () => void;
  private refreshTimer?: ReturnType<typeof setTimeout>;
  private refreshAgain = false;
  private followingLatest = true;
  readonly detections = computed(() => this.incidents().map(item => item.latestDetection));
  readonly health = computed(() => {
    if (!this.service()) return 'UNKNOWN';
    const health = serviceHealth(this.service()!, this.incidents());
    return health === 'NORMAL' && this.incidentTotal() > this.incidents().length ? 'UNKNOWN' : health;
  });
  hasBaseline(name: string): boolean {
    return this.windows().some(row => row.kpis.some(kpi => kpi.name === name && kpi.baseline != null && Number.isFinite(kpi.baseline)));
  }
  readonly exactMetrics = computed(() => (this.service()?.scope.service === 'SMS' ? [
    { name: 'p95DeliveryMs', title: 'P95 delivery delay · actual versus baseline', unit: 'MILLISECONDS' },
    { name: 'deliverySrPct', title: 'Delivery success rate', unit: 'PERCENT' },
    { name: 'queueDepth', title: 'Queue depth', unit: 'COUNT' },
    { name: 'oldestPendingAgeSec', title: 'Oldest pending message age', unit: 'SECONDS' },
    { name: 'deliveredMessages', title: 'Delivered sample volume', unit: 'COUNT' },
  ] : [
    { name: 'cssrPct', title: 'Call setup success rate', unit: 'PERCENT' },
    { name: 'rrcSrPct', title: 'Radio / access · RRC SR', unit: 'PERCENT' },
    { name: 'bearerSrPct', title: 'Radio / access · Bearer SR', unit: 'PERCENT' },
    { name: 'sip503Ratio', title: 'IMS / core · SIP 503 rate', unit: 'RATIO' },
    { name: 'imsCpuPct', title: 'IMS / core · CPU', unit: 'PERCENT' },
    { name: 'packetLossRatio', title: 'Transport · packet loss', unit: 'RATIO' },
    { name: 'eligibleAttempts', title: 'Eligible attempt volume', unit: 'COUNT' },
    { name: 'sip503Count', title: 'SIP 503 count', unit: 'COUNT' },
  ]));
  readonly selectedKpi = signal('');
  readonly selectedMetric = computed(() => this.exactMetrics().find(metric => metric.name === this.selectedKpi()) ?? this.exactMetrics()[0]);
  kpiLabel(name: string): string { return [...VOLTE_METRICS, ...SMS_METRICS].find(metric => metric[0] === name)?.[1] ?? name; }
  readonly hoveredWindow = signal<KpiWindow | null>(null);
  readonly highlightedEpisodes = computed(() => {
    const window = this.hoveredWindow();
    return window ? this.incidents().filter(item => item.scopeId === window.scopeId
      && Date.parse(window.windowStart) < Date.parse(item.lastObservedAt)
      && Date.parse(window.windowEnd) > Date.parse(item.firstObservedAt)).map(item => item.episodeId) : [];
  });
  readonly pageSize = PAGE_SIZE;
  readonly fixture = dataSource.fixture;
  readonly scopeId = signal('');
  readonly service = signal<ServiceSummary | null>(null);
  readonly displayedService = computed(() => this.service()?.scope.scopeId === this.scopeId() ? this.service() : null);
  readonly loading = signal(true);
  readonly error = signal('');
  readonly streamError = signal('');
  readonly connectionInterrupted = signal('');
  readonly from = signal('');
  readonly to = signal('');
  readonly observedAt = signal('');
  readonly windows = signal<KpiWindow[]>([]);
  readonly incidents = signal<Incident[]>([]);
  readonly incidentPage = signal(0);
  readonly incidentTotal = signal(0);
  readonly incidentLoading = signal(false);
  readonly cityCatalogue = signal<readonly City[]>([]);
  readonly cityLoading = signal(true);
  readonly cityError = signal('');
  readonly cityOptions = computed(() => this.cityCatalogue().flatMap(city => {
    const service = this.service()?.scope.service;
    const scopeId = this.fixture ? city.scopeIds.find(id => id.startsWith(`fixture-${service}-`))
      : city.geography?.services.find(item => item.service === service)?.scopeId;
    return scopeId ? [{ id: city.id, name: city.name, scopeId }] : [];
  }));
  readonly selectedCity = computed(() => this.cityOptions().find(city => city.scopeId === this.scopeId()));

  async loadCities(): Promise<void> {
    this.cityController?.abort();
    const controller = this.cityController = new AbortController();
    this.cityLoading.set(true);
    this.cityError.set('');
    try {
      const cities = this.fixture ? (await dataSource.loadConnectedDashboard()).fixtureCities
        : bindGeography(await this.api.listGeographyCities(controller.signal));
      if (!controller.signal.aborted) this.cityCatalogue.set(cities);
    } catch {
      if (!controller.signal.aborted) { this.cityCatalogue.set([]); this.cityError.set('City list unavailable'); }
    } finally {
      if (!controller.signal.aborted) this.cityLoading.set(false);
    }
  }

  selectCity(scopeId: string): void {
    if (scopeId === this.scopeId() || !this.cityOptions().some(city => city.scopeId === scopeId)) return;
    void this.router.navigate(['/services', scopeId], { queryParams: {
      from: this.from(), to: this.to(), follow: this.followingLatest ? 'latest' : 'fixed',
    } });
  }

  constructor() {
    const destroy = inject(DestroyRef);
    const route = inject(ActivatedRoute);
    const stop = () => {
      ++this.generation;
      this.cityController?.abort();
      this.cancelReads();
      this.closeStream?.();
      this.closeStream = undefined;
    };
    inject(SessionStore).ended$.pipe(takeUntilDestroyed(destroy)).subscribe(stop);
    destroy.onDestroy(stop);
    void this.loadCities();
    route.paramMap.pipe(takeUntilDestroyed(destroy)).subscribe(params => {
      this.closeStream?.();
      this.closeStream = undefined;
      this.hoveredWindow.set(null);
      this.selectedKpi.set('');
      this.scopeId.set(params.get('scopeId') ?? '');
      this.from.set(route.snapshot.queryParamMap.get('from') ?? '');
      this.to.set(route.snapshot.queryParamMap.get('to') ?? '');
      this.followingLatest = !this.from() || route.snapshot.queryParamMap.get('follow') === 'latest';
      this.incidents.set([]);
      this.incidentPage.set(0);
      this.incidentTotal.set(0);
      if (!this.fixture) {
        this.closeStream = this.live.watch(
          () => this.queueIncidentRefresh(),
          () => this.connectionInterrupted.set('Live connection interrupted. Existing evidence is still shown; reconnecting…'),
          () => this.connectionInterrupted.set(''),
        );
      }
      void this.load();
    });
  }

  applyRange(range: HistoryRange): void {
    this.followingLatest = (this.followingLatest && range.to === this.to())
      || Math.abs(Date.now() - Date.parse(range.to)) <= 60_000;
    this.from.set(range.from);
    this.to.set(range.to);
    void this.load();
  }

  latestHour(): void {
    this.followingLatest = true;
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
      this.live.updated();
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
      if (!this.live.visible()) return;
      if (this.loading()) { this.queueIncidentRefresh(); return; }
      if (this.error()) void this.load();
      else void this.refreshIncidents();
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
      const end = Date.parse(current.latestWindow?.windowEnd ?? current.observedAt);
      const previousEnd = Date.parse(this.to());
      if (this.followingLatest && Number.isFinite(end) && end > previousEnd) {
        const duration = previousEnd - Date.parse(this.from());
        const from = new Date(end - duration).toISOString(), to = new Date(end).toISOString();
        const last = [...this.windows()].sort((a, b) => Date.parse(a.windowStart) - Date.parse(b.windowStart)).at(-1);
        const tailFrom = new Date(Math.max(Date.parse(from), Date.parse(last?.windowStart ?? from))).toISOString();
        const history = await this.history(this.scopeId(), tailFrom, to, controller.signal);
        if (generation !== this.generation) return;
        const rows = new Map(this.windows().filter(row => Date.parse(row.windowStart) >= Date.parse(from)
          && Date.parse(row.windowStart) < Date.parse(tailFrom)).map(row => [row.windowId, row]));
        for (const row of history.items) rows.set(row.windowId, row);
        if (rows.size > MAX_WINDOWS) throw new Error('History exceeded its limit. Choose a shorter range.');
        this.from.set(from);
        this.to.set(to);
        this.windows.set([...rows.values()].filter(row => Date.parse(row.windowStart) >= Date.parse(from)
          && Date.parse(row.windowStart) < end).sort((a, b) => Date.parse(a.windowStart) - Date.parse(b.windowStart)));
        this.observedAt.set(history.observedAt ?? current.observedAt);
      }
      this.service.set(current);
      this.incidents.set(mergeIncidentPage(this.incidents(), result.items));
      this.incidentPage.set(page);
      this.incidentTotal.set(result.total);
      this.streamError.set('');
      this.live.updated();
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
