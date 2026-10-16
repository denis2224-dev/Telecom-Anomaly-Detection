import { TelecomClient } from '../../core/api/telecom-client';
import { TraceQueryStore } from '../../core/state/trace-query.store';
import { SessionStore } from '../login-and-session/session.store';
import { loadRecordedRoaming, type RecordedRoaming, type RoamingRow } from './recorded-roaming';
import { dataSource } from '../../core/api/data-source';
import { Component, computed, effect, inject, input, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { MetricChartComponent } from '../service-kpi-history/metric-chart.component';
import { countryPreview, previewMetric, previewWindow, randomSource } from '../scenario-runner/monitoring-preview';
@Component({
  selector: 'app-roaming-overview', imports: [MetricChartComponent, RouterLink],
  template: `<section class="roaming" [class.compact]="compact()" [attr.aria-label]="preview() ? 'Roaming services preview' : 'Roaming services recorded'">
    <header><h2>Roaming services</h2><span class="badge" data-state="UNKNOWN">{{ preview() ? 'Preview' : 'Recorded' }}</span></header>
    <p class="preview-note">{{ preview() ? 'Illustrative values · roaming API unavailable · not live subscriber counts' : recorded()?.note || error() || 'Loading recorded roaming measurements…' }}</p>
    @if (error()) { <button type="button" class="ghost" (click)="refresh.update(value => value + 1)">Retry roaming data</button> }
    <div class="roaming-kpis">
      @for (kpi of summary(); track kpi.label) { <div><span>{{ kpi.label }}</span><strong>{{ kpi.value }}</strong><small>{{ preview() ? 'Sample only' : 'Recorded' }}</small></div> }
    </div>
    <div class="roaming-chart"><h3>Location update success rate <span>UTC · {{ preview() ? 'sample' : 'recorded' }}</span></h3>
      <app-metric-chart view="chart" [hero]="true" [compact]="true" name="registrationSrPct" [title]="preview() ? 'Roaming location update success rate · preview' : 'Roaming location update success rate'" unit="PERCENT" [windows]="windows()" [from]="recorded()?.from ?? from()" [to]="recorded()?.to ?? to()" [loading]="loading()" />
    </div>
    @if (!compact() || showCountries()) {
      <label class="sort-country">Sort countries<select aria-label="Sort roaming countries" [value]="sort()" (change)="sort.set($any($event.target).value)"><option value="users">Active users · highest first</option><option value="voice">Voice success · lowest first</option><option value="registration">Registration success · lowest first</option><option value="country">Country · A–Z</option></select></label>
      <div class="country-panels">
        <section><h3>{{ sort() === 'users' ? 'Top countries by active users' : 'Top affected countries' }}</h3><div class="chart-scroll" tabindex="0" aria-label="Roaming users and share by country"><table class="kpi-table"><thead><tr><th>Country</th><th>Active users</th><th>Share</th></tr></thead><tbody>
          @for (row of sorted(); track row.country) { <tr><th scope="row">{{ row.country }}</th><td><span class="country-bar"><span [style.width.%]="(row.users ?? 0) / maxUsers() * 100"></span><strong>{{ count(row.users) }}</strong></span></td><td>{{ share(row.users) }}</td></tr> }
        </tbody></table></div></section>
        <section><h3>Roaming KPIs by country</h3><div class="chart-scroll" tabindex="0" aria-label="Roaming country KPI table"><table class="kpi-table"><thead><tr><th>Country</th><th><span title="Location update / registration success">LU %</span></th><th>Data %</th><th>Voice %</th><th>SMS %</th></tr></thead><tbody>
          @for (row of sorted(); track row.country) { <tr><th scope="row">{{ row.country }}</th><td>{{ percent(row.registration) }}</td><td>{{ percent(row.data) }}</td><td [class.affected]="row.voice !== null && row.voice < 95">{{ percent(row.voice) }}</td><td>{{ percent(row.sms) }}</td></tr> }
        </tbody></table></div></section>
      </div>
    }
    @if (compact()) { <a class="button roaming-link" routerLink="/roaming">{{ preview() ? 'Open roaming preview →' : 'Open roaming overview →' }}</a> }
  </section>`,
  styles: [`
    :host { display: block; min-width: 0; }
    .roaming { background: var(--surface); border-radius: var(--radius); padding: 16px; }
    header { display: flex; gap: 12px; align-items: center; justify-content: space-between; }
    h2 { margin: 0; font-size: 20px; } h3 { margin: 8px 0; font-size: 14px; }
    h3 span, .preview-note, small { color: var(--text-muted); font-size: 11px; }
    .roaming-kpis { display: grid; grid-template-columns: repeat(5,minmax(0,1fr)); gap: 8px; margin: 14px 0; }
    .roaming-kpis > div { display: grid; gap: 10px; background: var(--field); padding: 12px; border-radius: var(--radius-control); }
    .roaming-kpis span { color: var(--text-muted); font-size: 12px; } .roaming-kpis strong { font-size: 24px; font-variant-numeric: tabular-nums; }
    .roaming-chart { background: var(--field); border-radius: var(--radius-control); padding: 8px; }
    .roaming-chart app-metric-chart { display: block; height: 290px; }
    .country-panels { display: grid; grid-template-columns: 1fr 1fr; gap: 16px; }
    .country-panels section { min-width: 0; background: var(--field); padding: 12px; border-radius: var(--radius-control); }
    .sort-country { display: grid; gap: 6px; font-size: 12px; margin: 16px 0; max-width: 300px; }
    th, td { font-size: 12px; padding: 10px 8px; text-transform: none; letter-spacing: normal; }
    .country-bar { display: block; position: relative; min-width: 100px; padding: 4px; }
    .country-bar > span { position: absolute; inset: 0 auto 0 0; background: var(--accent-soft); border-radius: 4px; }
    .country-bar strong { position: relative; font-weight: 400; }
    .affected { color: var(--danger); }
    .compact { padding: 12px; } .compact h2 { font-size: 14px; }
    .compact .roaming-kpis { grid-template-columns: repeat(5,minmax(0,1fr)); gap: 4px; margin: 8px 0; }
    .compact .roaming-kpis > div { padding: 8px; gap: 4px; } .compact .roaming-kpis strong { font-size: 17px; } .compact .roaming-kpis span { font-size: 10px; }
    .compact .roaming-chart app-metric-chart { height: 200px; } .compact .roaming-chart h3 { font-size: 11px; }
    .compact .country-panels { gap: 8px; }
    .compact .country-panels section { padding: 6px; }
    .compact .country-panels h3 { font-size: 11px; }
    .compact .country-panels th, .compact .country-panels td { padding: 5px 3px; font-size: 10px; }
    .compact .kpi-table { min-width: 0; width: 100%; table-layout: fixed; }
    .compact .kpi-table th, .compact .kpi-table td { white-space: normal; overflow-wrap: anywhere; }
    .compact .kpi-table th:first-child { width: 28%; }
    .compact .country-panels section:first-child th:nth-child(2) { width: 40%; }
    .compact .country-panels section:first-child th:nth-child(3) { width: 32%; }
    .compact .country-bar { min-width: 0; }
    .compact .country-panels section:last-child td { color: var(--success); }
    .compact .country-panels section:last-child td.affected { color: var(--danger); }
    .compact .sort-country { margin: 8px 0; }
    .roaming-link { display: flex; margin-top: 10px; font-size: 12px; background: var(--surface-raised); }
    @media(max-width: 900px) { .roaming:not(.compact) .country-panels { grid-template-columns: 1fr; } }
    @media(max-width: 600px) { .roaming-kpis, .compact .roaming-kpis { grid-template-columns: repeat(2,minmax(0,1fr)); } .roaming-kpis strong { font-size: 20px; } .country-panels { grid-template-columns: 1fr; } }
  `],
})
export class RoamingOverviewComponent {
  readonly preview = input(dataSource.fixture);
  readonly compact = input(false);
  readonly showCountries = input(false);
  readonly from = input('2026-10-06T00:00:00Z');
  readonly to = input('2026-10-08T00:00:00Z');
  readonly seed = input(42);
  readonly affectedCountry = input('Ukraine');
  readonly affectedMetric = input('voice');
  readonly sort = signal('users');
  readonly refresh = signal(0);
  readonly recorded = signal<RecordedRoaming | null>(null);
  readonly loading = signal(false);
  readonly error = signal('');
  readonly rows = computed<RoamingRow[]>(() => this.preview() ? countryPreview(this.seed(), this.affectedCountry(), this.affectedMetric()) : this.recorded()?.rows ?? []);
  readonly totalUsers = computed(() => this.rows().reduce((sum, row) => sum + (row.users ?? 0), 0));
  readonly maxUsers = computed(() => Math.max(1, ...this.rows().map(row => row.users ?? 0)));
  readonly sorted = computed(() => [...this.rows()].sort((a,b) => this.sort() === 'country' ? a.country.localeCompare(b.country)
    : this.sort() === 'voice' ? (a.voice ?? Infinity) - (b.voice ?? Infinity) : this.sort() === 'registration' ? (a.registration ?? Infinity) - (b.registration ?? Infinity) : (b.users ?? -1) - (a.users ?? -1)));
  readonly summary = computed(() => {
    const rows = this.rows(), users = rows.reduce((sum, row) => sum + (row.users ?? 0), 0);
    const mean = (name: 'registration'|'data'|'voice'|'sms') => this.preview()
      ? (rows.reduce((sum,row) => sum + (row[name] ?? 0) * (row.users ?? 0), 0) / users).toFixed(1) + '%'
      : this.recorded()?.rates[name] == null ? '—' : this.recorded()!.rates[name]!.toFixed(1) + '%';
    return [{ label: 'Location update success', value: mean('registration') }, { label: 'Roaming active users', value: rows.some(row => row.users !== null) ? users.toLocaleString('en') : '—' },
      { label: 'Data session success', value: mean('data') }, { label: 'Voice call success', value: mean('voice') }, { label: 'SMS delivery success', value: mean('sms') }];
  });
  readonly windows = computed(() => {
    if (!this.preview()) return this.recorded()?.windows ?? [];
    const base = Number.parseFloat(this.summary()[0].value);
    const random = randomSource(this.seed()), start = Date.parse(this.from()), step = (Date.parse(this.to()) - start) / 48;
    return Array.from({ length: 48 }, (_, index) => previewWindow('roaming-preview', start + index * step, start + (index + 1) * step,
      [previewMetric('registrationSrPct', +(base + (index === 47 ? 0 : (random() - .5) * .4) - (index === 25 ? 1.6 : 0)).toFixed(2), base, 'PERCENT')]));
  });
  count(value: number | null): string { return value === null ? '—' : value.toLocaleString('en', { maximumFractionDigits: 0 }); }
  percent(value: number | null): string { return value === null ? '—' : String(+value.toFixed(2)); }
  share(value: number | null): string { return value === null || !this.totalUsers() ? '—' : (value / this.totalUsers() * 100).toFixed(1) + '%'; }
  constructor() {
    const api = inject(TelecomClient), session = inject(SessionStore), query = inject(TraceQueryStore);
    effect(cleanup => {
      this.refresh();
      const preview = this.preview(), from = this.from(), to = this.to();
      const direction = query.context().roamingDirection === 'in' ? 'in' : 'out';
      const authenticated = session.phase() === 'authenticated';
      this.recorded.set(null); this.error.set(''); this.loading.set(false);
      if (preview || !authenticated) return;
      const request = new AbortController();
      cleanup(() => request.abort());
      this.loading.set(true);
      void loadRecordedRoaming(api, from, to, direction, request.signal).then(result => {
        if (!request.signal.aborted) this.recorded.set(result);
      }).catch(error => {
        if (!request.signal.aborted) { request.abort(); this.error.set(error instanceof Error ? error.message : 'Recorded roaming measurements could not be loaded.'); this.loading.set(false); }
      }).finally(() => { if (!request.signal.aborted) this.loading.set(false); });
    });
  }

}
