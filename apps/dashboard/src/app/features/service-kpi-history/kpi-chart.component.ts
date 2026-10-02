import { Component, computed, effect, input, signal } from '@angular/core';
import { IconComponent } from '../../shared/icon.component';
import { cssr, observed, clock, type KpiWindow, type Incident } from './voice-model';

@Component({
  selector: 'app-kpi-chart',
  imports: [IconComponent],
  template: `
    <section class="detail-panel chart-panel" aria-labelledby="chart-title">
      <div class="section-heading"><div><p class="eyebrow">Voice performance</p><h2 id="chart-title">Call setup success rate</h2></div><span class="badge">CSSR · %</span></div>
      <p class="muted">CSSR is the percentage of call attempts that connected. Times are UTC; each point represents one server window.</p>
      <div class="chart-legend"><span class="actual-key">Actual CSSR (%)</span><span class="expected-key">Expected baseline (%)</span><span class="incident-key">Incident interval</span></div>
      <p>{{ hasAttemptCounts() ? attempts().toLocaleString('en') + ' recorded attempts' : 'Attempt counts unavailable' }} · {{ unavailable() }} windows with unavailable success rate. Exact per-window counts are below.</p>
      @if (rows().length) {
        <div class="plot-wrapper" (mouseleave)="chartScrollable.matches(':focus') || hovered.set(null)">
          <div class="chart-tooltip" [class.tooltip-visible]="hovered() !== null" aria-live="polite">
            @if (hovered(); as row) { <span>{{ clock(row.windowStart) }} UTC</span><strong>{{ value(row) ?? 'Unavailable' }}{{ value(row) !== null ? '%' : '' }}</strong><small>{{ metric(row)?.denominator ?? 'Unknown' }} attempts</small> } @else { <span>Window detail</span><strong>Explore the observations</strong><small>Hover a point or focus the chart and use arrow keys.</small> }
          </div>
        <div #chartScrollable class="chart-scroll" tabindex="0" aria-label="Scrollable CSSR chart" aria-describedby="chart-keyboard-hint" (keydown)="navigateChart($event)" (blur)="hovered.set(null)">
          <svg viewBox="0 0 900 280" role="img" aria-labelledby="plot-title plot-desc">
            <defs><linearGradient id="cssr-area" x1="0" y1="0" x2="0" y2="1"><stop offset="0%" class="area-start" /><stop offset="100%" class="area-end" /></linearGradient></defs>
            <title id="plot-title">Actual and expected voice call setup success</title>
            <desc id="plot-desc">Vertical scale {{ floor() }} to 100 percent. Blank gaps mean unavailable observations. Shaded bands show incident intervals. Exact values and attempts are in the table below.</desc>
            @for (incident of intervals(); track incident.episodeId) {
              <rect class="incident-band" [attr.x]="x(incident.firstObservedAt)" y="20" [attr.width]="Math.max(0, x(incident.lastObservedAt) - x(incident.firstObservedAt))" height="210" />
            }
            @for (tick of ticks(); track tick) {
              <line x1="65" x2="875" [attr.y1]="y(tick)" [attr.y2]="y(tick)" class="chart-grid" />
              <text x="55" [attr.y]="y(tick) + 5" text-anchor="end">{{ tick }}%</text>
            }
            <path class="actual-area" [attr.d]="areaPath()" />
            <path class="expected-line" [attr.d]="path(true)" />
            <path class="actual-line" [attr.d]="path(false)" />
            @for (row of rows(); track row.windowId) {
              @if (value(row) !== null) { <circle class="actual-dot" [class.is-dip]="metric(row)?.baseline != null && value(row)! < metric(row)!.baseline!" [class.is-active]="hovered()?.windowId === row.windowId" (mouseenter)="hovered.set(row)" [attr.cx]="midpoint(row)" [attr.cy]="y(value(row)!)" r="4"><title>{{ clock(row.windowStart) }} UTC: {{ value(row) }}%; {{ metric(row)?.denominator ?? 'unknown' }} attempts</title></circle> }
            }
            @for (label of labels(); track label; let first = $first; let last = $last) { <text [attr.x]="x(label)" y="258" [attr.text-anchor]="first ? 'start' : last ? 'end' : 'middle'">{{ clock(label) }}</text> }
          </svg>
        </div></div>
        <p id="chart-keyboard-hint" class="helper">Focus the chart and use arrow keys to inspect windows. Exact values are available below.</p>
        <p class="muted">Scale: {{ floor() }}–100%. Gaps are unavailable data, not 0% success. A zero attempt count also has no success rate.</p>
        <details><summary>Show exact values and attempt counts ({{ rows().length }} windows)</summary>
          <div class="chart-scroll"><table class="kpi-table"><caption>Server windows in UTC · start inclusive, end exclusive</caption><thead><tr><th>Window</th><th>Actual (%)</th><th>Expected (%)</th><th>Attempts</th><th>Data quality</th></tr></thead><tbody>
            @for (row of tableRows(); track row.windowId) { <tr [attr.data-window-id]="row.windowId"><th>{{ clock(row.windowStart) }}–{{ clock(row.windowEnd) }}</th><td>{{ value(row) ?? 'Unavailable' }}</td><td>{{ metric(row)?.baseline ?? 'Unavailable' }}</td><td>{{ metric(row)?.denominator ?? 'Unavailable' }}</td><td>{{ row.quality }}</td></tr> }
          </tbody></table></div>
          <nav class="pagination" aria-label="History table pages">
            <button (click)="tablePage.set(tablePage() - 1)" [disabled]="tablePage() === 0"><app-icon name="left" />Previous windows</button>
            <span>Page {{ tablePage() + 1 }} of {{ tablePages() }} · {{ rows().length }} windows total</span>
            <button (click)="tablePage.set(tablePage() + 1)" [disabled]="tablePage() + 1 >= tablePages()">Next windows<app-icon name="right" /></button>
          </nav>
        </details>
      } @else { <p role="status">No voice KPI history in this time range.</p> }
    </section>`,
})
export class KpiChartComponent {
  readonly hovered = signal<KpiWindow | null>(null);
  navigateChart(event: KeyboardEvent): void {
    if (!['ArrowLeft', 'ArrowRight', 'Home', 'End'].includes(event.key)) return;
    event.preventDefault();
    const rows = this.rows();
    if (!rows.length) return;
    const current = rows.findIndex(row => row.windowId === this.hovered()?.windowId);
    const next = event.key === 'Home' ? 0 : event.key === 'End' ? rows.length - 1
      : Math.max(0, Math.min(rows.length - 1, current + (event.key === 'ArrowRight' ? 1 : -1)));
    this.hovered.set(rows[next]);
  }
  areaPath(): string {
    return this.path(false).split('M').filter(Boolean).map(segment => {
      const points = segment.trim().split('L');
      const firstX = points[0].split(',')[0], lastX = points.at(-1)!.split(',')[0];
      return `M${segment.trim()} L${lastX},230 L${firstX},230 Z`;
    }).join(' ');
  }
  readonly windows = input<KpiWindow[]>([]);
  readonly incidents = input<Incident[]>([]);
  readonly from = input.required<string>();
  readonly to = input.required<string>();
  readonly rows = computed(() => [...this.windows()].sort((a,b) => Date.parse(a.windowStart) - Date.parse(b.windowStart)));
  readonly tablePage = signal(0);
  readonly tablePages = computed(() => Math.max(1, Math.ceil(this.rows().length / 50)));
  readonly tableRows = computed(() => this.rows().slice(this.tablePage() * 50, (this.tablePage() + 1) * 50));
  constructor() {
    effect(() => {
      this.windows();
      this.tablePage.set(0);
      this.hovered.set(null);
    });
  }
  readonly attempts = computed(() => this.rows().reduce((total, row) => total + (cssr(row)?.denominator ?? 0), 0));
  readonly hasAttemptCounts = computed(() => this.rows().some(row => cssr(row)?.denominator != null));
  readonly unavailable = computed(() => this.rows().filter(row => observed(row) === null).length);
  readonly intervals = computed(() => this.incidents().filter(item => Date.parse(item.firstObservedAt) < Date.parse(this.to()) && Date.parse(item.lastObservedAt) > Date.parse(this.from())));
  readonly floor = computed(() => {
    const values = this.rows().flatMap(row => [observed(row), cssr(row)?.baseline]).filter((v): v is number => v != null && Number.isFinite(v));
    return Math.max(0, Math.min(95, Math.floor((Math.min(100, ...values) - 2) / 5) * 5));
  });
  readonly ticks = computed(() => [this.floor(), (100 + this.floor()) / 2, 100]);
  readonly labels = computed(() => [this.from(), new Date((Date.parse(this.from()) + Date.parse(this.to())) / 2).toISOString(), this.to()]);
  readonly clock = clock; readonly metric = cssr; readonly value = observed; readonly Math = Math;
  x(time: string) { return 65 + 810 * Math.max(0, Math.min(1, (Date.parse(time) - Date.parse(this.from())) / (Date.parse(this.to()) - Date.parse(this.from())))); }
  y(value: number) { return 230 - 210 * (value - this.floor()) / (100 - this.floor()); }
  midpoint(row: KpiWindow) { return this.x(new Date((Date.parse(row.windowStart) + Date.parse(row.windowEnd)) / 2).toISOString()); }
  path(expected: boolean) {
    let connected = false, previousEnd = '', result = '';
    for (const row of this.rows()) {
      const value = expected ? cssr(row)?.baseline ?? null : observed(row);
      if (value === null || !Number.isFinite(value)) { connected = false; continue; }
      const contiguous = Date.parse(previousEnd) === Date.parse(row.windowStart);
      result += `${connected && contiguous ? 'L' : 'M'}${this.midpoint(row)},${this.y(value)} `;
      connected = true; previousEnd = row.windowEnd;
    }
    return result.trim();
  }
}
