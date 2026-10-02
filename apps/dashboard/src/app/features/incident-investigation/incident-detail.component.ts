import { Component, DestroyRef, inject, signal } from '@angular/core';
import { DatePipe } from '@angular/common';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { TelecomClient, Incident } from '../../core/api/telecom-client';
import type { components } from '../../core/api/schema';
import { dataSource } from '../../core/api/data-source';
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
      <app-incident-actions [incident]="item" (updated)="incident.set($event)" />
      <p class="muted">
        Evidence page {{ page() + 1 }} · {{ detections().length }} shown · {{ total() }} total.
        This page is part of the timeline; the incident summary shows the current state.
      </p>
      <nav aria-label="Evidence pages">
        <button (click)="load(page() - 1)" [disabled]="page() === 0">Previous evidence</button>
        <button (click)="load(page() + 1)"
          [disabled]="(page() + 1) * pageSize >= total()">Next evidence</button>
      </nav>
      <app-evidence-timeline [detections]="detections()" />
    }
  `,
})
export class IncidentDetailComponent {
  private readonly api = inject(TelecomClient);
  private id = '';
  private generation = 0;
  private controller?: AbortController;
  readonly fixture = dataSource.fixture;
  readonly pageSize = 20;
  readonly page = signal(0);
  readonly total = signal(0);
  readonly loading = signal(true);
  readonly error = signal('');
  readonly incident = signal<Incident | null>(null);
  readonly detections = signal<components['schemas']['ServiceDetection'][]>([]);

  constructor() {
    const destroy = inject(DestroyRef);
    destroy.onDestroy(() => { ++this.generation; this.controller?.abort(); });
    inject(ActivatedRoute).paramMap.pipe(takeUntilDestroyed(destroy)).subscribe(params => {
      this.id = params.get('id') ?? '';
      this.page.set(0);
      this.total.set(0);
      void this.load();
    });
  }

  async load(page = 0): Promise<void> {
    if (page < 0) return;
    const generation = ++this.generation;
    this.controller?.abort();
    const controller = this.controller = new AbortController();
    const id = this.id;
    this.loading.set(true);
    this.error.set('');
    this.incident.set(null);
    this.detections.set([]);
    try {
      const [item, result] = await Promise.all([
        this.api.getIncident(id, controller.signal),
        this.api.getDetections(id, page, this.pageSize, controller.signal),
      ]);
      if (generation !== this.generation) return;
      if (!Number.isInteger(result.total) || result.total < 0
        || result.items.length > this.pageSize || result.items.length > result.total
        || (result.total > 0 && !result.items.length)) {
        throw new Error('This evidence page changed or exceeded its limit. Retry from the first page.');
      }
      this.incident.set(item);
      this.detections.set(result.items);
      this.page.set(page);
      this.total.set(result.total);
    } catch (error) {
      controller.abort();
      if (generation === this.generation) {
        this.error.set(error instanceof Error ? error.message : 'Could not load incident evidence.');
      }
    } finally {
      if (generation === this.generation) this.loading.set(false);
    }
  }
}
