import { Component, input } from '@angular/core';
import { DatePipe } from '@angular/common';
import type { ServiceSummary } from '../../core/api/telecom-client';
import { SMS_METRICS, VOLTE_METRICS, delta, formatMetric, metricValue } from './assurance-model';
@Component({
  selector: 'app-kpi-cards', imports: [DatePipe],
  template: `<section class="kpi-summary" aria-label="Current service KPIs"><div class="chart-scroll" tabindex="0" aria-label="Current KPI table">
    <table class="kpi-table"><thead><tr><th>KPI</th><th>Observed</th><th>Baseline</th><th>Change</th><th>State</th><th>Updated (UTC)</th></tr></thead><tbody>
    @for (metric of metrics(); track metric[0]) {
      <tr [attr.data-kpi-card]="metric[0]" [class.no-measurement]="value(metric[0]) === null">
        <th scope="row">{{ metric[1] }}</th><td class="kpi-value">{{ format(value(metric[0]), kpi(metric[0])?.unit) }}</td>
        <td>{{ format(kpi(metric[0])?.baseline, kpi(metric[0])?.unit) }}</td><td>{{ difference(service().latestWindow, metric[0]) }}</td>
        <td>{{ value(metric[0]) === null ? 'UNKNOWN / MISSING' : service().freshness === 'FRESH' ? 'OBSERVED' : 'HISTORICAL / ' + service().freshness }}</td>
        <td>{{ service().latestWindow?.windowStart | date:'dd MMM HH:mm':'UTC' }}</td>
      </tr>
    }</tbody></table></div></section>`,
  styles: [`
    .kpi-summary { background: var(--surface); border-radius: var(--radius); }
    th, td { padding: 5px 10px; font-family: var(--font-family); font-size: 13px; font-weight: 400; white-space: nowrap; letter-spacing: normal; }
    td:nth-child(2), td:nth-child(3), td:nth-child(4) { text-align: right; }
    .kpi-value { color: var(--text); }
  `],
})
export class KpiCardsComponent {
  readonly service = input.required<ServiceSummary>();
  readonly format = formatMetric; readonly difference = delta;
  metrics() { return this.service().scope.service === 'VOLTE' ? VOLTE_METRICS : SMS_METRICS; }
  kpi(name: string) { return this.service().latestWindow?.kpis.find(item => item.name === name); }
  value(name: string) { return metricValue(this.service().latestWindow, name); }
}
