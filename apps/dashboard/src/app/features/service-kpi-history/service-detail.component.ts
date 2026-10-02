import { Component, DestroyRef, inject, signal } from '@angular/core';
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

const PAGE_SIZE = 20;
const MAX_WINDOWS = 1440;

@Component({
  selector: 'app-service-detail',
  imports: [RouterLink, DatePipe, HistoryRangeComponent, KpiChartComponent,
    IncidentListComponent, SmsQualityComponent, SmsHistoryComponent],
  template: `
    <a class="back-link" routerLink="/dashboard">← Service overview</a>
    <div class="page-heading">
      <p class="eyebrow">Service investigation</p>
      <h1>{{ service()?.scope?.service === 'SMS' ? 'SMS service' : 'Voice call setup' }}</h1>
      <p>{{ scopeId() }}</p>
    </div>
    @if (loading()) { <p role="status">Loading service evidence…</p> }
    @if (error()) {
      <section class="state-panel" role="alert">
        <h2>Evidence unavailable</h2><p>{{ error() }}</p>
        <button (click)="load()">Retry</button>
      </section>
    }
    @if (!loading() && !error() && service(); as item) {
      <p class="muted">{{ item.scope.region }} · {{ item.scope.route }} · Source: {{ item.freshness }}</p>
      <app-history-range [from]="from()" [to]="to()"
        (changed)="applyRange($event)" (refresh)="load()" (latest)="latestHour()" />
      <p class="muted">
        {{ from() | date:'dd MMM yyyy HH:mm':'UTC' }} –
        {{ to() | date:'dd MMM yyyy HH:mm':'UTC' }} UTC · {{ windows().length }} windows
      </p>
      <p class="muted">Server evidence as of
        {{ observedAt() | date:'dd MMM yyyy HH:mm:ss':'UTC' }} UTC
        {{ fixture ? ' · Synthetic sample' : '' }}
      </p>
      @if (streamError()) {
        <p role="status">{{ streamError() }}</p>
        <button (click)="refreshIncidents()">Retry incident refresh</button>
        <button (click)="refreshIncidents(0)" [disabled]="incidentLoading()">Show first incident page</button>
      }
      @if (item.scope.service === 'VOLTE') {
        <p class="notice">Incident shading uses only incident page {{ incidentPage() + 1 }}.
          Unshaded time may contain incidents on other pages.</p>
        <app-kpi-chart [windows]="windows()" [incidents]="incidents()" [from]="from()" [to]="to()" />
      } @else {
        <app-sms-quality [window]="item.latestWindow" [freshness]="item.freshness" />
        <app-sms-history [windows]="windows()" />
      }
      <p class="muted">Incidents are paged for this scope independently of the KPI time range.</p>
      @if (incidentLoading()) { <p role="status">Refreshing incident page…</p> }
      <app-incident-list [incidents]="incidents()" />
      <nav aria-label="Incident pages">
        <button (click)="refreshIncidents(incidentPage() - 1)"
          [disabled]="incidentLoading() || incidentPage() === 0">Previous incidents</button>
        <span>Page {{ incidentPage() + 1 }} · {{ incidents().length }} shown · {{ incidentTotal() }} total</span>
        <button (click)="refreshIncidents(incidentPage() + 1)"
          [disabled]="incidentLoading() || (incidentPage() + 1) * pageSize >= incidentTotal()">Next incidents</button>
      </nav>
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

  constructor() {
    const destroy = inject(DestroyRef);
    destroy.onDestroy(() => {
      ++this.generation;
      this.cancelReads();
      this.closeStream?.();
    });
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
      const result = await this.api.listIncidents({
        scopeId: this.scopeId(), service: service.scope.service, page, size: PAGE_SIZE,
      }, controller.signal);
      if (generation !== this.generation) return;
      this.checkIncidentPage(result);
      this.incidents.set(mergeIncidentPage(this.incidents(), result.items));
      this.incidentPage.set(page);
      this.incidentTotal.set(result.total);
      this.streamError.set('');
    } catch (error) {
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
