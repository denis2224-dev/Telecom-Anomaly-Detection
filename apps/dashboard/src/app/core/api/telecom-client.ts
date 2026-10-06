import { HttpClient, HttpErrorResponse } from "@angular/common/http";
import { Injectable, inject } from "@angular/core";
import { firstValueFrom, fromEvent, takeUntil } from "rxjs";
import type { components, operations } from "./schema";
import { SessionStore } from "../../features/login-and-session/session.store";
import { dataSource } from "./data-source";
import { ApiFailure } from "./api-errors";

export type ServiceSummary = components["schemas"]["ServiceSummary"];
export type Incident = components["schemas"]["Incident"];
export type ScenarioRun = components["schemas"]["ScenarioRun"];
export type ScenarioType = ScenarioRun["scenarioType"];
export type StartScenarioRequest = components["schemas"]["StartScenarioRequest"];
type IncidentQuery = operations["listIncidents"]["parameters"]["query"];
type KpiQuery = operations["getServiceKpis"]["parameters"]["query"];

@Injectable({ providedIn: "root" })
export class TelecomClient {
  private readonly http = inject(HttpClient);
  private readonly session = inject(SessionStore);

  async listServices(signal?: AbortSignal): Promise<ServiceSummary[]> {
    if (dataSource.fixture)
      return structuredClone(
        await dataSource.loadServices(),
      ) as ServiceSummary[];
    return this.request<ServiceSummary[]>("GET", "/api/services", undefined, undefined, signal);
  }

  async listIncidents(query: IncidentQuery = {}, signal?: AbortSignal) {
    if (dataSource.fixture) {
      const { voiceIncidents } = await dataSource.loadVoice();
      signal?.throwIfAborted();
      const { smsIncidents } = await dataSource.loadSms();
      const { cityIncidents } = await dataSource.loadConnectedDashboard();
      signal?.throwIfAborted();
      const items = [...voiceIncidents, ...smsIncidents, ...cityIncidents].filter(item => (!query.scopeId || item.scopeId === query.scopeId)
        && (!query.service || item.service === query.service)
        && (!query.status || item.status === query.status)
        && (!query.technicalState || item.technicalState === query.technicalState))
        .sort((a, b) => Date.parse(b.detectedAt) - Date.parse(a.detectedAt) || b.id.localeCompare(a.id));
      const page = query.page ?? 0, size = query.size ?? 100;
      return { items: structuredClone(items.slice(page * size, (page + 1) * size)), total: items.length, page, size };
    }
    return this.request<components["schemas"]["IncidentPage"]>(
      "GET",
      "/api/incidents",
      undefined,
      query,
      signal,
    );
  }

  async getIncident(id: string, signal?: AbortSignal) {
    if (dataSource.fixture) {
      signal?.throwIfAborted();
      const { smsIncidents } = await dataSource.loadSms();
      const { cityIncidents } = await dataSource.loadConnectedDashboard();
      signal?.throwIfAborted();
      const incident = [...(await dataSource.loadVoice()).voiceIncidents, ...smsIncidents, ...cityIncidents].find(item => item.id === id);
      if (!incident) throw new ApiFailure(404);
      return structuredClone(incident);
    }
    return this.request<Incident>(
      "GET",
      `/api/incidents/${encodeURIComponent(id)}`,
      undefined,
      undefined,
      signal,
    );
  }

  async getDetections(id: string, page = 0, size = 20, signal?: AbortSignal) {
    if (dataSource.fixture) {
      const incident = await this.getIncident(id, signal);
      const items = incident.scopeId === 'SMS-MD-ROUTE-A' ? (await dataSource.loadSms()).smsDetections : [incident.latestDetection];
      return { items: items.slice(page * size, (page + 1) * size), total: items.length, page, size };
    }
    return this.request<components['schemas']['DetectionPage']>('GET',
      `/api/incidents/${encodeURIComponent(id)}/detections`, undefined, { page, size }, signal);
  }

  async getServiceKpis(scopeId: string, query: KpiQuery, signal?: AbortSignal) {
    if (dataSource.fixture) {
      const { voiceWindows, voiceRange } = await dataSource.loadVoice();
      const { smsWindows, smsRange } = await dataSource.loadSms();
      const { cityWindows } = await dataSource.loadConnectedDashboard();
      signal?.throwIfAborted();
      const items = [...voiceWindows, ...smsWindows, ...cityWindows].filter(item => item.scopeId === scopeId
        && Date.parse(item.windowStart) >= Date.parse(query.from) && Date.parse(item.windowStart) < Date.parse(query.to));
      const page = query.page ?? 0, size = query.size ?? 100;
      return { items: structuredClone(items.slice(page * size, (page + 1) * size)), total: items.length, page, size, observedAt: scopeId === 'SMS-MD-ROUTE-A' ? smsRange.to : voiceRange.to };
    }
    return this.request<components["schemas"]["ServiceKpiPage"]>(
      "GET",
      `/api/services/${encodeURIComponent(scopeId)}/kpis`,
      undefined,
      query,
      signal,
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

  getTimeline(id: string, page = 0) {
    if (dataSource.fixture) return Promise.resolve({ items: [], total: 0, page, size: 100 });
    return this.request<components['schemas']['AuditPage']>('GET',
      `/api/incidents/${encodeURIComponent(id)}/timeline`, undefined, { page, size: 100 });
  }

  commentIncident(
    id: string,
    body: components['schemas']['CommentRequest'],
  ): Promise<Incident> {
    return this.request<Incident>(
      'POST',
      `/api/incidents/${encodeURIComponent(id)}/comments`,
      body,
    );
  }

  startScenario(
  type: ScenarioType,
  body: StartScenarioRequest,
): Promise<ScenarioRun> {
  return this.request<ScenarioRun>(
    "POST",
    `/api/simulator/scenarios/${encodeURIComponent(type)}`,
    body,
  );
}

getScenarioRun(runId: string): Promise<ScenarioRun> {
  return this.request<ScenarioRun>(
    "GET",
    `/api/simulator/runs/${encodeURIComponent(runId)}`,
  );
}

stopScenario(runId: string): Promise<ScenarioRun> {
  return this.request<ScenarioRun>(
    "POST",
    `/api/simulator/runs/${encodeURIComponent(runId)}/stop`,
  );
}

    private async request<T>(
    method: 'GET' | 'POST',
    url: string,
    body?: unknown,
    query?: object,
    signal?: AbortSignal,
  ): Promise<T> {
    if (dataSource.fixture) {
      throw new Error(
        'This action is unavailable in the fixture preview.',
      );
    }

    signal?.throwIfAborted();
    const revision = this.session.revision;
    const params: Record<string, string> = {};

    for (const [key, value] of Object.entries(query ?? {})) {
      if (value !== undefined) params[key] = String(value);
    }

    try {
      const response = this.http.request<T>(method, url, { body, params });
      const result = await firstValueFrom(signal
        ? response.pipe(takeUntil(fromEvent(signal, 'abort')))
        : response);

      if (
        this.session.revision !== revision
        || this.session.phase() !== 'authenticated'
      ) {
        throw new ApiFailure(401);
      }

      return result;
    } catch (error) {
      if (error instanceof ApiFailure) throw error;

      if (
        this.session.revision !== revision
        || this.session.phase() !== 'authenticated'
      ) {
        throw new ApiFailure(401);
      }

      if (signal?.aborted) throw new DOMException('Request cancelled', 'AbortError');

      if (error instanceof HttpErrorResponse) {
        throw new ApiFailure(
          error.status,
          error.error?.code,
        );
      }

      throw error;
    }
  }
}
