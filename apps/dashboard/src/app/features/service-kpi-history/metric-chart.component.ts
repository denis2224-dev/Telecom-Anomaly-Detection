import { Component, computed, effect, input, output, signal, viewChild, ElementRef } from '@angular/core';
import { DatePipe } from '@angular/common';
import type { KpiWindow, Incident } from './voice-model';
import { Detection, formatMetric, metricValue, phaseAt } from './assurance-model';
@Component({
  selector: 'app-metric-chart', imports: [DatePipe],
  template: `<section class="detail-panel metric-chart" [class.hero-chart]="hero()" [class.compact-chart]="compact()" [class.traffic-chart]="traffic()" [attr.data-chart]="name()">
    @if (!compact()) { <div class="metric-heading"><h2>{{ title() }}</h2>@if (hero()) { <div class="hero-reading"><strong>{{ format(latestValue(), unit()) }}</strong><span>{{ rows().at(-1)?.windowStart | date:'HH:mm:ss':'UTC' }} UTC</span></div> }</div> }
    @if (view() === 'both') { <div class="chart-legend"><span>━━ Actual {{ unit() }}</span><span class="expected-key">┄┄ Contextual baseline</span><span class="state-degraded">DEGRADED</span><span class="state-recovery">RECOVERY</span><span class="state-unknown">UNKNOWN / MISSING</span></div> }
    @if (!hasBaseline() && !hero()) { <p class="muted">No baseline available for this metric.</p> }
    @if (rows().length || hero()) {
      @if (view() !== 'table') { <div class="plot-wrapper">
        <div class="chart-tooltip" [class.tooltip-visible]="hovered() !== null" aria-live="polite">
          @if (hovered(); as row) { <span>{{ row.windowStart | date:'dd MMM HH:mm:ss':'UTC' }} UTC</span><strong>{{ format(value(row), unit()) }}</strong>
            @if (traffic()) { @if (counts(row); as count) { <small>{{ name() === 'cssrPct' ? 'Successful calls' : 'Delivered messages' }}: {{ format(count.success, 'COUNT') }} · Failed: {{ format(count.failed, 'COUNT') }}</small> } @else { <small>Traffic counts: Unavailable</small> } }
            <small>Baseline: {{ format(baseline(row), unit()) }} · {{ phase(row) }}</small> }
        </div>
        <div class="chart-scroll" tabindex="0" [attr.aria-label]="name() === 'cssrPct' ? 'Scrollable CSSR chart' : title() + ' chart'" (keydown)="navigate($event)" (blur)="hoveredId.set(null)">

        <svg #plot [attr.viewBox]="'0 0 ' + plotWidth() + ' ' + plotHeight()" role="img" [attr.aria-label]="traffic() ? title() + ': successful and failed traffic lines on the left axis, success rate and baseline on the right axis' : name() === 'cssrPct' ? 'Actual and expected voice call setup success' : title() + ': actual versus baseline, with persisted detection phases'" [attr.aria-describedby]="chartId + '-description'" (pointermove)="point($event)" (pointerleave)="clearPointer($event)">
          <title>{{ title() }} · {{ unit() }}</title><desc [id]="chartId + '-description'">Blank gaps mean unavailable observations. Phase bands follow persisted detections; healthy points alone do not prove recovery. Focus the chart and use arrow keys, Home or End to inspect values.</desc>
          @for (interval of incidentBands(); track $index) { <rect class="incident-band" [attr.x]="x(interval.start)" y="15" [attr.width]="Math.max(1, x(interval.end) - x(interval.start))" [attr.height]="plotHeight() - 60"><title>Incident interval {{ interval.start }} – {{ interval.end }}</title></rect> }
          @for (band of bands(); track $index) { <rect [attr.data-phase]="band.phase" [attr.class]="'phase-band ' + band.phase" [attr.x]="x(band.start)" y="15" [attr.width]="Math.max(1, x(band.end) - x(band.start))" [attr.height]="plotHeight() - 60"><title>{{ band.phase }} {{ band.start }} – {{ band.end }}</title></rect> }
          @for (tick of ticks(); track tick) { <line x1="65" [attr.x2]="plotWidth() - plotRight()" [attr.y1]="y(tick)" [attr.y2]="y(tick)" class="chart-grid"/><text [attr.x]="traffic() ? plotWidth() - 55 : 55" [attr.y]="y(tick) + 4" [attr.text-anchor]="traffic() ? 'start' : 'end'">{{ tickFormat(tick) }}{{ traffic() ? '%' : '' }}</text> }
          @if (traffic()) {
            @for (tick of countTicks(); track tick) { <text x="55" [attr.y]="countY(tick) + 4" text-anchor="end">{{ tickFormat(tick) }}</text> }
            <path class="traffic-success" [attr.d]="trafficPath(true)" /><path class="traffic-failed" [attr.d]="trafficPath(false)" />
            @if (rows().length < 120) { @for (item of trafficRows(); track item.row.windowId) {
              <circle class="traffic-success-dot" [attr.cx]="rowX(item.row)" [attr.cy]="countY(item.count.success)" r="2.5" />
              <circle class="traffic-failed-dot" [attr.cx]="rowX(item.row)" [attr.cy]="countY(item.count.failed)" r="2.5" />
            } }
          }
          <path class="expected-line" [attr.d]="path(true)"/><path class="actual-line" [attr.d]="path(false)"/>
          @if (rows().length < 120) { @for (row of rows(); track row.windowId) { @if (value(row) !== null) { <circle class="actual-dot" [attr.cx]="rowX(row)" [attr.cy]="y(value(row)!)" r="3"><title>{{ row.windowStart }}: {{ format(value(row), unit()) }} · {{ phase(row) }}</title></circle> } } }
          @if (hovered(); as row) {
            <line class="chart-crosshair" [attr.x1]="rowX(row)" [attr.x2]="rowX(row)" y1="15" [attr.y2]="plotHeight() - 45" />
            @if (value(row) !== null) { <circle class="actual-dot selected-dot" [attr.cx]="rowX(row)" [attr.cy]="y(value(row)!)" r="5" /> }
          }
          <text x="65" [attr.y]="plotHeight() - 15">{{ from() | date:'HH:mm':'UTC' }}</text><text [attr.x]="plotWidth() - plotRight()" [attr.y]="plotHeight() - 15" text-anchor="end">{{ to() | date:'HH:mm':'UTC' }} UTC</text>
        </svg>
      </div>
      @if (!hasValues() && !trafficRows().length) { <p class="chart-empty" role="status">{{ loading() ? 'Loading KPI history…' : rows().length ? 'No usable observations for this KPI. Gaps are unavailable.' : 'No KPI history in this time range.' }}</p> }
      </div>
      }
      @if (view() !== 'chart') { <details [open]="view() === 'table'"><summary>Exact {{ title() }} values ({{ rows().length }} windows)</summary><div class="chart-scroll" tabindex="0"><table class="kpi-table"><thead><tr><th>UTC start</th><th>Actual</th><th>Baseline</th><th>State</th><th>Quality</th></tr></thead><tbody>
        @for (row of tableRows(); track row.windowId) { <tr><th>{{ row.windowStart | date:'dd MMM HH:mm':'UTC' }}</th><td>{{ format(value(row), unit()) }}</td><td>{{ format(baseline(row), unit()) }}</td><td>{{ phase(row) }}</td><td>{{ row.quality }}</td></tr> }
      </tbody></table></div><nav class="pagination" [attr.aria-label]="title() + ' table pages'">
        <button (click)="tablePage.set(tablePage() - 1)" [disabled]="tablePage() === 0">Previous windows</button>
        <span>Page {{ tablePage() + 1 }} of {{ tablePages() }}</span>
        <button (click)="tablePage.set(tablePage() + 1)" [disabled]="tablePage() + 1 >= tablePages()">Next windows</button>
      </nav></details> }
    } @else { <p role="status">No KPI history in this time range.</p> }
  </section>`,
})
export class MetricChartComponent {
  private static nextId = 0;
  readonly chartId = `metric-${MetricChartComponent.nextId++}`;
  readonly plot = viewChild<ElementRef<SVGSVGElement>>('plot');
  readonly plotWidth = signal(900);
  readonly plotHeight = signal(320);
  readonly hero = input(false);
  readonly compact = input(false);
  readonly traffic = input(false);
  readonly windowSelected = output<KpiWindow | null>();
  readonly plotRight = computed(() => this.traffic() ? 65 : 24);
  readonly loading = input(false);
  readonly incidents = input<Incident[]>([]);
  readonly hoveredId = signal<string | null>(null);
  readonly hoveredIndex = computed(() => {
    const index = this.rows().findIndex(row => row.windowId === this.hoveredId());
    return index < 0 ? null : index;
  });
  readonly hovered = computed(() => this.hoveredIndex() === null ? null : this.rows()[this.hoveredIndex()!] ?? null);
  readonly latestValue = computed(() => this.rows().length ? this.value(this.rows().at(-1)!) : null);
  readonly hasValues = computed(() => this.rows().some(row => this.value(row) !== null));
  readonly unitLabel = computed(() => ({ PERCENT: '%', RATIO: 'ratio', COUNT: 'count', MILLISECONDS: 'ms', SECONDS: 's' }[this.unit()] ?? this.unit()));
  readonly incidentBands = computed(() => {
    const start = Date.parse(this.from()), end = Date.parse(this.to());
    const intervals = this.incidents().map(item => ({
      start: Math.max(start, Date.parse(item.firstObservedAt)),
      end: Math.min(end, Date.parse(item.lastObservedAt)),
    })).filter(item => item.end > item.start).sort((a,b) => a.start - b.start);
    // Merge overlapping intervals so incident volume cannot darken the plot indefinitely.
    const merged: { start: number; end: number }[] = [];
    for (const interval of intervals) {
      const previous = merged.at(-1);
      if (previous && interval.start <= previous.end) previous.end = Math.max(previous.end, interval.end);
      else merged.push({ ...interval });
    }
    return merged.map(item => ({ start: new Date(item.start).toISOString(), end: new Date(item.end).toISOString() }));
  });
  navigate(event: KeyboardEvent): void {
    if (!['ArrowLeft', 'ArrowRight', 'Home', 'End'].includes(event.key) || !this.rows().length) return;
    event.preventDefault();
    const last = this.rows().length - 1;
    const index = event.key === 'Home' ? 0 : event.key === 'End' ? last
      : Math.max(0, Math.min(last, (this.hoveredIndex() ?? -1) + (event.key === 'ArrowLeft' ? -1 : 1)));
    this.hoveredId.set(this.rows()[index].windowId);
    const region = event.currentTarget as HTMLElement;
    const svg = region?.querySelector('svg');
    if (svg) region.scrollLeft = this.x(this.hovered()!.windowStart) * svg.clientWidth / this.plotWidth() - region.clientWidth / 2;
  }
  point(event: PointerEvent): void {
    const svg = event.currentTarget as SVGSVGElement;
    const matrix = svg.getScreenCTM();
    if (!matrix || !this.rows().length) return;
    const point = new DOMPoint(event.clientX, event.clientY).matrixTransform(matrix.inverse());
    const time = Date.parse(this.from()) + Math.max(0, Math.min(1, (point.x - 65) / (this.plotWidth() - 65 - this.plotRight()))) * (Date.parse(this.to()) - Date.parse(this.from()));
    let closest = 0;
    for (let index = 1; index < this.rows().length; index++) {
      if (Math.abs(this.rowTime(this.rows()[index]) - time) < Math.abs(this.rowTime(this.rows()[closest]) - time)) closest = index;
    }
    this.hoveredId.set(this.rows()[closest].windowId);
  }
  clearPointer(event: PointerEvent): void {
    if (!(event.currentTarget as SVGSVGElement).parentElement?.matches(':focus')) this.hoveredId.set(null);
  }
  readonly view = input<'chart' | 'table' | 'both'>('both');
  readonly windows = input<KpiWindow[]>([]); readonly detections = input<Detection[]>([]);
  readonly name = input.required<string>(); readonly title = input.required<string>(); readonly unit = input('');
  readonly from = input.required<string>(); readonly to = input.required<string>();
  readonly rows = computed(() => [...this.windows()].sort((a,b) => Date.parse(a.windowStart) - Date.parse(b.windowStart)));
  readonly tablePage = signal(0);
  readonly tablePages = computed(() => Math.max(1, Math.ceil(this.rows().length / 50)));
  readonly tableRows = computed(() => this.rows().slice(this.tablePage() * 50, (this.tablePage() + 1) * 50));
  constructor() {
    effect(() => { this.name(); this.tablePage.set(0); this.hoveredId.set(null); });
    effect(() => this.windowSelected.emit(this.hovered()));
    effect(() => { if (this.tablePage() >= this.tablePages()) this.tablePage.set(this.tablePages() - 1); });
    effect(cleanup => {
      const svg = this.plot()?.nativeElement;
      if (!svg || typeof ResizeObserver === 'undefined') return;
      const observer = new ResizeObserver(() => { this.plotWidth.set(Math.max(280, svg.clientWidth)); this.plotHeight.set(Math.max(220, svg.clientHeight)); });
      observer.observe(svg);
      cleanup(() => observer.disconnect());
    });
  }
  readonly bounds = computed(() => {
    const values = this.rows().flatMap(row => [this.value(row), this.baseline(row)]).filter((v): v is number => v != null && Number.isFinite(v));
    if (!values.length) return [0, 1];
    const min = Math.min(...values), max = Math.max(...values), margin = Math.max((max - min) * .1, Math.abs(max) * .001, .000001);
    return [Math.max(0, min - margin), this.traffic() && max <= 100 ? Math.min(100, max + margin) : max + margin];
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
  tickFormat(value: number): string {
    return new Intl.NumberFormat('en', { maximumSignificantDigits: 5,
      notation: Math.abs(value) >= 10000 ? 'compact' : value !== 0 && Math.abs(value) < .0001 ? 'scientific' : 'standard',
    }).format(value);
  }
  readonly format = formatMetric; readonly Math = Math;
  counts(row: KpiWindow): { success: number; failed: number; total: number } | null {
    const kpi = row.kpis.find(item => item.name === this.name());
    const success = kpi?.numerator, total = kpi?.denominator;
    if (!this.traffic() || row.quality === 'MISSING' || kpi?.unit !== 'PERCENT'
      || success == null || total == null || !Number.isSafeInteger(success) || !Number.isSafeInteger(total)
      || success < 0 || total <= 0 || success > total) return null;
    return { success, failed: total - success, total };
  }
  readonly trafficRows = computed(() => this.rows().flatMap(row => {
    const count = this.counts(row);
    return count ? [{ row, count }] : [];
  }));
  readonly countMax = computed(() => Math.max(1, ...this.trafficRows().map(item => item.count.total)));
  readonly countTicks = computed(() => [0, this.countMax() / 2, this.countMax()]);
  countY(value: number): number { return this.plotHeight() - 45 - (this.plotHeight() - 60) * value / this.countMax(); }
  rowTime(row: KpiWindow): number { return this.traffic() ? (Date.parse(row.windowStart) + Date.parse(row.windowEnd)) / 2 : Date.parse(row.windowStart); }
  rowX(row: KpiWindow): number { return this.x(new Date(this.rowTime(row)).toISOString()); }
  trafficPath(success: boolean): string {
    let connected = false, previousEnd = '', result = '';
    for (const row of this.rows()) {
      const count = this.counts(row);
      if (!count) { connected = false; continue; }
      result += `${connected && Date.parse(previousEnd) === Date.parse(row.windowStart) ? 'L' : 'M'}${this.rowX(row)},${this.countY(success ? count.success : count.failed)} `;
      connected = true; previousEnd = row.windowEnd;
    }
    return result.trim();
  }
  value(row: KpiWindow) { return metricValue(row, this.name()); }
  baseline(row: KpiWindow) { return row.kpis.find(item => item.name === this.name())?.baseline ?? null; }
  phase(row: KpiWindow) { return phaseAt(row, this.detections()); }
  x(time: string) { return 65 + (this.plotWidth() - 65 - this.plotRight()) * Math.max(0, Math.min(1, (Date.parse(time) - Date.parse(this.from())) / (Date.parse(this.to()) - Date.parse(this.from())))); }
  y(value: number) { return this.plotHeight() - 45 - (this.plotHeight() - 60) * (value - this.bounds()[0]) / (this.bounds()[1] - this.bounds()[0]); }
  path(expected: boolean) {
    let connected = false, previousEnd = '', result = '';
    for (const row of this.rows()) {
      const value = expected ? this.baseline(row) : this.value(row);
      if (value === null || !Number.isFinite(value)) { connected = false; continue; }
      const command = connected && Date.parse(previousEnd) === Date.parse(row.windowStart) ? 'L' : 'M';
      result += expected && this.traffic()
        ? `${command}${this.x(row.windowStart)},${this.y(value)}L${this.x(row.windowEnd)},${this.y(value)} `
        : `${command}${this.rowX(row)},${this.y(value)} `;
      connected = true; previousEnd = row.windowEnd;
    }
    return result.trim();
  }
}
