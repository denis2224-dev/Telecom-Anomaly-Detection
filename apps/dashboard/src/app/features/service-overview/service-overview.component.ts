import { Component, DestroyRef, inject, signal, computed } from "@angular/core";
import { takeUntilDestroyed } from "@angular/core/rxjs-interop";
import { ActivatedRoute, RouterLink } from "@angular/router";
import { IconComponent } from "../../shared/icon.component";
import { IncidentStream } from '../../core/state/incident-stream';
import { SessionStore } from '../login-and-session/session.store';
import { dataSource } from '../../core/api/data-source';
import { delta } from '../service-kpi-history/assurance-model';
import { ServiceStore, ServiceHealth } from "./service.store";
import { primaryMetric, supportedValue } from '../../shared/metric-presentation';

@Component({
  selector: "app-service-overview",
  imports: [RouterLink, IconComponent],
  templateUrl: "./service-overview.component.html",
})
export class ServiceOverviewComponent {
  readonly store = inject(ServiceStore);
  private readonly destroyRef = inject(DestroyRef);
  readonly services = this.store.services;
  readonly loading = this.store.loading;
  readonly error = this.store.error;
  readonly serviceFilter = signal('ALL');
  readonly visibleServices = computed(() => this.services().filter(item => this.serviceFilter() === 'ALL' || item.scope.service === this.serviceFilter()));
  readonly healthyCount = computed(() => this.services().filter(item => this.health(item) === 'NORMAL').length);
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
  private readonly stream = inject(IncidentStream);
  private closeStream?: () => void;
  private currentTimer?: ReturnType<typeof setInterval>;
  private refreshTimer?: ReturnType<typeof setTimeout>;
  private refreshing = false;
  private refreshAgain = false;
  private active = true;

  constructor() {
    inject(ActivatedRoute)
      .paramMap.pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe((params) => this.scopeId.set(params.get("scopeId")));
    void this.load();
    const stop = () => {
      this.active = false;
      this.refreshAgain = false;
      clearInterval(this.currentTimer);
      clearTimeout(this.refreshTimer);
      this.closeStream?.();
      this.closeStream = undefined;
      this.store.invalidate();
    };
    this.destroyRef.onDestroy(stop);
    inject(SessionStore).ended$.pipe(takeUntilDestroyed(this.destroyRef)).subscribe(stop);
    if (!dataSource.fixture) {
      this.closeStream = this.stream.connect(() => {
        clearTimeout(this.refreshTimer);
        this.refreshTimer = setTimeout(() => void this.load(true), 150);
      }, () => this.streamError.set('Live connection interrupted. Existing evidence is still shown; reconnecting…'));
      this.currentTimer = setInterval(() => void this.load(true), 30_000);
    }
  }

  async load(quiet = false): Promise<void> {
    if (!this.active) return;
    if (quiet && this.refreshing) { this.refreshAgain = true; return; }
    this.refreshing = true;
    try {
      await this.store.load(quiet);
      if (!this.store.error()) this.streamError.set('');
    } finally {
      this.refreshing = false;
      if (this.active && this.refreshAgain) {
        this.refreshAgain = false;
        clearTimeout(this.refreshTimer);
        this.refreshTimer = setTimeout(() => void this.load(true), 150);
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
