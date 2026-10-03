import { Component, DestroyRef, inject, signal, computed } from "@angular/core";
import { takeUntilDestroyed } from "@angular/core/rxjs-interop";
import { ActivatedRoute, RouterLink } from "@angular/router";
import { IconComponent } from "../../shared/icon.component";
import { ServiceStore, ServiceHealth } from "./service.store";

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
    if (!window || window.quality === 'MISSING') return 'Unavailable';
    const name = service.scope.service === 'VOLTE' ? 'cssrPct' : 'p95DeliveryMs';
    const kpi = window.kpis.find(item => item.name === name);
    if (kpi?.denominator === 0 || (name === 'p95DeliveryMs' && !window.kpis.find(item => item.name === 'deliveredMessages')?.observed)) return 'Unavailable';
    return kpi ? this.metric(kpi.observed, kpi.unit) : 'Unavailable';
  }
  readonly scopeId = signal<string | null>(null);

  constructor() {
    inject(ActivatedRoute)
      .paramMap.pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe((params) => this.scopeId.set(params.get("scopeId")));
    void this.load();
  }

  async load(): Promise<void> {
    await this.store.load();
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
