import { Component, DestroyRef, inject, signal } from '@angular/core';
import { DatePipe } from '@angular/common';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { TelecomClient, type ServiceSummary } from '../../core/api/telecom-client';
import { dataSource } from '../../core/api/data-source';
import { KpiChartComponent } from './kpi-chart.component';
import { IncidentListComponent } from '../incident-investigation/incident-list.component';
import { episodes, type Incident, type KpiWindow } from './voice-model';

@Component({
  selector: 'app-service-detail', imports: [RouterLink, DatePipe, KpiChartComponent, IncidentListComponent],
  styles: [`.page-heading { margin: 14px 0; } .page-heading h1, .page-heading p { margin: 6px 0; } :host > .muted { margin: 8px 0; }`],
  template: `
    <a class="back-link" routerLink="/dashboard">← Service overview</a>
    <div class="page-heading"><p class="eyebrow">Service investigation</p><h1>{{ service()?.scope?.service === 'SMS' ? 'SMS service' : 'Voice call setup' }}</h1><p>{{ scopeId() }}</p></div>
    @if (loading()) { <p role="status">Loading service evidence…</p> }
    @if (error()) { <section class="state-panel" role="alert"><h2>Evidence unavailable</h2><p>{{ error() }}</p><button (click)="load()">Retry</button></section> }
    @if (!loading() && !error() && service(); as service) {
      <p class="muted">{{ service.scope.region }} · {{ service.scope.route }} · Source: {{ service.freshness }}</p>
      @if (service.scope.service === 'VOLTE') {
        <form class="time-filter" (submit)="applyRange($event, start.value, end.value)">
          <label>From (UTC)<input #start type="datetime-local" [value]="from().slice(0,16)" required /></label>
          <label>To (UTC, exclusive)<input #end type="datetime-local" [value]="to().slice(0,16)" required /></label>
          <button type="submit">Apply time range</button>
        </form>
        @if (rangeError()) { <p role="alert">{{ rangeError() }}</p> }
        <p class="muted">{{ from() | date:'dd MMM yyyy HH:mm':'UTC' }} – {{ to() | date:'dd MMM yyyy HH:mm':'UTC' }} UTC · {{ windows().length }} windows · {{ incidents().length }} {{ incidents().length === 1 ? 'episode' : 'episodes' }}</p>
        <p class="muted">Server evidence as of {{ observedAt() | date:'dd MMM yyyy HH:mm:ss':'UTC' }} UTC{{ fixture ? ' · Synthetic sample' : '' }}</p>
        <app-kpi-chart [windows]="windows()" [incidents]="incidents()" [from]="from()" [to]="to()" />
        <app-incident-list [incidents]="incidents()" />
      } @else {
        <section class="detail-panel"><h2>Latest SMS observation</h2>
          @if (service.latestWindow; as window) {
            <p>{{ window.windowStart | date:'dd MMM yyyy HH:mm':'UTC' }} UTC · {{ window.quality }}</p>
            @for (kpi of window.kpis; track kpi.name) { <p>{{ kpi.name }}: {{ kpi.observed ?? 'Unavailable' }} {{ kpi.unit }} · Expected {{ kpi.baseline ?? 'Unavailable' }}</p> }
          } @else { <p>No observation available.</p> }
        </section>
      }
    }
  `,
})
export class ServiceDetailComponent {
  private readonly api = inject(TelecomClient);
  private generation = 0;
  readonly fixture = dataSource.fixture;
  readonly scopeId = signal('');
  readonly service = signal<ServiceSummary | null>(null);
  readonly loading = signal(true);
  readonly error = signal('');
  readonly rangeError = signal('');
  readonly from = signal(''); readonly to = signal(''); readonly observedAt = signal('');
  readonly windows = signal<KpiWindow[]>([]); readonly incidents = signal<Incident[]>([]);
  constructor() {
    const destroy = inject(DestroyRef);
    destroy.onDestroy(() => { this.generation++; });
    inject(ActivatedRoute).paramMap.pipe(takeUntilDestroyed(destroy)).subscribe(params => {
      this.scopeId.set(params.get('scopeId') ?? ''); this.from.set(''); this.to.set('');
      void this.load();
    });
  }
  applyRange(event: Event, start: string, end: string) {
    event.preventDefault();
    const from = Date.parse(start + 'Z'), to = Date.parse(end + 'Z');
    if (!Number.isFinite(from) || !Number.isFinite(to) || to <= from || to - from > 86400000) {
      this.rangeError.set('Choose an end after the start, with a range of at most 24 hours.'); return;
    }
    this.rangeError.set(''); this.from.set(new Date(from).toISOString()); this.to.set(new Date(to).toISOString());
    void this.load();
  }
  async load() {
    const generation = ++this.generation, scopeId = this.scopeId();
    this.loading.set(true); this.error.set(''); this.windows.set([]); this.incidents.set([]);
    try {
      const services = await this.api.listServices();
      if (generation !== this.generation) return;
      const service = services.find(item => item.scope.scopeId === scopeId);
      if (!service) throw new Error('This service could not be found. Return to the service overview.');
      this.service.set(service);
      if (service.scope.service !== 'VOLTE') return;
      if (!this.from()) {
        const end = Date.parse(service.latestWindow?.windowEnd ?? service.observedAt);
        const range = this.fixture ? (await dataSource.loadVoice()).voiceRange : { from: new Date(end - 3600000).toISOString(), to: new Date(end).toISOString() };
        if (generation !== this.generation) return;
        this.from.set(range.from); this.to.set(range.to);
      }
      const from = this.from(), to = this.to();
      const [history, incidents] = await Promise.all([
        this.allPages(page => this.api.getServiceKpis(scopeId, { from, to, page, size: 100 })),
        this.allPages(page => this.api.listIncidents({ scopeId, service: 'VOLTE', page, size: 100 })),
      ]);
      if (generation !== this.generation) return;
      this.windows.set(history.items);
      this.observedAt.set(history.observedAt ?? service.observedAt);
      this.incidents.set(episodes(incidents.items).filter(item => Date.parse(item.firstObservedAt) < Date.parse(to) && Date.parse(item.lastObservedAt) > Date.parse(from)));
    } catch (error) {
      if (generation === this.generation) this.error.set(error instanceof Error ? error.message : 'Could not load evidence. Try again.');
    } finally { if (generation === this.generation) this.loading.set(false); }
  }
  private async allPages<T>(fetch: (page: number) => Promise<{items: T[]; total: number; observedAt?: string}>) {
    const items: T[] = []; let observedAt: string | undefined;
    for (let page = 0; page < 100; page++) {
      const result = await fetch(page); items.push(...result.items); observedAt ??= result.observedAt;
      if (items.length >= result.total) return { items, observedAt };
      if (!result.items.length) throw new Error('Evidence changed while loading. Retry to get a complete view.');
    }
    throw new Error('Too much evidence to load. Ask your administrator to review the service history.');
  }
}
