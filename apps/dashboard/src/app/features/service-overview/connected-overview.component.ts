import { Component, DestroyRef, viewChild, computed, effect, inject, input, output, signal, untracked } from '@angular/core';
import { takeUntilDestroyed, toSignal } from '@angular/core/rxjs-interop';
import { SessionStore } from '../login-and-session/session.store';
import { DatePipe } from '@angular/common';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { TelecomClient, type PriorityPage } from '../../core/api/telecom-client';
import { dataSource } from '../../core/api/data-source';
import { ServiceStore } from './service.store';
import { MetricChartComponent } from '../service-kpi-history/metric-chart.component';
import { IconComponent } from '../../shared/icon.component';
import { DrawerComponent } from '../../shared/drawer.component';
import { RoamingOverviewComponent } from '../roaming/roaming-overview.component';
import { CityEvidenceComponent } from './city-evidence.component';
import { probableCause } from '../../shared/metric-presentation';
import { metricValue, formatMetric } from '../service-kpi-history/assurance-model';
import { OVERVIEW_RANGE_MS, overviewHistory } from './overview-history';
import { moldovaOutline } from './moldova-map';
import {
  baseline, cities, cityForScope, cityLabel, cityServices, deviation, measured, metric, number, geographyState, geographyValue,
  type City, type Episode, type Filter, type Service, type Summary, type Window,
} from './dashboard-geography';

interface History {
  rows: Window[];
  from: string;
  to: string;
  observedAt: string;
  total: number;
  error: string;
}

