import { DatePipe } from '@angular/common';
import { Component, OnDestroy, computed, effect, inject, signal, untracked } from '@angular/core';
import type { TraceScenarioParameters, TraceScenarioRun, TraceScenarioScope, TraceScenarioStart } from '../../core/api/telecom-client';
import { requestId } from '../../core/api/request-id';
import { SessionStore } from '../login-and-session/session.store';
import { TraceRunStore, terminalTraceRun } from './trace-run.store';

const supported = new Set(['TRANSPORT_LINK_OVERLOAD','ROAMING_REGISTRATION_FAILURE','MOBILE_DATA_DNS_FAILURE','NORMAL_CONTROL','TELEMETRY_GAP']);
export function percentageBasisPoints(raw: string): number | null {
  if (!/^\d{1,3}(\.\d{1,2})?$/.test(raw)) return null;
  const [whole,fraction = ''] = raw.split('.');
  const value = Number(whole)*100 + Number(fraction.padEnd(2,'0'));
  return value <= 10000 ? value : null;
}
export function missingMinuteIndices(raw: string): number[] | null {
  if (!raw.trim()) return [];
  const parts = raw.split(',').map(part => part.trim());
  if (parts.some(part => !/^[0-7]$/.test(part))) return null;
  const values = parts.map(Number);
  return new Set(values).size === values.length ? values : null;
}
export function plannedTraceStage(type: string, minute: number): string {
  if (type === 'NORMAL_CONTROL') return 'Healthy measured control planned';
  const phase = minute <= 2 ? 0 : minute <= 5 ? 1 : 2;
  const stages: Record<string,string[]> = {
    TRANSPORT_LINK_OVERLOAD: ['Normal carried traffic','High measured utilization','Returning utilization'],
    ROAMING_REGISTRATION_FAILURE: ['Normal registration success','Registration failures','Restored registration success'],
    MOBILE_DATA_DNS_FAILURE: ['Normal DNS success','DNS failures; activation control independent','Restored DNS success'],
    TELEMETRY_GAP: ['Normal telemetry','Missing telemetry','Telemetry resumes'],
  };
  return stages[type]?.[phase] ?? 'Unavailable';
}

