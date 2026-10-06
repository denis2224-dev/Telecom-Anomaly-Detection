import { Component, computed, input, signal } from '@angular/core';
import { IconComponent } from '../../shared/icon.component';
import { DatePipe } from '@angular/common';
import { RouterLink } from '@angular/router';
import { IncidentStoryComponent } from './incident-story.component';
import { episodes, type Incident } from '../service-kpi-history/voice-model';
@Component({
  selector: 'app-incident-list', imports: [DatePipe, RouterLink, IconComponent, IncidentStoryComponent],
  template: `<section class="detail-panel" aria-labelledby="incidents-title">
    <div class="episode-heading"><h2 id="incidents-title">Incident episodes <span>{{ total() ?? incidents().length }}</span></h2><span class="refresh-status" role="status">@if (loading()) { <span class="spinner"></span> Updating… }</span></div>
    <div class="incident-filters">
      <label>Severity<select [value]="severity()" (change)="severity.set($any($event.target).value)"><option value="">All severities</option><option>HIGH</option><option>MEDIUM</option><option>CRITICAL</option></select></label>
      <label>State<select [value]="state()" (change)="state.set($any($event.target).value)"><option value="">All states</option><option>ONGOING</option><option>RECOVERED</option><option>UNKNOWN</option></select></label>
      <label>Service<select [value]="service()" (change)="service.set($any($event.target).value)"><option value="">All services</option><option>VOLTE</option><option>SMS</option></select></label>
      <p>{{ rows().length }} on this page</p>
    </div><div class="episode-scroll">
    @for (item of rows(); track item.episodeId) {
      <article class="episode-card" [class.is-highlighted]="highlighted().includes(item.episodeId)" [attr.data-episode-id]="item.episodeId" [attr.data-highlighted]="highlighted().includes(item.episodeId)">
        <div class="episode-summary"><div class="badge-row"><h3>{{ item.service === 'VOLTE' ? 'VoLTE' : 'SMS' }}</h3><span class="badge" [attr.data-state]="item.severity">{{ item.severity }}</span><span class="badge" [attr.data-state]="item.technicalState" [title]="item.technicalState === 'ONGOING' ? 'Still ongoing at the last observation; this is not a recovery time.' : 'Technical state'">{{ item.technicalState }}</span><span class="badge" [attr.data-state]="item.status" title="Analyst workflow">{{ item.status }}</span><span class="update-count">{{ item.latestSequence }} updates</span></div>
        <p>{{ item.firstObservedAt | date:'dd MMM HH:mm:ss':'UTC' }} – {{ item.lastObservedAt | date:'dd MMM HH:mm:ss':'UTC' }} UTC</p></div>
        <a class="button primary" [routerLink]="['/incidents', item.id]">Open incident detail<app-icon name="right" /></a>
        <details><summary>View incident evidence</summary><app-incident-story [incident]="item" /></details>
      </article>
    } @empty { <div class="empty-state" role="status"><app-icon name="shield" /><p>No incident episodes on this page.</p></div> }
  </div></section>`,
  styles: [`
    .detail-panel { padding: 12px; margin: 0; }
    .episode-heading { display: flex; align-items: center; justify-content: space-between; gap: 8px; height: 28px; }
    h2 { margin: 0; font-size: 15px; } h2 span { color: var(--text-muted); font-weight: 400; font-size: 12px; }
    .refresh-status { font-size: 11px; color: var(--text-muted); }
    .incident-filters { display: flex; flex-wrap: wrap; gap: 8px; align-items: end; margin: 8px 0; }
    label { display: grid; gap: 4px; font-size: 11px; }
    select { min-height: 34px; padding: 6px 10px; font-size: 11px; }
    .incident-filters p { margin: 0 0 8px auto; font-size: 11px; color: var(--text-muted); }
    .episode-scroll { height: 240px; overflow: auto; scrollbar-gutter: stable; }
    .episode-card { display: grid; grid-template-columns: minmax(0, 1fr) auto; align-items: center; gap: 4px 12px; padding: 8px 10px; margin: 0 0 6px; }
    .episode-card.is-highlighted { background: var(--accent-soft); box-shadow: inset 3px 0 var(--accent); }
    .episode-summary { min-width: 0; }
    .episode-card h3, .episode-card p { margin: 0; font-size: 11px; }
    .episode-card p { margin-top: 4px; font-variant-numeric: tabular-nums; }
    .badge-row { gap: 6px; }
    .update-count { color: var(--text-muted); font-size: 10px; }
    .episode-card > a { margin: 0; min-height: 40px; font-size: 11px; white-space: nowrap; }
    .episode-card details { grid-column: 1 / -1; }
    .episode-card summary { padding: 4px 0; font-size: 10px; }
    @media (max-width: 600px) { .episode-card { grid-template-columns: minmax(0, 1fr); } .episode-card > a { justify-self: start; } .incident-filters label { flex: 1; min-width: 80px; } .incident-filters p { flex-basis: 100%; margin: 0; } }
  `],
})
export class IncidentListComponent {
  readonly incidents = input<Incident[]>([]);
  readonly total = input<number>();
  readonly highlighted = input<string[]>([]);
  readonly loading = input(false);
  readonly severity = signal(''); readonly state = signal(''); readonly service = signal('');
  readonly rows = computed(() => episodes(this.incidents()).filter(item =>
    (!this.severity() || item.severity === this.severity()) && (!this.state() || item.technicalState === this.state())
    && (!this.service() || item.service === this.service()))
    .sort((a,b) => Date.parse(b.detectedAt) - Date.parse(a.detectedAt)));
}
