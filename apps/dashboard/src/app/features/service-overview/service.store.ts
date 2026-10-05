import { Injectable, inject, signal } from "@angular/core";
import { ServiceSummary, TelecomClient } from "../../core/api/telecom-client";
import type { Incident } from '../../core/api/telecom-client';
import { mergeIncidentPage } from '../../core/state/incident-stream';
import { allPages, metricValue, serviceHealth } from '../service-kpi-history/assurance-model';

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

  async load(quiet = false): Promise<void> {
    const requestId = ++this.requestId;
    this.controller?.abort();
    const controller = this.controller = new AbortController();
    if (!quiet) this.loading.set(true);
    this.error.set("");

    try {
      const services = await this.api.listServices(controller.signal);
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

  health(service: ServiceSummary): ServiceHealth {
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
  invalidate() { this.requestId++; this.controller?.abort(); this.controller = undefined; }

  healthExplanation(health: ServiceHealth): string {
    return {
      NORMAL: "Complete service telemetry; no persisted ongoing anomaly. Compare actual values with the contextual baseline.",
      DEGRADED:
        "A persisted service episode is ongoing. Review the supporting evidence.",
      STALE:
        "The last observation is stale. Current service health cannot be confirmed.",
      UNKNOWN:
        "Monitoring data is missing. Service health is unknown; no healthy value can be inferred.",
    }[health];
  }
}
