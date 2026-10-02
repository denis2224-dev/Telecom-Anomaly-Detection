import { Component, DestroyRef, inject, signal } from "@angular/core";
import { takeUntilDestroyed } from "@angular/core/rxjs-interop";
import { ActivatedRoute, RouterLink } from "@angular/router";
import { ServiceStore, ServiceHealth } from "./service.store";
import { LiveUpdates } from '../../core/api/live-updates';
import { delta, formatMetric, metricValue } from '../service-kpi-history/assurance-model';
import type { ServiceSummary } from '../../core/api/telecom-client';

@Component({
  selector: "app-service-overview",
  imports: [RouterLink],
  templateUrl: "./service-overview.component.html",
})
export class ServiceOverviewComponent {
  readonly store = inject(ServiceStore);
  private readonly destroyRef = inject(DestroyRef);
  readonly services = this.store.services;
  readonly loading = this.store.loading;
  readonly error = this.store.error;
  readonly scopeId = signal<string | null>(null);
  readonly serviceFilter = signal('ALL'); readonly healthFilter = signal('ALL'); readonly hours = signal('1');
  private refreshing = false;
  readonly difference = delta;

  constructor() {
    inject(ActivatedRoute)
      .paramMap.pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe((params) => this.scopeId.set(params.get("scopeId")));
    void this.load();
    this.destroyRef.onDestroy(() => this.store.invalidate());
    inject(LiveUpdates).refresh$.pipe(takeUntilDestroyed(this.destroyRef)).subscribe(() => {
      if (!this.refreshing) void this.load(true);
    });
  }

  async load(quiet = false): Promise<void> {
    this.refreshing = true;
    try { await this.store.load(quiet); } finally { this.refreshing = false; }
  }
  filtered() { return this.services().filter(service => (this.serviceFilter() === 'ALL' || this.serviceFilter() === service.scope.service)
    && (this.healthFilter() === 'ALL' || this.healthFilter() === (this.health(service) === 'STALE' ? 'UNKNOWN' : this.health(service)))
    && (!this.scopeId() || service.scope.scopeId === this.scopeId())); }
  primary(service: ServiceSummary) { return service.scope.service === 'VOLTE' ? 'cssrPct' : 'p95DeliveryMs'; }
  primaryValue(service: ServiceSummary) { const name = this.primary(service); return formatMetric(metricValue(service.latestWindow, name), service.latestWindow?.kpis.find(item => item.name === name)?.unit); }

  health(service: Parameters<ServiceStore["health"]>[0]): ServiceHealth {
    return this.store.health(service);
  }

  healthExplanation(service: Parameters<ServiceStore["health"]>[0]): string {
    return this.store.healthExplanation(this.health(service));
  }
  time(value: string): string {
    return new Intl.DateTimeFormat("en-GB", {
      timeZone: "UTC",
      dateStyle: "medium",
      timeStyle: "short",
    }).format(new Date(value));
  }
}
