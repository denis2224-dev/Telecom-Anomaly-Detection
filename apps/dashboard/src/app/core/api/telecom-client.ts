import { HttpClient, HttpErrorResponse, type HttpResponse } from "@angular/common/http";
import { Injectable, inject } from "@angular/core";
import { firstValueFrom, fromEvent, takeUntil, type Observable } from "rxjs";
import type { components, operations } from "./schema";
import { SessionStore } from "../../features/login-and-session/session.store";
import { dataSource } from "./data-source";
import { ApiFailure } from "./api-errors";

export type ServiceSummary = components["schemas"]["ServiceSummary"];
export type Incident = components["schemas"]["Incident"];
export type ScenarioRun = components["schemas"]["ScenarioRun"];
export type ScenarioType = ScenarioRun["scenarioType"];
export type TraceScenarioRun = components['schemas']['TraceScenarioRun'];
export type TraceScenarioStart = components['schemas']['TraceScenarioStart'];
export type TraceScenarioCatalogue = components['schemas']['TraceScenarioCatalogue'];
export type TraceScenarioScope = components['schemas']['TraceScenarioScope'];
export type TraceScenarioParameters = components['schemas']['TraceScenarioParameters'];
export type NotificationItem = components['schemas']['NotificationItem'];
export type NotificationPage = components['schemas']['NotificationPage'];
export type TraceNativeSeries = components['schemas']['TraceDomainNativeSeriesV1'];
export type TraceNativeQuery = operations['getTraceDomainNativeMeasurements']['parameters']['query'];
export type TraceMetricSeries = components['schemas']['TraceMetricSeries'];
export type TraceMetricQuery = operations['getTraceMetricSeries']['parameters']['query'] & {from:string;to:string};
export type TraceKpiDefinition = components['schemas']['TraceKpiDefinition'];
export type TraceCity = components['schemas']['TraceCity'];
export type TraceLink = components['schemas']['TraceLink'];
export type TracePartner = components['schemas']['TracePartner'];
export type StartScenarioRequest = components["schemas"]["StartScenarioRequest"];
type IncidentQuery = operations["listIncidents"]["parameters"]["query"];
type KpiQuery = operations["getServiceKpis"]["parameters"]["query"];
export type GeographyCatalogue = operations['listGeographyCities']['responses'][200]['content']['application/json'];
export type PriorityPage = components['schemas']['GeographyPriorityPage'];

@Injectable({ providedIn: "root" })
export class TelecomClient {
  private readonly http = inject(HttpClient);
  private readonly session = inject(SessionStore);

  traceDatasets(signal?: AbortSignal) {return this.request<components['schemas']['TraceDataset'][]>('GET','/api/v2/datasets',undefined,undefined,signal);}
  traceIncidents(query:operations['listTraceLinkIncidents']['parameters']['query']={},signal?:AbortSignal){return this.request<components['schemas']['TraceLinkIncidentPage']>('GET','/api/v2/trace-link/incidents',undefined,query,signal);}
  traceIncident(episode:string,signal?:AbortSignal){return this.request<components['schemas']['TraceLinkIncidentView']>('GET',`/api/v2/trace-link/incidents/${encodeURIComponent(episode)}`,undefined,undefined,signal);}
  traceIncidentByPublicId(id:string,signal?:AbortSignal){return this.request<components['schemas']['TraceLinkIncidentView']>('GET',`/api/v2/trace-link/incidents/by-id/${encodeURIComponent(id)}`,undefined,undefined,signal);}
  traceIncidentEvidence(episode:string,offset=0,signal?:AbortSignal){return this.request<components['schemas']['TraceLinkDetectionEvidencePage']>('GET',`/api/v2/trace-link/incidents/${encodeURIComponent(episode)}/evidence`,undefined,{limit:50,offset},signal);}
  traceIncidentWorkflow(episode:string,signal?:AbortSignal){return this.request<components['schemas']['TraceIncidentWorkflow']>('GET',`/api/v2/trace-link/incidents/${encodeURIComponent(episode)}/workflow`,undefined,undefined,signal);}
  traceIncidentAudit(episode:string,offset=0,signal?:AbortSignal){return this.request<components['schemas']['TraceIncidentAudit'][]>('GET',`/api/v2/trace-link/incidents/${encodeURIComponent(episode)}/audit`,undefined,{limit:50,offset},signal);}
  assignTraceIncident(episode:string,body:components['schemas']['TraceIncidentAssignment'],signal?:AbortSignal){return this.request<components['schemas']['TraceIncidentWorkflow']>('POST',`/api/v2/trace-link/incidents/${encodeURIComponent(episode)}/assignment`,body,undefined,signal);}
  updateTraceIncident(episode:string,body:components['schemas']['TraceIncidentStatus'],signal?:AbortSignal){return this.request<components['schemas']['TraceIncidentWorkflow']>('POST',`/api/v2/trace-link/incidents/${encodeURIComponent(episode)}/status`,body,undefined,signal);}
  commentTraceIncident(episode:string,body:components['schemas']['TraceIncidentComment'],signal?:AbortSignal){return this.request<components['schemas']['TraceIncidentWorkflow']>('POST',`/api/v2/trace-link/incidents/${encodeURIComponent(episode)}/comments`,body,undefined,signal);}
  listNotifications(page = 0, signal?: AbortSignal) {
    return this.request<NotificationPage>('GET', '/api/notifications', undefined, { page, size: 20 }, signal);
  }
  markNotificationRead(id: string, signal?: AbortSignal) {
    return this.request<void>('POST', `/api/notifications/${encodeURIComponent(id)}/read`, undefined, undefined, signal);
  }
  notificationPushConfig(signal?: AbortSignal) {
    return this.request<components['schemas']['PushConfig']>('GET', '/api/notifications/push/config', undefined, undefined, signal);
  }
  savePushSubscription(body: components['schemas']['PushSubscriptionInput'], signal?: AbortSignal) {
    return this.request<HttpResponse<void>>('POST', '/api/notifications/push-subscriptions', body, undefined, signal, 'response');
  }
  deletePushSubscription(id: string, signal?: AbortSignal) {
    return this.request<void>('DELETE', `/api/notifications/push-subscriptions/${encodeURIComponent(id)}`, undefined, undefined, signal);
  }
  traceCities(signal?: AbortSignal) {
    return this.request<components['schemas']['TraceCityList']>('GET','/api/v2/inventory/cities',undefined,undefined,signal);
  }
  traceLinks(signal?: AbortSignal) {
    return this.request<components['schemas']['TraceLinkList']>('GET','/api/v2/inventory/links',undefined,undefined,signal);
  }
  tracePartners(signal?: AbortSignal) {
    return this.request<components['schemas']['TracePartnerList']>('GET','/api/v2/inventory/partners',undefined,undefined,signal);
  }
  traceDefinitions(signal?: AbortSignal) {
    return this.request<TraceKpiDefinition[]>('GET','/api/v2/kpi-definitions',undefined,undefined,signal);
  }
  traceNativeSeries(query: TraceNativeQuery, signal?: AbortSignal) {
    return this.request<TraceNativeSeries>('GET','/api/v2/trace-domain-measurements',undefined,query,signal);
  }
  traceMetricSeries(query: TraceMetricQuery, signal?: AbortSignal) {
    return this.request<TraceMetricSeries>('GET','/api/v2/metrics/series',undefined,query,signal);
  }
  traceMetricCsv(query: TraceMetricQuery, signal?: AbortSignal) {
    return this.request<string>('GET','/api/v2/exports/metrics.csv',undefined,query,signal,'text');
  }