@Component({
  selector: 'app-trace-scenario-runner',
  imports: [DatePipe],
  template: `
    <section class="detail-panel" aria-labelledby="trace-scenarios-title">
      <h2 id="trace-scenarios-title">Trace Link synthetic scenarios</h2>
      <p>Eight native one-minute windows. Local generation is available only when the runtime feature is enabled. Kafka delivery and live detection require separate approval.</p>
      @if (store.error()) { <p role="alert">{{ store.error() }}</p> }
      @if (!store.catalogue()) { <button type="button" (click)="store.load()">Load Trace Link catalogue</button> }
      @else if (!store.catalogue()?.enabled) { <p role="status">Trace Link scenarios are disabled in this environment. Legacy VoLTE and SMS controls remain available below.</p> }
      @else {
        @if (store.pending()) {
          <p role="status">An unchanged command is retained for retry: <code>{{ store.pending()?.requestId }}</code>. Its outcome may be uncertain.</p>
          <button type="button" [disabled]="store.busy()" (click)="store.retry()">Retry same Trace Link command</button>
          @if (store.rejected()) { <button type="button" (click)="store.abandonRejected()">Prepare a different Trace Link command</button> }
        } @else {
          <div class="scenario-form">
            <div class="field"><label for="trace-type">Trace Link scenario</label><select id="trace-type" [value]="type()" (change)="selectType($any($event.target).value)">
              @for (definition of definitions(); track definition.type) { <option [value]="definition.type" [selected]="definition.type === type()">{{ label(definition.type) }}</option> }
            </select></div>
            @if (type() === 'NORMAL_CONTROL' || type() === 'TELEMETRY_GAP') {
              <div class="field"><label for="trace-domain">Domain</label><select id="trace-domain" [value]="domain()" (change)="domain.set($any($event.target).value)">
                @for (item of domains(); track item) { <option [value]="item" [selected]="item === domain()">{{ item === 'MOBILE_DATA' ? 'LTE mobile data' : label(item) }}</option> }
              </select></div>
            }
            <div class="field"><label for="trace-entity">{{ domain() === 'TRANSPORT' ? 'Transport link' : domain() === 'ROAMING' ? 'Country / synthetic partner' : 'Reference city / LTE DNS' }}</label>
              <select id="trace-entity" [value]="entity()" (change)="entity.set($any($event.target).value)">
                @for (item of entities(); track item.id) { <option [value]="item.id" [selected]="item.id === entity()">{{ item.label }}</option> }
              </select>
            </div>
            @if (domain() !== 'MOBILE_DATA') {
              <div class="field"><label for="trace-direction">{{ domain() === 'ROAMING' ? 'Roaming direction' : 'Link direction' }}</label><select id="trace-direction" [value]="direction()" (change)="direction.set($any($event.target).value)">
                @for (item of directions(); track item) { <option [value]="item" [selected]="item === direction()">{{ item }}</option> }
              </select></div>
            }
            <div class="field"><label for="trace-seed">Repeatable seed</label><input id="trace-seed" type="text" inputmode="numeric" [value]="seed()" (input)="seed.set($any($event.target).value)" /></div>
            @if (domain() === 'TRANSPORT') {
              <div class="field"><label for="trace-normal">Normal utilization (%)</label><input id="trace-normal" type="text" inputmode="decimal" [value]="normal()" (input)="normal.set($any($event.target).value)" /></div>
              <div class="field"><label for="trace-degraded">Degraded utilization (%)</label><input id="trace-degraded" type="text" inputmode="decimal" [value]="degraded()" (input)="degraded.set($any($event.target).value)" /></div>
              <div class="field"><label for="trace-capacity">Configured synthetic capacity (Gbps)</label><input id="trace-capacity" type="text" inputmode="numeric" [value]="capacity()" (input)="capacity.set($any($event.target).value)" /></div>
            } @else {
              <div class="field"><label for="trace-attempts">Attempts per minute</label><input id="trace-attempts" type="text" inputmode="numeric" [value]="attempts()" (input)="attempts.set($any($event.target).value)" /></div>
              <div class="field"><label for="trace-healthy">Healthy success (%)</label><input id="trace-healthy" type="text" inputmode="decimal" [value]="healthy()" (input)="healthy.set($any($event.target).value)" /></div>
              <div class="field"><label for="trace-failure">Degraded success (%)</label><input id="trace-failure" type="text" inputmode="decimal" [value]="failure()" (input)="failure.set($any($event.target).value)" /></div>
            }
            <div class="field"><label for="trace-missing">Missing minute indices (0–7, comma separated)</label><input id="trace-missing" type="text" [value]="missing()" (input)="missing.set($any($event.target).value)" /></div>
          </div>
          @if (selected(); as selected) {
            <p>Scope: {{ selected.label }}. Namespace {{ store.catalogue()?.sourceNamespace }}. Topology {{ selected.scope.topologyVersion }}.</p>
            @if (domain() === 'TRANSPORT') { <p>Source {{ selected.sourceCity }} → destination {{ selected.destinationCity }}. Reference nominal capacity {{ selected.nominalCapacityGbps }} Gbps. Configured run capacity is separate; no reverse measurements or customer outage are inferred.</p> }
            @if (domain() === 'ROAMING') { <p>Country {{ selected.scope.country }}; partner {{ selected.scope.id }}; procedure REGISTRATION. Partner and direction populations remain separate.</p> }
            @if (domain() === 'MOBILE_DATA') { <p>LTE DNS procedure. EPS bearer activation is a separate control population.</p> }
          }
          @if (!valid()) { <p role="status">Use whole-number seeds, capacity 1–1000, attempts 0–1,000,000, percentages with at most two decimal places, and unique missing minute indices. Degraded utilization must be at least normal; degraded success must be at most healthy.</p> }
          <button type="button" class="primary" [disabled]="!valid() || store.busy()" [attr.aria-busy]="store.busy()" (click)="start()">Start Trace Link scenario</button>
        }
        <h3>Planned eight-minute profile</h3><p>Planned recovery is not observed technical recovery. The server determines lifecycle and completed windows.</p>
        <ol class="scenario-stepper">@for (minute of minutes; track minute) { <li [attr.data-phase]="type() === 'NORMAL_CONTROL' || minute <= 2 ? 'NORMAL' : minute <= 5 ? 'DEGRADED' : 'RECOVERY'"><strong>{{ minute }}</strong><span>{{ stage(type(),minute) }}</span></li> }</ol>
        <details><summary>Unavailable future scenarios</summary><ul>@for (definition of unavailable(); track definition.type) { <li>{{ label(definition.type) }} — not executable</li> }</ul></details>
        <h3>Durable run history</h3>
        @if (!store.runs().length) { <p>No runs have been returned for this account.</p> }
        @for (run of store.runs(); track run.runId) {
          <article class="detail-panel" [attr.aria-label]="label(run.command.type) + ' ' + run.command.scope.id">
            <h4>{{ label(run.command.type) }} · {{ run.command.scope.id }} {{ run.command.scope.direction }}</h4>
            <p><strong>{{ run.status }}</strong> · Run <code>{{ run.runId }}</code></p>
            <p>Server schedule UTC: {{ run.command.start | date:'dd MMM yyyy HH:mm:ss':'UTC' }} — {{ end(run) | date:'HH:mm:ss':'UTC' }}.</p>
            <p>{{ run.completedWindows }}/8 locally generated windows; {{ run.generatedObservations }} generated observations. Offered {{ run.offered }}; acknowledged {{ run.acknowledged }}; persisted and accepted unavailable.</p>
            <p>{{ notice(run.status) }}</p>
            @if (!terminal(run.status)) { <button type="button" class="danger" [disabled]="store.busy()" (click)="store.stop(run.runId)">Stop Trace Link generation</button> }
            @if (run.status === 'INTERRUPTED') { <button type="button" [disabled]="store.busy()" (click)="store.reconcile(run.runId)">Reconcile retained history</button> }
            <details><summary>Local generated observation history · delivery disabled</summary>
              @for (window of run.history ?? []; track window.start) { <p>{{ window.start }} UTC</p><ul>@for (raw of window.observations; track raw) { <li>{{ observation(raw) }}</li> }</ul> }
            </details>
          </article>
        }
      }
    </section>
  `,
})
export class TraceScenarioRunnerComponent implements OnDestroy {
  readonly store = inject(TraceRunStore);
  private readonly session = inject(SessionStore);
  readonly type = signal<TraceScenarioStart['type']>('TRANSPORT_LINK_OVERLOAD');
  readonly domain = signal<TraceScenarioScope['domain']>('TRANSPORT');
  readonly entity = signal(''); readonly direction = signal('');
  readonly seed = signal('42'); readonly normal = signal(''); readonly degraded = signal('');
  readonly capacity = signal(''); readonly attempts = signal(''); readonly healthy = signal(''); readonly failure = signal(''); readonly missing = signal('');
  readonly minutes = [1,2,3,4,5,6,7,8]; readonly terminal = terminalTraceRun; readonly stage = plannedTraceStage;
  readonly definitions = computed(() => (this.store.catalogue()?.definitions ?? []).filter(item => item.implemented && supported.has(item.type)));
  readonly unavailable = computed(() => (this.store.catalogue()?.definitions ?? []).filter(item => !item.implemented));
  readonly domains = computed(() => this.definitions().find(item => item.type === this.type())?.domains ?? []);
  readonly scopes = computed(() => (this.store.catalogue()?.scopes ?? []).filter(item => item.scope.domain === this.domain()));
  readonly entities = computed(() => [...new Map(this.scopes().map(item => [item.scope.id, {id:item.scope.id,label:this.domain() === 'ROAMING' ? `${item.scope.country} / ${item.scope.id}` : item.scope.id}])).values()]);
  readonly directions = computed(() => [...new Set(this.scopes().filter(item => item.scope.id === this.entity()).map(item => item.scope.direction).filter((item): item is string => !!item))]);
  readonly selected = computed(() => this.scopes().find(item => item.scope.id === this.entity() && (this.domain() === 'MOBILE_DATA' || item.scope.direction === this.direction())));
  readonly parameters = computed<TraceScenarioParameters | null>(() => {
    const normal = percentageBasisPoints(this.normal()), degraded = percentageBasisPoints(this.degraded());
    const healthy = percentageBasisPoints(this.healthy()), failure = percentageBasisPoints(this.failure());
    const missing = missingMinuteIndices(this.missing());
    const capacity = Number(this.capacity()), attempts = Number(this.attempts());
    if (normal == null || degraded == null || degraded < normal || healthy == null || failure == null || failure > healthy || !missing
      || !/^\d+$/.test(this.capacity()) || !Number.isInteger(capacity) || capacity < 1 || capacity > 1000
      || !/^\d+$/.test(this.attempts()) || !Number.isInteger(attempts) || attempts > 1000000) return null;
    return { normalUtilizationBp:normal,degradedUtilizationBp:degraded,capacityGbps:capacity,attempts,healthySuccessBp:healthy,degradedSuccessBp:failure,missingMinutes:missing };
  });
  readonly valid = computed(() => !!this.selected() && !!this.parameters() && /^\d+$/.test(this.seed()) && Number.isSafeInteger(Number(this.seed())) && this.definitions().some(item => item.type === this.type()));
  private initializedDefaults = false;
  private readonly timer = setInterval(() => void this.store.refresh(),5000);
  constructor() {
    effect(() => { if (this.session.phase() === 'authenticated') untracked(() => void this.store.load()); });
    effect(() => { const domains = this.domains(); if (domains.length && !domains.includes(this.domain())) this.domain.set(domains[0] as TraceScenarioScope['domain']); });
    effect(() => { const entities = this.entities(); if (entities.length && !entities.some(item => item.id === this.entity())) this.entity.set(entities[0].id); });
    effect(() => { const directions = this.directions(); if (directions.length && !directions.includes(this.direction())) this.direction.set(directions[0]); });
    effect(() => {
      const defaults = this.store.catalogue()?.defaults;
      if (!defaults || this.initializedDefaults) return;
      this.initializedDefaults = true;
      this.normal.set(String(defaults.normalUtilizationBp/100)); this.degraded.set(String(defaults.degradedUtilizationBp/100));
      this.capacity.set(String(defaults.capacityGbps)); this.attempts.set(String(defaults.attempts));
      this.healthy.set(String(defaults.healthySuccessBp/100)); this.failure.set(String(defaults.degradedSuccessBp/100));
      this.missing.set(defaults.missingMinutes.join(','));
    });
  }
  selectType(type: TraceScenarioStart['type']) { this.type.set(type); }
  start() {
    const selected = this.selected(), parameters = this.parameters(); if (!this.valid() || !selected || !parameters) return;
    void this.store.start({requestId:requestId(),type:this.type(),version:'trace-link-scenarios-v1',scope:selected.scope,seed:Number(this.seed()),parameters});
  }
  label(value: string) { return value.replaceAll('_',' ').toLowerCase(); }
  end(run: TraceScenarioRun) { return new Date(Date.parse(run.command.start)+480000).toISOString(); }
  notice(status: TraceScenarioRun['status']) {
    if (status === 'INTERRUPTED') return 'Interrupted by restart or late delivery. Incomplete executions do not resume; reconcile retained history.';
    if (status === 'STOPPED') return 'Generation stopped. This is not healthy recovery.';
    if (status === 'COMPLETED') return 'Generation completed. Kafka acknowledgement, persistence, detection and incident recovery are not established.';
    if (status === 'FAILED') return 'Generation failed. Inspect incomplete history.';
    return 'Planned generation is synthetic. Accepted telemetry and detector decisions remain unavailable.';
  }
  observation(raw: string): string {
    try { const value = JSON.parse(raw); return `${value.metricId} · ${value.quality ?? 'UNKNOWN'} · coverage ${value.coverage ?? 'unavailable'} · primitives ${JSON.stringify(value.primitives)}`; }
    catch { return 'Observation unavailable'; }
  }
  ngOnDestroy() { clearInterval(this.timer); }
}