@Component({
  selector: 'app-connected-overview',
  imports: [RouterLink, DatePipe, MetricChartComponent, IconComponent, DrawerComponent, CityEvidenceComponent, RoamingOverviewComponent],
  templateUrl: './connected-overview.component.html',
  styleUrl: './connected-overview.component.css',
})
export class ConnectedOverviewComponent {
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);
  private readonly fragment = toSignal(this.route.fragment);
  readonly queueDialog = viewChild<DrawerComponent>('queueDialog');
  clearQueueFragment(): void {
    if (this.fragment() === 'incident-queue') void this.router.navigate([], { relativeTo: this.route, queryParamsHandling: 'preserve', replaceUrl: true });
  }
  private readonly api = inject(TelecomClient);
  private readonly store = inject(ServiceStore);
  readonly services = input.required<Summary[]>();
  readonly serviceFilter = input<Filter>('ALL');
  readonly filterChanged = output<Filter>();
  readonly fixture = dataSource.fixture;
  readonly presets = [{ label: '15m', minutes: 15 }, { label: '1h', minutes: 60 }, { label: '6h', minutes: 360 }, { label: '24h', minutes: 1440 },
    { label: '3d', minutes: 3 * 1440 }, { label: '7d', minutes: 7 * 1440 }, { label: '14d', minutes: 14 * 1440 }, { label: '30d', minutes: 30 * 1440 }];
  readonly roamingPanel = signal(true);
  readonly selectedScopes = signal({ VOLTE: '', SMS: '' });
  readonly pageSize = 20;
  private readonly fixtureCatalogue = signal<readonly City[]>(cities);
  readonly catalogue = computed(() => this.fixture ? this.fixtureCatalogue() : this.store.cities());
  readonly geographyError = this.store.geographyError;
  readonly geographyUpdatedAt = this.store.geographyUpdatedAt;
  readonly selectedId = signal<string | null>(null);
  readonly search = signal('');
  readonly minutes = signal(15);
  readonly customRange = signal<{ from: string; to: string } | null>(null);
  private readonly followsLatest = signal(false);
  readonly draftFrom = signal<string | null>(null);
  readonly draftTo = signal<string | null>(null);
  readonly rangeError = signal('');
  readonly moldovaOutline = moldovaOutline;
  readonly tableService = computed<Service>(() => this.serviceFilter() === 'SMS' ? 'SMS' : 'VOLTE');
  readonly chartTo = computed(() => {
    const custom = this.customRange();
    if (custom && !this.followsLatest()) return custom.to;
    const times = this.services().map(item => Date.parse(item.latestWindow?.windowEnd ?? item.observedAt)).filter(Number.isFinite);
    return new Date(Math.max(custom ? Date.parse(custom.to) : 0, times.length ? Math.max(...times) : Date.now())).toISOString();
  });
  readonly chartFrom = computed(() => {
    const custom = this.customRange();
    if (custom && !this.followsLatest()) return custom.from;
    const duration = custom ? Date.parse(custom.to) - Date.parse(custom.from) : this.minutes() * 60_000;
    return new Date(Date.parse(this.chartTo()) - duration).toISOString();
  });
  readonly refresh = signal(0);
  readonly statusMessage = signal('');
  readonly queueSeverityFilter = signal('');
  readonly queueStateFilter = signal('');
  readonly queueSeverity = computed(() => ['CRITICAL', 'HIGH', 'MEDIUM'].find(level => this.incidents().some(item => item.severity === level)) ?? 'NONE');
  readonly queuePage = signal(0);
  readonly queueTotal = signal(0);
  readonly queueHasNext = signal(false);
  readonly priorityItems = signal<PriorityPage['items']>([]);
  readonly queueLoading = signal(false);
  readonly queueError = signal('');
  readonly incidents = signal<Episode[]>([]);
  readonly histories = signal<Record<string, History>>({});
  readonly historyLoading = signal(false);
  private queueController?: AbortController;
  private historyController?: AbortController;
  private historyRequestKey = '';
  private historyBusy = false;
  private stopped = false;

  readonly markerCities = computed(() => this.catalogue().filter(city => city.marker !== null));
  readonly featuredCities = computed(() => this.catalogue().filter(city => city.featured).slice(0, 5));
  readonly selectedCity = computed(() => this.catalogue().find(city => city.id === this.selectedId()));
  readonly visibleIncidents = computed(() => {
    const city = this.selectedCity();
    const details = this.incidents();
    if (!this.fixture) return this.priorityItems()
      .map(item => details.find(detail => detail.id === item.incidentId))
      .filter((item): item is Episode => !!item)
      .filter(item => !this.queueSeverityFilter() || item.severity === this.queueSeverityFilter());
    return details.filter(item => (!city || city.scopeIds.includes(item.scopeId))
      && (!this.queueSeverityFilter() || item.severity === this.queueSeverityFilter())
      && (!this.queueStateFilter() || item.technicalState === this.queueStateFilter()))
      .sort((a, b) => Date.parse(b.detectedAt) - Date.parse(a.detectedAt));
  });
  priorityFor(id: string) { return this.priorityItems().find(item => item.incidentId === id); }
  readonly cause = probableCause;
  readonly cityServices = cityServices;
  readonly cityForScope = cityForScope;
  readonly cityLabel = cityLabel;
  readonly metric = metric;
  readonly baseline = baseline;
  readonly deviation = deviation;
  readonly number = number;
  readonly next = (value: number) => value + 1;
  readonly previous = (value: number) => Math.max(0, value - 1);

  tableScopes(service: Service): Summary[] {
    if (this.serviceFilter() !== 'ALL' && this.serviceFilter() !== service) return [];
    const city = this.selectedCity();
    return this.services().filter(item => item.scope.service === service
      && (!city || city.scopeIds.includes(item.scope.scopeId))).slice(0, 5);
  }
  chartScope(service: Service): Summary | undefined {
    const rows = this.tableScopes(service);
    return rows.find(item => item.scope.scopeId === this.selectedScopes()[service])
      ?? rows.find(item => item.latestWindow !== null) ?? rows[0];
  }
  selectScope(service: Service, scopeId: string): void {
    this.selectedScopes.update(previous => ({ ...previous, [service]: scopeId }));
  }
  serviceTarget(service: Service): Summary | undefined {
    return this.chartScope(service) ?? this.services().find(item => item.scope.service === service);
  }
  chartHistory(service: Service): History | undefined {
    const scopeId = this.chartScope(service)?.scope.scopeId;
    return scopeId ? this.histories()[scopeId] : undefined;
  }
  chartRows(service: Service): Window[] {
    const from = Date.parse(this.chartFrom()), to = Date.parse(this.chartTo());
    return (this.chartHistory(service)?.rows ?? []).filter(row => Date.parse(row.windowStart) >= from && Date.parse(row.windowStart) < to);
  }
  chartIncidents(service: Service): Episode[] {
    const scopeId = this.chartScope(service)?.scope.scopeId;
    return this.store.incidents().filter(item => item.scopeId === scopeId);
  }
  chartDetections(service: Service) { return this.chartIncidents(service).map(item => item.latestDetection); }
  tableMetric(item: Summary, name: string): string {
    const value = metricValue(item.latestWindow, name);
    const unit = item.latestWindow?.kpis.find(kpi => kpi.name === name)?.unit;
    return value === null ? '—' : formatMetric(value, unit === 'PERCENT' || unit === 'RATIO' ? '' : unit);
  }
  tableState(item: Summary): string {
    const health = this.scopeHealth(item);
    const city = cityForScope(this.catalogue(), item.scope.scopeId);
    const state = city?.geography?.services.find(state => state.scopeId === item.scope.scopeId);
    return health === 'DEGRADED' && ((state?.technicalActiveCount ?? 0) > 0
      || this.store.incidents().some(incident => incident.scopeId === item.scope.scopeId && incident.technicalState === 'ONGOING'))
      ? 'INCIDENT' : health;
  }
  cityDelta(item: Summary): string {
    return deviation(item.latestWindow, item.scope.service).replace(' from baseline', '').replace('Delta unavailable', '—');
  }

  constructor() {
    const destroy = inject(DestroyRef);
    effect(() => {
      if (this.fragment() === 'incident-queue') this.queueDialog()?.open();
    });
    const stop = () => {
      this.stopped = true;
      this.queueController?.abort();
      this.historyController?.abort();
      this.incidents.set([]);
      this.priorityItems.set([]);
      this.histories.set({});
    };
    destroy.onDestroy(stop);
    inject(SessionStore).ended$.pipe(takeUntilDestroyed(destroy)).subscribe(stop);
    if (this.fixture) {
      void dataSource.loadConnectedDashboard().then(data => {
        if (!this.stopped) this.fixtureCatalogue.set(data.fixtureCities);
      }).catch(() => { if (!this.stopped) this.statusMessage.set('City design fixture could not be loaded.'); });
    }
    // A new server filter starts at its first priority page.
    effect(() => { this.serviceFilter(); this.queueStateFilter(); this.queuePage.set(0); });
    effect(() => {
      this.services(); // Parent REST refresh follows the existing incident stream.
      const filter = this.serviceFilter(), page = this.queuePage();
      this.selectedId(); this.queueStateFilter();
      this.refresh();
      void this.loadIncidents(filter, page);
    });
    effect(() => {
      const summaries = [this.chartScope('VOLTE'), this.chartScope('SMS')].filter((item): item is Summary => !!item);
      const from = this.chartFrom(), to = this.chartTo();
      this.refresh();
      void this.loadHistories(summaries, from, to);
    });
  }

  select(id: string): void { this.queuePage.set(0); this.selectedId.set(id); this.search.set(this.cityById(id)?.name ?? ''); }

  cityById(id: string): City | undefined { return this.catalogue().find(city => city.id === id); }

  setRegion(value: string): void {
    this.search.set(value);
    const normalize = (text: string) => text.normalize('NFD').replace(/\p{Diacritic}/gu, '').trim().toLowerCase();
    this.queuePage.set(0);
    this.selectedId.set(this.catalogue().find(city => normalize(city.name) === normalize(value))?.id ?? null);
  }

  setPeriod(minutes: number, fromInput: HTMLInputElement, toInput: HTMLInputElement): void {
    this.minutes.set(this.presets.some(preset => preset.minutes === minutes) ? minutes : 15);
    this.customRange.set(null);
    this.draftFrom.set(null); this.draftTo.set(null);
    this.rangeError.set('');
    // Restore edited native controls even when a computed endpoint is unchanged.
    fromInput.value = this.chartFrom().slice(0, 16);
    toInput.value = this.chartTo().slice(0, 16);
  }

  applyRange(event: Event, start: string, end: string): void {
    event.preventDefault();
    const from = Date.parse(start + 'Z'), to = Date.parse(end + 'Z');
    if (!Number.isFinite(from) || !Number.isFinite(to) || to <= from || to - from > OVERVIEW_RANGE_MS) {
      this.rangeError.set('Choose an end after the start, with an overview range of at most 30 days.');
      return;
    }
    this.customRange.set({ from: new Date(from).toISOString(), to: new Date(to).toISOString() });
    this.followsLatest.set(Math.abs(Date.now() - to) <= 60_000);
    this.draftFrom.set(null); this.draftTo.set(null);
    this.rangeError.set('');
  }

  nodeValue(city: City): string {
    if (city.geography) return this.cityObservation(city, this.tableService());
    const items = cityServices(city, this.services(), this.serviceFilter());
    if (!items.length) return 'Unavailable';
    if (this.serviceFilter() === 'ALL') return metric(items.find(item => item.scope.service === 'VOLTE')?.latestWindow ?? null, 'VOLTE');
    return metric(items[0].latestWindow, items[0].scope.service);
  }

  cityObservation(city: City, service: Service): string {
    const state = geographyState(city, service)[0];
    if (!state) return metric(cityServices(city, this.services(), service)[0]?.latestWindow ?? null, service);
    const value = geographyValue(state);
    return value === null ? 'Unavailable' : `${number(value)} ${state.metric.unit === 'PERCENT' ? '%' : 'ms'}`;
  }

  cityChange(city: City, service: Service): string {
    const state = geographyState(city, service)[0];
    if (!state) {
      const item = cityServices(city, this.services(), service)[0];
      return item ? this.cityDelta(item) : '—';
    }
    const actual = geographyValue(state), expected = state.metric.baseline;
    if (actual === null || expected === null) return '—';
    const change = service === 'VOLTE' ? state.metric.deltaPp : actual - expected;
    return change === null ? '—' : `${change > 0 ? '+' : ''}${number(change)} ${service === 'VOLTE' ? 'pp' : 'ms'}`;
  }

  scopeHealth(item: Summary): string {
    if (item.freshness === 'MISSING' || measured(item.latestWindow, item.scope.service) === null) return 'UNKNOWN';
    if (item.freshness === 'STALE') return 'STALE';
    if (baseline(item.latestWindow, item.scope.service) === null) return 'UNKNOWN';
    // Reuse existing presentation rules; this is not a new detector verdict.
    return this.store.health(item);
  }

  cityHealth(city: City): string {
    if (city.geography) {
      if (this.geographyError()) return 'STALE';
      const states = geographyState(city, this.serviceFilter());
      if (states.some(state => state.freshness === 'STALE' || state.coverage.state === 'STALE')) return 'STALE';
      if (states.some(state => geographyValue(state) === null || state.coverage.state !== 'COMPLETE'
        || state.metric.baseline === null)) return 'UNKNOWN';
      return states.some(state => state.technicalActiveCount > 0) ? 'DEGRADED' : 'NORMAL';
    }
    if (city.scopeIds.length === 0) return 'MAPPING PENDING';
    const items = cityServices(city, this.services(), this.serviceFilter());
    if (items.length === 0) return 'UNAVAILABLE';
    const relevantIds = city.scopeIds.filter(scopeId => this.serviceFilter() === 'ALL'
      || this.services().some(item => item.scope.scopeId === scopeId && item.scope.service === this.serviceFilter()));
    const states = items.map(item => this.scopeHealth(item));
    if (states.includes('DEGRADED')) return 'DEGRADED';
    if (items.length !== relevantIds.length || states.includes('UNKNOWN')) return 'UNKNOWN';
    if (states.includes('STALE')) return 'STALE';
    return 'NORMAL';
  }

  symbol(state: string): string {
    return state === 'NORMAL' ? '✓' : state === 'DEGRADED' ? '!' : state === 'STALE' ? '◷' : '?';
  }

  detectionWindow(incident: Episode): Window {
    const detection = incident.latestDetection;
    return {
      schemaVersion: 2, featureVersion: 2, windowId: detection.detectionId,
      scopeId: detection.scopeId, service: detection.service,
      windowStart: detection.windowStart, windowEnd: detection.windowEnd,
      quality: detection.technicalState === 'UNKNOWN' ? 'MISSING' : 'COMPLETE',
      baselineVersion: detection.baselineVersion, topologyVersion: detection.topologyVersion,
      kpis: detection.kpis, featureNames: [], featureValues: [], mlEligible: false, sourceEventIds: [],
    };
  }

  private async loadIncidents(filter: Filter, page: number): Promise<void> {
    if (this.stopped) return;
    this.queueController?.abort();
    const controller = this.queueController = new AbortController();
    this.queueLoading.set(true);
    this.queueError.set('');
    try {
      if (!this.fixture) {
        const result = await this.api.getOperationalPriority({
          cityId: this.selectedId() ?? undefined,
          service: filter === 'ALL' ? undefined : filter,
          technicalState: this.queueStateFilter() ? this.queueStateFilter() as 'ONGOING' | 'UNKNOWN' | 'RECOVERED' : undefined,
          page, size: this.pageSize,
        }, controller.signal);
        if (controller.signal.aborted || this.stopped) return;
        if (page > 0 && !result.items.length && !result.hasNext) {
          this.queuePage.set(0);
          return;
        }
        const details = await Promise.all(result.items.map(item => this.api.getIncident(item.incidentId, controller.signal)));
        if (controller.signal.aborted || this.stopped) return;
        if (details.some((detail, index) => detail.id !== result.items[index].incidentId
          || detail.location?.cityId !== result.items[index].cityId)) throw new Error('Priority and incident evidence disagree. Refresh the queue.');
        this.priorityItems.set(result.items);
        this.incidents.set(details);
        this.queueHasNext.set(result.hasNext);
        this.queueTotal.set(page * this.pageSize + result.items.length + (result.hasNext ? 1 : 0));
        return;
      }
      const result = await this.api.listIncidents({ service: filter === 'ALL' ? undefined : filter, page, size: this.pageSize }, controller.signal);
      if (controller.signal.aborted || this.stopped) return;
      this.incidents.set(result.items);
      this.queueHasNext.set((page + 1) * this.pageSize < result.total);
      this.queueTotal.set(result.total);
    } catch (error) {
      if (!controller.signal.aborted && !this.stopped) {
        this.queueError.set(error instanceof Error ? error.message : 'Incidents could not be loaded.');
      }
    } finally {
      if (!controller.signal.aborted && !this.stopped) this.queueLoading.set(false);
    }
  }

  private async loadHistories(summaries: Summary[], from: string, to: string): Promise<void> {
    if (this.stopped) return;
    const key = `${summaries.map(item => item.scope.scopeId).join(',')}/${from}/${to}`;
    if (this.historyBusy && key === this.historyRequestKey && Date.parse(to) - Date.parse(from) > 86_400_000) return;
    this.historyRequestKey = key;
    this.historyBusy = true;
    this.historyController?.abort();
    const controller = this.historyController = new AbortController();
    this.historyLoading.set(true);
    const unique = [...new Map(summaries.map(item => [item.scope.scopeId, item])).values()];
    const entries = await Promise.all(unique.map(async (item): Promise<[string, History]> => {
      try {
        const result = await overviewHistory(item.scope.scopeId, from, to,
          (from, to, page) => this.api.getServiceKpis(item.scope.scopeId, { from, to, page, size: 100 }, controller.signal),
          () => !controller.signal.aborted && !this.stopped, untracked(() => this.histories()[item.scope.scopeId]));
        return [item.scope.scopeId, { rows: result.items, from, to, observedAt: result.observedAt ?? item.observedAt, total: result.items.length, error: '' }];
      } catch (error) {
        return [item.scope.scopeId, { rows: [], from, to, observedAt: item.observedAt, total: 0,
          error: error instanceof Error ? error.message : 'History could not be loaded.' }];
      }
    }));
    if (!controller.signal.aborted && !this.stopped) {
      this.historyBusy = false;
      this.histories.set(Object.fromEntries(entries));
      this.historyLoading.set(false);
    }
  }
}
