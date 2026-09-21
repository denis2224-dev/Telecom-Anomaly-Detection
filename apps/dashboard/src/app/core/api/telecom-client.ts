import { HttpClient, HttpErrorResponse } from "@angular/common/http";
import { Injectable, inject } from "@angular/core";
import { firstValueFrom } from "rxjs";
import type { components, operations } from "./schema";
import { SessionStore } from "../../features/session/session.store";
import { dataSource } from "./data-source";

export type ServiceSummary = components["schemas"]["ServiceSummary"];
export type Incident = components["schemas"]["Incident"];
type IncidentQuery = operations["listIncidents"]["parameters"]["query"];
type KpiQuery = operations["getServiceKpis"]["parameters"]["query"];

export class ApiFailure extends Error {
  constructor(
    readonly status: number,
    readonly code?: string,
  ) {
    super(
      status === 401
        ? "Your session has expired. Please sign in again."
        : status === 403
          ? code === "CSRF_INVALID"
            ? "Session protection needs to be refreshed. Review and retry your action."
            : "You do not have permission to access this content."
          : status === 409
            ? "This item changed. Reload and review it before trying again."
            : status === 404
              ? "This item could not be found."
              : "The service could not be reached. Try again.",
    );
  }
}

@Injectable({ providedIn: "root" })
export class TelecomClient {
  private readonly http = inject(HttpClient);
  private readonly session = inject(SessionStore);

  async listServices(): Promise<ServiceSummary[]> {
    if (dataSource.fixture)
      return structuredClone(
        await dataSource.loadServices(),
      ) as ServiceSummary[];
    return this.request<ServiceSummary[]>("GET", "/api/services");
  }

  async listIncidents(query: IncidentQuery = {}) {
    if (dataSource.fixture) {
      const { voiceIncidents } = await dataSource.loadVoice();
      const items = voiceIncidents.filter(item => (!query.scopeId || item.scopeId === query.scopeId)
        && (!query.service || item.service === query.service)
        && (!query.status || item.status === query.status)
        && (!query.technicalState || item.technicalState === query.technicalState));
      const page = query.page ?? 0, size = query.size ?? 100;
      return { items: structuredClone(items.slice(page * size, (page + 1) * size)), total: items.length, page, size };
    }
    return this.request<components["schemas"]["IncidentPage"]>(
      "GET",
      "/api/incidents",
      undefined,
      query,
    );
  }

  getIncident(id: string) {
    return this.request<Incident>(
      "GET",
      `/api/incidents/${encodeURIComponent(id)}`,
    );
  }

  async getServiceKpis(scopeId: string, query: KpiQuery) {
    if (dataSource.fixture) {
      const { voiceWindows, voiceRange } = await dataSource.loadVoice();
      const items = voiceWindows.filter(item => item.scopeId === scopeId
        && Date.parse(item.windowStart) >= Date.parse(query.from) && Date.parse(item.windowStart) < Date.parse(query.to));
      const page = query.page ?? 0, size = query.size ?? 100;
      return { items: structuredClone(items.slice(page * size, (page + 1) * size)), total: items.length, page, size, observedAt: voiceRange.to };
    }
    return this.request<components["schemas"]["ServiceKpiPage"]>(
      "GET",
      `/api/services/${encodeURIComponent(scopeId)}/kpis`,
      undefined,
      query,
    );
  }

  listAnalysts() {
    return this.request<components["schemas"]["AnalystSummary"][]>(
      "GET",
      "/api/analysts",
      undefined,
      { enabled: true },
    );
  }

  changeStatus(id: string, body: components["schemas"]["ChangeStatusRequest"]) {
    return this.request<Incident>(
      "POST",
      `/api/incidents/${encodeURIComponent(id)}/status`,
      body,
    );
  }

  assignIncident(id: string, body: components["schemas"]["AssignmentRequest"]) {
    return this.request<Incident>(
      "POST",
      `/api/incidents/${encodeURIComponent(id)}/assignment`,
      body,
    );
  }

  private async request<T>(
    method: "GET" | "POST",
    url: string,
    body?: unknown,
    query?: object,
  ): Promise<T> {
    if (dataSource.fixture)
      throw new Error("This action is unavailable in the fixture preview.");
    if (this.session.phase() !== "authenticated") throw new ApiFailure(401);
    const actor = this.session.actor();
    if (!actor || Date.parse(actor.expiresAt) <= Date.now()) {
      this.session.expire();
      throw new ApiFailure(401);
    }
    const params: Record<string, string> = {};
    for (const [key, value] of Object.entries(query ?? {}))
      if (value !== undefined) params[key] = String(value);
    const headers: Record<string, string> = {};
    if (method === "POST") {
      const csrf = this.session.csrf();
      if (!csrf) throw new ApiFailure(403, "CSRF_INVALID");
      headers[csrf.headerName] = csrf.token;
    }
    try {
      const result = await firstValueFrom(
        this.http.request<T>(method, url, { body, params, headers }),
      );
      if (this.session.phase() !== "authenticated") throw new ApiFailure(401);
      return result;
    } catch (error) {
      if (error instanceof HttpErrorResponse) {
        if (error.status === 401) this.session.expire();
        if (error.status === 403 && error.error?.code === "CSRF_INVALID")
          this.session.csrf.set(null);
        throw new ApiFailure(error.status, error.error?.code);
      }
      throw error;
    }
  }
}
