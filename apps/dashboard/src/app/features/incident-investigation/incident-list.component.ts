import { Component, computed, input, signal, viewChild } from '@angular/core';
import { IconComponent } from '../../shared/icon.component';
import { DatePipe } from '@angular/common';
import { RouterLink } from '@angular/router';
import { DrawerComponent } from '../../shared/drawer.component';
import { episodes, type Incident } from '../service-kpi-history/voice-model';
import { workflowLabel, probableCause } from '../../shared/metric-presentation';
@Component({
  selector: 'app-incident-list', imports: [DatePipe, RouterLink, IconComponent, DrawerComponent],
  template: `<section class="detail-panel" aria-labelledby="incidents-title">
    <div class="episode-heading"><h2 id="incidents-title">Incident episodes <span>{{ total() ?? incidents().length }}</span></h2><span class="refresh-status" role="status">@if (loading()) { <span class="spinner"></span> Updating… }</span></div>
    <div class="incident-filters">
      <label>Severity<select [value]="severity()" (change)="severity.set($any($event.target).value)"><option value="">All severities</option><option>HIGH</option><option>MEDIUM</option><option>CRITICAL</option></select></label>
      <label>State<select [value]="state()" (change)="state.set($any($event.target).value)"><option value="">All states</option><option>ONGOING</option><option>RECOVERED</option><option>UNKNOWN</option></select></label>
      <label>Service<select [value]="service()" (change)="service.set($any($event.target).value)"><option value="">All services</option><option>VOLTE</option><option>SMS</option></select></label>
      <p>{{ rows().length }} on this page</p>
    </div><div class="episode-scroll">
    @for (item of rows(); track item.episodeId) {
      <article class="episode-card" [class.is-highlighted]="highlighted().includes(item.episodeId)" [attr.data-severity]="item.severity" [attr.data-episode-id]="item.episodeId" [attr.data-highlighted]="highlighted().includes(item.episodeId)">
        <div class="episode-summary"><p class="episode-info"><span class="sr-only">{{ item.severity }} severity · </span>{{ item.service === 'VOLTE' ? 'VoLTE' : 'SMS' }} · {{ duration(item) }}@if (impact(item); as estimate) { · {{ estimate }} } · <span [title]="item.assigneeId ?? ''">{{ item.assigneeId ? 'Assigned' : 'Unassigned' }}</span> · <span [title]="workflowState(item)">{{ summaryWorkflow(item) }}</span></p>
        <p class="episode-time">{{ item.firstObservedAt | date:'dd MMM HH:mm:ss':'UTC' }} – {{ item.lastObservedAt | date:'dd MMM HH:mm:ss':'UTC' }} UTC · {{ item.technicalState }}</p>
        <button class="ghost evidence-toggle" type="button" aria-haspopup="dialog" [attr.aria-expanded]="expanded() === item.episodeId" [attr.aria-controls]="'episode-evidence-' + item.episodeId" (click)="openEvidence(item)"><span>View incident evidence</span><app-icon name="right" /></button></div>
        <a class="button primary" [routerLink]="['/incidents', item.id]">Open incident detail<app-icon name="right" /></a>
      </article>
    } @empty { <div class="empty-state" role="status"><app-icon name="shield" /><p>No incident episodes on this page.</p></div> }
  </div></section>
  <app-drawer #evidenceDrawer panelClass="episode-evidence" [drawerId]="'episode-evidence-' + (expanded() ?? '')" title="Incident evidence" closeLabel="Close incident evidence" (closed)="expanded.set(null)">
    @if (selectedEpisode(); as item) { <section class="incident-story" [attr.data-phase]="item.latestDetection.phase" [attr.data-episode-id]="item.episodeId">
      <h4 class="episode-title">{{ item.service === 'VOLTE' ? 'VoLTE' : 'SMS' }} · {{ item.latestDetection.anomalyType }}</h4>
      <p>{{ item.scopeId }}</p>
      <dl><div><dt>Technical state</dt><dd>{{ item.technicalState }}</dd></div><div><dt>Workflow state</dt><dd>{{ workflowState(item) }}</dd></div></dl>
      <dl class="evidence-times"><div><dt>First observed (UTC)</dt><dd>{{ item.firstObservedAt | date:'dd MMM yyyy HH:mm:ss':'UTC' }}</dd></div><div><dt>Last observed (UTC)</dt><dd>{{ item.lastObservedAt | date:'dd MMM yyyy HH:mm:ss':'UTC' }}</dd></div><div><dt>Detected (UTC)</dt><dd>{{ item.detectedAt | date:'dd MMM yyyy HH:mm:ss':'UTC' }}</dd></div></dl>
      <h4>Probable cause</h4><p>{{ cause(item.latestDetection) }}</p>
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
    .episode-scroll { height: 240px; overflow: auto; scrollbar-gutter: stable; }
    .episode-card { display: grid; grid-template-columns: minmax(0, 1fr) auto; align-items: center; gap: 4px 12px; padding: 8px 10px; margin: 0 0 6px; }
    .episode-card { box-shadow: inset 2px 0 var(--warning); }
    .episode-card[data-severity=HIGH], .episode-card[data-severity=CRITICAL] { box-shadow: inset 2px 0 var(--danger); }
    .episode-card.is-highlighted { background: var(--accent-soft); }
    .episode-summary { min-width: 0; padding-inline-start: 8px; }
    .episode-card p { margin: 0; font-size: 13px; font-variant-numeric: tabular-nums; overflow-wrap: anywhere; }
    .episode-card .episode-info { color: var(--text); }
    .episode-card .episode-time { margin-top: 3px; font-size: 11px; }
    .episode-card > a { margin: 0; min-height: 36px; padding: 7px 12px; font-size: 12px; white-space: nowrap; align-self: center; }
    .evidence-toggle { min-height: 28px; margin-top: 2px; padding: 3px 0; font-size: 12px; width: max-content; white-space: nowrap; flex-wrap: nowrap; }
    .evidence-toggle .icon { width: 12px; height: 12px; }
    .incident-story { font-size: 14px; overflow-wrap: anywhere; }
    .incident-story h4 { margin: 18px 0 6px; font-size: 14px; letter-spacing: normal; text-transform: none; color: var(--text); }
    .incident-story .episode-title { margin-top: 0; }
    .incident-story dl { grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 12px; margin: 18px 0; }
    .incident-story dt { font-size: 12px; }
    .incident-story dd { font-size: 14px; font-family: var(--font-family); }
    .incident-story .evidence-times { grid-template-columns: 1fr; }
    @media (max-width: 600px) { .episode-card { grid-template-columns: minmax(0, 1fr); } .episode-card > a { justify-self: center; } .incident-filters label { flex: 1; min-width: 80px; } .incident-filters p { flex-basis: 100%; margin: 0; } }
  `],
})
export class IncidentListComponent {
  readonly workflowState = workflowLabel;
  readonly cause = probableCause;
  readonly incidents = input<Incident[]>([]);
  readonly total = input<number>();
  readonly highlighted = input<string[]>([]);
  readonly loading = input(false);
  readonly expanded = signal<string | null>(null);
  readonly selectedEpisode = computed(() => episodes(this.incidents()).find(item => item.episodeId === this.expanded()));
  readonly evidenceDrawer = viewChild<DrawerComponent>('evidenceDrawer');
  openEvidence(item: Incident): void { this.expanded.set(item.episodeId); this.evidenceDrawer()?.open(); }
  summaryWorkflow(item: Incident): string {
    return item.technicalState === 'RECOVERED' && item.status !== 'RESOLVED' ? 'Awaiting analyst resolution' : item.status;
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
  readonly severity = signal(''); readonly state = signal(''); readonly service = signal('');
  readonly rows = computed(() => episodes(this.incidents()).filter(item =>
    (!this.severity() || item.severity === this.severity()) && (!this.state() || item.technicalState === this.state())
    && (!this.service() || item.service === this.service()))
    .sort((a,b) => Date.parse(b.detectedAt) - Date.parse(a.detectedAt)));
}
