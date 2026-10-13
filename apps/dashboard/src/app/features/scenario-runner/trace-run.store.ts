import { DestroyRef, Injectable, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { TelecomClient, type TraceScenarioCatalogue, type TraceScenarioRun, type TraceScenarioStart } from '../../core/api/telecom-client';
import { ApiFailure } from '../../core/api/api-errors';
import { SessionStore } from '../login-and-session/session.store';

export const terminalTraceRun = (status: TraceScenarioRun['status']) => ['COMPLETED','STOPPED','INTERRUPTED','FAILED'].includes(status);

function errorMessage(error: unknown): string {
  if (error instanceof ApiFailure) {
    switch (error.status) {
      case 400: return 'Invalid command. Check scope, versions and parameter bounds.';
      case 401: return 'Your session ended. Sign in again.';
      case 403: return 'This account or session cannot perform this action.';
      case 404: return 'This run is unavailable to this account.';
      case 409: return 'Request identity or scope interval conflicts with an existing command.';
      case 503: return 'Runtime unavailable or feature disabled. Preserve the command identity and retry.';
    }
  }
  return 'The runtime could not be reached. Retry the same command if start may have succeeded.';
}

@Injectable({ providedIn: 'root' })
export class TraceRunStore {
  private readonly api = inject(TelecomClient);
  private readonly session = inject(SessionStore);
  readonly catalogue = signal<TraceScenarioCatalogue | null>(null);
  readonly runs = signal<TraceScenarioRun[]>([]);
  readonly pending = signal<TraceScenarioStart | null>(null);
  readonly busy = signal(false);
  readonly error = signal('');
  readonly rejected = signal(false);
  private generation = 0;
  private mutation = 0;
  private loading = false;
  private polling = false;
  private controller?: AbortController;
  private storageKey?: string;

  constructor() {
    const destroyRef = inject(DestroyRef);
    this.session.ended$?.pipe(takeUntilDestroyed(destroyRef)).subscribe(() => this.clear());
  }
  private current(revision: number, generation: number) {
    return this.session.phase() === 'authenticated' && this.session.revision === revision && this.generation === generation;
  }
  async load(): Promise<void> {
    if (this.session.phase() !== 'authenticated' || this.loading) return;
    const revision = this.session.revision, generation = this.generation, mutation = this.mutation;
    this.loading = true;
    this.controller?.abort(); this.controller = new AbortController();
    this.storageKey = `trace-link.pending-command:${this.session.actor()?.analystId}`;
    // Only a retry identity is cached. Run state always comes from the server.
    if (!this.pending()) try {
      const raw = sessionStorage.getItem(this.storageKey);
      if (raw) {
        const body = JSON.parse(raw) as TraceScenarioStart;
        if (typeof body.requestId === 'string' && Number.isSafeInteger(body.seed)) this.pending.set(body);
      }
    } catch { /* Storage is optional. */ }
    try {
      const catalogue = await this.api.traceScenarioCatalogue(this.controller.signal);
      if (!this.current(revision,generation)) return;
      this.catalogue.set(catalogue);
      if (catalogue.enabled) {
        const runs = await this.api.traceScenarioHistory(this.controller.signal);
        if (!this.current(revision,generation) || mutation !== this.mutation) return;
        this.runs.set(runs);
      }
      this.error.set('');
    } catch (error) { if (this.current(revision,generation)) this.error.set(errorMessage(error)); }
    finally { if (this.current(revision,generation)) this.loading = false; }
  }
  async start(body: TraceScenarioStart): Promise<void> {
    if (this.session.phase() !== 'authenticated' || this.busy()) return;
    const prior = this.pending();
    if (prior && JSON.stringify(prior) !== JSON.stringify(body)) {
      this.error.set('Retry the original unchanged command before preparing another.'); return;
    }
    this.pending.set(structuredClone(body)); this.save(); this.rejected.set(false);
    await this.mutate(() => this.api.startTraceScenario(this.pending()!), true);
  }
  async retry(): Promise<void> { const command = this.pending(); if (command) await this.start(command); }
  async stop(id: string): Promise<void> { await this.mutate(() => this.api.stopTraceScenario(id)); }
  async reconcile(id: string): Promise<void> { await this.mutate(() => this.api.reconcileTraceScenario(id)); }
  abandonRejected(): void {
    if (!this.rejected() || this.busy()) return;
    this.pending.set(null); this.rejected.set(false); this.error.set(''); this.save();
  }
  private async mutate(operation: () => Promise<TraceScenarioRun>, start = false) {
    if (this.session.phase() !== 'authenticated' || this.busy()) return;
    const revision = this.session.revision, generation = this.generation;
    ++this.mutation;
    this.busy.set(true); this.error.set('');
    try {
      const run = await operation();
      if (!this.current(revision,generation)) return;
      this.upsert(run);
      if (start) { this.pending.set(null); this.save(); }
    } catch (error) {
      if (!this.current(revision,generation)) return;
      this.error.set(errorMessage(error));
      if (start) this.rejected.set(error instanceof ApiFailure && [400,409].includes(error.status));
    } finally { if (this.current(revision,generation)) this.busy.set(false); }
  }
  async refresh(): Promise<void> {
    if (this.session.phase() !== 'authenticated' || this.polling || this.busy()) return;
    const active = this.runs().filter(run => !terminalTraceRun(run.status));
    if (!active.length) return;
    const revision = this.session.revision, generation = this.generation;
    this.polling = true;
    try {
      for (const run of active) {
        const latest = await this.api.traceScenarioRun(run.runId);
        if (!this.current(revision,generation)) return;
        // A stop may have completed while this GET was outstanding.
        const current = this.runs().find(item => item.runId === run.runId);
        if (!this.busy() && current && !terminalTraceRun(current.status)) this.upsert(latest);
      }
    } catch (error) { if (this.current(revision,generation)) this.error.set(errorMessage(error)); }
    finally { if (this.current(revision,generation)) this.polling = false; }
  }
  private upsert(run: TraceScenarioRun) {
    this.runs.update(items => [run,...items.filter(item => item.runId !== run.runId)].slice(0,128));
  }
  private save() {
    if (!this.storageKey || this.session.phase() !== 'authenticated') return;
    try { const command = this.pending(); if (command) sessionStorage.setItem(this.storageKey,JSON.stringify(command)); else sessionStorage.removeItem(this.storageKey); }
    catch { /* An open tab still retains the original retry identity. */ }
  }
  private clear() {
    ++this.generation; this.controller?.abort();
    if (this.storageKey) try { sessionStorage.removeItem(this.storageKey); } catch { /* Storage is optional. */ }
    this.storageKey = undefined; this.catalogue.set(null); this.runs.set([]); this.pending.set(null);
    this.busy.set(false); this.error.set(''); this.rejected.set(false); this.loading = false; this.polling = false;
  }
}
