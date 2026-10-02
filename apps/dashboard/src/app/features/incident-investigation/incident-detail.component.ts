import { Component, DestroyRef, inject, signal } from '@angular/core';
import { DatePipe } from '@angular/common';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { TelecomClient, Incident } from '../../core/api/telecom-client';
import type { components } from '../../core/api/schema';
import { dataSource } from '../../core/api/data-source';
import { IncidentStream } from '../../core/state/incident-stream';
import { EvidenceTimelineComponent } from './evidence-timeline.component';
import { IncidentActionsComponent } from './incident-actions.component';

@Component({
  selector: 'app-incident-detail', imports: [DatePipe, RouterLink, EvidenceTimelineComponent, IncidentActionsComponent],
  styles: ['code { overflow-wrap: anywhere; }'],
  template: `
    <h1>Incident investigation</h1>
    @if (!loading() && !error()) {
      <button type="button" (click)="load()">Refresh incident</button>
    }
    @if (loading()) { <p role="status">Loading incident evidence…</p> }
    @if (streamError()) { <p role="status">{{ streamError() }}</p> }
    @if (error()) { <section role="alert"><h2>Evidence unavailable</h2><p>{{ error() }}</p><button (click)="load()">Retry</button></section> }
    @if (!loading() && !error() && incident(); as item) {
      <a [routerLink]="['/services', item.scopeId]">← Back to service</a>
      <section class="detail-panel">
        <h2>{{ item.scopeId }}</h2>
        <p>Technical state: <strong>{{ item.technicalState }}</strong> · Workflow state: <strong>{{ item.status }}</strong></p>
        <p>Episode: <code>{{ item.episodeId }}</code></p>
        <p>First observed {{ item.firstObservedAt | date:'dd MMM yyyy HH:mm:ss':'UTC' }} UTC · Last observed {{ item.lastObservedAt | date:'dd MMM yyyy HH:mm:ss':'UTC' }} UTC</p>
        <p>{{ fixture ? 'Synthetic preview: only the sample latest update is available.' : 'Server-recorded evidence. Times below are UTC.' }}</p>
        @if (!fixture) { <p class="muted">Synthetic telecom demo · measurements processed by the running backend.</p> }
        <p>Severity: <strong>{{ item.severity }}</strong>. This is the rule's service-impact priority, not a probability.</p>
        @if (item.technicalState === 'RECOVERED') {
          <p>The service has recovered. The analyst investigation stays open until someone resolves it.</p>
        } @else if (item.technicalState === 'UNKNOWN') {
          <p>Technical state is unknown because evidence is incomplete. Do not treat this as recovery.</p>
        } @else {
          <p>The issue is still ongoing at the latest observation.</p>
        }
      </section>
      <app-incident-actions [incident]="item" (updated)="acceptAction($event)" />
      <app-evidence-timeline [detections]="detections()" />
      <section class="detail-panel" aria-label="Investigation timeline">
        <h2>Investigation timeline</h2>
        @for (event of timeline(); track event.id) {
          <div [attr.data-audit-id]="event.id">
            <strong>{{ event.action }}</strong> · {{ event.occurredAt | date:'dd MMM yyyy HH:mm:ss':'UTC' }} UTC
            @if (event.note) { <p>{{ event.note }}</p> }
          </div>
        } @empty { <p>No investigation actions yet.</p> }
      </section>
    }
  `,
})
export class IncidentDetailComponent {
  private readonly api = inject(TelecomClient);
  private readonly stream = inject(IncidentStream);
  private closeStream?: () => void;
  private refreshTimer?: ReturnType<typeof setTimeout>;
  private refreshing = false;
  private destroyed = false;
  readonly streamError = signal('');
  readonly timeline = signal<components['schemas']['AuditEvent'][]>([]);
  private id = '';
  private generation = 0;
  readonly fixture = dataSource.fixture;
  readonly loading = signal(true);
  readonly error = signal('');
  readonly incident = signal<Incident | null>(null);
  readonly detections = signal<components['schemas']['ServiceDetection'][]>([]);
  constructor() {
    const destroy = inject(DestroyRef);
    destroy.onDestroy(() => {
      this.destroyed = true;
      this.generation++;
      clearTimeout(this.refreshTimer);
      this.closeStream?.();
    });
    inject(ActivatedRoute).paramMap.pipe(takeUntilDestroyed(destroy)).subscribe(params => {
      clearTimeout(this.refreshTimer);
      this.id = params.get('id') ?? ''; void this.load();
    });
  }
  acceptAction(item: Incident) {
    if (!this.incident() || item.version >= this.incident()!.version) this.incident.set(item);
    this.queueRefresh();
  }
  private queueRefresh() {
    if (this.destroyed) return;
    clearTimeout(this.refreshTimer);
    this.refreshTimer = setTimeout(() => {
      if (this.loading() || this.refreshing) { this.queueRefresh(); return; }
      void this.load(true);
    }, 150);
  }
  async load(background = false) {
    const generation = ++this.generation, id = this.id;
    this.refreshing = true;
    if (!background) {
      this.loading.set(true); this.error.set(''); this.incident.set(null);
      this.detections.set([]); this.timeline.set([]);
    }
    try {
      const item = await this.api.getIncident(id);
      if (generation !== this.generation) return;
      const updates: components['schemas']['ServiceDetection'][] = [];
      for (let page = 0; ; page++) {
        if (page >= 100) throw new Error('Too much evidence to load. Contact your administrator.');
        const result = await this.api.getDetections(id, page);
        if (generation !== this.generation) return;
        updates.push(...result.items);
        if (updates.length >= result.total) break;
        if (!result.items.length) throw new Error('Evidence changed while loading. Please retry.');
      }
      if (generation !== this.generation) return;
      const actions: components['schemas']['AuditEvent'][] = [];
      for (let page = 0; ; page++) {
        if (page >= 100) throw new Error('Too much investigation history to load.');
        const result = await this.api.getTimeline(id, page);
        if (generation !== this.generation) return;
        actions.push(...result.items);
        if (actions.length >= result.total) break;
        if (!result.items.length) throw new Error('Investigation history changed. Retry to refresh it.');
      }
      const current = this.incident();
      if (current && item.version < current.version) { this.queueRefresh(); return; }
      this.incident.set(item); this.detections.set(updates); this.timeline.set(actions);
      this.streamError.set('');
      if (!this.closeStream) this.closeStream = this.stream.connect(
        () => this.queueRefresh(),
        () => this.streamError.set('Live connection interrupted. Reconnecting…'));

    } catch (error) {
      if (generation === this.generation) (background ? this.streamError : this.error).set(error instanceof Error ? error.message : 'Could not load incident evidence.');
    } finally { if (generation === this.generation) { this.loading.set(false); this.refreshing = false; } }
  }
}
