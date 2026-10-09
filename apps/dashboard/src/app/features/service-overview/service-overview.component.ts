import { Component, DestroyRef, inject, signal, computed } from "@angular/core";
import { DatePipe } from '@angular/common';
import { takeUntilDestroyed } from "@angular/core/rxjs-interop";
import { ActivatedRoute, Router, RouterLink } from "@angular/router";
import { StatusBannerComponent } from '../../shared/status-banner.component';
import { IconComponent } from "../../shared/icon.component";
import { LiveUpdates } from '../../core/state/live-updates';
import { SessionStore } from '../login-and-session/session.store';
import { dataSource } from '../../core/api/data-source';
import { delta } from '../service-kpi-history/assurance-model';
import { ServiceStore, ServiceHealth } from "./service.store";
import { primaryMetric, supportedValue } from '../../shared/metric-presentation';

import { ConnectedOverviewComponent } from './connected-overview.component';
import type { Filter } from './dashboard-geography';

@Component({
  selector: "app-service-overview",
  imports: [DatePipe, StatusBannerComponent, RouterLink, IconComponent, ConnectedOverviewComponent],
  templateUrl: "./service-overview.component.html",
  styles: [`
    .page-heading { margin-bottom: 2px; gap: 12px; }
    .page-heading h1 { font-size: 24px; margin: 2px 0; }
    .page-heading .eyebrow { display: none; }
    .page-heading p:not(.eyebrow) { display: none; }
    .page-heading .heading-copy { flex: 1; }
    .page-heading button { min-height: 36px; }
    .kpi-strip { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); padding: 6px 0; background: var(--surface); border-radius: var(--radius-control); }
    .kpi-strip > div { position: relative; display: grid; grid-template-columns: minmax(0, 1fr) auto; align-items: center; gap: 12px; min-width: 0; min-height: 32px; padding: 4px 16px; }
    .kpi-strip > div + div::before { content: ''; position: absolute; left: 0; top: 6px; bottom: 6px; width: 1px; background: color-mix(in srgb, var(--text-muted) 35%, var(--transparent)); }
    .kpi-strip span { color: var(--text-muted); font-size: 12px; line-height: 1.35; }
    .kpi-strip strong { min-width: 2ch; text-align: right; font-size: 20px; line-height: 1.2; font-weight: 600; font-variant-numeric: tabular-nums; }
    .scope-toolbar { display: flex; align-items: flex-end; flex-wrap: wrap; gap: var(--space-3); margin: var(--space-3) 0 var(--space-4); }
    .scope-toolbar .button, .scope-toolbar select { min-height: 44px; }
    .scope-service { display: grid; gap: var(--space-2); width: 200px; max-width: 100%; color: var(--text-muted); font-size: 12px; }
    @media (max-width: 600px) {
      .page-heading { flex-wrap: nowrap; gap: 8px; align-items: center; }
      .page-heading h1 { font-size: 18px; }
      .page-heading button { padding: 8px; }
      .refresh-copy { display: none; }
      .kpi-strip { grid-template-columns: repeat(2, minmax(0, 1fr)); padding: 4px 0; }
      .kpi-strip > div { gap: 8px; padding: 10px 12px; }
      .kpi-strip > div:nth-child(odd)::before { content: none; }
      .kpi-strip > div:nth-child(n + 3)::after { content: ''; position: absolute; top: 0; left: 12px; right: 12px; height: 1px; background: color-mix(in srgb, var(--text-muted) 35%, var(--transparent)); }
      .kpi-strip span { font-size: 11px; }
      .scope-toolbar { display: grid; grid-template-columns: minmax(0, 1fr); }
      .scope-service { width: 100%; }
    }
    .source-inventory { margin-top: 14px; }
    .source-inventory > summary { padding: 10px; cursor: pointer; color: var(--text-muted); }
    .source-inventory > .button { margin-bottom: var(--space-3); }
  `],
})
export class ServiceOverviewComponent {
  readonly store = inject(ServiceStore);
  private readonly destroyRef = inject(DestroyRef);
  readonly services = this.store.services;
  readonly fixture = dataSource.fixture;
  readonly loading = this.store.loading;
  readonly error = this.store.error;
  readonly incidentError = this.store.incidentError;
  readonly serviceFilter = signal<Filter>('ALL');
  readonly connected = signal(true);
  private readonly router = inject(Router);
  setService(service: Filter): void {
    void this.router.navigate(['/dashboard'], { queryParams: { service: service === 'ALL' ? null : service }, queryParamsHandling: 'merge' });
  }
  readonly visibleServices = computed(() => this.services().filter(item => this.serviceFilter() === 'ALL' || item.scope.service === this.serviceFilter()));
  readonly healthyCount = computed(() => this.services().filter(item => this.health(item) === 'NORMAL').length);
  readonly ongoingCount = computed(() => new Set(this.store.incidents().filter(item => item.technicalState === 'ONGOING').map(item => item.episodeId)).size);
  readonly openCount = computed(() => this.services().reduce((sum, item) => sum + item.openIncidents, 0));
  readonly observedCount = computed(() => this.services().filter(item => item.latestWindow !== null).length);
  mainMetric(service: Parameters<ServiceStore['health']>[0]): string {
    const window = service.latestWindow;
    if (!window) return 'Unavailable';
    const kpi = primaryMetric(service.scope.service, window.kpis);
    const actual = supportedValue(kpi, window.kpis, window.quality);
    return kpi ? this.metric(actual, kpi.unit) : 'Unavailable';
  }
  readonly scopeId = signal<string | null>(null);
  readonly difference = delta;
  readonly streamError = signal('');
  readonly live = inject(LiveUpdates);
  private closeStream?: () => void;
  private refreshTimer?: ReturnType<typeof setTimeout>;
  private refreshing = false;
  private refreshAgain = false;
  private active = true;

