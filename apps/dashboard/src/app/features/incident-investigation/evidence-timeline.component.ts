import { Component, computed, input } from '@angular/core';
import { DatePipe } from '@angular/common';
import type { components } from '../../core/api/schema';
import { CauseEvidenceComponent } from './cause-evidence.component';

type Detection = components['schemas']['ServiceDetection'];

@Component({
  selector: 'app-evidence-timeline',
  imports: [DatePipe, CauseEvidenceComponent],
  styles: [`
    .evidence-table {
      overflow-x: auto;
    }

    table {
      width: 100%;
      border-collapse: collapse;
    }

    th, td {
      padding: 10px;
      text-align: left;
      border-bottom: 1px solid #d8e2dd;
    }

    code {
      overflow-wrap: anywhere;
    }

    article.detail-panel {
      margin: 0;
    }
    .evidence-rail { list-style: none; margin: 24px 0 0; padding: 0 0 0 24px; }
    .evidence-event { position: relative; padding: 0 0 22px 24px; border-left: 2px solid var(--telecom-border); }
    .evidence-event:last-child { border-left-color: transparent; padding-bottom: 0; }
    .evidence-event::before { content: ""; position: absolute; left: -8px; top: 22px; width: 12px; height: 12px; border-radius: 50%; background: var(--telecom-primary); border: 2px solid var(--telecom-surface); box-shadow: 0 0 0 1px var(--telecom-border); }
    .evidence-event--unknown::before { background: #5674a0; }
    .evidence-event--recovery::before { background: #0d6140; }
    .event-header { display: flex; flex-wrap: wrap; justify-content: space-between; align-items: baseline; gap: 8px 20px; }
    .event-header h3 { margin: 0; }
    .event-header time { color: var(--telecom-muted-text); font-size: 13px; }
    @media (max-width: 650px) { .evidence-rail { padding-left: 8px; } .evidence-event { padding-left: 16px; } }
  `],
  template: `
    <h2>Evidence timeline</h2>

    <p>
      Accepted detection records, ordered by sequence from earliest to latest.
      Each update preserves the evidence recorded for its own window.
    </p>

    <ol class="evidence-rail" aria-label="Detection updates">
    @for (detection of ordered(); track detection.detectionId) {
      <li class="evidence-event" [class.evidence-event--unknown]="detection.phase === 'UNKNOWN'" [class.evidence-event--recovery]="detection.phase === 'RECOVERY'">
      <article
        class="detail-panel"
        [attr.data-detection-id]="detection.detectionId"
      >
        <header class="event-header">
          <h3>Update {{ detection.sequence }} · {{ detection.phase }}</h3>
          <time [attr.datetime]="detection.detectedAt">{{ detection.detectedAt | date:'dd MMM yyyy HH:mm:ss':'UTC' }} UTC</time>
        </header>

        <p>
          {{ detection.windowStart | date:'dd MMM yyyy HH:mm:ss':'UTC' }}
          –
          {{ detection.windowEnd | date:'dd MMM yyyy HH:mm:ss':'UTC' }}
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
      </li>
    } @empty {
      <li><p role="status">No evidence updates available.</p></li>
    }
    </ol>
  `,
})
export class EvidenceTimelineComponent {
  readonly detections = input<Detection[]>([]);

  readonly ordered = computed(() =>
    [...this.detections()].sort((a, b) => a.sequence - b.sequence),
  );
}
