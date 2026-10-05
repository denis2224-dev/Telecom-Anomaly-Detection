import { Component, computed, effect, input, signal } from '@angular/core';
import { DatePipe } from '@angular/common';
import type { KpiWindow } from './voice-model';
import { Detection, formatMetric, metricValue, phaseAt } from './assurance-model';
@Component({
  selector: 'app-metric-chart', imports: [DatePipe],
  template: `<section class="detail-panel metric-chart" [attr.data-chart]="name()">
    <h2>{{ title() }}</h2>
    <div class="chart-legend"><span>━━ Actual {{ unit() }}</span><span class="expected-key">┄┄ Contextual baseline</span><span class="state-degraded">DEGRADED</span><span class="state-recovery">RECOVERY</span><span class="state-unknown">UNKNOWN / MISSING</span></div>
    @if (!hasBaseline()) { <p class="muted">No baseline available for this metric.</p> }
    @if (rows().length) {
      <div class="chart-scroll" tabindex="0" [attr.aria-label]="title() + ' chart'">
        <svg viewBox="0 0 900 220" role="img" [attr.aria-label]="title() + ': actual versus baseline, with persisted detection phases'">
          <title>{{ title() }} · {{ unit() }}</title><desc>Blank actual gaps are unavailable measurements. Phase bands follow persisted detections; healthy points alone do not prove recovery. Exact values are below.</desc>
          @for (band of bands(); track $index) { <rect [attr.data-phase]="band.phase" [attr.class]="'phase-band ' + band.phase" [attr.x]="x(band.start)" y="15" [attr.width]="Math.max(1, x(band.end) - x(band.start))" height="160"><title>{{ band.phase }} {{ band.start }} – {{ band.end }}</title></rect> }
          @for (tick of ticks(); track tick) { <line x1="65" x2="875" [attr.y1]="y(tick)" [attr.y2]="y(tick)" class="chart-grid"/><text x="55" [attr.y]="y(tick) + 4" text-anchor="end">{{ format(tick) }}</text> }
          <path class="expected-line" [attr.d]="path(true)"/><path class="actual-line" [attr.d]="path(false)"/>
          @if (rows().length < 120) { @for (row of rows(); track row.windowId) { @if (value(row) !== null) { <circle class="actual-dot" [attr.cx]="x(row.windowStart)" [attr.cy]="y(value(row)!)" r="3"><title>{{ row.windowStart }}: {{ format(value(row), unit()) }} · {{ phase(row) }}</title></circle> } } }
          <text x="65" y="205">{{ from() | date:'dd MMM HH:mm':'UTC' }}</text><text x="875" y="205" text-anchor="end">{{ to() | date:'dd MMM HH:mm':'UTC' }} UTC</text>
        </svg>
      </div>
      <details><summary>Exact {{ title() }} values ({{ rows().length }} windows)</summary><div class="chart-scroll" tabindex="0"><table class="kpi-table"><thead><tr><th>UTC start</th><th>Actual</th><th>Baseline</th><th>State</th><th>Quality</th></tr></thead><tbody>
        @for (row of tableRows(); track row.windowId) { <tr><th>{{ row.windowStart | date:'dd MMM HH:mm':'UTC' }}</th><td>{{ format(value(row), unit()) }}</td><td>{{ format(baseline(row), unit()) }}</td><td>{{ phase(row) }}</td><td>{{ row.quality }}</td></tr> }
      </tbody></table></div><nav class="pagination" [attr.aria-label]="title() + ' table pages'">
        <button (click)="tablePage.set(tablePage() - 1)" [disabled]="tablePage() === 0">Previous windows</button>
        <span>Page {{ tablePage() + 1 }} of {{ tablePages() }}</span>
        <button (click)="tablePage.set(tablePage() + 1)" [disabled]="tablePage() + 1 >= tablePages()">Next windows</button>
      </nav></details>
    } @else { <p role="status">No KPI history in this time range.</p> }
  </section>`,
})
export class MetricChartComponent {
  readonly windows = input<KpiWindow[]>([]); readonly detections = input<Detection[]>([]);
  readonly name = input.required<string>(); readonly title = input.required<string>(); readonly unit = input('');
  readonly from = input.required<string>(); readonly to = input.required<string>();
  readonly rows = computed(() => [...this.windows()].sort((a,b) => Date.parse(a.windowStart) - Date.parse(b.windowStart)));
  readonly tablePage = signal(0);
  readonly tablePages = computed(() => Math.max(1, Math.ceil(this.rows().length / 50)));
  readonly tableRows = computed(() => this.rows().slice(this.tablePage() * 50, (this.tablePage() + 1) * 50));
  constructor() { effect(() => { this.windows(); this.tablePage.set(0); }); }
  readonly bounds = computed(() => {
    const values = this.rows().flatMap(row => [this.value(row), this.baseline(row)]).filter((v): v is number => v != null && Number.isFinite(v));
    if (!values.length) return [0, 1];
    const min = Math.min(...values), max = Math.max(...values), margin = Math.max((max - min) * .1, max * .02, .01);
    return [Math.max(0, min - margin), max + margin];
  });
  readonly ticks = computed(() => [this.bounds()[0], (this.bounds()[0] + this.bounds()[1]) / 2, this.bounds()[1]]);
  readonly hasBaseline = computed(() => this.rows().some(row => this.baseline(row) !== null));
  readonly bands = computed(() => {
    const bands: { start: string; end: string; phase: string }[] = [];
    let previousEnd = this.from();
    for (const row of this.rows()) {
      if (Date.parse(row.windowStart) > Date.parse(previousEnd)) bands.push({ start: previousEnd, end: row.windowStart, phase: 'UNKNOWN' });
      const phase = this.phase(row), previous = bands.at(-1);
      if (previous?.phase === phase && Date.parse(previous.end) === Date.parse(row.windowStart)) previous.end = row.windowEnd;
      else bands.push({ start: row.windowStart, end: row.windowEnd, phase });
      previousEnd = row.windowEnd;
    }
    if (Date.parse(previousEnd) < Date.parse(this.to())) bands.push({ start: previousEnd, end: this.to(), phase: 'UNKNOWN' });
    return bands;
  });
  readonly format = formatMetric; readonly Math = Math;
  value(row: KpiWindow) { return metricValue(row, this.name()); }
  baseline(row: KpiWindow) { return row.kpis.find(item => item.name === this.name())?.baseline ?? null; }
  phase(row: KpiWindow) { return phaseAt(row, this.detections()); }
  x(time: string) { return 65 + 810 * Math.max(0, Math.min(1, (Date.parse(time) - Date.parse(this.from())) / (Date.parse(this.to()) - Date.parse(this.from())))); }
  y(value: number) { return 175 - 160 * (value - this.bounds()[0]) / (this.bounds()[1] - this.bounds()[0]); }
  path(expected: boolean) {
    let connected = false, previousEnd = '', result = '';
    for (const row of this.rows()) {
      const value = expected ? this.baseline(row) : this.value(row);
      if (value === null || !Number.isFinite(value)) { connected = false; continue; }
      result += `${connected && Date.parse(previousEnd) === Date.parse(row.windowStart) ? 'L' : 'M'}${this.x(row.windowStart)},${this.y(value)} `;
      connected = true; previousEnd = row.windowEnd;
    }
    return result.trim();
  }
}
