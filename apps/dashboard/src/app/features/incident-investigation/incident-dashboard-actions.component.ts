import { Component, Injectable, computed, effect, inject, input, output, signal } from '@angular/core';
import { DatePipe } from '@angular/common';
import { Router } from '@angular/router';
import { TelecomClient, type Incident } from '../../core/api/telecom-client';
import { incidentActionMessage } from '../../core/api/api-errors';
import { dataSource } from '../../core/api/data-source';
import { SessionStore } from '../login-and-session/session.store';

export function incidentDashboardParameters(item: Incident) {
  const start = Date.parse(item.firstObservedAt), end = Date.parse(item.lastObservedAt);
  return { incidentId: item.id, service: item.service, scopeId: item.scopeId,
    city: item.location?.cityId ?? null, region: item.scopeId,
    from: new Date(start).toISOString(), to: new Date(Math.max(start + 60_000, end + 60_000)).toISOString() };
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
  selector: 'app-incident-dashboard-actions', imports: [DatePipe],
  template: `<div class="incident-card-actions"><button type="button" (click)="show()">Show on dashboard</button><button type="button" [disabled]="busy() || acknowledged() || fixture" (click)="acknowledge()">{{ acknowledged() ? 'Acknowledged' : busy() ? 'Acknowledging…' : 'Acknowledge' }}</button></div>
    @if (acknowledged()) { <p class="acknowledgement" role="status">Acknowledged@if (audit(); as event) { · {{ event.actorId }} · {{ event.occurredAt | date:'dd MMM HH:mm':'UTC' }} UTC } @else { · audit details unavailable }</p> }
    @if (error()) { <p role="alert">{{ error() }}</p> }`,
  styles: [`.incident-card-actions { display:flex; flex-wrap:wrap; gap:8px; margin-top:8px; } button { min-height:36px; font-size:13px; padding:7px 12px; } p { font-size:12px; margin:6px 0 0; overflow-wrap:anywhere; } .acknowledgement { color:var(--text-muted); }`],
})
export class IncidentDashboardActionsComponent {
  readonly incident = input.required<Incident>();
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
    void this.router.navigate(['/dashboard'], { queryParams: incidentDashboardParameters(this.item()), fragment: this.item().service === 'SMS' ? 'sms-panel-title' : 'volte-panel-title' });
  }
  async acknowledge() {
    if (this.fixture || this.busy() || this.acknowledged()) return;
    this.busy.set(true); this.error.set('');
    try { this.updated.emit(await this.acknowledgements.acknowledge(this.item().id)); }
    catch (error) { this.error.set(incidentActionMessage(error)); }
    finally { this.busy.set(false); }
  }
}
