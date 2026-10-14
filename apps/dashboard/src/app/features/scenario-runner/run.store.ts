import { DestroyRef, Injectable, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import {
  TelecomClient,
  type ScenarioRun,
  type ScenarioType,
} from '../../core/api/telecom-client';
import { ApiFailure } from '../../core/api/api-errors';
import { SessionStore } from '../login-and-session/session.store';
import { requestId } from '../../core/api/request-id';

export type ScenarioCommand = {
  type: ScenarioType;
  requestId: string;
  seed: number;
  scopeId: string;
};

type SavedState = {
  command: ScenarioCommand | null;
  run: ScenarioRun | null;
  canAbandon: boolean;
};

function scenarioError(error: unknown): string {
  if (error instanceof ApiFailure) {
    if (error.status === 401) {
      return 'Your session expired. Sign in again before changing a run.';
    }
    if (error.status === 403) {
      return 'This action is unavailable to your account or session. Check access before retrying.';
    }
    if (error.status === 409) {
      return 'The command conflicts with an existing run or request. Review it before starting a different command.';
    }
    if (error.status === 503) {
      return 'The outcome is uncertain. Retry this same command with its original request ID.';
    }
    if (error.status === 404) {
      return 'The public scenario run was not found. Ask the backend owner to check the durable run record.';
    }
    return error.message;
  }

  return 'The scenario service could not be reached. If start may have succeeded, retry the same command.';
}

@Injectable({ providedIn: 'root' })
export class RunStore {
  private readonly api = inject(TelecomClient);
  private readonly session = inject(SessionStore);

  readonly command = signal<ScenarioCommand | null>(null);
  readonly run = signal<ScenarioRun | null>(null);
  readonly busy = signal(false);
  readonly refreshing = signal(false);
  readonly error = signal('');
  readonly notice = signal('');
  readonly canAbandon = signal(false);
  private operation = 0;

  constructor() {
    const destroyRef = inject(DestroyRef);
    this.session.ended$?.pipe(takeUntilDestroyed(destroyRef)).subscribe(() => {
      ++this.operation;
      this.command.set(null); this.run.set(null); this.busy.set(false); this.refreshing.set(false);
      this.error.set(''); this.notice.set(''); this.canAbandon.set(false);
    });
  }
  private current(revision: number, operation: number): boolean {
    return this.session.phase() === 'authenticated' && this.session.revision === revision && this.operation === operation;
  }

  private get storageKey(): string {
    return `telecom.scenario-runner:${this.session.actor()?.analystId ?? 'unknown'}`;
  }

  restore(): void {
    try {
      const raw = sessionStorage.getItem(this.storageKey);
      if (!raw) return;

      const saved = JSON.parse(raw) as SavedState;

      if (
        saved.command
        && typeof saved.command.requestId === 'string'
        && typeof saved.command.scopeId === 'string'
        && Number.isSafeInteger(saved.command.seed)
      ) {
        this.command.set(saved.command);
      }

      if (saved.run && typeof saved.run.runId === 'string') {
        const revision = this.session.revision, operation = this.operation;
        // Browser storage supplies only a locator; server state remains authoritative.
        void this.api.getScenarioRun(saved.run.runId).then(run => {
          if (this.current(revision,operation)) { this.run.set(run); this.save(); }
        }).catch(error => { if (this.current(revision,operation)) this.error.set(scenarioError(error)); });
      }

      this.canAbandon.set(saved.canAbandon === true);
    } catch {
      // The runner remains usable when browser storage is unavailable.
    }
  }

  async start(
    type: ScenarioType,
    seed: number,
    scopeId: string,
  ): Promise<void> {
    if (this.session.phase() !== 'authenticated') return;
    if (this.busy() || this.run()) return;

    if (!Number.isSafeInteger(seed) || seed < 0 || !scopeId) {
      this.error.set('Choose a scope and a nonnegative whole-number seed.');
      return;
    }

    const previous = this.command();

    if (
      previous
      && (
        previous.type !== type
        || previous.seed !== seed
        || previous.scopeId !== scopeId
      )
    ) {
      this.error.set(
        'A retry must use the original scenario, seed, and scope.',
      );
      return;
    }

    const command = previous ?? {
      type,
      seed,
      scopeId,
      requestId: requestId(),
    };

    this.command.set(command);
    this.save();
    this.busy.set(true);
    this.error.set('');
    this.notice.set('');
    this.canAbandon.set(false);
    const revision = this.session.revision, operation = ++this.operation;

    try {
      const run = await this.api.startScenario(command.type, {
        requestId: command.requestId,
        seed: command.seed,
        scopeId: command.scopeId,
      });

      if (!this.current(revision,operation)) return;
      this.run.set(run);
      this.notice.set(
        'The server accepted the command. Use the returned schedule below.',
      );
      this.save();
    } catch (error) {
      if (!this.current(revision,operation)) return;
      this.error.set(scenarioError(error));

      // A 400 or 409 is a definite rejection. A network failure or 503
      // might have succeeded, so the same command remains the safe retry.
      this.canAbandon.set(
        error instanceof ApiFailure
        && (error.status === 400 || error.status === 409),
      );
      this.save();
    } finally {
      if (this.current(revision,operation)) this.busy.set(false);
    }
  }

  async refresh(): Promise<void> {
    if (this.session.phase() !== 'authenticated') return;
    const current = this.run();

    if (
      !current
      || this.refreshing()
      || this.busy()
      || ['COMPLETED', 'STOPPED', 'FAILED'].includes(current.status)
    ) return;

    this.refreshing.set(true);
    const revision = this.session.revision, operation = this.operation;

    try {
      const latest = await this.api.getScenarioRun(current.runId);

      // A stop may have begun while this GET was in flight.
      if (this.current(revision,operation) && !this.busy() && this.run()?.runId === current.runId) {
        this.run.set(latest);
        this.save();
        this.error.set('');
      }
    } catch (error) {
      if (this.current(revision,operation)) this.error.set(scenarioError(error));
    } finally {
      if (this.current(revision,operation)) this.refreshing.set(false);
    }
  }

  async stop(): Promise<void> {
    if (this.session.phase() !== 'authenticated') return;
    const current = this.run();

    if (
      !current
      || this.busy()
      || !['SCHEDULED', 'RUNNING'].includes(current.status)
    ) return;

    this.busy.set(true);
    this.error.set('');
    const revision = this.session.revision, operation = ++this.operation;
    this.refreshing.set(false);

    try {
      const stopped = await this.api.stopScenario(current.runId);
      if (!this.current(revision,operation)) return;
      this.run.set(stopped);
      this.notice.set(
        'Telemetry was stopped. This does not demonstrate healthy recovery.',
      );
      this.save();
    } catch (error) {
      if (this.current(revision,operation)) this.error.set(scenarioError(error));
    } finally {
      if (this.current(revision,operation)) this.busy.set(false);
    }
  }

  newCommand(): void {
    if (this.busy()) return;

    const current = this.run();
    const terminal = current
      && ['COMPLETED', 'STOPPED', 'FAILED'].includes(current.status);

    if (!terminal && !this.canAbandon()) return;
    ++this.operation;
    this.refreshing.set(false);

    this.command.set(null);
    this.run.set(null);
    this.error.set('');
    this.notice.set('');
    this.canAbandon.set(false);
    this.save();
  }

  private save(): void {
    if (!this.session.actor()) return;
    try {
      sessionStorage.setItem(
        this.storageKey,
        JSON.stringify({
          command: this.command(),
          run: this.run(),
          canAbandon: this.canAbandon(),
        } satisfies SavedState),
      );
    } catch {
      // An open tab still retains the signals if storage is unavailable.
    }
  }
}
