import { Component, input } from '@angular/core';
import type { components } from '../../core/api/schema';
import { MetricExplanationComponent } from '../../shared/metric-explanation.component';
import { modelStatusLabel, rankValue } from '../../shared/metric-presentation';

type Detection = components['schemas']['ServiceDetection'];

@Component({
  selector: 'app-cause-evidence',
  imports: [MetricExplanationComponent],
  template: `
    <section aria-label="Cause hypothesis">
      <h4>Probable cause</h4>
      <p>{{ detection().probableCause }}</p>
      <p>Cause confidence: {{ detection().causeConfidence }}</p>
      <p>This is a probable explanation, not a confirmed root cause.</p>
      <app-metric-explanation topic="confidence" />
      <p>ML result: {{ statusLabel(detection().mlStatus) }}</p>
      <p>Model anomaly rank: {{ rank(detection()) }}</p>
      <app-metric-explanation topic="rank" />
      @if (detection().mlStatus !== 'OK' || detection().anomalyRank === null) {
        <app-metric-explanation topic="ml-unavailable" mode="state" />
      }
      <h4>Recommended checks</h4>
      <ul>
        @for (check of detection().recommendedChecks; track $index) { <li>{{ check }}</li> }
        @empty { <li>No checks supplied for this update. Inspect the measured evidence or ask the service owner for a next step.</li> }
      </ul>
    </section>
  `,
})
export class CauseEvidenceComponent {
  readonly detection = input.required<Detection>();
  readonly statusLabel = modelStatusLabel;
  readonly rank = rankValue;
}
