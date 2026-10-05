import { Component, computed, input } from '@angular/core';
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
  </section>`,
})
export class IncidentListComponent {
  readonly incidents = input<Incident[]>([]);
  readonly rows = computed(() => episodes(this.incidents()));
}
