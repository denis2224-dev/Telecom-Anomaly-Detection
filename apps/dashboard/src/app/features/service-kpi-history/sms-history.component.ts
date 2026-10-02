import { DatePipe } from '@angular/common';
import { Component, computed, input } from '@angular/core';
import type { KpiWindow } from './voice-model';

@Component({
  selector: 'app-sms-history',
  imports: [DatePipe],
  template: `
    <section class="detail-panel">
      <h2>SMS delivery history</h2>

      <p>
        Compare delivery delay, queue depth, and completed samples
        across the selected interval. Missing service telemetry
        cannot demonstrate healthy delivery.
      </p>

      <div
        class="chart-scroll"
        tabindex="0"
        role="region"
        aria-label="SMS history"
      >
        <table class="kpi-table">
          <caption>SMS windows in UTC</caption>
          <thead>
            <tr>
              <th scope="col">Window start</th>
              <th scope="col">Quality</th>
              <th scope="col">Delivery p95 (ms)</th>
              <th scope="col">Expected p95 (ms)</th>
              <th scope="col">Queue depth</th>
              <th scope="col">Oldest age (s)</th>
              <th scope="col">Completed samples</th>
            </tr>
          </thead>
          <tbody>
            @for (row of rows(); track row.windowId) {
              <tr [attr.data-window-id]="row.windowId">
                <th scope="row">
                  {{ row.windowStart | date:'HH:mm:ss':'UTC' }}
                </th>
                <td>{{ row.quality }}</td>
                <td>{{ p95(row) ?? 'Unavailable' }}</td>
                <td>{{ baseline(row) ?? 'Unavailable' }}</td>
                <td>{{ value(row, 'queueDepth') ?? 'Unavailable' }}</td>
                <td>
                  {{ value(row, 'oldestPendingAgeSec') ?? 'Unavailable' }}
                </td>
                <td>
                  {{ value(row, 'deliveredMessages') ?? 'Unavailable' }}
                </td>
              </tr>
            } @empty {
              <tr>
                <td colspan="7">No SMS history in this interval.</td>
              </tr>
            }
          </tbody>
        </table>
      </div>
    </section>
  `,
})
export class SmsHistoryComponent {
  readonly windows = input<KpiWindow[]>([]);

  readonly rows = computed(() =>
    [...this.windows()].sort(
      (a, b) =>
        Date.parse(a.windowStart) - Date.parse(b.windowStart),
    ),
  );

  value(row: KpiWindow, name: string): number | null {
    if (row.quality === 'MISSING') return null;

    return row.kpis.find(kpi => kpi.name === name)?.observed
      ?? null;
  }

  p95(row: KpiWindow): number | null {
    const count = this.value(row, 'deliveredMessages');

    return count === null || count === 0
      ? null
      : this.value(row, 'p95DeliveryMs');
  }

  baseline(row: KpiWindow): number | null {
    return row.kpis.find(kpi => kpi.name === 'p95DeliveryMs')
      ?.baseline ?? null;
  }
}
