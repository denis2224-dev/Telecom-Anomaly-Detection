import { Injectable, inject, signal } from "@angular/core";
import { ServiceSummary, TelecomClient } from "../../core/api/telecom-client";
import type { Incident } from '../../core/api/telecom-client';
import { mergeIncidentPage } from '../../core/state/incident-stream';
import { allPages, metricValue, serviceHealth } from '../service-kpi-history/assurance-model';
import { dataSource } from '../../core/api/data-source';
import { bindGeography, cities, geographyValue, type City } from './dashboard-geography';

export type ServiceHealth = "NORMAL" | "DEGRADED" | "STALE" | "UNKNOWN";

@Injectable({ providedIn: "root" })
export class ServiceStore {
  private readonly api = inject(TelecomClient);
  private requestId = 0;
  private controller?: AbortController;

  readonly services = signal<ServiceSummary[]>([]);
  readonly incidents = signal<Incident[]>([]);
  readonly previous = signal<ServiceSummary[]>([]);
  readonly loading = signal(true);
  readonly error = signal("");
  readonly cities = signal<readonly City[]>(cities);
  readonly geographyError = signal('');
  readonly geographyUpdatedAt = signal('');

  async load(quiet = false): Promise<void> {
    const requestId = ++this.requestId;
    this.controller?.abort();
    const controller = this.controller = new AbortController();
    if (!quiet) this.loading.set(true);
    this.error.set("");

    try {
      const [services] = await Promise.all([
        this.api.listServices(controller.signal),
        this.loadGeography(requestId, controller.signal),
      ]);
      const incidentPage = await allPages(page => this.api.listIncidents({ page, size: 100 }, controller.signal), () => requestId === this.requestId);
      if (requestId === this.requestId) {
        const old = this.services();
        // Keep the prior completed window until a genuinely new minute arrives.
        this.previous.update(previous => services.map(service => old.find(item => item.scope.scopeId === service.scope.scopeId && item.latestWindow?.windowId !== service.latestWindow?.windowId)
          ?? previous.find(item => item.scope.scopeId === service.scope.scopeId)).filter((item): item is ServiceSummary => !!item));
        this.services.set(services); this.incidents.set(mergeIncidentPage(this.incidents(), incidentPage.items));
      }
    } catch (error) {
      if (requestId === this.requestId) {
        this.error.set(
          error instanceof Error ? error.message : "Services could not be loaded.",
        );
      }
    } finally {
      if (requestId === this.requestId) { this.loading.set(false); this.controller = undefined; }
    }
  }

  private async loadGeography(requestId: number, signal: AbortSignal): Promise<void> {
    if (dataSource.fixture) return;
    try {
      const response = await this.api.listGeographyCities(signal);
      const mapped = bindGeography(response);
      if (requestId !== this.requestId || signal.aborted) return;
      this.cities.set(mapped);
      this.geographyUpdatedAt.set(response.generatedAt);
      this.geographyError.set('');
    } catch (error) {
      if (requestId === this.requestId && !signal.aborted) {
        this.geographyError.set(error instanceof Error ? error.message : 'City catalogue unavailable.');
      }
    }
  }

  health(service: ServiceSummary): ServiceHealth {
    const state = this.cities().flatMap(city => city.geography?.services ?? []).find(state => state.scopeId === service.scope.scopeId);
    if (state) {
      if (this.geographyError() || state.freshness === 'STALE' || state.coverage.state === 'STALE') return 'STALE';
      if (geographyValue(state) === null || state.coverage.state !== 'COMPLETE' || state.metric.baseline === null) return 'UNKNOWN';
      if (state.technicalActiveCount > 0) return 'DEGRADED';
    }
    return serviceHealth(service, this.incidents());
  }
  trend(service: ServiceSummary) {
    const primary = service.scope.service === 'VOLTE' ? 'cssrPct' : 'p95DeliveryMs';
    const current = metricValue(service.latestWindow, primary);
    const previous = metricValue(this.previous().find(item => item.scope.scopeId === service.scope.scopeId)?.latestWindow, primary);
    if (current === null || previous === null) return 'Trend unavailable until next measured minute';
    return current === previous ? 'Unchanged from previous minute' : current > previous ? '↑ Increased from previous minute' : '↓ Decreased from previous minute';
  }
  selected(scopeId: string | null): ServiceSummary | undefined {
    return this.services().find(service => service.scope.scopeId === scopeId);
  }
  invalidate() { this.requestId++; this.controller?.abort(); this.controller = undefined; this.cities.set(cities); this.geographyUpdatedAt.set(''); this.geographyError.set(''); }

  healthExplanation(health: ServiceHealth): string {
    return {
      NORMAL: "Complete service telemetry; no persisted ongoing anomaly. Compare actual values with the contextual baseline.",
      DEGRADED:
        "A persisted service episode is ongoing. Review the supporting evidence.",
      STALE:
        "The last observation is stale. Current service health cannot be confirmed.",
      UNKNOWN:
        'Service health is unknown: current measurements, sample support, or an expected value are unavailable. Open the service evidence to check what is missing.',
    }[health];
  }
}
