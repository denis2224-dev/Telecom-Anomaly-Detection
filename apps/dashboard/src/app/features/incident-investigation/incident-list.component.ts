import { Component, computed, effect, inject, input, signal, untracked, viewChild } from '@angular/core';
import { IconComponent } from '../../shared/icon.component';
import { DatePipe } from '@angular/common';
import { RouterLink } from '@angular/router';
import { DrawerComponent } from '../../shared/drawer.component';
import { episodes, type Incident } from '../service-kpi-history/voice-model';
import { workflowLabel, probableCause, primaryMetric, supportedValue } from '../../shared/metric-presentation';
import { TelecomClient, type ServiceSummary } from '../../core/api/telecom-client';
import type { City } from '../service-overview/dashboard-geography';
import { SessionStore } from '../login-and-session/session.store';
@Component({
  selector: 'app-incident-list', imports: [DatePipe, RouterLink, IconComponent, DrawerComponent],
  template: `<section class="detail-panel" aria-labelledby="incidents-title">
    <div class="episode-heading"><h2 id="incidents-title">Incident episodes <span>{{ total() ?? incidents().length }}</span></h2><span class="refresh-status" role="status">@if (loading()) { <span class="spinner"></span> Updating… }</span></div>
    <div class="incident-filters">
      <label>Severity<select [value]="severity()" (change)="severity.set($any($event.target).value)"><option value="">All severities</option><option>HIGH</option><option>MEDIUM</option><option>CRITICAL</option></select></label>
      <label>State<select [value]="state()" (change)="state.set($any($event.target).value)"><option value="">All states</option><option>ONGOING</option><option>RECOVERED</option><option>UNKNOWN</option></select></label>
      <p>{{ rows().length }} on this page</p>
    </div><div class="episode-list">
    @for (item of rows(); track item.episodeId) {
      <article class="episode-card" [class.is-highlighted]="highlighted().includes(item.episodeId)" [attr.data-severity]="item.severity" [attr.data-technical-state]="item.technicalState" [attr.data-episode-id]="item.episodeId" [attr.data-highlighted]="highlighted().includes(item.episodeId)">
        <div class="episode-summary">
          <header class="episode-header"><h3><span class="episode-severity">{{ item.severity }}<span class="sr-only"> severity</span></span>{{ problem(item) }}</h3>
            <p class="episode-time">{{ item.firstObservedAt | date:'dd MMM HH:mm:ss':'UTC' }} – {{ item.lastObservedAt | date:'dd MMM HH:mm:ss':'UTC' }} UTC · {{ duration(item) }}</p>
          </header>
          <p class="episode-location"><span class="episode-label">Location</span> {{ location(item) }}</p>
          <p class="episode-description">{{ description(item) }}</p>
          <p class="episode-info"><span class="episode-state-group"><span class="episode-label">Technical state</span><span class="episode-state" [attr.data-state]="item.technicalState">{{ item.technicalState }}</span></span>
            <span class="episode-state-group"><span class="episode-label">Workflow</span><span class="episode-workflow" [attr.data-state]="item.status" [class.awaiting-resolution]="item.technicalState === 'RECOVERED' && item.status !== 'RESOLVED'" [title]="workflowState(item)">{{ workflowState(item) }}</span></span>
            <span [title]="item.assigneeId ?? ''">{{ item.assigneeId ? 'Assigned' : 'Unassigned' }}</span>
            @if (impact(item); as estimate) { <span>{{ estimate }}</span> }
          </p>
        </div>
        <div class="episode-actions"><button class="ghost evidence-toggle" type="button" aria-haspopup="dialog" [attr.aria-expanded]="expanded() === item.episodeId" [attr.aria-controls]="'episode-evidence-' + item.episodeId" (click)="openEvidence(item)"><span>View incident evidence</span><app-icon name="right" /></button>
          <a class="button primary" [routerLink]="['/incidents', item.id]">Open incident detail<app-icon name="right" /></a></div>
      </article>
    } @empty { <div class="empty-state" role="status"><app-icon name="shield" /><p>No incident episodes on this page.</p></div> }
  </div></section>
  <app-drawer #evidenceDrawer panelClass="episode-evidence" [drawerId]="'episode-evidence-' + (expanded() ?? '')" title="Incident evidence" closeLabel="Close incident evidence" (closed)="expanded.set(null)">
    @if (selectedEpisode(); as item) { <section class="incident-story" [attr.data-phase]="item.latestDetection.phase" [attr.data-episode-id]="item.episodeId">
      <h4 class="episode-title incident-behavior">{{ problem(item) }}</h4>
      <p class="incident-context">{{ location(item) }} · {{ item.scopeId }}</p>
      <div class="incident-states" aria-label="Incident severity and state">
        <span class="incident-state"><span class="incident-state-label">Severity</span><span class="badge" [attr.data-state]="item.severity">{{ item.severity }}</span></span>
        <span class="incident-state"><span class="incident-state-label">Technical state</span><span class="badge" [attr.data-state]="item.technicalState">{{ item.technicalState }}</span></span>
        <span class="incident-state"><span class="incident-state-label">Workflow state</span><span class="badge" [attr.data-state]="item.status">{{ item.status }}</span></span>
      </div>
      @if (item.technicalState === 'RECOVERED' && item.status !== 'RESOLVED') {
        <p class="analyst-pending">Awaiting analyst resolution</p>
      }
      <dl class="evidence-times"><div><dt>First observed (UTC)</dt><dd>{{ item.firstObservedAt | date:'dd MMM yyyy HH:mm:ss':'UTC' }}</dd></div><div><dt>Last observed (UTC)</dt><dd>{{ item.lastObservedAt | date:'dd MMM yyyy HH:mm:ss':'UTC' }}</dd></div><div><dt>Detected (UTC)</dt><dd>{{ item.detectedAt | date:'dd MMM yyyy HH:mm:ss':'UTC' }}</dd></div></dl>
      <section class="evidence-cause" aria-label="Probable cause"><h4>Probable cause</h4><p>{{ cause(item.latestDetection) }}</p></section>
    </section> }
  </app-drawer>`,
  styles: [`
    .detail-panel { padding: 12px; margin: 0; }
    .episode-heading { display: flex; align-items: center; justify-content: space-between; gap: 8px; height: 28px; }
    h2 { margin: 0; font-size: 16px; } h2 span { color: var(--text-muted); font-weight: 400; font-size: 13px; }
    .refresh-status { font-size: 12px; color: var(--text-muted); }
    .incident-filters { display: flex; flex-wrap: wrap; gap: 8px; align-items: end; margin: 8px 0; }
    label { display: grid; gap: 4px; font-size: 12px; }
    select { min-height: 34px; padding: 6px 10px; font-size: 12px; }
    .incident-filters p { margin: 0 0 8px auto; font-size: 12px; color: var(--text-muted); }
    .episode-card { display: grid; gap: 10px; padding: 14px 16px; margin: 0 0 8px; }
    .episode-card { box-shadow: inset 2px 0 var(--warning); }
    .episode-card[data-severity=HIGH], .episode-card[data-severity=CRITICAL] { box-shadow: inset 2px 0 var(--danger); }
    .episode-card[data-technical-state=RECOVERED] { box-shadow: inset 2px 0 var(--success); }
    .episode-card[data-technical-state=UNKNOWN] { box-shadow: inset 2px 0 var(--warning); }
    .episode-card.is-highlighted { background: var(--accent-soft); }
    .episode-summary { min-width: 0; padding-inline-start: 8px; }
    .episode-card p { margin: 0; font-size: 13px; font-variant-numeric: tabular-nums; overflow-wrap: anywhere; }
    .episode-header { display: flex; flex-wrap: wrap; align-items: baseline; justify-content: space-between; gap: 6px 16px; }
    .episode-header h3 { display: flex; flex-wrap: wrap; align-items: center; gap: 8px; margin: 0; font-size: 16px; color: var(--text); }
    .episode-severity { padding: 3px 6px; border-radius: 6px; background: var(--surface-raised); color: var(--text-muted); font-size: 10px; letter-spacing: .04em; }
    .episode-card .episode-location { margin-top: 6px; color: var(--text-muted); font-size: 12px; }
    .episode-card .episode-description { margin: 8px 0; color: var(--text); }
    .episode-card .episode-info { display: flex; flex-wrap: wrap; align-items: baseline; gap: 6px 16px; color: var(--text-muted); font-size: 12px; }
    .episode-state-group { display: inline-flex; flex-wrap: wrap; align-items: baseline; gap: 6px; }
    .episode-label { color: var(--text-muted); font-size: 11px; }
    .episode-workflow { padding: 3px 8px; border-radius: 6px; font-weight: 650; color: var(--info); background: var(--info-soft); }
    .episode-workflow[data-state=RESOLVED] { color: var(--success); background: var(--success-soft); }
    .episode-workflow[data-state=OPEN], .episode-workflow.awaiting-resolution { color: var(--warning); background: var(--warning-soft); }
    .episode-state { padding: 3px 8px; border-radius: 6px; color: var(--text-muted); background: var(--surface-raised); font-weight: 650; }
    .episode-state[data-state=RECOVERED] { color: var(--success); background: var(--success-soft); }
    .episode-state[data-state=ONGOING] { color: var(--danger); background: var(--danger-soft); }
    .episode-state[data-state=UNKNOWN] { color: var(--warning); background: var(--warning-soft); }
    .episode-card .episode-time { font-size: 11px; }
    .episode-actions { display: flex; flex-wrap: wrap; align-items: center; justify-content: space-between; gap: 8px 16px; padding-inline-start: 8px; }
    .episode-actions > a { display: inline-flex; align-items: center; gap: 6px; margin: 0; min-height: 36px; padding: 7px 12px; font-size: 12px; white-space: nowrap; }
    .evidence-toggle { min-height: 28px; margin-top: 2px; padding: 3px 0; font-size: 12px; width: max-content; white-space: nowrap; flex-wrap: nowrap; }
    .evidence-toggle .icon { width: 12px; height: 12px; }
    .incident-story { font-size: 14px; overflow-wrap: anywhere; }
    .incident-story h4 { margin: 18px 0 6px; font-size: 14px; letter-spacing: normal; text-transform: none; color: var(--text); }
    .incident-story .episode-title { margin-top: 0; }
    .incident-story dl { grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 12px; margin: 18px 0; }
    .incident-story dt { font-size: 12px; }
    .incident-story dd { font-size: 13px; font-family: var(--font-family); }
    .incident-story .evidence-times { grid-template-columns: 1fr; }
    .evidence-cause { padding: 12px; background: var(--field); border-radius: var(--radius-control); }
    .evidence-cause h4 { margin-top: 0; }
    .evidence-cause p { margin: 0; }
    @media (max-width: 600px) { .episode-actions > a { margin-left: auto; } .incident-filters label { flex: 1; min-width: 80px; } .incident-filters p { flex-basis: 100%; margin: 0; } }
  `],
})
export class IncidentListComponent {
  readonly workflowState = workflowLabel;
  readonly cause = probableCause;
  readonly incidents = input<Incident[]>([]);
  readonly total = input<number>();
  readonly highlighted = input<string[]>([]);
  readonly loading = input(false);
  readonly cities = input<readonly City[]>([]);
  readonly scope = input<ServiceSummary['scope'] | null>(null);
  private readonly api = inject(TelecomClient);
  private readonly session = inject(SessionStore);
  readonly openingDetections = signal(new Map<string, Incident['latestDetection'] | null>());
  readonly expanded = signal<string | null>(null);
  readonly selectedEpisode = computed(() => episodes(this.incidents()).find(item => item.episodeId === this.expanded()));
  readonly evidenceDrawer = viewChild<DrawerComponent>('evidenceDrawer');
  constructor() {
    effect(onCleanup => {
      const updated = this.session.phase() === 'authenticated'
        ? episodes(this.incidents()).filter(item => item.latestDetection.phase !== 'OPEN') : [];
      const retained = new Map([...untracked(this.openingDetections)].filter(([id]) => updated.some(item => item.id === id)));
      this.openingDetections.set(retained);
      const controller = new AbortController();
      onCleanup(() => controller.abort());
      for (const item of updated.filter(item => !retained.has(item.id))) {
        void this.api.getDetections(item.id, 0, 1, controller.signal).then(result => {
          const first = result.items[0];
          if (controller.signal.aborted) return;
          const valid = first?.phase === 'OPEN' && first.sequence === 1
            && first.episodeId === item.episodeId && first.scopeId === item.scopeId && first.service === item.service
            && supportedValue(primaryMetric(first.service, first.kpis), first.kpis) !== null;
          this.openingDetections.update(rows => new Map(rows).set(item.id, valid ? first : null));
        }).catch(() => {
          // Cache absence on this page so reconnects do not repeatedly request unavailable opening evidence.
          if (!controller.signal.aborted) this.openingDetections.update(rows => new Map(rows).set(item.id, null));
        });
      }
    });
  }
  openEvidence(item: Incident): void { this.expanded.set(item.episodeId); this.evidenceDrawer()?.open(); }
  location(item: Incident): string {
    const location = item.location;
    if (location?.cityId && !location.nullReason)
      return this.cities().find(city => city.id === location.cityId)?.name ?? location.cityId;
    const scope = this.scope();
    const region = scope?.scopeId === item.scopeId ? scope.region : item.scopeId;
    return `${region} · city unavailable`;
  }
  problem(item: Incident): string {
    return item.latestDetection.anomalyType === 'VOLTE_SETUP_DEGRADATION' ? 'VoLTE setup success drop' : 'SMS delivery delay';
  }
  description(item: Incident): string {
    const opening = this.openingDetections().get(item.id);
    const detection = opening ?? item.latestDetection;
    if (!opening && (item.technicalState === 'UNKNOWN' || detection.phase === 'UNKNOWN'))
      return 'Current evidence is incomplete; recovery is not confirmed.';
    const metric = primaryMetric(item.service, detection.kpis);
    const value = supportedValue(metric, detection.kpis);
    const label = item.service === 'VOLTE' ? 'Call setup success' : 'Delivery delay (p95)';
    if (!metric || value === null) return `${label} measurements are unavailable.`;
    const unit = item.service === 'VOLTE' ? '%' : ' ms';
    const format = (number: number) => number.toLocaleString('en', { maximumFractionDigits: 2 });
    let measurement = `${format(value)}${unit}`;
    if (metric.baseline !== null && Number.isFinite(metric.baseline)) {
      const change = Math.round((value - metric.baseline) * 100) / 100;
      measurement += change === 0 ? `, matching the ${format(metric.baseline)}${unit} baseline`
        : `, ${format(Math.abs(change))} ${item.service === 'VOLTE' ? 'percentage points' : 'ms'} ${change < 0 ? 'below' : 'above'} the ${format(metric.baseline)}${unit} baseline`;
    }
    const prefix = opening ? 'At first detection, ' : 'Latest ';
    const currentMetric = primaryMetric(item.service, item.latestDetection.kpis);
    const current = supportedValue(currentMetric, item.latestDetection.kpis);
    const latest = !opening ? '' : item.technicalState === 'UNKNOWN' || item.latestDetection.phase === 'UNKNOWN'
      ? ' Current evidence is incomplete; recovery is not confirmed.'
      : current === null ? ' Latest measurement unavailable.' : ` Now ${format(current)}${unit}.`;
    return `${prefix}${label.toLowerCase()}: ${measurement}.${latest}`;
  }
  duration(item: Incident): string {
    const seconds = Math.floor((Date.parse(item.lastObservedAt) - Date.parse(item.firstObservedAt)) / 1000);
    if (!Number.isFinite(seconds) || seconds < 0) return 'Unavailable';
    return seconds < 60 ? `${seconds}s` : `${Math.floor(seconds / 60)}m${seconds % 60 ? ` ${seconds % 60}s` : ''}`;
  }
  impact(item: Incident): string {
    const presentation = item.presentation;
    const impact = presentation ? presentation.currentImpact ?? presentation.retainedImpact : item.latestDetection.impact;
    if (!impact) return '';
    const count = item.service === 'VOLTE' ? impact.extraFailedAttempts : impact.affectedDeliveredMessages;
    const label = presentation?.impactState === 'STALE' ? 'Retained est.' : presentation?.impactState === 'RECOVERED' ? 'Recovery est.' : 'Est.';
    return Number.isFinite(count) && count > 0 ? `${label} ${count.toLocaleString('en')} ${item.service === 'VOLTE' ? 'extra failures' : 'affected messages'}` : '';
  }
  readonly severity = signal(''); readonly state = signal('');
  readonly rows = computed(() => episodes(this.incidents()).filter(item =>
    (!this.severity() || item.severity === this.severity()) && (!this.state() || item.technicalState === this.state()))
    .sort((a,b) => Date.parse(b.detectedAt) - Date.parse(a.detectedAt)));
}
