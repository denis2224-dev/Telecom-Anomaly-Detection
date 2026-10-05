import { Component, input } from '@angular/core';
import type { components } from '../../core/api/schema';

type Detection = components['schemas']['ServiceDetection'];

@Component({
  selector: 'app-cause-evidence',
  template: `
    <section aria-label="Cause hypothesis">
      <h4>Probable cause</h4>

      <p>{{ detection().probableCause }}</p>

      <p>Cause confidence: {{ detection().causeConfidence }}</p>

      <p>
        This is a probable explanation, not a confirmed root cause.
        Confidence describes supporting evidence, not proof.
      </p>

      <p>ML status: {{ detection().mlStatus }}</p>

      <p>
        Model anomaly rank:
        {{ detection().anomalyRank ?? 'Unavailable' }}
      </p>
      <p>
        Rank is a model signal from 0 to 1, not a failure probability or
        the service severity. An unavailable rank is not zero.
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
