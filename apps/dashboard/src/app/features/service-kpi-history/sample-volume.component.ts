import { Component, input } from '@angular/core';
import { MetricExplanationComponent } from '../../shared/metric-explanation.component';

@Component({
  selector: 'app-sample-volume',
  imports: [MetricExplanationComponent],
  template: `
    <p><strong>Completed-message samples:</strong> {{ count() ?? 'Unavailable' }}</p>
    @if (count() === null) {
      <app-metric-explanation topic="sample-count-missing" mode="state" />
    } @else if (count() === 0) {
      <app-metric-explanation topic="no-samples" mode="state" />
    } @else if (count()! < 30) {
      <app-metric-explanation topic="low-volume" mode="state" />
    }
  `,
})
export class SampleVolumeComponent {
  readonly count = input<number | null>(null);
}
