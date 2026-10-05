import { Component, input } from '@angular/core';
import { DatePipe } from '@angular/common';
import type { ServiceSummary } from '../../core/api/telecom-client';
import { SMS_METRICS, VOLTE_METRICS, delta, formatMetric, metricValue } from './assurance-model';
@Component({
  selector: 'app-kpi-cards', imports: [DatePipe],
  template: `<section class="kpi-cards" aria-label="Current service KPIs">
    @for (metric of metrics(); track metric[0]) {
      <article class="kpi-card" [attr.data-kpi-card]="metric[0]" [class.no-measurement]="value(metric[0]) === null">
        <h3>{{ metric[1] }}</h3><strong class="kpi-value">{{ format(value(metric[0]), kpi(metric[0])?.unit) }}</strong>
        <p>Baseline: {{ format(kpi(metric[0])?.baseline, kpi(metric[0])?.unit) }}</p>
        <p>{{ difference(service().latestWindow, metric[0]) }}</p>
        <small>{{ value(metric[0]) === null ? 'UNKNOWN / MISSING' : service().freshness === 'FRESH' ? 'OBSERVED' : 'HISTORICAL / ' + service().freshness }} · {{ service().latestWindow?.quality ?? 'MISSING' }}</small>
        <small>{{ service().latestWindow?.windowStart | date:'dd MMM HH:mm':'UTC' }} UTC</small>
      </article>
    }
  </section>`,
})
export class KpiCardsComponent {
  readonly service = input.required<ServiceSummary>();
  readonly format = formatMetric; readonly difference = delta;
  metrics() { return this.service().scope.service === 'VOLTE' ? VOLTE_METRICS : SMS_METRICS; }
  kpi(name: string) { return this.service().latestWindow?.kpis.find(item => item.name === name); }
  value(name: string) { return metricValue(this.service().latestWindow, name); }
}
