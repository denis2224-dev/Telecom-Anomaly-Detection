import { Component, computed, input } from '@angular/core';
import type { components } from '../../core/api/schema';

type KpiWindow = components['schemas']['ServiceKpiWindow'];

@Component({
  selector: 'app-sample-volume',
  template: `
    <section class="detail-panel" aria-label="SMS samples and queue">
      <h3>Samples and pending messages</h3>

      <p>Completed messages: {{ delivered() ?? 'Unavailable' }}</p>
      <p>Queue depth: {{ depth() ?? 'Unavailable' }}</p>
      <p>
        Oldest pending age:
        @if (age() !== null) {
          {{ age() }} seconds
        } @else {
          Unavailable
        }
      </p>

      @if (depth() === 0) {
        <p>Measured empty queue.</p>
      } @else if (depth() !== null) {
        <p>Messages are still waiting in the queue.</p>
      } @else {
        <p>Queue health is unknown because queue data is unavailable.</p>
      }

      @if (delivered() === 0 && depth() !== null && depth()! > 0) {
        <p role="status">No messages completed, but a backlog remains.</p>
      }
    </section>
  `,
})
export class SampleVolumeComponent {
  readonly window = input.required<KpiWindow>();

  private readonly value = (name: string) =>
    this.window().kpis.find(kpi => kpi.name === name)?.observed ?? null;

  readonly delivered = computed(() => this.value('deliveredMessages'));
  readonly depth = computed(() => this.value('queueDepth'));
  readonly age = computed(() => this.value('oldestPendingAgeSec'));
}
