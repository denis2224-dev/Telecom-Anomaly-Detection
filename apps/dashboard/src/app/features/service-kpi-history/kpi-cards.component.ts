import type { KpiWindow } from './voice-model';
import { Component, computed, input } from '@angular/core';
import { DatePipe } from '@angular/common';
import type { ServiceSummary } from '../../core/api/telecom-client';
import { SMS_METRICS, VOLTE_METRICS, delta, formatMetric, metricValue } from './assurance-model';
@Component({
  selector: 'app-kpi-cards', imports: [DatePipe],
  template: `<section class="kpi-summary" aria-label="Current service KPIs"><div class="chart-scroll" tabindex="0" aria-label="Current KPI table">
    <table class="kpi-table"><thead><tr><th>KPI</th><th>Observed</th><th>Baseline</th><th>Δ</th><th>State</th><th>Updated (UTC)</th><th>History</th></tr></thead><tbody>
    @for (metric of metrics(); track metric[0]) {
      <tr [attr.data-kpi-card]="metric[0]" [class.no-measurement]="value(metric[0]) === null">
        <th>{{ metric[1] }}</th><td class="kpi-value">{{ format(value(metric[0]), kpi(metric[0])?.unit) }}</td>
        <td>{{ format(kpi(metric[0])?.baseline, kpi(metric[0])?.unit) }}</td><td>{{ difference(service().latestWindow, metric[0]) }}</td>
        <td>{{ value(metric[0]) === null ? 'UNKNOWN / MISSING' : service().freshness === 'FRESH' ? 'OBSERVED' : 'HISTORICAL / ' + service().freshness }}</td>
        <td>{{ service().latestWindow?.windowStart | date:'dd MMM HH:mm':'UTC' }}</td>
        <td>@if (sparklines()[metric[0]]; as path) { <svg viewBox="0 0 80 24" role="img" [attr.aria-label]="metric[1] + ' history; gaps mean unavailable'"><path [attr.d]="path" /></svg> } @else { <span class="helper">No history</span> }</td>
      </tr>
    }</tbody></table></div></section>`,
  styles: [`
    .kpi-summary { background: var(--surface); border-radius: var(--radius); }
    th, td { padding: 6px 10px; font-size: 11px; white-space: nowrap; }
    .kpi-value { font-size: 13px; font-weight: 600; }
    svg { display: block; width: 80px; height: 24px; }
    path { fill: none; stroke: var(--accent); stroke-width: 1.5; }
  `],
})
export class KpiCardsComponent {
  readonly service = input.required<ServiceSummary>();
  readonly windows = input<KpiWindow[]>([]);
  readonly sparklines = computed(() => {
    const rows = [...this.windows()].sort((a,b) => Date.parse(a.windowStart) - Date.parse(b.windowStart));
    return Object.fromEntries(this.metrics().map(([name]) => {
      const values = rows.map(row => metricValue(row, name));
      const valid = values.filter((v): v is number => v !== null && Number.isFinite(v));
      if (valid.length < 2) return [name, ''];
      const min = Math.min(...valid), span = Math.max(...valid) - min || 1;
      let connected = false;
      return [name, values.map((value, i) => {
        if (value === null || !Number.isFinite(value)) { connected = false; return ''; }
        const command = connected && rows[i-1].windowEnd === rows[i].windowStart ? 'L' : 'M';
        connected = true;
        return `${command}${i / Math.max(1, rows.length - 1) * 80},${22 - (value - min) / span * 20}`;
      }).join(' ')];
    }));
  });
  readonly format = formatMetric; readonly difference = delta;
  metrics() { return this.service().scope.service === 'VOLTE' ? VOLTE_METRICS : SMS_METRICS; }
  kpi(name: string) { return this.service().latestWindow?.kpis.find(item => item.name === name); }
  value(name: string) { return metricValue(this.service().latestWindow, name); }
}
