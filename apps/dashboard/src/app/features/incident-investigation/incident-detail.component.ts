import { IncidentAssistComponent } from '../analyst-support/incident-assist.component';
import { legacyAnalystIncident } from '../analyst-support/analyst-support.model';
import { IncidentDashboardActionsComponent } from './incident-dashboard-actions.component';
import { ApiFailure } from '../../core/api/api-errors';
import { SmsShadowComponent } from '../../shared/sms-shadow.component';
import { Component, DestroyRef, computed, inject, signal } from '@angular/core';
import { StatusBannerComponent } from '../../shared/status-banner.component';
import { IconComponent } from '../../shared/icon.component';
import { DatePipe } from '@angular/common';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { TelecomClient, Incident } from '../../core/api/telecom-client';
import type { components } from '../../core/api/schema';
import { dataSource } from '../../core/api/data-source';
import { IncidentStream } from '../../core/state/incident-stream';
import { EvidenceTimelineComponent } from './evidence-timeline.component';
import { IncidentActionsComponent } from './incident-actions.component';
import { deviation, metricLabel, primaryMetric, supportedValue, workflowLabel, probableCause } from '../../shared/metric-presentation';

@Component({
  styles: [`.incident-at-a-glance { display:grid; grid-template-columns:minmax(0,1fr); gap:8px; } .incident-at-a-glance > span { width:100%; }`],
  selector: 'app-incident-detail', imports: [IncidentAssistComponent,IncidentDashboardActionsComponent, SmsShadowComponent, StatusBannerComponent, DatePipe, RouterLink, EvidenceTimelineComponent, IncidentActionsComponent, IconComponent],
  template: `
    <div class="page-heading"><div class="heading-copy"><p class="eyebrow">Analyst workspace</p><h1>Incident investigation</h1></div>
    @if (!loading() && !error()) {
      <button type="button" class="ghost" (click)="load(page(), true)"><app-icon name="refresh" />Refresh incident</button>
    }
    </div>
    @if (connectionInterrupted()) {
      <app-status-banner [message]="connectionInterrupted()" [busy]="loading()" (retry)="load()" />
    }
    @if (loading()) { <section class="state-panel skeleton-panel" role="status">Loading incident evidence…<div class="skeleton"></div><div class="skeleton chart"></div></section> }
    @if (streamError()) { <p role="status">{{ streamError() }}</p> }
    @if (error()) { <section role="alert"><h2>Evidence unavailable</h2><p>{{ error() }}</p><button (click)="load()">Retry</button></section> }
    @if (!loading() && !error() && incident(); as item) {
      <a class="back-link" [routerLink]="['/services', item.scopeId]"><app-icon name="left" />Back to service</a>
      <dialog #detailsDrawer class="workflow-drawer" aria-labelledby="details-title"
        (click)="$event.target === detailsDrawer && detailsDrawer.close()">
        <div class="drawer-heading"><h2 id="details-title" tabindex="-1" autofocus>Details &amp; workflow</h2><button type="button" class="drawer-close primary" aria-label="Close details and workflow" (click)="detailsDrawer.close()">Close</button></div>
        <section class="detail-panel incident-summary">
          <h2>{{ item.service === 'VOLTE' ? 'VoLTE setup' : 'SMS delivery' }}</h2>
          <div class="badge-row"><span class="sr-only">Technical state: </span><span class="badge" [attr.data-state]="item.technicalState">{{ item.technicalState }}</span><span class="sr-only">Workflow state: </span><span class="badge" [attr.data-state]="item.status">{{ item.status }}</span><span class="badge" [attr.data-state]="item.severity">{{ item.severity }}</span></div>
          <details class="evidence-disclosure"><summary>Troubleshooting</summary><p>Incident: <code>{{ item.id }}</code></p><p>Episode: <code>{{ item.episodeId }}</code></p><p>Scope: <code>{{ item.scopeId }}</code></p></details>
          <p>First observed {{ item.firstObservedAt | date:'dd MMM yyyy HH:mm:ss':'UTC' }} UTC · Last observed {{ item.lastObservedAt | date:'dd MMM yyyy HH:mm:ss':'UTC' }} UTC</p>
          <p>{{ fixture ? 'Sample data · UTC' : 'Synthetic evidence · UTC' }}</p>


          @if (item.technicalState === 'RECOVERED') {
            <p>Service recovered. {{ workflowState(item) }}</p>
          } @else if (item.technicalState === 'UNKNOWN') {
            <p>Incomplete evidence · recovery unconfirmed.</p>
          } @else {
            <p>Ongoing.</p>
          }
        </section>
        <app-incident-actions #workflow [incident]="item" (updated)="acceptAction($event)" />
        <section class="detail-panel" aria-label="Investigation timeline">
          <h2>Investigation timeline</h2>
          @for (event of timeline(); track event.id) {
            <div [attr.data-audit-id]="event.id">
              <strong>{{ event.action }}</strong> · {{ event.occurredAt | date:'dd MMM yyyy HH:mm:ss':'UTC' }} UTC
              @if (event.note) { <p>{{ event.note }}</p> }
            </div>
          } @empty { <p>No actions yet.</p> }
        </section>
      </dialog>
      <app-incident-dashboard-actions [incident]="item" (updated)="acceptAction($event)"><button class="incident-evidence-action" type="button" (click)="detailsDrawer.showModal()">Details &amp; workflow</button></app-incident-dashboard-actions>
      <header class="incident-summary-bar">
        <div class="incident-summary-title"><p class="eyebrow">Incident episode</p><h2>{{ item.service === 'VOLTE' ? 'VoLTE setup' : 'SMS delivery' }}</h2></div>
        <div class="incident-at-a-glance">
          <span><small>Severity</small><strong class="badge" [attr.data-state]="item.severity">{{ item.severity }}</strong></span>
          <span><small>Technical state</small><strong class="badge" [attr.data-state]="item.technicalState">{{ item.technicalState }}</strong></span>
          <span><small>Analyst workflow</small><strong>{{ workflowState(item) }}</strong></span>
          <span><small>Latest KPI deviation</small><strong class="mono">{{ latestDeviation() }}</strong></span>
        </div>
      </header>
      <app-incident-assist [incident]="analystItem()!" />
      <div class="evidence-column">
      <p class="muted">
        Evidence page {{ page() + 1 }} · {{ detections().length }} shown · {{ total() }} total.

      </p>
      <nav class="pagination" aria-label="Evidence pages">
        <button (click)="load(page() - 1)" [disabled]="page() === 0"><app-icon name="left" />Previous evidence</button><span>{{ page() + 1 }} / {{ Math.max(1, Math.ceil(total() / pageSize)) }}</span>
        <button (click)="load(page() + 1)"
          [disabled]="(page() + 1) * pageSize >= total()">Next evidence<app-icon name="right" /></button>
      </nav>
      <app-evidence-timeline [detections]="detections()" />
      @if (item.presentation; as presentation) {
        <details class="evidence-disclosure"><summary>Current impact and cause</summary>
          <p>{{ presentation.impactState }} · Impact source window: {{ presentation.impactWindowEnd ? (presentation.impactWindowEnd | date:'dd MMM HH:mm:ss':'UTC') + ' UTC' : 'Unavailable' }}</p>
          @if (item.technicalState === 'UNKNOWN') { <p>Comparison unavailable · retained impact is historical.</p> }
          @if (item.location; as location) {
            <p>Opening city: {{ location.cityId ?? 'Unavailable (' + (location.nullReason ?? 'unallocated') + ')' }} · Captured catalogue: {{ location.catalogueVersion ?? 'Unavailable' }} · Topology: {{ location.topologyVersion ?? 'Unavailable' }}</p>
            <p>Captured path: {{ location.containmentPath.join(' → ') || 'Unavailable' }} · Dependencies: {{ location.dependencyNodeIds.join(', ') || 'Unavailable' }}</p>
          }
          @if (presentation.currentImpact ?? presentation.retainedImpact; as impact) {
            <p>{{ presentation.retainedImpact && !presentation.currentImpact ? 'Retained estimate, not current impact' : 'Latest evaluated impact' }}:
              @if (item.service === 'VOLTE') { {{ impact.extraFailedAttempts }} extra failed attempts }
              @else { {{ impact.affectedDeliveredMessages }} affected delivered messages · {{ impact.pendingMessages }} pending messages }
              </p>
          } @else { <p>Impact: Unavailable</p> }
          <p>{{ cause(item.latestDetection) }} · Confidence: {{ presentation.causeConfidence }}</p>
        </details>
      }
      @if (!fixture && item.service === 'SMS') { <app-sms-shadow [scopeId]="item.scopeId" [incidentId]="item.id" [revision]="item.updatedAt" /> }
      </div>
    }
  `,
})
export class IncidentDetailComponent {
  readonly Math = Math;
  readonly workflowState = workflowLabel;
  readonly cause = probableCause;
  private readonly api = inject(TelecomClient);
  private readonly stream = inject(IncidentStream);
  private closeStream?: () => void;
  private refreshTimer?: ReturnType<typeof setTimeout>;
  private refreshing = false;
  readonly streamError = signal('');
  readonly connectionInterrupted = signal('');
  readonly timeline = signal<components['schemas']['AuditEvent'][]>([]);
  private id = '';
  private generation = 0;
  private controller?: AbortController;
  private readonly nativeRouter=inject(Router);
  readonly fixture = dataSource.fixture;
  readonly pageSize = 20;
  readonly page = signal(0);
  readonly total = signal(0);
  readonly loading = signal(true);
  readonly error = signal('');
  readonly incident = signal<Incident | null>(null);
  readonly analystItem=computed(()=>{const item=this.incident();return item?legacyAnalystIncident(item):null;});
  readonly detections = signal<components['schemas']['ServiceDetection'][]>([]);
  readonly recoveryDetectedAt = computed(() => {
    const item = this.incident();
    // A RECOVERY detection records a transition; lastObservedAt is not a recovery timestamp.
    return item?.technicalState === 'RECOVERED' && item.latestDetection.phase === 'RECOVERY' ? item.latestDetection.detectedAt : null;
  });
  readonly latestDeviation = computed(() => {
    const detection = this.incident()?.latestDetection;
    if (!detection || detection.phase === 'UNKNOWN') return 'Comparison unavailable';
    const kpi = primaryMetric(detection.service, detection.kpis);
    const actual = supportedValue(kpi, detection.kpis);
    if (!kpi || actual === null || kpi.baseline === null) return 'Comparison unavailable';
    return `${metricLabel(kpi.name)}: ${deviation(kpi, actual)}`;
  });

