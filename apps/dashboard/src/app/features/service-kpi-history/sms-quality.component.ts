import { Component, computed, input } from '@angular/core';
import type { components } from '../../core/api/schema';
import { SampleVolumeComponent } from './sample-volume.component';

type KpiWindow = components['schemas']['ServiceKpiWindow'];

@Component({
  selector: 'app-sms-quality',
  imports: [SampleVolumeComponent],
  template: `
    <section class="detail-panel">
      <h2>SMS delivery and queue</h2>

      <p>Window: {{ window().windowStart }} to {{ window().windowEnd }}</p>
      <p>Evidence quality: {{ window().quality }}</p>

      <p>
        Delivery p95:
        @if (p95() !== null) {
          {{ p95() }} ms
        } @else {
          Unavailable
        }
      </p>

      <p>
        Expected delivery p95:
        @if (expectedP95() !== null) {
          {{ expectedP95() }} ms
        } @else {
          Unavailable
        }
      </p>

      <p>
        Only completed messages contribute to p95. Accepting an SMS for
        processing does not mean delivery is complete.
      </p>

      @if (p95() === null && delivered() === 0) {
        <p>No completed-message delay samples in this window. Check the queue below.</p>
      }

      <app-sample-volume [window]="window()" />
    </section>
  `,
})
export class SmsQualityComponent {
  readonly window = input.required<KpiWindow>();

  private readonly p95Kpi = computed(() =>
    this.window().kpis.find(kpi => kpi.name === 'p95DeliveryMs')
  );

  readonly p95 = computed(() => this.p95Kpi()?.observed ?? null);
  readonly expectedP95 = computed(() => this.p95Kpi()?.baseline ?? null);

  readonly delivered = computed(() =>
    this.window().kpis.find(kpi => kpi.name === 'deliveredMessages')?.observed ?? null
  );
}
