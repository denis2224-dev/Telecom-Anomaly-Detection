import { Component, input } from '@angular/core';
import { DatePipe } from '@angular/common';
import type { ServiceSummary } from '../../core/api/telecom-client';
import type { KpiWindow } from './voice-model';
import { SampleVolumeComponent } from './sample-volume.component';

@Component({
  selector: 'app-sms-quality',
  imports: [DatePipe, SampleVolumeComponent],
  styles: [`
    .metrics {
      display: grid;
      grid-template-columns: repeat(auto-fit, minmax(180px, 1fr));
      gap: 16px;
    }

    dt {
      font-weight: 600;
    }

    dd {
      margin: 8px 0;
    }
  `],
  template: `
    <section class="detail-panel" aria-labelledby="sms-quality-heading">
      <h2 id="sms-quality-heading">SMS delivery and queue</h2>

      <p>
        Submission success means a message was accepted;
        delivery means it completed.
        Successful submission alone does not prove timely delivery.
      </p>

      @if (window(); as window) {
        <p>
          {{ window.windowStart | date:'dd MMM yyyy HH:mm':'UTC' }} UTC
          · Window quality: {{ window.quality }}
          · Source: {{ freshness() }}
        </p>

        @if (freshness() !== 'FRESH') {
          <p role="status">
            Current queue health cannot be confirmed.
            Available values are historical evidence.
          </p>
        }

        <dl class="metrics">
          <div>
            <dt>Delivery p95</dt>
            <dd>
              {{ p95() ?? 'Unavailable' }}
              {{ p95() !== null ? ' ms' : '' }}
            </dd>
          </div>

          <div>
            <dt>Expected delivery p95</dt>
            <dd>
              {{ baseline() ?? 'Unavailable' }}
              {{ baseline() !== null ? ' ms' : '' }}
            </dd>
          </div>

          <div>
            <dt>Queue depth</dt>
            <dd>
              {{ depth() ?? 'Unavailable' }}
              {{ depth() !== null ? ' messages' : '' }}
            </dd>
          </div>

          <div>
            <dt>Oldest queued-message age</dt>
            <dd>
              {{ age() ?? 'Unavailable' }}
              {{ age() !== null ? ' s' : '' }}
            </dd>
          </div>
        </dl>

        <app-sample-volume [count]="samples()" />

        <p>
          Delivery p95 describes the delay at or below which
          95% of completed-message samples fall.
          Pending messages are outside those samples.
        </p>

        @if (samples() === 0 && depth() !== null && depth()! > 0) {
          <p role="status">
            No messages completed while a backlog remains.
            The service may still be degraded;
            inspect queue depth and oldest age even though p95 is unavailable.
          </p>
        }

        @if (depth() === null || age() === null) {
          <p role="status">
            Queue evidence is unavailable or incomplete.
            An empty queue cannot be confirmed.
          </p>
        }
      } @else {
        <p>
          No SMS observation available.
          Delivery and queue metrics are unavailable.
        </p>
      }
    </section>
  `,
})
export class SmsQualityComponent {
  readonly window = input<KpiWindow | null>(null);
  readonly freshness = input<ServiceSummary['freshness']>('MISSING');

  private kpi(name: string, unit: string) {
    return this.window()?.kpis.find(
      kpi => kpi.name === name && kpi.unit === unit,
    );
  }

  private value(name: string, unit: string): number | null {
    if (this.window()?.quality === 'MISSING') return null;
    return this.kpi(name, unit)?.observed ?? null;
  }

  samples() {
    return this.value('deliveredMessages', 'COUNT');
  }

  depth() {
    return this.value('queueDepth', 'COUNT');
  }

  age() {
    return this.value('oldestPendingAgeSec', 'SECONDS');
  }

  p95() {
    // A missing or zero completed count cannot support a measured percentile.
    const samples = this.samples();

    return samples === null || samples === 0
      ? null
      : this.value('p95DeliveryMs', 'MILLISECONDS');
  }

  baseline() {
    return this.kpi('p95DeliveryMs', 'MILLISECONDS')?.baseline ?? null;
  }
}