  constructor() {
    const destroy = inject(DestroyRef);
    destroy.onDestroy(() => {
      ++this.generation;
      clearTimeout(this.refreshTimer);
      this.controller?.abort();
      this.closeStream?.();
    });
    inject(ActivatedRoute).paramMap.pipe(takeUntilDestroyed(destroy)).subscribe(params => {
      this.closeStream?.();
      this.closeStream = undefined;
      clearTimeout(this.refreshTimer);
      this.id = params.get('id') ?? '';
      this.page.set(0);
      this.total.set(0);
      void this.load();
    });
  }

  acceptAction(item: Incident): void {
    if (!this.incident() || item.version >= this.incident()!.version) this.incident.set(item);
    this.queueRefresh();
  }

  private queueRefresh(): void {
    clearTimeout(this.refreshTimer);
    this.refreshTimer = setTimeout(() => {
      if (this.loading() || this.refreshing) { this.queueRefresh(); return; }
      void this.load(this.page(), true);
    }, 150);
  }

  async load(page = 0, background = false): Promise<void> {
    if (page < 0) return;
    const generation = ++this.generation;
    this.controller?.abort();
    const controller = this.controller = new AbortController();
    const id = this.id;
    this.refreshing = true;
    if (!background) {
      this.loading.set(true);
      this.error.set('');
      this.incident.set(null);
      this.detections.set([]);
      this.timeline.set([]);
    }
    try {
      let [item, result] = await Promise.all([
        this.api.getIncident(id, controller.signal),
        this.api.getDetections(id, page, this.pageSize, controller.signal),
      ]);
      if (generation !== this.generation) return;
      // A shrinking history can remove the selected page between refreshes.
      // Reconcile from a valid page before publishing the current incident.
      if (Number.isInteger(result.total) && result.total >= 0 && page > 0
        && page * this.pageSize >= result.total) {
        page = 0;
        result = await this.api.getDetections(id, page, this.pageSize, controller.signal);
        if (generation !== this.generation) return;
      }
      if (!Number.isInteger(result.total) || result.total < 0
        || result.items.length > this.pageSize || result.items.length > result.total
        || (result.total > 0 && !result.items.length)) {
        throw new Error('This evidence page changed or exceeded its limit. Retry from the first page.');
      }
      const actions: components['schemas']['AuditEvent'][] = [];
      for (let auditPage = 0; ; auditPage++) {
        if (auditPage >= 100) throw new Error('Too much investigation history to load.');
        const audit = await this.api.getTimeline(id, auditPage);
        if (generation !== this.generation) return;
        actions.push(...audit.items);
        if (actions.length >= audit.total) break;
        if (!audit.items.length) throw new Error('Investigation history changed. Retry to refresh it.');
      }
      if (this.incident() && item.version < this.incident()!.version) {
        this.queueRefresh();
        return;
      }
      this.incident.set(item);
      this.detections.set(result.items);
      this.page.set(page);
      this.total.set(result.total);
      this.timeline.set(actions);
      this.streamError.set('');
      if (!this.closeStream) this.closeStream = this.stream.connect(
        () => this.queueRefresh(),
        () => this.connectionInterrupted.set('Live connection interrupted. Reconnecting…'),
        () => this.connectionInterrupted.set(''));
    } catch (error) {
      if(error instanceof ApiFailure && error.status===404 && !this.fixture && !controller.signal.aborted) {
        try {
          const native=await this.api.traceIncidentByPublicId(id,controller.signal);
          if(generation===this.generation && !controller.signal.aborted) {
            await this.nativeRouter.navigate(['/trace-link/incidents',native.episodeId],{queryParamsHandling:'preserve'});return;
          }
        } catch { /* Preserve the original missing-incident error; never infer a native identity. */ }
      }
      controller.abort();
      if (generation === this.generation) {
        (background ? this.streamError : this.error).set(
          error instanceof Error ? error.message : 'Could not load incident evidence.');
      }
    } finally {
      if (generation === this.generation) {
        this.loading.set(false);
        this.refreshing = false;
      }
    }
  }
}