  constructor() {
    inject(ActivatedRoute).queryParamMap.pipe(takeUntilDestroyed(this.destroyRef)).subscribe(params => {
      const service = params.get('service');
      this.serviceFilter.set(service === 'VOLTE' || service === 'SMS' ? service : 'ALL');
      this.connected.set(params.get('view') !== 'scopes');
    });
    inject(ActivatedRoute)
      .paramMap.pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe((params) => this.scopeId.set(params.get("scopeId")));
    const stop = () => {
      this.active = false;
      this.refreshAgain = false;
      clearTimeout(this.refreshTimer);
      this.closeStream?.();
      this.closeStream = undefined;
      this.store.invalidate();
    };
    this.destroyRef.onDestroy(stop);
    inject(SessionStore).ended$.pipe(takeUntilDestroyed(this.destroyRef)).subscribe(stop);
    if (!dataSource.fixture) {
      this.closeStream = this.live.watch(() => {
        clearTimeout(this.refreshTimer);
        this.refreshTimer = setTimeout(() => { if (this.live.visible()) void this.load(true); }, 150);
      }, () => this.streamError.set('Live connection interrupted. Existing evidence is still shown; reconnecting…'),
      () => this.streamError.set(''));
    }
    void this.load();
  }

  async load(quiet = this.services().length > 0): Promise<void> {
    if (!this.active) return;
    if (quiet && this.refreshing) { this.refreshAgain = true; return; }
    this.refreshing = true;
    try {
      await this.store.load(quiet);
      if (!this.error()) this.live.updated();
    } finally {
      this.refreshing = false;
      if (this.active && this.refreshAgain) {
        this.refreshAgain = false;
        clearTimeout(this.refreshTimer);
        this.refreshTimer = setTimeout(() => { if (this.live.visible()) void this.load(true); }, 150);
      }
    }
  }

  selected() {
    return this.store.selected(this.scopeId());
  }

  health(service: Parameters<ServiceStore["health"]>[0]): ServiceHealth {
    return this.store.health(service);
  }

  healthExplanation(service: Parameters<ServiceStore["health"]>[0]): string {
    return this.store.healthExplanation(this.health(service));
  }
  metric(value: number | null, unit: string): string {
    if (value === null) return "Unavailable";
    return `${new Intl.NumberFormat("en", { maximumFractionDigits: 2 }).format(value)} ${({ PERCENT: "%", PERCENTAGE_POINTS: "pp", RATIO: "(ratio)", COUNT: "", MILLISECONDS: "ms", SECONDS: "s", MBPS: "Mbps" } as Record<string, string>)[unit] ?? unit}`.trim();
  }
  time(value: string): string {
    return new Intl.DateTimeFormat("en-GB", {
      timeZone: "Europe/Chisinau",
      dateStyle: "medium",
      timeStyle: "short",
    }).format(new Date(value));
  }
}
