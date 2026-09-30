import { Component, input } from '@angular/core';

@Component({
  selector: 'app-sample-volume',
  template: `
    <p>
      <strong>Completed-message samples:</strong>
      {{ count() ?? 'Unavailable' }}
    </p>

    @if (count() === 0) {
      <p role="status">
        No completed messages in this window.
        Delivery p95 cannot be measured.
      </p>
    } @else if (count() !== null && count()! < 30) {
      <p role="status">
        Low sample volume: fewer than 30 completed messages.
        Interpret p95 cautiously.
      </p>
    }
  `,
})
export class SampleVolumeComponent {
  readonly count = input<number | null>(null);
}
