import { Component, computed, input } from '@angular/core';
import { DatePipe } from '@angular/common';
import { RouterLink } from '@angular/router';
import { episodes, type Incident } from '../service-detail/voice-model';
@Component({
  selector: 'app-incident-list', imports: [DatePipe, RouterLink],
  template: `<section class="detail-panel" aria-labelledby="incidents-title">
    <h2 id="incidents-title">Incident episodes</h2>
    <p class="muted">One entry per episode. Service recovery and investigation progress are separate.</p>
    @for (item of rows(); track item.episodeId) {
      <article class="episode-card" [attr.data-episode-id]="item.episodeId">
        <h3>{{ item.service }} · {{ item.severity }} severity</h3>
        <a [routerLink]="['/incidents', item.id]">Open incident detail</a>
        <dl><div><dt>Technical state</dt><dd>{{ item.technicalState }}</dd></div><div><dt>Workflow state</dt><dd>{{ item.status }}</dd></div><div><dt>Evidence updates</dt><dd>{{ item.latestSequence }}</dd></div></dl>
        <p>Observed interval: {{ item.firstObservedAt | date:'dd MMM yyyy HH:mm:ss':'UTC' }} – {{ item.lastObservedAt | date:'dd MMM yyyy HH:mm:ss':'UTC' }} UTC</p>
        @if (item.technicalState === 'ONGOING') { <p class="muted">Still ongoing at the last observation; the end of this interval is not a recovery time.</p> }
        @if (item.technicalState === 'RECOVERED' && item.status !== 'RESOLVED') { <p class="notice">The service recovered. The investigation still needs attention.</p> }
        <details><summary>View incident evidence</summary><p>{{ item.latestDetection.probableCause }}</p><p class="muted">Cause confidence: {{ item.latestDetection.causeConfidence }} · Detected {{ item.detectedAt | date:'dd MMM yyyy HH:mm:ss':'UTC' }} UTC</p><ul>@for (check of item.latestDetection.recommendedChecks; track check) { <li>{{ check }}</li> }</ul></details>
      </article>
    } @empty { <p role="status">No incident episodes overlap this time range.</p> }
  </section>`,
})
export class IncidentListComponent {
  readonly incidents = input<Incident[]>([]);
  readonly rows = computed(() => episodes(this.incidents()));
}
