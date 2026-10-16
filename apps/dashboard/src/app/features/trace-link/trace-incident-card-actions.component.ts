import { incidentDisplayRange } from '../../shared/incident-display-range';
import { Component, computed, effect, inject, input, output, signal } from '@angular/core';
import { AcknowledgementLabelComponent, incidentReferencePrefix } from '../../shared/acknowledgement-label.component';
import { Router } from '@angular/router';
import { TelecomClient } from '../../core/api/telecom-client';
import type { components } from '../../core/api/schema';
import { TraceQueryStore, traceContextParameters, type TraceQueryContext, validTraceContext } from '../../core/state/trace-query.store';
import { SessionStore } from '../login-and-session/session.store';

type Incident = components['schemas']['TraceLinkIncidentView'];
const object = (value: unknown): Record<string, unknown> => value && typeof value === 'object' && !Array.isArray(value) ? value as Record<string, unknown> : {};

export function nativeIncidentContext(item: Incident, context: TraceQueryContext): TraceQueryContext {
  const source = object(item.latestDetection.context), scope = object(source['scope']);
  const domain = String(source['domain']).toLowerCase().replace('_', '-');
  if (!['volte', 'sms', 'roaming', 'transport', 'mobile-data'].includes(domain)) throw new Error('Incident domain unavailable.');
  if (typeof source['datasetId'] !== 'string' || !source['datasetId'] || typeof scope['id'] !== 'string' || !scope['id']) throw new Error('Recorded incident scope unavailable.');
  if (domain === 'roaming' && (typeof scope['partnerId'] !== 'string' || !['in','out'].includes(String(scope['directionId'])))) throw new Error('Recorded partner population unavailable.');
  const from = item.firstObservedAt, to = item.latestWindowEnd;
  if (!Number.isFinite(Date.parse(from)) || Date.parse(to) <= Date.parse(from)) throw new Error('Incident interval unavailable.');
  const result: TraceQueryContext = {...context, datasetId: String(source['datasetId']), domain: domain as TraceQueryContext['domain'], ...incidentDisplayRange(from,to,context),
    cityId: domain === 'transport' || domain === 'roaming' ? 'all' : String(scope['id']).toLowerCase(),
    linkDirectionId: domain === 'transport' ? String(scope['id']) : undefined,
    countryId: domain === 'roaming' ? String(scope['partnerId']).split('-')[0] : 'all',
    roamingDirection: domain === 'roaming' ? String(scope['directionId']) as 'in' | 'out' : 'both',
    partnerDirectionId: domain === 'roaming' ? `${scope['partnerId']}:${scope['directionId']}` : undefined,
    visibleFrom: undefined, visibleTo: undefined};
  if (!validTraceContext(result)) throw new Error('Recorded incident context is invalid.');
  return result;
}