  listGeographyCities(signal?: AbortSignal) {
    return this.request<GeographyCatalogue>('GET', '/api/geography/cities', undefined, undefined, signal);
  }

  getGeographyCity(cityId: string, signal?: AbortSignal) {
    return this.request<components['schemas']['GeographyCityDetail']>('GET',
      `/api/geography/cities/${encodeURIComponent(cityId)}`, undefined, undefined, signal);
  }

  getGeographyCityKpis(cityId: string, query: operations['getGeographyCityKpis']['parameters']['query'], signal?: AbortSignal) {
    return this.request<components['schemas']['GeographyKpiPage']>('GET',
      `/api/geography/cities/${encodeURIComponent(cityId)}/kpis`, undefined, query, signal);
  }

  getGeographyTopology(cityId: string, query: operations['getGeographyTopology']['parameters']['query'], signal?: AbortSignal) {
    return this.request<components['schemas']['GeographyTopologyPage']>('GET',
      `/api/geography/cities/${encodeURIComponent(cityId)}/topology`, undefined, query, signal);
  }

  getOperationalPriority(query: operations['listOperationalPriority']['parameters']['query'] = {}, signal?: AbortSignal) {
    return this.request<PriorityPage>('GET', '/api/operations/priority', undefined, query, signal);
  }

  getSmsShadow(scopeId: string, query: KpiQuery, signal?: AbortSignal) {
    return this.request<components['schemas']['MlShadowPage']>('GET',
      `/api/services/${encodeURIComponent(scopeId)}/ml-shadow`, undefined, query, signal);
  }

  getIncidentSmsShadow(id: string, page = 0, signal?: AbortSignal) {
    return this.request<components['schemas']['MlShadowPage']>('GET',
      `/api/incidents/${encodeURIComponent(id)}/ml-shadow`, undefined, { page, size: 20 }, signal);
  }

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

  traceScenarioCatalogue(signal?: AbortSignal) {
    return this.request<TraceScenarioCatalogue>('GET', '/api/trace-link/v1/scenarios/catalogue', undefined, undefined, signal);
  }
  traceScenarioHistory(signal?: AbortSignal) {
    return this.request<TraceScenarioRun[]>('GET', '/api/trace-link/v1/scenarios/runs', undefined, undefined, signal);
  }
  startTraceScenario(body: TraceScenarioStart) {
    return this.request<TraceScenarioRun>('POST', '/api/trace-link/v1/scenarios/runs', body);
  }
  traceScenarioRun(id: string) {
    return this.request<TraceScenarioRun>('GET', `/api/trace-link/v1/scenarios/runs/${encodeURIComponent(id)}`);
  }
  stopTraceScenario(id: string) {
    return this.request<TraceScenarioRun>('POST', `/api/trace-link/v1/scenarios/runs/${encodeURIComponent(id)}/stop`);
  }
  reconcileTraceScenario(id: string) {
    return this.request<TraceScenarioRun>('POST', `/api/trace-link/v1/scenarios/runs/${encodeURIComponent(id)}/reconcile`);
  }

    private async request<T>(
    method: 'GET' | 'POST' | 'DELETE',
    url: string,
    body?: unknown,
    query?: object,
    signal?: AbortSignal,
    responseType: 'json' | 'text' | 'response' = 'json',
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
      const response = (responseType === 'response'
        ? this.http.request(method, url, {body, params, observe: 'response'})
        : responseType === 'text'
        ? this.http.request(method,url,{body,params,responseType:'text'})
        : this.http.request<T>(method, url, { body, params })) as Observable<T>;
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
