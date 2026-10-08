import { Component, DestroyRef, computed, effect, inject, input, signal } from '@angular/core';
import { DatePipe } from '@angular/common';
import { RouterLink } from '@angular/router';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { TelecomClient } from '../../core/api/telecom-client';
import type { components } from '../../core/api/schema';
import { SessionStore } from '../login-and-session/session.store';
import { type City, type Filter, type Service, number } from './dashboard-geography';

type Page = components['schemas']['GeographyKpiPage'];
type TopologyPage = components['schemas']['GeographyTopologyPage'];

@Component({
  selector: 'app-city-evidence', imports: [DatePipe, RouterLink],
  template: `<section class="detail-panel" aria-label="Selected city investigation">
    <h2>{{ city().name }} investigation</h2>
    @if (catalogueError()) { <p role="status">City catalogue unavailable; these retained observations may be stale.</p> }
    @if (city().geography?.synthetic) { <p class="muted">Configured synthetic footprint · backend observations</p> }
    @for (state of city().geography?.services ?? []; track state.scopeId) {
      <p>{{ state.service === 'VOLTE' ? 'VoLTE' : 'SMS' }} · City/service impact: {{ state.technicalActiveCount }} ongoing · {{ state.analystOpenCount }} analyst open · {{ state.freshness }} · Latest: {{ state.latestWindowEnd ? (state.latestWindowEnd | date:'dd MMM HH:mm:ss':'UTC') + ' UTC' : 'Unavailable' }}</p>
    }
    <div class="button-row">@for (state of city().geography?.services ?? []; track state.scopeId) {
      <a class="button" [routerLink]="['/services', state.scopeId]">Open {{ city().name }} {{ state.service === 'VOLTE' ? 'VoLTE setup' : 'SMS delivery' }} →</a>
    }</div>
    <details class="evidence-disclosure"><summary>Containment and dependencies</summary>
      @if (topologyError()) { <p role="alert">{{ topologyError() }}</p> }
      @if (topology(); as tree) {
        <p>Catalogue {{ tree.catalogueVersion }} · Parent {{ tree.parentId }} · {{ tree.nodes.length }} children on this page</p>
        <div class="button-row">
          @if (topologyPath().length) { <button type="button" (click)="backTopology()">Back to parent</button> }
          @for (node of tree.nodes; track node.nodeId) {
            <button type="button" (click)="openTopology(node.nodeId)" [disabled]="node.kind === 'CELL'">{{ node.kind }} {{ node.nodeId }}{{ node.measured ? ' · measured footprint' : '' }}</button>
          }
        </div>
        <nav class="pagination" aria-label="Topology pages"><button type="button" (click)="changeTopologyPage(-1)" [disabled]="topologyPage() === 0 || topologyLoading()">Previous</button><span>Page {{ topologyPage() + 1 }}</span><button type="button" (click)="changeTopologyPage(1)" [disabled]="!tree.hasNext || topologyLoading()">Next</button></nav>
        <p>Dependencies: @for (dependency of tree.dependencies; track dependency.scopeId + dependency.role) { {{ dependency.service }} {{ dependency.role }} → {{ dependency.nodeId }}; }</p>
      } @else { <p>{{ topologyLoading() ? 'Loading containment…' : 'Containment unavailable.' }}</p> }
      <p>Local device measurements: Unavailable. City/service impact is context for this footprint.</p>
    </details>
  </section>
  <details class="evidence-disclosure city-evidence"><summary>{{ city().name }} · City coverage and history</summary>
    @if (error()) { <p role="alert">{{ error() }} No substitute city measurements are used.</p> }
    @if (detail(); as item) {
      <p>Catalogue response: {{ item.generatedAt | date:'dd MMM yyyy HH:mm:ss':'UTC' }} UTC</p>
      <details class="evidence-disclosure"><summary>Troubleshooting</summary><p>Catalogue: {{ item.catalogueVersion }} · Topology: {{ item.topologyVersion }}</p><p>Footprint references: {{ item.footprintNodeIds.join(', ') || 'Unavailable' }}</p>
        @for (state of item.services; track state.scopeId) { <p>{{ state.service }} scope: {{ state.scopeId }}</p> }
      </details>
      @for (state of item.services; track state.scopeId) {
        <p>{{ state.service }} · {{ state.freshness }} · {{ state.technicalActiveCount }} ongoing / {{ state.analystOpenCount }} analyst open · Latest: {{ state.latestWindowEnd ? (state.latestWindowEnd | date:'dd MMM HH:mm:ss':'UTC') + ' UTC' : 'Unavailable' }}</p>
        <p>Coverage: {{ state.coverage.state }} · {{ value(state.coverage.usableSources) }} usable / {{ value(state.coverage.receivedSources) }} received / {{ value(state.coverage.expectedSources) }} expected · {{ state.metric.nullReason ?? 'Measured' }}</p>
      }
    }
    @for (service of selectedServices(); track service) {
      <h3>{{ service === 'VOLTE' ? 'VoLTE CSSR' : 'SMS delivery p95' }} · UTC history</h3>
      <div class="chart-scroll" tabindex="0" [attr.aria-label]="service + ' city history'" [attr.aria-busy]="loading()">
        <table class="kpi-table"><thead><tr><th>UTC start</th><th>Observed</th><th>Baseline</th><th>{{ service === 'VOLTE' ? 'Change (pp)' : 'Delay ratio' }}</th><th>Sample volume</th><th>Coverage</th></tr></thead><tbody>
          @for (point of histories()[service]?.points ?? []; track point.windowId) {
            <tr [attr.data-city-window]="point.windowId"><th scope="row">{{ point.windowStart | date:'dd MMM HH:mm':'UTC' }}</th><td>{{ value(point.metric.observed) }} {{ point.metric.unit === 'PERCENT' ? '%' : 'ms' }}</td><td>{{ value(point.metric.baseline) }}</td><td>{{ value(service === 'VOLTE' ? point.metric.deltaPp : point.metric.delayRatio) }}</td><td>{{ value(service === 'VOLTE' ? point.metric.denominator : point.metric.sampleCount) }}</td><td [title]="point.metric.nullReason ?? ''">{{ point.coverage.state }} · {{ value(point.coverage.usableSources) }}/{{ value(point.coverage.expectedSources) }}</td></tr>
          } @empty { <tr><td colspan="6">{{ loading() ? 'Loading city history…' : 'No city KPI history in this range.' }}</td></tr> }
        </tbody></table>
      </div>
      <nav class="pagination" [attr.aria-label]="service + ' city history pages'"><button type="button" (click)="changePage(service, -1)" [disabled]="loading() || pages()[service] === 0">Previous</button><span>Page {{ pages()[service] + 1 }}</span><button type="button" (click)="changePage(service, 1)" [disabled]="loading() || !histories()[service]?.hasNext">Next</button></nav>
    }
  </details>`,
})
export class CityEvidenceComponent {
  readonly city = input.required<City>();
  readonly filter = input.required<Filter>();
  readonly from = input.required<string>();
  readonly to = input.required<string>();
  readonly updatedAt = input.required<string>();
  readonly catalogueError = input('');
  readonly selectedServices = computed<readonly Service[]>(() => this.filter() === 'ALL' ? ['VOLTE', 'SMS'] : [this.filter() as Service]);
  readonly detail = signal<components['schemas']['GeographyCityDetail'] | null>(null);
  readonly histories = signal<Partial<Record<Service, Page>>>({});
  readonly topology = signal<TopologyPage | null>(null);
  readonly topologyPath = signal<string[]>([]);
  readonly topologyPage = signal(0);
  readonly topologyError = signal('');
  readonly topologyLoading = signal(false);
  readonly pages = signal({ VOLTE: 0, SMS: 0 });
  readonly error = signal('');
  readonly loading = signal(false);
  private readonly api = inject(TelecomClient);
  private stopped = false;
  private controller?: AbortController;
  private evidenceIdentity = '';
  value(value: number | null | undefined): string { return value == null || !Number.isFinite(value) ? 'Unavailable' : number(value); }
  changePage(service: Service, change: number): void { this.pages.update(pages => ({ ...pages, [service]: Math.max(0, pages[service] + change) })); }
  openTopology(nodeId: string): void { this.topologyPath.update(path => [...path, nodeId]); this.topologyPage.set(0); }
  backTopology(): void { this.topologyPath.update(path => path.slice(0, -1)); this.topologyPage.set(0); }
  changeTopologyPage(delta: number): void { this.topologyPage.update(page => Math.max(0, page + delta)); }

