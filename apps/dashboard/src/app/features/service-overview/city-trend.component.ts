import { Component, computed, input } from '@angular/core';
import { DatePipe } from '@angular/common';
import { baseline, measured, number, type Service, type Window } from './dashboard-geography';

@Component({
  selector: 'app-city-trend',
  imports: [DatePipe],
  template: `
    <details class="trend" [open]="!collapsed()">
    <summary class="trend-title">{{ service() === 'VOLTE' ? (compact() ? 'CSSR (%)' : 'Call setup success (%)') : 'Delivery p95 (ms)' }}</summary>
    @if (!compact()) { <p class="helper">{{ rows().length }} windows · {{ available() }} measured · {{ available() === 0 ? 'No usable measurements' : 'Observed and baseline' }}</p> }
    @if (rows().length > 0) {
      <svg viewBox="0 0 300 140" role="img" [attr.aria-label]="description()">
        <path d="M40 15V105H290" class="axis" />
        <text x="2" y="20">{{ number(bounds().max) }}</text>
        <text x="2" y="105">{{ number(bounds().min) }}</text>
        <text x="40" y="123">{{ from() | date:'HH:mm':'UTC' }}</text>
        <text x="290" y="123" text-anchor="end">{{ to() | date:'HH:mm':'UTC' }}</text>
        <text x="165" y="138" text-anchor="middle">Time (UTC)</text>
        <path [attr.d]="path(true)" class="expected" />
        <path [attr.d]="path(false)" class="actual" />
      </svg>
      @if (!compact()) { <p class="helper">Solid cyan: observed · dashed: baseline · gaps: unavailable</p> }
      @if (!compact()) { <details>
        <summary>Exact values and sample counts</summary>
        <div class="values"><table>
          <caption>{{ service() === 'VOLTE' ? 'CSSR (%)' : 'Delivery p95 (ms)' }} by one-minute window; UTC</caption>
          <thead><tr><th>Start</th><th>Observed</th><th>Baseline</th><th>Samples</th></tr></thead>
          <tbody>@for (row of rows(); track row.windowId) {
            <tr><td>{{ row.windowStart | date:'HH:mm':'UTC' }}</td>
              <td>{{ measured(row, service()) === null ? 'Unavailable' : number(measured(row, service())!) }}</td>
              <td>{{ baseline(row, service()) === null ? 'Unavailable' : number(baseline(row, service())!) }}</td>
              <td>{{ samples(row) }}</td></tr>
          }</tbody>
        </table></div>
      </details> }
    } @else { <p>No KPI windows returned for this range.</p> }
    </details>
  `,
  styles: [`
    :host { display: block; min-width: 0; }
    .trend { margin-top: 6px; }
    .trend-title { padding: 2px 0; font-size: 10px; font-weight: 600; }
    .helper { font-size: 10px; line-height: 1.3; }
    summary { font-size: 10px; }
    svg { width: 100%; height: auto; max-height: 105px; overflow: visible; }
    text { fill: var(--text-muted); font-size: 9px; }
    path { fill: none; stroke-width: 2; }
    .axis { stroke: var(--text-muted); stroke-width: 1; }
    .expected { stroke: var(--text-muted); stroke-dasharray: 5 4; }
    .actual { stroke: var(--accent); }
    .values { overflow-x: auto; }
    table { width: 100%; font-size: 11px; border-collapse: collapse; }
    th, td { text-align: left; padding: 5px; }
    caption { text-align: left; color: var(--text-muted); }
    summary { cursor: pointer; }
  `],
})
export class CityTrendComponent {
  readonly compact = input(false);
  readonly collapsed = input(false);
  readonly rows = input.required<Window[]>();
  readonly service = input.required<Service>();
  readonly from = input.required<string>();
  readonly to = input.required<string>();
  readonly measured = measured;
  readonly baseline = baseline;
  readonly number = number;
  readonly available = computed(() => this.rows().filter(row => measured(row, this.service()) !== null).length);
  readonly bounds = computed(() => {
    const values = this.rows().flatMap(row => [measured(row, this.service()), baseline(row, this.service())])
      .filter((value): value is number => value !== null);
    if (this.service() === 'VOLTE') return { min: 0, max: 100 };
    return { min: 0, max: Math.max(1, ...values) * 1.1 };
  });

  description(): string {
    return `${this.service() === 'VOLTE' ? 'Call setup success in percent' : 'Delivery p95 in milliseconds'}; ${this.available()} usable windows. Horizontal axis: time in UTC. Vertical axis: ${this.service() === 'VOLTE' ? 'percent' : 'milliseconds'}.`;
  }

  samples(row: Window): string {
    const value = this.service() === 'VOLTE'
      ? row.kpis.find(kpi => kpi.name === 'cssrPct')?.denominator
      : row.kpis.find(kpi => kpi.name === 'deliveredMessages')?.observed;
    return value === null || value === undefined ? 'Unavailable' : number(value);
  }

  path(expected: boolean): string {
    const from = Date.parse(this.from()), duration = Date.parse(this.to()) - from;
    if (!Number.isFinite(from) || duration <= 0) return '';
    let result = '', connected = false, previousEnd = '';
    const { min, max } = this.bounds();
    for (const row of [...this.rows()].sort((a, b) => Date.parse(a.windowStart) - Date.parse(b.windowStart))) {
      const value = expected ? baseline(row, this.service()) : measured(row, this.service());
      if (value === null) { connected = false; continue; }
      const midpoint = (Date.parse(row.windowStart) + Date.parse(row.windowEnd)) / 2;
      const x = 40 + Math.max(0, Math.min(1, (midpoint - from) / duration)) * 250;
      const y = 105 - Math.max(0, Math.min(1, (value - min) / (max - min))) * 90;
      const contiguous = Date.parse(previousEnd) === Date.parse(row.windowStart);
      result += `${connected && contiguous ? 'L' : 'M'}${x},${y} `;
      previousEnd = row.windowEnd;
      connected = true;
    }
    return result.trim();
  }
}