@Component({
  selector: 'app-trace-incident-card-actions', imports: [AcknowledgementLabelComponent],
  template: `<div class="incident-action-bar"><ng-content select="[incident-leading]" /><button type="button" (click)="show()">Show on dashboard</button><button type="button" [disabled]="busy() || !workflow() || workflow()?.status !== 'OPEN'" (click)="acknowledge()">{{ workflow() && workflow()?.status !== 'OPEN' ? 'Acknowledged' : 'Acknowledge' }}</button><ng-content /></div>@if(acknowledgedAt()){<p role="status"><app-acknowledgement-label [incidentId]="'native:' + incident().episodeId" [prefix]="referencePrefix()" [at]="acknowledgedAt()!" /></p>}@if(error()){<p role="alert">{{error()}}</p>}`,
  styles: [`:host{display:block;min-width:0;}p{font-size:11px;overflow-wrap:anywhere;}`],
})
export class TraceIncidentCardActionsComponent {
  readonly incident = input.required<Incident>();
  readonly updated = output<void>();
  readonly workflowChanged=output<components['schemas']['TraceIncidentWorkflow']>();
  private readonly api = inject(TelecomClient);
  private readonly session = inject(SessionStore);
  private readonly query = inject(TraceQueryStore);
  private readonly router = inject(Router);
  readonly workflow = signal<components['schemas']['TraceIncidentWorkflow'] | null>(null);
  readonly referencePrefix = computed(() => incidentReferencePrefix(String(this.incident().latestDetection.context['domain'] ?? 'INCIDENT')));
  readonly acknowledgedAt = signal<string | null>(null);
  readonly busy = signal(false);
  readonly error = signal('');
  constructor() {
    effect(cleanup => {
      const item = this.incident(), revision = this.session.revision;
      const request = new AbortController(); cleanup(() => request.abort());
      this.workflow.set(null); this.acknowledgedAt.set(null);
      void this.api.traceIncidentWorkflow(item.episodeId, request.signal).then(state => {
        if (!request.signal.aborted && revision === this.session.revision) {
          this.acceptWorkflow(state);
          if(state.status !== 'OPEN') void this.loadAcknowledgement(item.episodeId,revision,request.signal);
        }
      }).catch(error => { if (!request.signal.aborted) this.error.set(error instanceof Error ? error.message : 'Workflow unavailable.'); });
    });
  }
  private acceptWorkflow(state:components['schemas']['TraceIncidentWorkflow']) {this.workflow.set(state);this.workflowChanged.emit(state);}
  private async loadAcknowledgement(id:string,revision:number,signal?:AbortSignal) {
    try {
      for(let offset=0;offset<5000;offset+=50) {
        const rows=await this.api.traceIncidentAudit(id,offset,signal);
        if(signal?.aborted||revision!==this.session.revision||id!==this.incident().episodeId)return;
        const event=rows.find(row=>row.action==='INVESTIGATING');
        if(event){this.acknowledgedAt.set(event.occurredAt);return;}
        if(rows.length<50)return;
      }
    } catch { /* Audit metadata is optional; persisted workflow remains authoritative. */ }
  }
  show() {
    try {
      const context = nativeIncidentContext(this.incident(), this.query.context());
      const evidence = this.incident().latestDetection.context['evidence'];
      const metric = Array.isArray(evidence) ? object(evidence[0])['metricId'] : undefined;
      void this.router.navigate(['/trace-link'], { queryParams: {...traceContextParameters(context), incidentId: this.incident().episodeId, metric: typeof metric === 'string' ? metric : null}, fragment: 'recorded-incident-chart' });
    } catch (error) { this.error.set(error instanceof Error ? error.message : 'Incident unavailable.'); }
  }
  async acknowledge() {
    if (this.busy() || !this.workflow() || this.workflow()?.status !== 'OPEN') return;
    const revision = this.session.revision, id = this.incident().episodeId;
    const valid = () => { if (revision !== this.session.revision || id !== this.incident().episodeId) throw new Error('Incident or session changed. Refresh.'); };
    this.busy.set(true); this.error.set('');
    try {
      let state = await this.api.traceIncidentWorkflow(id); valid();
      if (state.status !== 'OPEN') { this.acceptWorkflow(state); return; }
      const actor = this.session.actor();
      if (state.assigneeId && state.assigneeId !== actor?.analystId && !actor?.roles.some(role => role === 'ADMIN' || role === 'SUPERVISOR')) throw new Error('Only the assignee or a supervisor can acknowledge.');
      if (!state.assigneeId) {
        const analysts = await this.api.listAnalysts(); valid();
        if (!actor?.analystId || !analysts.some(row => row.id === actor.analystId && row.enabled)) throw new Error('An enabled analyst account is required.');
        state = await this.api.assignTraceIncident(id, {assigneeId: actor.analystId, version: state.version}); valid();
        this.acceptWorkflow(state);
      }
      state = await this.api.updateTraceIncident(id, {status:'INVESTIGATING',version:state.version}); valid();
      this.acceptWorkflow(state); void this.loadAcknowledgement(id,revision); this.updated.emit();
    } catch(error) { this.error.set(error instanceof Error ? error.message : 'Acknowledgement failed. Retry.'); }
    finally { this.busy.set(false); }
  }
}
