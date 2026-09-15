import { Component, DestroyRef, inject, signal } from "@angular/core";
import { ActivatedRoute, RouterLink } from "@angular/router";
import { takeUntilDestroyed } from "@angular/core/rxjs-interop";
import { TelecomClient, ServiceSummary } from "../../core/api/telecom-client";

@Component({
  selector: "app-service-overview",
  imports: [RouterLink],
  templateUrl: "./service-overview.component.html",
})
export class ServiceOverviewComponent {
  private readonly api = inject(TelecomClient);
  private readonly destroyRef = inject(DestroyRef);
  readonly services = signal<ServiceSummary[]>([]);
  readonly loading = signal(true);
  readonly error = signal("");
  readonly scopeId = signal<string | null>(null);
  constructor() {
    inject(ActivatedRoute)
      .paramMap.pipe(takeUntilDestroyed())
      .subscribe((params) => this.scopeId.set(params.get("scopeId")));
    void this.load();
  }
  async load(): Promise<void> {
    this.loading.set(true);
    this.error.set("");
    try {
      const services = await this.api.listServices();
      if (!this.destroyRef.destroyed) this.services.set(services);
    } catch (error) {
      if (!this.destroyRef.destroyed)
        this.error.set(
          error instanceof Error
            ? error.message
            : "Services could not be loaded.",
        );
    } finally {
      if (!this.destroyRef.destroyed) this.loading.set(false);
    }
  }
  selected(): ServiceSummary | undefined {
    return this.services().find(
      (service) => service.scope.scopeId === this.scopeId(),
    );
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
