import { Component, DestroyRef, inject, signal } from '@angular/core';
import { DatePipe } from '@angular/common';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { TelecomClient, Incident } from '../../core/api/telecom-client';
import type { components } from '../../core/api/schema';
import { dataSource } from '../../core/api/data-source';

@Component({
  selector: 'app-incident-detail', imports: [DatePipe, RouterLink],
  styles: [`.evidence-table { overflow-x: auto; } table { width:100%; border-collapse:collapse; } th, td { padding:10px; text-align:left; border-bottom:1px solid #d8e2dd; } code { overflow-wrap:anywhere; } article { margin-top:20px; }`],
  template: `
    <h1>Voice incident investigation</h1>
    @if (loading()) { <p role="status">Loading incident evidence…</p> }
    @if (error()) { <section role="alert"><h2>Evidence unavailable</h2><p>{{ error() }}</p><button (click)="load()">Retry</button></section> }
    @if (!loading() && !error() && incident(); as item) {
      <a [routerLink]="['/services', item.scopeId]">← Back to voice trend</a>
      <section class="detail-panel">
        <h2>{{ item.scopeId }}</h2>
        <p>Technical state: <strong>{{ item.technicalState }}</strong> · Workflow state: <strong>{{ item.status }}</strong></p>
        <p>Episode: <code>{{ item.episodeId }}</code></p>
        <p>First observed {{ item.firstObservedAt | date:'dd MMM yyyy HH:mm:ss':'UTC' }} UTC · Last observed {{ item.lastObservedAt | date:'dd MMM yyyy HH:mm:ss':'UTC' }} UTC</p>
        <p>{{ fixture ? 'Synthetic preview: only the sample latest update is available.' : 'Server-recorded evidence. Times below are UTC.' }}</p>
        @if (!fixture) { <p class="muted">Synthetic telecom demo · measurements processed by the running backend.</p> }
        <p>Recovery describes the service. The investigation remains open until an analyst resolves it.</p>
      </section>
      <h2>Evidence timeline</h2>
      @for (detection of detections(); track detection.detectionId) {
        <article class="detail-panel" [attr.data-detection-id]="detection.detectionId">
          <h3>Update {{ detection.sequence }} · {{ detection.phase }}</h3>
          <p>{{ detection.windowStart | date:'dd MMM yyyy HH:mm:ss':'UTC' }} – {{ detection.windowEnd | date:'HH:mm:ss':'UTC' }} UTC</p>
          <p>Server detected at {{ detection.detectedAt | date:'dd MMM yyyy HH:mm:ss':'UTC' }} UTC</p>
          <p>{{ detection.probableCause }}</p>
          <p>Confidence: {{ detection.causeConfidence }} · ML: {{ detection.mlStatus }}</p>
          @if (detection.phase === 'UNKNOWN') { <p class="notice">Evidence is incomplete. This update does not prove recovery; impact retains the last evaluated value.</p> }
          <div class="evidence-table"><table><caption>Measured KPIs for update {{ detection.sequence }}</caption>
            <thead><tr><th>KPI</th><th>Actual</th><th>Expected</th><th>Unit</th><th>Numerator</th><th>Denominator</th></tr></thead>
            <tbody>@for (kpi of detection.kpis; track kpi.name) {
              <tr [attr.data-kpi]="kpi.name"><th>{{ kpi.name }}</th><td>{{ kpi.observed ?? 'Unavailable' }}</td><td>{{ kpi.baseline ?? 'Unavailable' }}</td><td>{{ kpi.unit }}</td><td>{{ kpi.numerator ?? '—' }}</td><td>{{ kpi.denominator ?? '—' }}</td></tr>
            }</tbody>
          </table></div>
          <p>Rules: {{ detection.rulesetVersion }} · Baseline: {{ detection.baselineVersion }} · Topology: {{ detection.topologyVersion }}</p>
          <details><summary>Source evidence</summary>
            @for (evidence of detection.evidence; track $index) { <p>{{ evidence.summary }}</p><ul>@for (id of evidence.sourceEventIds; track id) { <li><code>{{ id }}</code></li> }</ul> }
          </details>
          <ul>@for (check of detection.recommendedChecks; track check) { <li>{{ check }}</li> }</ul>
        </article>
      } @empty { <p role="status">No evidence updates available.</p> }
    }
  `,
})
export class IncidentDetailComponent {
  private readonly api = inject(TelecomClient);
  private id = '';
  private generation = 0;
  readonly fixture = dataSource.fixture;
  readonly loading = signal(true);
  readonly error = signal('');
  readonly incident = signal<Incident | null>(null);
  readonly detections = signal<components['schemas']['ServiceDetection'][]>([]);
  constructor() {
    const destroy = inject(DestroyRef);
    destroy.onDestroy(() => { this.generation++; });
    inject(ActivatedRoute).paramMap.pipe(takeUntilDestroyed(destroy)).subscribe(params => {
      this.id = params.get('id') ?? ''; void this.load();
    });
  }
  async load() {
    const generation = ++this.generation, id = this.id;
    this.loading.set(true); this.error.set(''); this.incident.set(null); this.detections.set([]);
    try {
      const item = await this.api.getIncident(id);
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
    } finally { if (generation === this.generation) this.loading.set(false); }
  }
}
