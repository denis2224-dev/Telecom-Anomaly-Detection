import { Component, DestroyRef, ElementRef, viewChild, computed, effect, inject, input, output, signal } from '@angular/core';
import { takeUntilDestroyed, toSignal } from '@angular/core/rxjs-interop';
import { SessionStore } from '../login-and-session/session.store';
import { DatePipe } from '@angular/common';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { TelecomClient } from '../../core/api/telecom-client';
import { dataSource } from '../../core/api/data-source';
import { ServiceStore } from './service.store';
import { CityTrendComponent } from './city-trend.component';
import { moldovaOutline } from './moldova-map';
import {
  approvedConnections, baseline, cities, cityForScope, cityLabel, cityServices, deviation, measured, metric, number,
  sampleVolume, type City, type Episode, type Filter, type Service, type Summary, type Window,
} from './dashboard-geography';

interface History {
  rows: Window[];
  from: string;
  to: string;
  observedAt: string;
  total: number;
  error: string;
}

@Component({
  selector: 'app-connected-overview',
  imports: [RouterLink, DatePipe, CityTrendComponent],
  template: `
    <section class="connected" aria-labelledby="connected-title">
      <div class="section-heading"><div><h2 id="connected-title">City service overview</h2>
        <p class="helper">{{ fixture ? 'Synthetic design fixture · city membership pending owner review' : 'Live APIs · only explicitly mapped scopes appear on cities' }}</p></div>
        <button type="button" (click)="queueDialog.showModal()" aria-haspopup="dialog">Incidents ({{ queueTotal() }}) <span class="badge" title="Highest severity on the loaded queue page" [attr.data-state]="queueSeverity()">{{ queueSeverity() }}</span></button>
        <button type="button" (click)="refresh.update(next)" [disabled]="queueLoading() || historyLoading()">Refresh city evidence</button>
      </div>
      <form class="controls" (submit)="applyRange($event, fromInput.value, toInput.value)">
        <label>Chart period
          <select aria-label="Chart period" [value]="minutes()" (change)="setPeriod(+$any($event.target).value, fromInput, toInput)">
            <option value="15">Latest 15 minutes</option><option value="60">Latest hour</option>
          </select>
        </label>
        <label>From (UTC)<input #fromInput type="datetime-local" [value]="chartFrom().slice(0, 16)" required /></label>
        <label>To (UTC, exclusive)<input #toInput type="datetime-local" [value]="chartTo().slice(0, 16)" required /></label>
        <label>Service
          <select aria-label="Service" [value]="serviceFilter()" (change)="filterChanged.emit($any($event.target).value)">
            <option value="ALL">All services</option><option value="VOLTE">VoLTE setup</option><option value="SMS">SMS delivery</option>
          </select>
        </label>
        <label>Technology<select disabled aria-label="Technology"><option>LTE</option></select></label>
        <label>Region
          <input type="search" list="dashboard-city-options" [value]="search()" (input)="setRegion($any($event.target).value)" placeholder="All cities · search Orhei" />
          <datalist id="dashboard-city-options">@for (city of catalogue(); track city.id) { <option [value]="city.name"></option> }</datalist>
        </label>
        <label><span aria-hidden="true">Apply range</span><button type="submit" aria-label="Apply">Apply</button></label>
      </form>
      @if (rangeError()) { <p role="alert">{{ rangeError() }}</p> }
      <p class="toolbar-caption">Time range: charts · Map values: latest stored observation · Region: map selection and incident-page matches</p>
      @if (search().trim()) {
        <div class="search-results" aria-label="City search results">
          @for (city of searchResults(); track city.id) {
            <button type="button" (click)="select(city.id)" [attr.aria-pressed]="selectedId() === city.id">{{ city.name }}</button>
          } @empty { <p>No matching city. Clear the search and try another name.</p> }
        </div>
      }
      @if (statusMessage()) { <p role="alert">{{ statusMessage() }}</p> }
      <div class="map-queue">
        <section class="map-panel" aria-labelledby="city-map-title">
          <h3 id="city-map-title">Network service map · Moldova</h3>
          <div class="city-map" aria-label="Moldova geographic outline and nine city markers">
            <svg viewBox="0 0 600 740" aria-hidden="true">
              <defs><pattern id="moldova-grid" width="30" height="30" patternUnits="userSpaceOnUse"><path d="M30 0H0V30" class="grid-line" /></pattern></defs>
              <rect width="600" height="740" fill="url(#moldova-grid)" />
              <path [attr.d]="moldovaOutline" class="country-outline" />
              <text x="20" y="490" class="neighbor">ROMANIA</text><text x="410" y="100" class="neighbor">UKRAINE</text>
              @for (connection of connections; track connection.id) {
                @if (cityById(connection.fromCityId)?.marker; as from) {
                  @if (cityById(connection.toCityId)?.marker; as to) {
                    <line [attr.x1]="from.x * 6" [attr.y1]="from.y * 7.4" [attr.x2]="to.x * 6" [attr.y2]="to.y * 7.4" class="network-link" />
                  }
                }
              }
            </svg>
            @for (city of markerCities(); track city.id) {
              <button class="city-marker" type="button" [style.left.%]="city.marker!.x" [style.top.%]="city.marker!.y"
                [attr.data-label-side]="city.labelSide" [attr.data-state]="cityHealth(city)" [attr.aria-pressed]="selectedId() === city.id"
                [attr.aria-label]="city.name + ', ' + cityHealth(city)" (click)="select(city.id)">
                <span class="marker-symbol">{{ symbol(cityHealth(city)) }}</span>
                <span class="node-label">{{ city.name }}<small>{{ nodeValue(city) }}</small></span>
              </button>
            }
          </div>
          <p class="map-legend">✓ Normal · ! Degraded · ? Unavailable · ◷ Stale</p>
          <p class="map-source">Map: <a href="https://www.naturalearthdata.com/" target="_blank" rel="noopener noreferrer">Natural Earth</a> · Cities: <a href="https://www.geonames.org/" target="_blank" rel="noopener noreferrer">GeoNames</a> · {{ fixture ? 'Synthetic service values' : 'Stored service values' }}</p>
          <details class="map-notes"><summary>Map meaning and topology</summary><p>City positions are geographic. Orhei is available through Region search. Only catalogue-approved network links are drawn; neutral lines do not assert capacity or utilization. All-services node labels summarize service health; select VoLTE or SMS to see that service's KPI. The compact table defaults to VoLTE when All services is selected. Missing mappings and missing measurements remain unavailable.</p></details>
        </section>
        <div class="city-workspace">
      @if (selectedCity(); as city) {
        <section class="city-detail" aria-labelledby="selected-city-title">
          <div class="section-heading"><h3 id="selected-city-title">{{ city.name }} service detail</h3>
            <button type="button" (click)="selectedId.set(null)">Clear city selection</button></div>

          @for (item of cityServices(city, services(), serviceFilter()); track item.scope.scopeId) {
            <article class="city-service">
              <h4>{{ item.scope.service === 'VOLTE' ? 'VoLTE setup' : 'SMS delivery' }} · {{ scopeHealth(item) }}</h4>
              <dl>
                <div><dt>Observed</dt><dd>{{ metric(item.latestWindow, item.scope.service) }}</dd></div>
                <div><dt>Baseline</dt><dd>{{ baseline(item.latestWindow, item.scope.service) === null ? 'Unavailable' : number(baseline(item.latestWindow, item.scope.service)!) + (item.scope.service === 'VOLTE' ? ' %' : ' ms') }}</dd></div>
                <div><dt>Deviation</dt><dd>{{ deviation(item.latestWindow, item.scope.service).replace(' from baseline', '') }}</dd></div>
              </dl>
              <details class="city-source"><summary>Source &amp; samples</summary><p class="helper">Samples: {{ sampleVolume(item.latestWindow, item.scope.service) }} · Open incidents: {{ item.openIncidents }}</p><p class="helper">Source: {{ item.freshness }} · As of {{ item.observedAt | date:'dd MMM HH:mm:ss':'UTC' }} UTC</p></details>
              <a [routerLink]="['/services', item.scope.scopeId]">Open {{ city.name }} {{ item.scope.service === 'VOLTE' ? 'VoLTE' : 'SMS' }} evidence</a>
            </article>
          } @empty { <p>{{ city.scopeIds.length === 0 ? 'Mapping pending. No city values are inferred from regional scopes.' : 'No linked service scopes returned for this filter. Current service health is unavailable.' }}</p> }

        </section>
      }
          <div class="map-table"><table>
            <caption>{{ tableService() === 'VOLTE' ? 'VoLTE setup' : 'SMS delivery' }} · five featured cities · latest observations</caption>
            <thead><tr><th>City</th><th>Observed</th><th>Baseline Δ</th><th>State</th></tr></thead>
            <tbody>@for (city of featuredCities(); track city.id) {
              <tr><th><button type="button" (click)="select(city.id)">{{ city.name }}</button></th>
                @if (cityServices(city, services(), tableService())[0]; as item) {
                  <td>{{ metric(item.latestWindow, item.scope.service) }}</td><td>{{ deviation(item.latestWindow, item.scope.service) }}</td><td>{{ scopeHealth(item) }}</td>
                } @else { <td>Unavailable</td><td>—</td><td>{{ city.scopeIds.length ? 'UNKNOWN' : 'Mapping pending' }}</td> }
              </tr>
            }</tbody>
          </table></div>
      <div class="section-heading"><div><h3>Featured cities</h3>
        <p class="helper">Solid: observed · dashed: baseline · gaps: unavailable · UTC</p></div></div>
      @if (historyLoading()) { <p role="status">Loading featured-city trends…</p> }
      <div class="featured-cities">
        @for (city of featuredCities(); track city.id) {
          <article class="featured-city">
            <button type="button" (click)="select(city.id)" [attr.aria-pressed]="selectedId() === city.id">{{ city.name }}</button>
            @for (item of cityServices(city, services(), serviceFilter()); track item.scope.scopeId) {
              @if (histories()[item.scope.scopeId]; as history) {
                @if (history.error) { <p role="alert">{{ history.error }}</p> }
                @else {
                  <app-city-trend [compact]="true" [rows]="history.rows" [service]="item.scope.service" [from]="history.from" [to]="history.to" [collapsed]="serviceFilter() === 'ALL' && item.scope.service === 'SMS'" />

                  @if (history.total > history.rows.length) { <p class="helper">First 100 windows shown. Open service evidence for paginated history.</p> }
                }
              }
            } @empty { <p class="helper">{{ city.scopeIds.length === 0 ? 'Mapping pending' : 'No linked scope returned' }}</p> }
          </article>
        }
      </div>
      <details class="featured-sources"><summary>Featured city sources &amp; evidence</summary>
        @for (city of featuredCities(); track city.id) {
          <h4>{{ city.name }}</h4>
          @for (item of cityServices(city, services(), serviceFilter()); track item.scope.scopeId) {
            @if (histories()[item.scope.scopeId]; as history) {
                  <details class="chart-source"><summary>{{ item.scope.service === 'VOLTE' ? 'VoLTE' : 'SMS' }} evidence</summary><p class="helper">{{ fixture ? 'Synthetic API fixture' : 'Stored API history' }} · {{ history.from | date:'dd MMM HH:mm':'UTC' }} – {{ history.to | date:'HH:mm':'UTC' }} UTC · as of {{ history.observedAt | date:'dd MMM HH:mm':'UTC' }} UTC</p><a [routerLink]="['/services', item.scope.scopeId]">Open {{ item.scope.service === 'VOLTE' ? 'VoLTE' : 'SMS' }} history</a></details>
            }
          }
        }
      </details>
        </div>
      </div>
        <dialog #queueDialog class="queue-panel" id="incident-queue" aria-labelledby="queue-title" (click)="closeOutside($event, queueDialog)" (close)="clearQueueFragment()">
<button class="drawer-close" type="button" (click)="queueDialog.close()" aria-label="Close incidents">Close</button>
          <h3 id="queue-title">Incident queue</h3>
          <p class="helper">Newest detected first · one row per incident record · page {{ queuePage() + 1 }} · {{ queueTotal() }} total for the service filter</p>
          @if (selectedCity()) { <p class="helper">Showing selected-city matches on this incident page.</p> }
          <div class="queue-filters"><label>Severity<select [value]="queueSeverityFilter()" (change)="queueSeverityFilter.set($any($event.target).value)"><option value="">All severities</option><option>HIGH</option><option>MEDIUM</option><option>CRITICAL</option></select></label><label>State<select [value]="queueStateFilter()" (change)="queueStateFilter.set($any($event.target).value)"><option value="">All states</option><option>ONGOING</option><option>RECOVERED</option><option>UNKNOWN</option></select></label><label>Service<select aria-label="Queue service" [value]="serviceFilter()" (change)="filterChanged.emit($any($event.target).value)"><option value="ALL">All services</option><option value="VOLTE">VoLTE</option><option value="SMS">SMS</option></select></label></div>
          <p class="helper">Severity and state filter this page · {{ visibleIncidents().length }} matches</p>
          <div class="queue-scroll" [attr.aria-busy]="queueLoading()">
          @if (queueLoading()) { <p class="queue-refresh" role="status">Refreshing incidents…</p> }
          @if (queueError()) { <p role="alert">{{ queueError() }}</p><button type="button" (click)="refresh.update(next)">Retry incidents</button> }
          @else {
            @for (incident of visibleIncidents(); track incident.id) {
              <article class="queue-item">
                <h4>{{ cityLabel(catalogue(), incident.scopeId) }} · {{ incident.service === 'VOLTE' ? 'VoLTE' : 'SMS' }}</h4>
                <p>{{ incident.severity }} severity · Technical: {{ incident.technicalState }} · Workflow: {{ incident.status }}</p>
                <p>First observed: {{ incident.firstObservedAt | date:'dd MMM HH:mm:ss':'UTC' }} UTC</p>
                <p>Detected: {{ incident.detectedAt | date:'dd MMM HH:mm:ss':'UTC' }} UTC</p>
                <p>{{ deviation(detectionWindow(incident), incident.service) }}</p>
                <p class="helper">{{ incident.latestDetection.probableCause }}</p>
                <div class="button-row">
                  @if (cityForScope(catalogue(), incident.scopeId); as city) {
                    <button type="button" (click)="select(city.id)">Show {{ city.name }}</button>
                  }
                  <a [routerLink]="['/incidents', incident.id]">Open incident evidence</a>
                </div>
              </article>
            } @empty {
              <p>{{ selectedCity() ? 'No selected-city incidents on this page. Other pages may contain matches.' : 'No incidents returned on this page.' }}</p>
            }
          }
          </div>
          <nav class="pagination" aria-label="Overview incident pages">
            <button type="button" (click)="queuePage.update(previous)" [disabled]="queueLoading() || queuePage() === 0">Previous</button>
            <button type="button" (click)="queuePage.update(next)" [disabled]="queueLoading() || (queuePage() + 1) * pageSize >= queueTotal()">Next</button>
          </nav>
          <p class="helper">Open incident totals in service cards are independent of this page. Unmapped incidents remain visible when no city is selected.</p>
        </dialog>
    </section>
  `,
  styles: [`
    :host { display: block; margin: 8px 0; }
    .city-workspace { min-width: 0; display: grid; grid-template-columns: minmax(0, .9fr) minmax(0, 1.1fr); gap: 6px; }
    .city-workspace > .city-detail { max-height: 230px; margin: 0; }
    .city-workspace > .map-table { grid-column: 2; padding: 0; }
    .city-workspace:not(:has(.city-detail)) > .map-table, .city-workspace > .section-heading, .city-workspace > .featured-cities { grid-column: 1 / -1; }
    .city-detail dl { grid-template-columns: repeat(3, minmax(0, 1fr)) !important; }
    .city-workspace > .section-heading { margin: 2px 0 !important; }
    @media (max-width: 1000px) { .city-workspace { grid-template-columns: 1fr; } .city-workspace > .map-table { grid-column: 1; } .city-workspace > .city-detail { max-height: none; } }

    .featured-sources { grid-column: 1 / -1; font-size: 10px; }
    .featured-sources > summary { padding: 2px 0; font-size: 10px; }
    .city-workspace > .section-heading { margin: 8px 0; }
    .city-workspace > .section-heading p { font-size: 10px; }
    .queue-panel { position: fixed; inset: 0 0 0 auto; margin: 0; width: min(520px, 100%); max-width: 100%; height: 100dvh; max-height: 100dvh; border: 0; color: var(--text); box-shadow: var(--shadow-raised); }
    .queue-panel::backdrop { background: color-mix(in srgb, var(--bg) 75%, transparent); backdrop-filter: blur(3px); }
    .queue-scroll { height: calc(100dvh - 300px); min-height: 180px; overflow: auto; scrollbar-gutter: stable; position: relative; }
    .queue-refresh { position: absolute; top: 0; right: 0; z-index: 1; background: var(--surface); }
    .queue-filters { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 8px; }
    .drawer-close { float: right; }
    .queue-panel .queue-item { padding: 8px 0; margin: 0; }
    .queue-item p { margin: 4px 0; }
    .featured-city .chart-source, .featured-city > a { font-size: 9px; margin: 2px 0; }
    .featured-city > button { min-height: 28px; }
    @media (max-width: 600px) { .city-detail dl { grid-template-columns: repeat(2, minmax(0, 1fr)); } }
    .connected { min-width: 0; }
    .connected > .section-heading { margin: 4px 0; gap: 8px; }
    .connected > .section-heading h2 { font-size: 15px; }
    .connected > .section-heading .helper { display: none; }
    .connected > .section-heading button { font-size: 11px; min-height: 32px; padding: 6px 10px; }
    .controls { display: grid; grid-template-columns: repeat(7, minmax(0, 1fr)); align-items: end; gap: 8px; padding: 6px; margin: 6px 0 4px; background: var(--surface); border-radius: var(--radius-control); }
    .controls label { min-width: 0; font-size: 10px; grid-template-rows: 16px 40px; }
    .controls input, .controls select { width: 100%; min-width: 0; padding: 7px; font-size: 11px; }
    .controls input, .controls select, .controls button { box-sizing: border-box; height: 40px; min-height: 40px; width: 100%; margin: 0; padding: 6px; font-size: 11px; }
    label { display: grid; gap: 5px; color: var(--text-muted); }
    input, select { max-width: 100%; padding: 10px; border-radius: var(--radius-control); background: var(--field); color: var(--text); }
    .search-results { display: flex; flex-wrap: wrap; gap: 6px; margin-bottom: 6px; }
    .map-queue { display: grid; grid-template-columns: minmax(300px, .85fr) minmax(0, 1.65fr); align-items: start; gap: 10px; }
    .map-panel, .queue-panel, .city-detail, .featured-city { min-width: 0; padding: 12px; background: var(--surface); border-radius: var(--radius); }
    .map-panel { padding: 0; overflow: hidden; border: 1px solid var(--accent-soft); }
    #city-map-title { padding: 8px 12px; background: linear-gradient(100deg, color-mix(in srgb, var(--accent) 22%, var(--surface)), color-mix(in srgb, var(--info) 30%, var(--surface))); border-bottom: 1px solid var(--accent-soft); }
    .city-map { position: relative; width: min(100%, 250px); aspect-ratio: 600 / 740; margin: 8px auto; }
    .city-map > svg { display: block; width: 100%; height: 100%; }
    .grid-line { fill: none; stroke: var(--mesh); stroke-width: 1; }
    .country-outline { fill: var(--field); stroke: var(--accent-end); stroke-width: 1.5; vector-effect: non-scaling-stroke; }
    .neighbor { fill: var(--text-muted); opacity: .5; font-size: 15px; letter-spacing: 2px; }
    .network-link { stroke: var(--accent); stroke-width: 2; opacity: .75; vector-effect: non-scaling-stroke; }
    .city-marker { position: absolute; transform: translate(-50%, -50%); width: 44px; height: 44px; padding: 0; border: 0; background: transparent; color: var(--bg); overflow: visible; }
    .city-marker:hover, .city-marker:active { transform: translate(-50%, -50%); background: transparent; box-shadow: none; }
    .marker-symbol { position: absolute; left: 50%; top: 50%; transform: translate(-50%, -50%); width: 22px; height: 22px; border: 2px solid var(--accent); border-radius: 50%; background: var(--text); line-height: 18px; font-size: 12px; font-weight: 700; }
    .city-marker[data-state="NORMAL"] .marker-symbol { border-color: var(--success); }
    .city-marker[data-state="DEGRADED"] .marker-symbol { border-color: var(--danger); }
    .city-marker[data-state="STALE"] .marker-symbol { border-color: var(--warning); }
    .city-marker[data-state="UNKNOWN"] .marker-symbol, .city-marker[data-state="MAPPING PENDING"] .marker-symbol, .city-marker[data-state="UNAVAILABLE"] .marker-symbol { border-color: var(--text-muted); }
    .city-marker[aria-label^="Chișinău"] .node-label { top: 18px; }
    .city-map .city-marker[aria-label^="Tiraspol"] .node-label { left: 36px; right: auto; text-align: left; }
    .controls input[type="datetime-local"] { font-size: 9px; padding: 4px; }
    .city-marker[aria-label^="Ungheni"] .node-label { top: -12px; }
    .node-label { pointer-events: none; position: absolute; left: 29px; top: 4px; display: grid; white-space: nowrap; padding: 3px 5px; border: 1px solid var(--surface-hover); border-radius: 4px; background: var(--glass); color: var(--text); font-size: 10px; line-height: 1.25; text-align: left; }
    .node-label small { color: var(--text-muted); font-size: 9px; }
    .city-marker[data-label-side="left"] .node-label { left: auto; right: 29px; text-align: right; }
    button[aria-pressed="true"] { outline: 2px solid var(--accent); outline-offset: 3px; }
    .map-notes summary { padding: 2px 0; font-size: 10px; }
    .map-legend, .map-source, .map-notes { margin: 8px 12px; font-size: 10px; color: var(--text-muted); }
    .map-table { overflow-x: auto; padding: 0 10px; }
    .map-table table { width: 100%; border-collapse: collapse; font-size: 11px; }
    .map-table caption { text-align: left; padding: 6px; color: var(--text-muted); }
    .map-table th, .map-table td { text-align: left; padding: 2px 4px; border-bottom: 1px solid var(--surface-raised); }
    .map-table button { min-height: 24px; padding: 1px 4px; font-size: 10px; }
    .toolbar-caption { font-size: 10px; color: var(--text-muted); margin-bottom: 8px; }
    .queue-item + .queue-item { border-top: 1px solid var(--surface-raised); margin-top: 12px; padding-top: 12px; }
    .queue-item h4, .city-service h4 { margin: 8px 0; }
    .queue-item p { font-size: 12px; overflow-wrap: anywhere; }
    .city-detail { margin: 0 0 8px; padding: 8px; max-height: 174px; overflow: auto; }
    .city-detail .helper { font-size: 10px; }
    .city-detail h3 { font-size: 14px; }
    .city-detail dl { grid-template-columns: repeat(5, minmax(0, 1fr)); gap: 6px; font-size: 11px; }
    .city-detail dt { font-size: 10px; }
    .city-service { padding: 2px 0; }
    .city-detail dd { font-size: 11px; }
    .city-source { display: inline-block; margin-right: 8px; }
    .city-source summary, .chart-source summary { padding: 2px 0; font-size: 9px; }
    .city-service h4 { margin: 0; font-size: 10px; }
    .city-service dl { margin: 2px 0; }
    .city-source, .city-service > a { font-size: 10px; }
    .city-detail .section-heading { margin: 0 0 4px; }
    .city-detail .section-heading button, .search-results button { min-height: 26px; padding: 2px 6px; font-size: 10px; }
    dl { display: grid; grid-template-columns: repeat(auto-fit, minmax(150px, 1fr)); gap: 12px; }
    dt { color: var(--text-muted); font-size: 12px; }
    dd { margin: 0; }
    .featured-cities { display: grid; grid-template-columns: repeat(5, minmax(0, 1fr)); gap: 5px; }
    .featured-city { padding: 8px; }
    .featured-city > button { padding: 4px 6px; font-size: 12px; }
    .chart-source { font-size: 10px; color: var(--text-muted); }
    .featured-city a { display: inline-block; margin: 2px 0; font-size: 9px; }
    @media (max-width: 1100px) { .controls { grid-template-columns: repeat(4, minmax(0, 1fr)); } }
    @media (max-width: 1000px) { .map-queue { grid-template-columns: 1fr; } .featured-cities { grid-template-columns: repeat(2, minmax(0, 1fr)); } }
    @media (max-width: 600px) { .controls { grid-template-columns: repeat(2, minmax(0, 1fr)); } .node-label { font-size: 10px; } .node-label small { font-size: 9px; } .city-marker[data-label-side="left"] .node-label { right: 26px; } .node-label { left: 26px; } .featured-cities { grid-template-columns: 1fr; } .queue-panel, .city-detail, .featured-city { padding: 12px; } .map-panel { padding: 0; } .controls { align-items: stretch; } label { width: 100%; }  }
  `],
})
export class ConnectedOverviewComponent {
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);
  private readonly fragment = toSignal(this.route.fragment);
  readonly queueDialog = viewChild<ElementRef<HTMLDialogElement>>('queueDialog');
  clearQueueFragment(): void {
    if (this.fragment() === 'incident-queue') void this.router.navigate([], { relativeTo: this.route, queryParamsHandling: 'preserve', replaceUrl: true });
  }
  private readonly api = inject(TelecomClient);
  private readonly store = inject(ServiceStore);
  readonly services = input.required<Summary[]>();
  readonly serviceFilter = input<Filter>('ALL');
  readonly filterChanged = output<Filter>();
  readonly fixture = dataSource.fixture;
  readonly filters: Filter[] = ['ALL', 'VOLTE', 'SMS'];
  readonly pageSize = 20;
  readonly catalogue = signal<readonly City[]>(cities);
  readonly selectedId = signal<string | null>(null);
  readonly search = signal('');
  readonly minutes = signal(15);
  readonly customRange = signal<{ from: string; to: string } | null>(null);
  readonly rangeError = signal('');
  readonly moldovaOutline = moldovaOutline;
  readonly connections = approvedConnections;
  readonly tableService = computed<Service>(() => this.serviceFilter() === 'SMS' ? 'SMS' : 'VOLTE');
  readonly chartTo = computed(() => {
    const custom = this.customRange();
    if (custom) return custom.to;
    const times = this.services().map(item => Date.parse(item.latestWindow?.windowEnd ?? item.observedAt)).filter(Number.isFinite);
    return new Date(times.length ? Math.max(...times) : Date.now()).toISOString();
  });
  readonly chartFrom = computed(() => this.customRange()?.from ?? new Date(Date.parse(this.chartTo()) - this.minutes() * 60_000).toISOString());
  readonly refresh = signal(0);
  readonly statusMessage = signal('');
  readonly queueSeverityFilter = signal('');
  readonly queueStateFilter = signal('');
  readonly queueSeverity = computed(() => ['CRITICAL', 'HIGH', 'MEDIUM'].find(level => this.incidents().some(item => item.severity === level)) ?? 'NONE');
  closeOutside(event: MouseEvent, dialog: HTMLDialogElement): void {
    if (event.target !== dialog) return;
    const rect = dialog.getBoundingClientRect();
    if (event.clientX < rect.left || event.clientX > rect.right || event.clientY < rect.top || event.clientY > rect.bottom) dialog.close();
  }
  readonly queuePage = signal(0);
  readonly queueTotal = signal(0);
  readonly queueLoading = signal(false);
  readonly queueError = signal('');
  readonly incidents = signal<Episode[]>([]);
  readonly histories = signal<Record<string, History>>({});
  readonly historyLoading = signal(false);
  private queueController?: AbortController;
  private historyController?: AbortController;
  private stopped = false;

  readonly markerCities = computed(() => this.catalogue().filter(city => city.marker !== null));
  readonly featuredCities = computed(() => this.catalogue().filter(city => city.featured));
  readonly selectedCity = computed(() => this.catalogue().find(city => city.id === this.selectedId()));
  readonly searchResults = computed(() => {
    const normalize = (value: string) => value.normalize('NFD').replace(/\p{Diacritic}/gu, '').toLowerCase();
    const query = normalize(this.search().trim());
    return this.catalogue().filter(city => normalize(city.name).includes(query));
  });
  readonly visibleIncidents = computed(() => {
    const city = this.selectedCity();
    return this.incidents().filter(item => (!city || city.scopeIds.includes(item.scopeId))
      && (!this.queueSeverityFilter() || item.severity === this.queueSeverityFilter())
      && (!this.queueStateFilter() || item.technicalState === this.queueStateFilter()))
      .sort((a, b) => Date.parse(b.detectedAt) - Date.parse(a.detectedAt));
  });
  readonly cityServices = cityServices;
  readonly cityForScope = cityForScope;
  readonly cityLabel = cityLabel;
  readonly metric = metric;
  readonly baseline = baseline;
  readonly deviation = deviation;
  readonly sampleVolume = sampleVolume;
  readonly number = number;
  readonly next = (value: number) => value + 1;
  readonly previous = (value: number) => Math.max(0, value - 1);

  constructor() {
    const destroy = inject(DestroyRef);
    effect(() => {
      if (this.fragment() === 'incident-queue') this.queueDialog()?.nativeElement.showModal();
    });
    const stop = () => {
      this.stopped = true;
      this.queueController?.abort();
      this.historyController?.abort();
      this.incidents.set([]);
      this.histories.set({});
    };
    destroy.onDestroy(stop);
    inject(SessionStore).ended$.pipe(takeUntilDestroyed(destroy)).subscribe(stop);
    if (this.fixture) {
      void dataSource.loadConnectedDashboard().then(data => {
        if (!this.stopped) this.catalogue.set(data.fixtureCities);
      }).catch(() => { if (!this.stopped) this.statusMessage.set('City design fixture could not be loaded.'); });
    }
    // Reset pagination when the service filter changes.
    effect(() => { this.serviceFilter(); this.queuePage.set(0); });
    effect(() => {
      this.services(); // Parent REST refresh follows the existing incident stream.
      const filter = this.serviceFilter(), page = this.queuePage();
      this.refresh();
      void this.loadIncidents(filter, page);
    });
    effect(() => {
      const summaries = this.services(), catalogue = this.catalogue();
      const filter = this.serviceFilter(), from = this.chartFrom(), to = this.chartTo();
      this.refresh();
      void this.loadHistories(summaries, catalogue, filter, from, to);
    });
  }

  select(id: string): void { this.selectedId.set(id); }

  cityById(id: string): City | undefined { return this.catalogue().find(city => city.id === id); }

  setRegion(value: string): void {
    this.search.set(value);
    const normalize = (text: string) => text.normalize('NFD').replace(/\p{Diacritic}/gu, '').trim().toLowerCase();
    this.selectedId.set(this.catalogue().find(city => normalize(city.name) === normalize(value))?.id ?? null);
  }

  setPeriod(minutes: number, fromInput: HTMLInputElement, toInput: HTMLInputElement): void {
    this.minutes.set(minutes === 60 ? 60 : 15);
    this.customRange.set(null);
    this.rangeError.set('');
    // Restore edited native controls even when a computed endpoint is unchanged.
    fromInput.value = this.chartFrom().slice(0, 16);
    toInput.value = this.chartTo().slice(0, 16);
  }

  applyRange(event: Event, start: string, end: string): void {
    event.preventDefault();
    const from = Date.parse(start + 'Z'), to = Date.parse(end + 'Z');
    if (!Number.isFinite(from) || !Number.isFinite(to) || to <= from || to - from > 3_600_000) {
      this.rangeError.set('Choose an end after the start, with an overview range of at most one hour.');
      return;
    }
    this.customRange.set({ from: new Date(from).toISOString(), to: new Date(to).toISOString() });
    this.rangeError.set('');
  }

  nodeValue(city: City): string {
    const items = cityServices(city, this.services(), this.serviceFilter());
    if (!items.length) return 'Unavailable';
    if (this.serviceFilter() === 'ALL') return this.cityHealth(city);
    return metric(items[0].latestWindow, items[0].scope.service);
  }

  scopeHealth(item: Summary): string {
    if (item.freshness === 'MISSING' || measured(item.latestWindow, item.scope.service) === null) return 'UNKNOWN';
    if (item.freshness === 'STALE') return 'STALE';
    if (baseline(item.latestWindow, item.scope.service) === null) return 'UNKNOWN';
    // Reuse existing presentation rules; this is not a new detector verdict.
    return this.store.health(item);
  }

  cityHealth(city: City): string {
    if (city.scopeIds.length === 0) return 'MAPPING PENDING';
    const items = cityServices(city, this.services(), this.serviceFilter());
    if (items.length === 0) return 'UNAVAILABLE';
    const relevantIds = city.scopeIds.filter(scopeId => this.serviceFilter() === 'ALL'
      || this.services().some(item => item.scope.scopeId === scopeId && item.scope.service === this.serviceFilter()));
    const states = items.map(item => this.scopeHealth(item));
    if (states.includes('DEGRADED')) return 'DEGRADED';
    if (items.length !== relevantIds.length || states.includes('UNKNOWN')) return 'UNKNOWN';
    if (states.includes('STALE')) return 'STALE';
    return 'NORMAL';
  }

  symbol(state: string): string {
    return state === 'NORMAL' ? '✓' : state === 'DEGRADED' ? '!' : state === 'STALE' ? '◷' : '?';
  }

  detectionWindow(incident: Episode): Window {
    const detection = incident.latestDetection;
    return {
      schemaVersion: 2, featureVersion: 2, windowId: detection.detectionId,
      scopeId: detection.scopeId, service: detection.service,
      windowStart: detection.windowStart, windowEnd: detection.windowEnd,
      quality: detection.technicalState === 'UNKNOWN' ? 'MISSING' : 'COMPLETE',
      baselineVersion: detection.baselineVersion, topologyVersion: detection.topologyVersion,
      kpis: detection.kpis, featureNames: [], featureValues: [], mlEligible: false, sourceEventIds: [],
    };
  }

  private async loadIncidents(filter: Filter, page: number): Promise<void> {
    if (this.stopped) return;
    this.queueController?.abort();
    const controller = this.queueController = new AbortController();
    this.queueLoading.set(true);
    this.queueError.set('');
    this.incidents.set([]);
    try {
      const result = await this.api.listIncidents({ service: filter === 'ALL' ? undefined : filter, page, size: this.pageSize }, controller.signal);
      if (controller.signal.aborted || this.stopped) return;
      this.incidents.set(result.items);
      this.queueTotal.set(result.total);
    } catch (error) {
      if (!controller.signal.aborted && !this.stopped) {
        this.queueTotal.set(0);
        this.queueError.set(error instanceof Error ? error.message : 'Incidents could not be loaded.');
      }
    } finally {
      if (!controller.signal.aborted && !this.stopped) this.queueLoading.set(false);
    }
  }

  private async loadHistories(summaries: Summary[], catalogue: readonly City[], filter: Filter, from: string, to: string): Promise<void> {
    if (this.stopped) return;
    this.historyController?.abort();
    const controller = this.historyController = new AbortController();
    this.histories.set({});
    this.historyLoading.set(true);
    const selected = catalogue.filter(city => city.featured).flatMap(city => cityServices(city, summaries, filter));
    const unique = [...new Map(selected.map(item => [item.scope.scopeId, item])).values()];
    const entries = await Promise.all(unique.map(async (item): Promise<[string, History]> => {
      try {
        const result = await this.api.getServiceKpis(item.scope.scopeId, { from, to, page: 0, size: 100 }, controller.signal);
        return [item.scope.scopeId, { rows: result.items, from, to, observedAt: result.observedAt, total: result.total, error: '' }];
      } catch (error) {
        return [item.scope.scopeId, { rows: [], from, to, observedAt: item.observedAt, total: 0,
          error: error instanceof Error ? error.message : 'History could not be loaded.' }];
      }
    }));
    if (!controller.signal.aborted && !this.stopped) {
      this.histories.set(Object.fromEntries(entries));
      this.historyLoading.set(false);
    }
  }
}