  constructor() {
    const destroy = inject(DestroyRef);
    const stop = () => { this.stopped = true; this.controller?.abort(); this.detail.set(null); this.histories.set({}); this.topology.set(null); };
    destroy.onDestroy(stop);
    inject(SessionStore).ended$.pipe(takeUntilDestroyed(destroy)).subscribe(stop);
    effect(() => { this.city().id; this.filter(); this.from(); this.to(); this.pages.set({ VOLTE: 0, SMS: 0 }); this.topologyPath.set([]); this.topologyPage.set(0); });
    effect(cleanup => {
      const city = this.city(), path = this.topologyPath(), page = this.topologyPage();
      this.updatedAt();
      const controller = new AbortController();
      cleanup(() => controller.abort());
      if (!city.geography || this.stopped) return;
      this.topologyLoading.set(true); this.topologyError.set('');
      void this.api.getGeographyTopology(city.id, { catalogueVersion: city.geography.catalogueVersion,
        parentId: path.at(-1), page, size: 20 }, controller.signal).then(tree => {
        if (controller.signal.aborted || this.stopped) return;
        if (tree.cityId !== city.id || tree.catalogueVersion !== city.geography?.catalogueVersion
          || tree.topologyVersion !== city.geography?.topologyVersion) throw new Error('Unexpected topology version.');
        this.topology.set(tree);
      }).catch(error => {
        if (!controller.signal.aborted && !this.stopped) this.topologyError.set(error instanceof Error ? error.message : 'Topology unavailable.');
      }).finally(() => { if (!controller.signal.aborted && !this.stopped) this.topologyLoading.set(false); });
    });
    effect(cleanup => {
      const city = this.city(), from = this.from(), to = this.to(), services = this.selectedServices(), pages = this.pages();
      this.updatedAt();
      const controller = this.controller = new AbortController();
      cleanup(() => controller.abort());
      const identity = `${city.id}/${city.geography?.catalogueVersion}/${city.scopeIds.join(',')}`;
      if (identity !== this.evidenceIdentity) {
        this.evidenceIdentity = identity;
        this.detail.set(null); this.histories.set({});
      }
      if (!city.geography || this.stopped) return;
      this.loading.set(true); this.error.set('');
      void Promise.all([
        this.api.getGeographyCity(city.id, controller.signal),
        Promise.all(services.map(async service => {
          const result = await this.api.getGeographyCityKpis(city.id, { service, from, to, page: pages[service], size: 20 }, controller.signal);
          const scope = city.geography!.services.find(state => state.service === service)!.scopeId;
          const ids = new Set<string>();
          if (result.cityId !== city.id || result.service !== service || result.page !== pages[service]
            || result.points.length > 20 || (result.hasNext && !result.points.length)) throw new Error('Unexpected city history page.');
          for (const point of result.points) {
            const start = Date.parse(point.windowStart);
            if (point.scopeId !== scope || point.catalogueVersion !== city.geography!.catalogueVersion
              || point.topologyVersion !== city.geography!.topologyVersion || ids.has(point.windowId)
              || !Number.isFinite(start) || start < Date.parse(from) || start >= Date.parse(to)) throw new Error('Unexpected city history identity.');
            ids.add(point.windowId);
          }
          return [service, result] as const;
        })),
      ]).then(([detail, entries]) => {
        if (controller.signal.aborted || this.stopped) return;
        if (detail.cityId !== city.id || detail.catalogueVersion !== city.geography!.catalogueVersion) throw new Error('City catalogue changed. Refresh overview.');
        this.detail.set(detail); this.histories.set(Object.fromEntries(entries));
      }).catch(error => {
        if (!controller.signal.aborted && !this.stopped) this.error.set(error instanceof Error ? error.message : 'City evidence unavailable.');
      }).finally(() => { if (!controller.signal.aborted && !this.stopped) this.loading.set(false); });
    });
  }
}
