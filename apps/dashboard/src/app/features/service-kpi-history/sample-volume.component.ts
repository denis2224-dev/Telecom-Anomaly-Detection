import { Component, input } from '@angular/core';

@Component({
  selector: 'app-sample-volume',
  template: `
    <p>Completed-message samples: {{ count() ?? 'Unavailable' }}</p>
    @if (count() === 0) {
      <p role="status">No completed messages in this window.</p>
    } @else if (count() !== null && count()! < 30) {
      <p role="status">Low sample volume; interpret delivery p95 with caution.</p>
    }
  `,
})
export class SampleVolumeComponent {
  readonly count = input<number | null>(null);
}
