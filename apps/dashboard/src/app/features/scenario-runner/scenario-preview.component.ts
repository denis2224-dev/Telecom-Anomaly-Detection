import { Component, computed, input } from '@angular/core';
import { DatePipe } from '@angular/common';
import { MetricChartComponent } from '../service-kpi-history/metric-chart.component';
import { formatMetric } from '../service-kpi-history/assurance-model';
import { RoamingOverviewComponent } from '../roaming/roaming-overview.component';
import { previewScenarios, scenarioPreview, type PreviewType } from './monitoring-preview';
import { cities } from '../service-overview/dashboard-geography';
import { projectCity, moldovaOutline } from '../service-overview/moldova-map';
@Component({
  selector: 'app-scenario-preview', imports: [DatePipe, MetricChartComponent, RoamingOverviewComponent],
  template: `<section class="detail-panel scenario-preview" aria-label="Local scenario preview">
    <div class="page-heading"><h2>{{ scenario().label }}</h2><span class="badge" data-state="UNKNOWN">Local preview</span></div>
    <p class="muted">Seed {{ seed() }} · {{ scopeId() }} · {{ location() }} · eight minutes · UTC. Sample values; no server run or recorded incident.</p>
    <div class="preview-chart"><app-metric-chart view="chart" [hero]="true" [traffic]="scenario().metric === 'cssrPct'" [trafficBars]="scenario().metric === 'cssrPct'" [secondaryName]="type() === 'SMS_DELAYED_DELIVERY' ? 'queueDepth' : ''" [threshold]="scenario().metric === 'imsCpuPct' ? 85 : scenario().metric === 'linkUtilizationPct' ? 90 : null" [name]="scenario().metric" [title]="scenario().label" [unit]="scenario().unit" [windows]="data().rows" [from]="startUTC()" [to]="data().rows.at(-1)!.windowEnd" /></div>
    <p class="muted">{{ scenario().metric === 'cssrPct' ? 'Bars: successful and failed calls · line: success rate' : type() === 'SMS_DELAYED_DELIVERY' ? 'Blue: queue depth (left) · orange: p95 delay in ms (right)' : 'Orange: observed · dashed: reference threshold' }}</p>
    <div class="chart-scroll" tabindex="0" aria-label="Scenario peak KPI table"><table class="kpi-table"><caption>Disruption peak · minute 4 · sample</caption><thead><tr><th>KPI</th><th>Observed</th><th>Baseline</th></tr></thead><tbody>
      @for (kpi of peakMetrics(); track kpi.name) { <tr><th>{{ label(kpi.name) }}</th><td>{{ format(kpi.observed, kpi.unit) }}</td><td>{{ format(kpi.baseline, kpi.unit) }}</td></tr> }
    </tbody></table></div>
    @if (type() === 'SMS_DELAYED_DELIVERY') { <p>Received: {{ data().cumulativeReceived }} · delivered: {{ data().cumulativeDelivered }} · pending: {{ data().queued }} · lost: 0 (sample). Queued messages are delivered during recovery.</p> }
    @if (type() === 'VOLTE_CITY_TRANSPORT_OVERLOAD') {
      <div class="preview-map" aria-label="City transport overload map preview"><svg viewBox="0 0 600 740" role="img" [attr.aria-label]="'Sample transport overload in ' + location()"><path [attr.d]="outline" fill="none" stroke="var(--accent)" stroke-width="3" />
        @for (city of mapCities; track city.id) { <circle [attr.cx]="city.marker!.x * 6" [attr.cy]="city.marker!.y * 7.4" r="11" [attr.fill]="city.name === location() ? 'var(--danger)' : 'var(--text-muted)'" /><text [attr.x]="city.marker!.x * 6 + 16" [attr.y]="city.marker!.y * 7.4 + 5">{{ city.name }}</text> }
      </svg><p>Only {{ location() }} degrades in this sample. Other cities retain their own baseline.</p></div>
    }
    @if (type().includes('ROAMING')) { <app-roaming-overview [seed]="seed()" [affectedCountry]="country()" [affectedMetric]="type().includes('REGISTRATION') ? 'registration' : 'voice'" [from]="startUTC()" [to]="data().rows.at(-1)!.windowEnd" /> }
    <h3>Incident evidence timeline · local preview</h3><p>Affected {{ scenario().target }}: {{ location() }} · sample workflow remains OPEN after technical recovery.</p>
    <ol class="preview-timeline">@for (event of data().timeline; track event.sequence) { <li><time>{{ event.at | date:'HH:mm:ss':'UTC' }} UTC</time><span class="badge" [attr.data-state]="event.state">{{ event.phase }} · {{ event.state }}</span><span>{{ event.target }}</span></li> }</ol>
  </section>`,
  styles: [`
    .preview-chart app-metric-chart { display: block; }
    .preview-timeline { display: grid; gap: 8px; padding: 0; list-style: none; }
    .preview-timeline li { display: flex; flex-wrap: wrap; align-items: center; gap: 12px; background: var(--field); padding: 12px; border-radius: var(--radius-control); font-size: 12px; }
    th,td { font-size: 12px; padding: 8px; text-transform: none; letter-spacing: normal; }
    .preview-map { display: flex; align-items: center; gap: 20px; } .preview-map svg { height: 300px; max-width: 100%; } .preview-map text { fill: var(--text); font-size: 15px; }
    @media(max-width:600px) { .preview-map { display: block; } }
  `],
})
export class ScenarioPreviewComponent {
  readonly type = input.required<PreviewType>(); readonly seed = input.required<number>();
  readonly scopeId = input.required<string>(); readonly country = input('Romania'); readonly location = input('IMS core');
  readonly startUTC = input.required<string>(); readonly format = formatMetric; readonly outline = moldovaOutline;
  readonly mapCities = cities.map(city => ({ ...city, marker: city.marker ?? projectCity(city.location.longitude, city.location.latitude) }));
  readonly scenario = computed(() => previewScenarios.find(item => item.id === this.type())!);
  readonly data = computed(() => scenarioPreview(this.type(), this.seed(), this.scopeId(), this.country(), this.startUTC()));
  label(name: string): string { return ({ cssrPct: 'Call success rate', failedCallSharePct: 'Failed call share', setupTimeMs: 'Call setup time', imsCpuPct: 'IMS CPU', linkUtilizationPct: 'Link utilization', crossOperatorFailurePct: 'Cross-operator failures', activeRoamingUsers: 'Active roaming users', roamingVoiceSrPct: 'Roaming call success', registrationSrPct: 'Registration success', queueDepth: 'Queued messages', avgDeliveryMs: 'Average delivery delay', p95DeliveryMs: 'P95 delivery delay', deliveredLatePct: 'Delivered late', lostMessages: 'Lost messages' } as Record<string,string>)[name] ?? name; }
  readonly peakMetrics = computed(() => this.data().rows[3].kpis.filter(kpi => this.type() === 'SMS_DELAYED_DELIVERY'
    ? ['queueDepth', 'avgDeliveryMs', 'p95DeliveryMs', 'deliveredLatePct', 'lostMessages'].includes(kpi.name)
    : this.type().includes('ROAMING') ? [this.scenario().metric, 'activeRoamingUsers'].includes(kpi.name)
    : ['cssrPct', 'failedCallSharePct', 'setupTimeMs', 'imsCpuPct', 'linkUtilizationPct', 'crossOperatorFailurePct'].includes(kpi.name)));
}
