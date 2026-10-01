import { Component, input } from '@angular/core';
import type { components } from '../../core/api/schema';

type Detection = components['schemas']['ServiceDetection'];

@Component({
  selector: 'app-cause-evidence',
  template: `
    <section aria-label="Cause hypothesis">
      <h4>Cause hypothesis</h4>

      <p>{{ detection().probableCause }}</p>

      <p>Cause confidence: {{ detection().causeConfidence }}</p>

      <p>
        This is a probable explanation, not a confirmed root cause.
        Confidence describes supporting evidence, not proof.
      </p>

      <p>ML status: {{ detection().mlStatus }}</p>

      <p>Calibrated anomaly rank: {{ detection().anomalyRank ?? 'Unavailable' }}</p>
      <p>
        Rank compares this window with normal calibration data; it is not an outage probability.
        Severity describes service impact. Cause confidence describes supporting evidence.
      </p>

      <h4>Recommended checks</h4>

      <ul>
        @for (check of detection().recommendedChecks; track $index) {
          <li>{{ check }}</li>
        } @empty {
          <li>No checks supplied for this update.</li>
        }
      </ul>
    </section>
  `,
})
export class CauseEvidenceComponent {
  readonly detection = input.required<Detection>();
}
