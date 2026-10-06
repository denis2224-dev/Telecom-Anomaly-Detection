import { Component, computed, input } from '@angular/core';
import { IconComponent } from '../../shared/icon.component';
import { DatePipe } from '@angular/common';
import type { components } from '../../core/api/schema';
import { CauseEvidenceComponent } from './cause-evidence.component';
import { MetricExplanationComponent } from '../../shared/metric-explanation.component';
import { SampleVolumeComponent } from '../service-kpi-history/sample-volume.component';
import { metricLabel, primaryMetric, supportedValue, type Kpi } from '../../shared/metric-presentation';

type Detection = components['schemas']['ServiceDetection'];

@Component({
  selector: 'app-evidence-timeline',
  imports: [DatePipe, CauseEvidenceComponent, IconComponent, MetricExplanationComponent, SampleVolumeComponent],
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
        [attr.data-phase]="detection.phase"
      >
        <span class="timeline-dot"><app-icon name="activity" /></span>
        <header class="evidence-entry-header">
          <div>
            <h3>Update {{ detection.sequence }} · {{ detection.phase }}</h3>
            <p class="mono">{{ detection.windowStart | date:'dd MMM HH:mm:ss':'UTC' }} – {{ detection.windowEnd | date:'dd MMM HH:mm:ss':'UTC' }} UTC</p>
          </div>
          <div class="badge-row"><span class="badge" [attr.data-state]="detection.severity"><span class="sr-only">Severity: </span>{{ detection.severity }}</span><span class="badge" [attr.data-state]="detection.technicalState"><span class="sr-only">Technical state: </span>{{ detection.technicalState }}</span><span class="badge">{{ detection.phase }}</span></div>
        </header>
        <p class="evidence-meta">Server detected at <time [attr.datetime]="detection.detectedAt">{{ detection.detectedAt | date:'dd MMM yyyy HH:mm:ss':'UTC' }}</time> UTC · Source scope: {{ detection.scopeId }} · Service: {{ detection.service }}</p>

        @if (detection.phase === 'UNKNOWN') {
          <p class="notice">
            Evidence is incomplete. This update does not prove recovery;
            impact retains the last evaluated value.
          </p>
        }

        <h4>Observed evidence</h4>
        @if (baselineMissing(detection)) {
          <app-metric-explanation topic="baseline-missing" mode="state" />
        }
        @if (detection.service === 'SMS') {
          <app-sample-volume [count]="completedSamples(detection)" />
          <app-metric-explanation topic="p95" />
        } @else {
          <app-metric-explanation topic="percentage-points" />
        }

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
                  <th scope="row">{{ label(kpi.name) }}</th>
                  <td>{{ observedFor(detection, kpi) ?? 'Unavailable' }}</td>
                  <td>{{ kpi.baseline ?? 'Unavailable' }}</td>
                  <td>{{ kpi.unit }}</td>
                  <td>{{ kpi.numerator ?? 'Unavailable' }}</td>
                  <td>{{ kpi.denominator ?? 'Unavailable' }}</td>
                </tr>
              }
            </tbody>
          </table>
        </div>

        <p class="evidence-impact"><strong>Estimated impact</strong> ·
          @if (detection.service === 'VOLTE') { Estimated extra failed attempts: {{ detection.impact.extraFailedAttempts }} }
          @else { Affected delivered messages: {{ detection.impact.affectedDeliveredMessages }} · Pending messages: {{ detection.impact.pendingMessages }} }
          · Unique customers: {{ detection.impact.uniqueSubscribers ?? 'Unavailable' }}
        </p>
        <p class="helper">Unique subscribers: not available in aggregate demo</p>
        <p class="helper">Attempts and messages are not unique customers. Aggregate observations do not identify distinct subscribers.</p>
        @if (detection.service === 'VOLTE') {
          <app-metric-explanation topic="failed-attempts" />
        }
        <p class="evidence-meta">Rules: {{ detection.rulesetVersion }} · Baseline: {{ detection.baselineVersion }} · Topology: {{ detection.topologyVersion }}</p>

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

        <details class="cause-details"><summary>Cause hypothesis &amp; recommended checks</summary><app-cause-evidence [detection]="detection" /></details>
      </article>
    } @empty {
      <p role="status">No evidence updates available on this page. Refresh the incident or return to its first evidence page. This does not prove recovery.</p>
    }
  `,
})
export class EvidenceTimelineComponent {
  readonly detections = input<Detection[]>([]);
  readonly label = metricLabel;

  observedFor(detection: Detection, kpi: Kpi): number | null {
    return supportedValue(kpi, detection.kpis, detection.phase === 'UNKNOWN' ? 'MISSING' : 'COMPLETE');
  }

  baselineMissing(detection: Detection): boolean {
    return primaryMetric(detection.service, detection.kpis)?.baseline === null;
  }

  completedSamples(detection: Detection): number | null {
    const kpi = detection.kpis.find(item => item.name === 'deliveredMessages' && item.unit === 'COUNT');
    return supportedValue(kpi, detection.kpis, detection.phase === 'UNKNOWN' ? 'MISSING' : 'COMPLETE');
  }

  readonly ordered = computed(() =>
    [...this.detections()].sort((a, b) => a.sequence - b.sequence),
  );
}
