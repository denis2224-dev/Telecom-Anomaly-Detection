import { incidentDisplayRange } from '../../shared/incident-display-range';
import { Component, Injectable, computed, effect, inject, input, output, signal } from '@angular/core';
import { AcknowledgementLabelComponent, incidentReferencePrefix } from '../../shared/acknowledgement-label.component';
import { Router } from '@angular/router';
import { TelecomClient, type Incident } from '../../core/api/telecom-client';
import { incidentActionMessage } from '../../core/api/api-errors';
import { dataSource } from '../../core/api/data-source';
import { SessionStore } from '../login-and-session/session.store';

export function incidentDashboardParameters(item: Incident, range?: {from:string;to:string} | null) {
  const start = Date.parse(item.firstObservedAt), end = Math.max(Date.parse(item.lastObservedAt),Date.parse(item.latestDetection.windowEnd));
  return { incidentId: item.id, service: item.service, scopeId: item.scopeId,
    city: item.location?.cityId ?? null, region: item.scopeId,
    ...incidentDisplayRange(new Date(start).toISOString(), new Date(Math.max(start + 60_000, end)).toISOString(), range) };
}

@Injectable({ providedIn: 'root' })
export class IncidentAcknowledgements {
  readonly revision = signal(0);
  readonly saved = signal(new Map<string, Incident>());
  private readonly api = inject(TelecomClient);
  private readonly session = inject(SessionStore);
  private readonly pending = new Map<string, Promise<Incident>>();
  constructor() { this.session.ended$?.subscribe(() => { this.saved.set(new Map()); this.pending.clear(); }); }
  current(item: Incident): Incident { const saved = this.saved().get(item.id); return saved && saved.version >= item.version ? saved : item; }
  publish(item: Incident) {
    this.saved.update(previous => {
      const rows = new Map(previous);
      if ((rows.get(item.id)?.version ?? -1) <= item.version) rows.set(item.id, item);
      if (rows.size > 500) rows.delete(rows.keys().next().value!);
      return rows;
    });
    if (item.status !== 'OPEN') this.revision.update(value => value + 1);
  }
  acknowledge(id: string): Promise<Incident> {
    const pending = this.pending.get(id);
    if (pending) return pending;
    const revision = this.session.revision;
    const currentSession = () => { if (revision !== this.session.revision) throw new Error('Session changed. Sign in again.'); };
    const write = async () => {
      let item = await this.api.getIncident(id);
      currentSession();
      if (item.status !== 'OPEN') { this.publish(item); return item; }
      const actor = this.session.actor();
      const privileged = actor?.roles.some(role => role === 'ADMIN' || role === 'SUPERVISOR');
      if (item.assigneeId && item.assigneeId !== actor?.analystId && !privileged)
        throw new Error('Only the assignee or a supervisor can acknowledge this incident.');
      if (!item.assigneeId) {
        const analysts = await this.api.listAnalysts();
        currentSession();
        if (!actor?.analystId || !analysts.some(row => row.enabled && row.id === actor.analystId))
          throw new Error('An enabled analyst account is required.');
        item = await this.api.assignIncident(id, { analystId: actor.analystId, version: item.version });
        currentSession();
        this.publish(item);
      }
      item = await this.api.changeStatus(id, { status: 'INVESTIGATING', version: item.version });
      currentSession();
      this.publish(item);
      return item;
    };
    const request = write().finally(() => { if (this.pending.get(id) === request) this.pending.delete(id); });
    this.pending.set(id, request);
    return request;
  }
}

@Component({
  selector: 'app-incident-dashboard-actions', imports: [AcknowledgementLabelComponent],
  template: `<div class="incident-action-bar"><ng-content select="[incident-leading]" /><button type="button" (click)="show()">Show on dashboard</button><button type="button" [disabled]="busy() || acknowledged() || fixture" (click)="acknowledge()">{{ acknowledged() ? 'Acknowledged' : busy() ? 'Acknowledging…' : 'Acknowledge' }}</button><ng-content /></div>
    @if (acknowledged() && !compact()) { <p class="acknowledgement" role="status">@if (audit(); as event) { <app-acknowledgement-label [incidentId]="'legacy:' + item().id" [prefix]="referencePrefix()" [at]="event.occurredAt" /> } @else { Acknowledged · audit details unavailable }</p> }
    @if (error()) { <p role="alert">{{ error() }}</p> }`,
  styles: [':host{display:block;min-width:0;}p{font-size:11px;margin:6px 0 0;overflow-wrap:anywhere;}.acknowledgement{color:var(--text-muted);}'],
})
export class IncidentDashboardActionsComponent {
  readonly incident = input.required<Incident>();
  readonly compact = input(false);
  readonly dashboardRange = input<{from:string;to:string} | null>(null);
  readonly updated = output<Incident>();
  readonly navigated = output<void>();
  readonly acknowledgements = inject(IncidentAcknowledgements);
  private readonly api = inject(TelecomClient);
  private readonly router = inject(Router);
  private readonly session = inject(SessionStore);
  readonly fixture = dataSource.fixture;
  readonly busy = signal(false);
  readonly error = signal('');
  readonly item = computed(() => {
    const original = this.incident(), saved = this.acknowledgements.saved().get(original.id);
    return saved && saved.version >= original.version ? saved : original;
  });
  readonly acknowledged = computed(() => this.item().status !== 'OPEN');
  readonly referencePrefix = computed(() => incidentReferencePrefix(this.item().service));
  readonly audit = signal<{ actorId: string | null; occurredAt: string } | null>(null);
  constructor() {
    effect(cleanup => {
      const item = this.item(), revision = this.session.revision;
      let active = true;
      cleanup(() => { active = false; });
      this.audit.set(null);
      if (!this.fixture && item.status !== 'OPEN') void (async () => {
        for (let page = 0; page < 100; page++) {
          const result = await this.api.getTimeline(item.id, page);
          if (!active || revision !== this.session.revision) return;
          const event = result.items.find(row => row.actorKind === 'ANALYST' && row.after?.['status'] === 'INVESTIGATING');
          if (event) { this.audit.set(event); return; }
          if (!result.items.length || (page + 1) * result.size >= result.total) return;
        }
      })().catch(() => { /* Keep the persisted state visible when audit detail is unavailable. */ });
    });
  }
  show() {
    this.navigated.emit();
    const params=this.router.parseUrl(this.router.url).queryParams;
    const range=this.dashboardRange() ?? (params['from'] && params['to'] ? {from:params['from'],to:params['to']} : null);
    void this.router.navigate(['/services', this.item().scopeId], { queryParams: {...incidentDashboardParameters(this.item(),range), follow:'fixed'}, fragment:'service-incident-chart' });
  }
  async acknowledge() {
    if (this.fixture || this.busy() || this.acknowledged()) return;
    this.busy.set(true); this.error.set('');
    try { this.updated.emit(await this.acknowledgements.acknowledge(this.item().id)); }
    catch (error) { this.error.set(incidentActionMessage(error)); }
    finally { this.busy.set(false); }
  }
}
