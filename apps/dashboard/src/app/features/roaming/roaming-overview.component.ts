import { TraceQueryStore, traceContextParameters } from '../../core/state/trace-query.store';
import { Component, computed, input, signal, inject } from '@angular/core';
import { RouterLink } from '@angular/router';
import { MetricChartComponent } from '../service-kpi-history/metric-chart.component';
import { countryPreview, previewMetric, previewWindow, randomSource, roamingSample } from '../scenario-runner/monitoring-preview';
@Component({
  selector: 'app-roaming-overview', imports: [MetricChartComponent, RouterLink],
  template: `@if (preview()) {<section class="roaming" [class.compact]="compact()" aria-label="Roaming services preview">
    <header><h2>Roaming services</h2><span class="badge" data-state="UNKNOWN">Sample data</span></header>
    <p class="preview-note">Artificial data · awaiting measured roaming data</p>
    <div class="roaming-chart"><h3>Location update success rate <span>UTC · sample</span></h3>
      <app-metric-chart view="chart" [hero]="true" [compact]="true" name="registrationSrPct" title="Roaming location update success rate · preview" unit="PERCENT" [windows]="windows()" [from]="from()" [to]="to()" />
    </div>
    @if (!compact() || showCountries()) {
      <label class="sort-country">Sort countries<select aria-label="Sort roaming countries" [value]="sort()" (change)="sort.set($any($event.target).value)"><option value="users">Active users · highest first</option><option value="voice">Voice success · lowest first</option><option value="registration">Registration success · lowest first</option><option value="country">Country · A–Z</option></select></label>
      <div class="country-panels">
        <section><h3>{{ sort() === 'users' ? 'Top 10 countries by roaming subscribers' : 'Top affected countries' }}</h3><div class="chart-scroll" tabindex="0" aria-label="Roaming users and share by country"><table class="kpi-table"><thead><tr><th>Country</th><th>Active subscribers</th><th>Share</th></tr></thead><tbody>
          @for (row of sorted(); track row.country) { <tr><th scope="row">{{ row.country }}</th><td><span class="country-bar"><span [style.width.%]="row.users / maxUsers() * 100"></span><strong>{{ row.users.toLocaleString('en') }}</strong></span></td><td>{{ (row.users / totalUsers() * 100).toFixed(1) }}%</td></tr> }
        </tbody></table></div></section>
        @if (!compact()) {<section><h3>Roaming KPIs by country</h3><div class="chart-scroll" tabindex="0" aria-label="Roaming country KPI table"><table class="kpi-table"><thead><tr><th>Country</th><th><span title="Location update / registration success">LU %</span></th><th>Data %</th><th>Voice %</th><th>SMS %</th></tr></thead><tbody>
          @for (row of sorted(); track row.country) { <tr><th scope="row">{{ row.country }}</th><td>{{ row.registration }}</td><td>{{ row.data }}</td><td [class.affected]="row.voice < 95">{{ row.voice }}</td><td>{{ row.sms }}</td></tr> }
        </tbody></table></div></section>}
      </div>
    }
    @if (compact()) { <a class="button roaming-link" routerLink="/roaming">Open roaming services →</a> }
    <a class="measured-history" routerLink="/trace-link" [queryParams]="roamingContext()">Measured partner history</a>
  </section>} @else {<section class="roaming" aria-label="Roaming measurements"><h2>Roaming services</h2><p role="status">Roaming measurements are unavailable in the legacy service response. Investigate persisted partner, country and direction populations in Trace Link history.</p><a class="button roaming-link" routerLink="/trace-link" [queryParams]="roamingContext()">Open roaming investigation →</a></section>}`,
  styles: [`
    :host { display: block; min-width: 0; }
    .roaming { background: var(--surface); border-radius: var(--radius); padding: 16px; }
    header { display: flex; gap: 12px; align-items: center; justify-content: space-between; }
    h2 { margin: 0; font-size: 20px; } h3 { margin: 8px 0; font-size: 14px; }
    h3 span, .preview-note, small { color: var(--text-muted); font-size: 11px; }
    .roaming-chart { background: var(--field); border-radius: var(--radius-control); padding: 8px; }
    .roaming-chart app-metric-chart { display: block; height: auto; }
    .country-panels { display: grid; grid-template-columns: 1fr 1fr; gap: 16px; }
    .country-panels section { min-width: 0; background: var(--field); padding: 12px; border-radius: var(--radius-control); }
    .sort-country { display: grid; gap: 6px; font-size: 12px; margin: 16px 0; max-width: 300px; }
    th, td { font-size: 12px; padding: 10px 8px; text-transform: none; letter-spacing: normal; }
    .country-bar { display: block; position: relative; min-width: 100px; padding: 4px; }
    .country-bar > span { position: absolute; inset: 0 auto 0 0; background: #39bafa66; border-radius: 4px; }
    .country-bar strong { position: relative; font-weight: 400; }
    .country-panels section:nth-child(2) td {color:var(--success);}
    .affected { color: var(--danger) !important; }
    .compact { padding: 8px; } .compact h2 { font-size: 14px; }
    .compact .roaming-chart app-metric-chart { height: auto; } .compact .roaming-chart h3 { font-size: 11px; }
    .compact .country-panels { gap: 8px; grid-template-columns:1fr; }
    .measured-history {display:inline-block;margin-top:10px;font-size:12px;}
    .compact .country-panels section { padding: 6px; }
    .compact .country-panels h3 { font-size: 11px; }
    .compact .country-panels th, .compact .country-panels td { padding: 4px 3px; font-size: 10px; }
    .compact .kpi-table { min-width: 0; width: 100%; table-layout: fixed; }
    .compact .kpi-table th, .compact .kpi-table td { white-space: normal; overflow-wrap: anywhere; }
    .compact .kpi-table th:first-child { width: 28%; }
    .compact .country-panels section:first-child th:nth-child(2) { width: 40%; }
    .compact .country-panels section:first-child th:nth-child(3) { width: 32%; }
    .compact .country-bar { min-width: 0; }
    .compact .country-panels section:last-child td { color: var(--success); }
    .compact .country-panels section:last-child td.affected { color: var(--danger); }
    .compact .sort-country { margin: 6px 0; }
    .roaming-link { display: flex; margin-top: 10px; font-size: 12px; background: var(--surface-raised); }
    @media(max-width: 600px) { .country-panels {grid-template-columns:1fr;} }
    @media(max-width: 900px) { .roaming:not(.compact) .country-panels { grid-template-columns: 1fr; } }
  `],
})
export class RoamingOverviewComponent {
  private readonly query=inject(TraceQueryStore);
  readonly roamingContext=computed(()=>traceContextParameters({...this.query.context(),domain:'roaming'}));
  readonly preview = input(true);
  readonly compact = input(false);
  readonly showCountries = input(false);
  readonly from = input(new Date(Date.now()-48*3_600_000).toISOString());
  readonly to = input(new Date().toISOString());
  readonly seed = input<number>(roamingSample.seed);
  readonly affectedCountry = input('');
  readonly affectedMetric = input('voice');
  readonly sort = signal('users');
  readonly rows = computed(() => countryPreview(this.seed(), this.affectedCountry(), this.affectedMetric()));
  readonly totalUsers = computed(() => this.rows().reduce((sum, row) => sum + row.users, 0));
  readonly maxUsers = computed(() => Math.max(...this.rows().map(row => row.users)));
  readonly sorted = computed(() => [...this.rows()].sort((a,b) => this.sort() === 'country' ? a.country.localeCompare(b.country)
    : this.sort() === 'voice' ? a.voice - b.voice : this.sort() === 'registration' ? a.registration - b.registration : b.users - a.users));
  readonly windows = computed(() => {
    const base = roamingSample.registrationSuccess;
    const random = randomSource(this.seed()), start = Date.parse(this.from()), step = (Date.parse(this.to()) - start) / 48;
    return Array.from({ length: 48 }, (_, index) => previewWindow('roaming-preview', start + index * step, start + (index + 1) * step,
      [previewMetric('registrationSrPct', +(base + (index === 47 ? 0 : (random() - .5) * .4) - (index === 25 ? 1.6 : 0)).toFixed(2), base, 'PERCENT')]));
  });
}
