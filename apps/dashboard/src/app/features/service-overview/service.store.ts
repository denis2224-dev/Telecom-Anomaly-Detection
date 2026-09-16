import { Injectable, inject, signal } from "@angular/core";
import { ServiceSummary, TelecomClient } from "../../core/api/telecom-client";

export type ServiceHealth = "NORMAL" | "DEGRADED" | "STALE" | "UNKNOWN";

@Injectable({ providedIn: "root" })
export class ServiceStore {
  private readonly api = inject(TelecomClient);
  private requestId = 0;

  readonly services = signal<ServiceSummary[]>([]);
  readonly loading = signal(true);
  readonly error = signal("");

  async load(): Promise<void> {
    const requestId = ++this.requestId;
    this.loading.set(true);
    this.error.set("");

    try {
      const services = await this.api.listServices();
      if (requestId === this.requestId) this.services.set(services);
    } catch (error) {
      if (requestId === this.requestId) {
        this.error.set(
          error instanceof Error ? error.message : "Services could not be loaded.",
        );
      }
    } finally {
      if (requestId === this.requestId) this.loading.set(false);
    }
  }

  selected(scopeId: string | null): ServiceSummary | undefined {
    return this.services().find((service) => service.scope.scopeId === scopeId);
  }

  health(service: ServiceSummary): ServiceHealth {
    if (service.freshness === "MISSING" || service.latestWindow === null) {
      return "UNKNOWN";
    }
    if (service.freshness === "STALE") return "STALE";

    const hasDegradedKpi = service.latestWindow.kpis.some((kpi) => {
      if (kpi.observed === null || kpi.baseline === null) return false;
      if (kpi.unit === "PERCENT") return kpi.observed < kpi.baseline - 1;
      if (kpi.unit === "MILLISECONDS" || kpi.unit === "SECONDS") {
        return kpi.observed > kpi.baseline * 2;
      }
      return false;
    });
    return hasDegradedKpi ? "DEGRADED" : "NORMAL";
  }

  healthExplanation(health: ServiceHealth): string {
    return {
      NORMAL: "The latest fixture observation is within its expected baseline.",
      DEGRADED:
        "The latest fixture observation is outside its expected baseline. Review the evidence.",
      STALE:
        "The last observation is stale. Current service health cannot be confirmed.",
      UNKNOWN:
        "Monitoring data is missing. Service health is unknown; no healthy value can be inferred.",
    }[health];
  }
}
