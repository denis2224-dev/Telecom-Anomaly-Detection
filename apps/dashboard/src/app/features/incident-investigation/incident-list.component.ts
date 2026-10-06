import { Component, computed, input, signal } from '@angular/core';
import { IconComponent } from '../../shared/icon.component';
import { DatePipe } from '@angular/common';
import { RouterLink } from '@angular/router';
import { IncidentStoryComponent } from './incident-story.component';
import { episodes, type Incident } from '../service-kpi-history/voice-model';
@Component({
  selector: 'app-incident-list', imports: [DatePipe, RouterLink, IconComponent, IncidentStoryComponent],
  template: `<section class="detail-panel" aria-labelledby="incidents-title">
    <h2 id="incidents-title">Incident episodes</h2>
    <p class="muted">One entry per episode. Service recovery and investigation progress are separate.</p>
    <div class="incident-filters">
      <label>Severity<select [value]="severity()" (change)="severity.set($any($event.target).value)"><option value="">All severities</option><option>HIGH</option><option>MEDIUM</option><option>CRITICAL</option></select></label>
      <label>State<select [value]="state()" (change)="state.set($any($event.target).value)"><option value="">All states</option><option>ONGOING</option><option>RECOVERED</option><option>UNKNOWN</option></select></label>
      <label>Service<select [value]="service()" (change)="service.set($any($event.target).value)"><option value="">All services</option><option>VOLTE</option><option>SMS</option></select></label>
      <p>{{ rows().length }} matches on this page · {{ total() ?? incidents().length }} total</p>
    </div><div class="episode-scroll">
    @for (item of rows(); track item.episodeId) {
      <article class="episode-card" [attr.data-episode-id]="item.episodeId">
        <h3>{{ item.service }} · {{ item.severity }} severity</h3>
        <a [routerLink]="['/incidents', item.id]">Open incident detail<app-icon name="right" /></a>
        <dl><div><dt>Technical state</dt><dd><span class="badge" [attr.data-state]="item.technicalState">{{ item.technicalState }}</span></dd></div><div><dt>Workflow state</dt><dd><span class="badge" [attr.data-state]="item.status">{{ item.status }}</span></dd></div><div><dt>Evidence updates</dt><dd>{{ item.latestSequence }}</dd></div></dl>
        <p>Observed interval: {{ item.firstObservedAt | date:'dd MMM yyyy HH:mm:ss':'UTC' }} – {{ item.lastObservedAt | date:'dd MMM yyyy HH:mm:ss':'UTC' }} UTC</p>
        @if (item.technicalState === 'ONGOING') { <p class="muted">Still ongoing at the last observation; the end of this interval is not a recovery time.</p> }
        @if (item.technicalState === 'RECOVERED' && item.status !== 'RESOLVED') { <p class="notice">The service recovered. The investigation still needs attention.</p> }
        <details><summary>View incident evidence</summary><app-incident-story [incident]="item" /></details>
      </article>
    } @empty { <div class="empty-state" role="status"><app-icon name="shield" /><h3>No incident episodes</h3><p>No incident episodes on this page.</p></div> }
  </div></section>`,
  styles: [`
    .incident-filters { display: flex; flex-wrap: wrap; gap: 12px; align-items: end; margin: 8px 0; }
    label { display: grid; gap: 4px; font-size: 11px; }
    .episode-scroll { height: 360px; overflow: auto; scrollbar-gutter: stable; }
    .episode-card { padding: 10px; margin: 0 0 6px; }
    .episode-card h3, .episode-card p { margin: 4px 0; font-size: 12px; }
    .episode-card dl { display: flex; flex-wrap: wrap; gap: 16px; margin: 6px 0; font-size: 11px; }
    .episode-card > a { float: right; font-size: 12px; }
  `],
})
export class IncidentListComponent {
  readonly incidents = input<Incident[]>([]);
  readonly total = input<number>();
  readonly severity = signal(''); readonly state = signal(''); readonly service = signal('');
  readonly rows = computed(() => episodes(this.incidents()).filter(item =>
    (!this.severity() || item.severity === this.severity()) && (!this.state() || item.technicalState === this.state())
    && (!this.service() || item.service === this.service()))
    .sort((a,b) => Date.parse(b.detectedAt) - Date.parse(a.detectedAt)));
}
