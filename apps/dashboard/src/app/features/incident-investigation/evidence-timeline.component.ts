import { Component, computed, input } from '@angular/core';
import { IconComponent } from '../../shared/icon.component';
import { DatePipe } from '@angular/common';
import type { components } from '../../core/api/schema';
import { CauseEvidenceComponent } from './cause-evidence.component';

type Detection = components['schemas']['ServiceDetection'];

@Component({
  selector: 'app-evidence-timeline',
  imports: [DatePipe, CauseEvidenceComponent, IconComponent],
  template: `
    <h2>Evidence timeline</h2>

    <p>
      Accepted detection records, ordered by sequence from earliest to latest.
      Each update preserves the evidence recorded for its own window.
    </p>

    @for (detection of ordered(); track detection.detectionId) {
      <article
        class="detail-panel evidence-entry"
        [attr.data-detection-id]="detection.detectionId"
      >
        <span class="timeline-dot"><app-icon name="activity" /></span><h3>Update {{ detection.sequence }} · <span class="badge" [attr.data-state]="detection.technicalState">{{ detection.phase }}</span></h3>

        <p>
          {{ detection.windowStart | date:'dd MMM yyyy HH:mm:ss':'UTC' }}
          –
          {{ detection.windowEnd | date:'dd MMM yyyy HH:mm:ss':'UTC' }}
          UTC
        </p>

        <p>
          Server detected at
          {{ detection.detectedAt | date:'dd MMM yyyy HH:mm:ss':'UTC' }}
          UTC
        </p>

        <p>
          Source scope: {{ detection.scopeId }}
          · Service: {{ detection.service }}
        </p>

        <p>
          Technical state: {{ detection.technicalState }}
          · Severity: {{ detection.severity }}
        </p>

        @if (detection.phase === 'UNKNOWN') {
          <p class="notice">
            Evidence is incomplete. This update does not prove recovery;
            impact retains the last evaluated value.
          </p>
        }

        <h4>Observed evidence</h4>

        <div
          class="evidence-table"
          tabindex="0"
          role="region"
          [attr.aria-label]="'KPI evidence for update ' + detection.sequence"
        >
          <table>
            <caption>
              Measured KPIs for update {{ detection.sequence }}
            </caption>

            <thead>
              <tr>
                <th scope="col">KPI</th>
                <th scope="col">Actual</th>
                <th scope="col">Baseline</th>
                <th scope="col">Unit</th>
                <th scope="col">Numerator</th>
                <th scope="col">Denominator</th>
              </tr>
            </thead>

            <tbody>
              @for (kpi of detection.kpis; track kpi.name) {
                <tr [attr.data-kpi]="kpi.name">
                  <th scope="row">{{ kpi.name }}</th>
                  <td>{{ kpi.observed ?? 'Unavailable' }}</td>
                  <td>{{ kpi.baseline ?? 'Unavailable' }}</td>
                  <td>{{ kpi.unit }}</td>
                  <td>{{ kpi.numerator ?? 'Unavailable' }}</td>
                  <td>{{ kpi.denominator ?? 'Unavailable' }}</td>
                </tr>
              }
            </tbody>
          </table>
        </div>

        <h4>Estimated impact</h4>

        @if (detection.service === 'VOLTE') {
          <p>
            Estimated extra failed attempts:
            {{ detection.impact.extraFailedAttempts }}
          </p>
        } @else {
          <p>
            Affected delivered messages:
            {{ detection.impact.affectedDeliveredMessages }}
          </p>

          <p>Pending messages: {{ detection.impact.pendingMessages }}</p>
        }

        <p>
          Unique customers:
          {{ detection.impact.uniqueSubscribers ?? 'Unavailable' }}
        </p>

        <p>
          Attempts and messages are not unique customers.
          Aggregate observations do not identify distinct subscribers.
        </p>

        <p>
          Rules: {{ detection.rulesetVersion }}
          · Baseline: {{ detection.baselineVersion }}
          · Topology: {{ detection.topologyVersion }}
        </p>

        <details>
          <summary>Source evidence</summary>

          @for (evidence of detection.evidence; track $index) {
            <p>{{ evidence.summary }}</p>

            <p>
              Evidence code: {{ evidence.code }}
              · Node: {{ evidence.nodeId ?? 'Unavailable' }}
            </p>

            <ul>
              @for (id of evidence.sourceEventIds; track id) {
                <li><code>{{ id }}</code></li>
              } @empty {
                <li>No source event IDs supplied.</li>
              }
            </ul>
          } @empty {
            <p>No source evidence supplied.</p>
          }
        </details>

        <app-cause-evidence [detection]="detection" />
      </article>
    } @empty {
      <p role="status">No evidence updates available.</p>
    }
  `,
})
export class EvidenceTimelineComponent {
  readonly detections = input<Detection[]>([]);

  readonly ordered = computed(() =>
    [...this.detections()].sort((a, b) => a.sequence - b.sequence),
  );
}
