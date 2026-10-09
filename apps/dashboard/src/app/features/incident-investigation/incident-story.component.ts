import { Component, input } from '@angular/core';
import { DatePipe } from '@angular/common';
import { RouterLink } from '@angular/router';
import type { Incident } from '../../core/api/telecom-client';
import { CauseEvidenceComponent } from './cause-evidence.component';
@Component({
  selector: 'app-incident-story', imports: [DatePipe, RouterLink, CauseEvidenceComponent],
  template: `<section class="detail-panel incident-story" [attr.data-phase]="incident().latestDetection.phase">
    <div class="section-heading"><h2>{{ incident().service }} · {{ incident().latestDetection.anomalyType }}</h2><a [routerLink]="['/incidents', incident().id]">Investigate incident →</a></div>
    <p><span [attr.class]="'phase-label ' + incident().latestDetection.phase">{{ incident().latestDetection.phase }}</span> · {{ incident().scopeId }} · {{ incident().severity }}</p>
    <dl><div><dt>Technical state</dt><dd>{{ incident().technicalState }}</dd></div><div><dt>Workflow state</dt><dd>{{ incident().status }}</dd></div><div><dt>Current sequence</dt><dd>{{ incident().latestSequence }}</dd></div></dl>
    <p>First observed {{ incident().firstObservedAt | date:'dd MMM HH:mm:ss':'UTC' }} UTC · Last observed {{ incident().lastObservedAt | date:'dd MMM HH:mm:ss':'UTC' }} UTC · Detected at {{ incident().detectedAt | date:'dd MMM HH:mm:ss':'UTC' }} UTC</p>
    <div class="story-grid"><div><app-cause-evidence [detection]="incident().latestDetection" /></div><div>
      <h3>Supporting evidence</h3><ul>@for (item of incident().latestDetection.evidence; track $index) { <li>{{ item.summary }}<small>Node: {{ item.nodeId ?? 'Unavailable' }} · {{ item.code }} · {{ item.sourceEventIds.length }} source event relationships</small></li> } @empty { <li>No supporting evidence supplied.</li> }</ul>
      <h3>Estimated service impact</h3>
      @if (incident().service === 'VOLTE') { <p>Estimated extra failed attempts: {{ incident().latestDetection.impact.extraFailedAttempts }}</p> }
      @else { <p>Affected delivered messages: {{ incident().latestDetection.impact.affectedDeliveredMessages }}</p><p>Pending messages: {{ incident().latestDetection.impact.pendingMessages }}</p> }
      @if (incident().latestDetection.phase === 'UNKNOWN') { <p class="notice">Incomplete evidence does not prove recovery. Impact retains the last evaluated estimate.</p> }
    </div></div>
  </section>`,
})
export class IncidentStoryComponent { readonly incident = input.required<Incident>(); }
