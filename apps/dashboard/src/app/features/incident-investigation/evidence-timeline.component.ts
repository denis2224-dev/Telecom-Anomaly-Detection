import { Component, computed, input } from '@angular/core';
import { IconComponent } from '../../shared/icon.component';
import { DatePipe } from '@angular/common';
import type { components } from '../../core/api/schema';
import { CauseEvidenceComponent } from './cause-evidence.component';
import { MetricExplanationComponent } from '../../shared/metric-explanation.component';
import { SampleVolumeComponent } from '../service-kpi-history/sample-volume.component';
import { deviation, relativeChange, probableCause, metricLabel, primaryMetric, supportedValue, type Kpi } from '../../shared/metric-presentation';

type Detection = components['schemas']['ServiceDetection'];

@Component({
  styles: [`.supporting-kpis{display:grid;grid-template-columns:1fr;gap:8px;margin:8px 0;}.supporting-kpis > div{min-width:0;}.supporting-kpis dt{font-size:12px;font-weight:600;}.supporting-kpis dd{margin:3px 0 0;font-size:12px;overflow-wrap:anywhere;}.recorded-signal{font-size:13px;overflow-wrap:anywhere;}`],
  selector: 'app-evidence-timeline',
  imports: [DatePipe, CauseEvidenceComponent, IconComponent, MetricExplanationComponent, SampleVolumeComponent],
  template: `
    <h2>Evidence timeline</h2>

    <p>
      Recorded updates · oldest first.
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
          <div class="badge-row"><span class="badge" [attr.data-state]="detection.severity"><span class="sr-only">Severity: </span>{{ detection.severity }}</span><span class="badge" [attr.data-state]="detection.technicalState"><span class="sr-only">Technical state: </span>{{ detection.technicalState }}</span></div>
        </header>
        <p class="evidence-meta">Server detected at <time [attr.datetime]="detection.detectedAt">{{ detection.detectedAt | date:'dd MMM yyyy HH:mm:ss':'UTC' }}</time> UTC · Service: {{ detection.service }}</p>

        @if (detection.phase === 'UNKNOWN') {
          <p class="notice">
            Incomplete evidence · recovery unconfirmed. Last evaluated impact retained.
          </p>
        }

        <h4>Observed evidence</h4>
        @if (baselineMissing(detection)) {
          <app-metric-explanation topic="baseline-missing" mode="state" />
        }
        @if (detection.service === 'SMS') {
          <app-sample-volume [count]="completedSamples(detection)" />
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
                <th scope="col">Change</th>
                <th scope="col">Relative change</th>
                <th scope="col">Numerator</th>
                <th scope="col">Denominator</th>
              </tr>
            </thead>

            <tbody>
              @for (kpi of primaryKpis(detection); track kpi.name) {
                <tr [attr.data-kpi]="kpi.name">
                  <th scope="row">{{ label(kpi.name) }}</th>
                  <td>{{ observedFor(detection, kpi) ?? 'Unavailable' }}</td>
                  <td>{{ kpi.baseline ?? 'Unavailable' }}</td>
                  <td>{{ kpi.unit }}</td>
                  <td>{{ change(kpi, observedFor(detection, kpi)) }}</td>
                  <td>{{ relative(kpi, observedFor(detection, kpi)) }}</td>
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
        </p>

        <details class="evidence-disclosure"><summary>Troubleshooting</summary>
          @if (supportingKpis(detection).length) {
            <dl class="supporting-kpis" aria-label="Supporting KPI evidence">
              @for (kpi of supportingKpis(detection); track kpi.name) {
                <div [attr.data-kpi]="kpi.name"><dt>{{ label(kpi.name) }}</dt><dd><span class="supporting-value">{{ observedFor(detection, kpi) ?? 'Unavailable' }}</span> {{ kpi.unit }} · Baseline: <span class="supporting-baseline">{{ kpi.baseline ?? 'Unavailable' }}</span> · {{ change(kpi, observedFor(detection, kpi)) }}</dd></div>
              }
            </dl>
          }
          <p>Scope: <code>{{ detection.scopeId }}</code></p>
          @for (evidence of detection.evidence; track $index) {
            <p class="recorded-signal">{{ evidence.summary }}@if (evidence.nodeId) { <span> · Node: {{ evidence.nodeId }}</span> }</p>
          }
          @if (cause(detection) !== detection.probableCause) { <p>Recorded hypothesis (not confirmed): {{ detection.probableCause || 'Unavailable' }}</p> }
        </details>

        <details class="cause-details"><summary>Cause hypothesis &amp; recommended checks</summary><app-cause-evidence [detection]="detection" /></details>
      </article>
    } @empty {
      <p role="status">No updates on this page. Refresh or return to the first page. Recovery unconfirmed.</p>
    }
  `,
})
export class EvidenceTimelineComponent {
  readonly detections = input<Detection[]>([]);
  primaryKpis(detection: Detection): Kpi[] {
    const names = detection.service === 'VOLTE'
      ? ['cssrPct', 'Call setup success rate (CSSR)', 'eligibleAttempts', 'sip503Ratio', 'imsCpuPct']
      : ['deliverySrPct', 'deliveredMessages', 'queueDepth', 'p95DeliveryMs', 'oldestPendingAgeSec', 'eligibleAttempts'];
    return detection.kpis.filter(kpi => names.includes(kpi.name));
  }
  supportingKpis(detection: Detection): Kpi[] { return detection.kpis.filter(kpi => !this.primaryKpis(detection).includes(kpi)); }
  readonly label = metricLabel;
  readonly change = deviation;
  readonly relative = relativeChange;
  readonly cause = probableCause;

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
