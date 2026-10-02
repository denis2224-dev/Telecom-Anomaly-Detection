import { Component, DestroyRef, inject, signal } from '@angular/core';
import { DatePipe } from '@angular/common';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { TelecomClient, Incident } from '../../core/api/telecom-client';
import type { components } from '../../core/api/schema';
import { dataSource } from '../../core/api/data-source';
import { EvidenceTimelineComponent } from './evidence-timeline.component';
import { IncidentActionsComponent } from './incident-actions.component';
import { LiveUpdates } from '../../core/api/live-updates';

@Component({
  selector: 'app-incident-detail', imports: [DatePipe, RouterLink, EvidenceTimelineComponent, IncidentActionsComponent],
  styles: ['code { overflow-wrap: anywhere; }'],
  template: `
    <h1>Incident investigation</h1>
    @if (loading()) { <p role="status">Loading incident evidence…</p> }
    @if (error()) { <section role="alert"><h2>Evidence unavailable</h2><p>{{ error() }}</p><button (click)="load()">Retry</button></section> }
    @if (!loading() && !error() && incident(); as item) {
      <a [routerLink]="['/services', item.scopeId]">← Back to service</a>
      <section class="detail-panel">
        <h2>{{ item.scopeId }}</h2>
        <p>Technical state: <strong>{{ item.technicalState }}</strong> · Workflow state: <strong>{{ item.status }}</strong></p>
        <p>Episode: <code>{{ item.episodeId }}</code></p>
        <p>First observed {{ item.firstObservedAt | date:'dd MMM yyyy HH:mm:ss':'UTC' }} UTC · Last observed {{ item.lastObservedAt | date:'dd MMM yyyy HH:mm:ss':'UTC' }} UTC</p>
        <p>{{ fixture ? 'Synthetic preview: sample detection history only.' : 'Server-recorded evidence. Times below are UTC.' }}</p>
        @if (!fixture) { <p class="muted">Synthetic telecom demo · measurements processed by the running backend.</p> }
        <p>Recovery describes the service. The investigation remains open until an analyst resolves it.</p>
      </section>
      <app-incident-actions [incident]="item" (updated)="incident.set($event)" />
      <app-evidence-timeline [detections]="detections()" />
    }
  `,
})
export class IncidentDetailComponent {
  private readonly api = inject(TelecomClient);
  private id = '';
  private generation = 0;
  private refreshing = false;
  readonly fixture = dataSource.fixture;
  readonly loading = signal(true);
  readonly error = signal('');
  readonly incident = signal<Incident | null>(null);
  readonly detections = signal<components['schemas']['ServiceDetection'][]>([]);
  constructor() {
    const destroy = inject(DestroyRef);
    destroy.onDestroy(() => { this.generation++; });
    inject(LiveUpdates).refresh$.pipe(takeUntilDestroyed(destroy)).subscribe(() => {
      if (!this.fixture && !this.loading() && !this.refreshing) void this.load(true);
    });
    inject(ActivatedRoute).paramMap.pipe(takeUntilDestroyed(destroy)).subscribe(params => {
      this.id = params.get('id') ?? ''; void this.load();
    });
  }
  async load(quiet = false) {
    const generation = ++this.generation, id = this.id;
    this.refreshing = true;
    if (!quiet) { this.loading.set(true); this.incident.set(null); this.detections.set([]); }
    this.error.set('');
    try {
      const item = await this.api.getIncident(id);
      if (generation !== this.generation) return;
      if (quiet && this.incident()?.latestSequence === item.latestSequence) {
        this.incident.set(item); return;
      }
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
      this.incident.set(item); this.detections.set(updates);
    } catch (error) {
      if (generation === this.generation) this.error.set(error instanceof Error ? error.message : 'Could not load incident evidence.');
    } finally { if (generation === this.generation) { this.loading.set(false); this.refreshing = false; } }
  }
}
