import { DatePipe } from '@angular/common';
import {
  Component,
  OnDestroy,
  computed,
  effect,
  inject,
  signal,
} from '@angular/core';
import {
  TelecomClient,
  type ScenarioType,
  type ServiceSummary,
} from '../../core/api/telecom-client';
import { SessionStore } from '../login-and-session/session.store';
import { IconComponent } from '../../shared/icon.component';
import { ToastService } from '../../shared/toast.service';
import { RunStore } from './run.store';

@Component({
  selector: 'app-scenario-runner',
  imports: [DatePipe, IconComponent],
  template: `
    <div class="page-heading"><div class="heading-copy"><p class="eyebrow">Controlled simulation</p><h1>Scenario runner</h1><p>Reproduce a service event and follow its recovery.</p></div><span class="badge" [attr.data-state]="store.run()?.status ?? 'IDLE'">{{ store.run()?.status ?? 'IDLE' }}</span></div>

    @if (!privileged()) {
      <p role="alert">
        A supervisor or admin account is required to run scenarios.
      </p>
    } @else {
      <section class="detail-panel">
        <h2>Schedule a scenario</h2>
        <p>
          Choose a service and repeatable traffic profile. Runs start on the next full minute and last eight minutes.
        </p>

        @if (scopeError()) {
          <p role="alert">{{ scopeError() }}</p>
          <button type="button" (click)="loadScopes()">
            Retry scopes
          </button>
        }

        <div class="scenario-form"><div class="field"><label for="scenario-type">Scenario</label>
        <select
          id="scenario-type"
          [value]="type()"
          [disabled]="!!store.command()"
          (change)="selectType($event)"
        >
          <option value="VOLTE_IMS_OVERLOAD">VoLTE IMS overload</option>
          <option value="SMS_QUEUE_DELAY">SMS queue delay</option>
          <option value="NORMAL_CONTROL">Normal control</option>
          <option value="TELEMETRY_GAP">Telemetry gap</option>
        </select>

        </div><div class="field"><label for="scenario-scope">Service scope</label>
        <select
          id="scenario-scope"
          [value]="scopeId()"
          [disabled]="!!store.command()"
          (change)="selectScope($event)"
        >
          <option value="">Choose a scope</option>
          @for (service of availableScopes(); track service.scope.scopeId) {
            <option [value]="service.scope.scopeId">
              {{ service.scope.scopeId }}
            </option>
          }
        </select>

        </div><div class="field"><label for="scenario-seed">Seed</label>
        <input
          id="scenario-seed"
          type="number"
          min="0"
          step="1"
          [value]="seed()"
          [disabled]="!!store.command()"
          (input)="editSeed($event)"
        />

        </div></div>
        @if (store.command(); as command) {
          <p>
            Saved command: <code>{{ command.requestId }}</code>
            · {{ command.type }}
            · {{ command.scopeId }}
            · seed {{ command.seed }}
          </p>
        }

        @if (!store.run()) {
          <button
            type="button"
            class="primary" [attr.aria-busy]="store.busy()" [disabled]="store.busy() || !canStart()"
            (click)="start()"
          >
            <app-icon name="play" />{{ store.command() ? 'Retry same command' : 'Start scenario' }}
          </button>
        }

        @if (store.canAbandon() && !store.run()) {
          <button type="button" (click)="store.newCommand()">
            Start a different command
          </button>
        }

        @if (store.error()) {
          <p role="alert">{{ store.error() }}</p>
        }

        @if (store.notice()) {
          <p role="status">{{ store.notice() }}</p>
        }
      </section>

      <section class="detail-panel" aria-labelledby="profile-title">
        <h2 id="profile-title">Eight-minute profile</h2>
        <p>
          Each stage lasts one minute. The labels describe planned
          synthetic traffic, not a measured health result.
        </p>
        <ol class="scenario-stepper">
          @for (minute of minutes; track minute) {
            <li [attr.data-phase]="phase(minute)" [class.is-current]="store.run()?.status === 'RUNNING' && currentMinute() === minute"><strong><span class="sr-only">Minute </span>{{ minute.toString().padStart(2, '0') }}</strong><span>{{ stage(minute) }}</span>
              @if (
                store.run()?.status === 'RUNNING'
                && currentMinute() === minute
              ) {
                <span class="sr-only">— current scheduled minute</span>
              }
            </li>
          }
        </ol>
        @if (store.run(); as run) {
          <div class="run-progress">@if (run.status !== 'STOPPED' && run.status !== 'FAILED') { <progress [value]="progress()" max="100" aria-label="Scheduled run progress"></progress> }<span class="mono">{{ countdown() }}</span></div>
        }
      </section>

      @if (store.run(); as run) {
        <section class="detail-panel" aria-labelledby="run-title">
          <h2 id="run-title">Server run</h2>

          <p>Run ID: <code>{{ run.runId }}</code></p>
          <p>Status: <strong>{{ run.status }}</strong></p>
          <p>Scenario: {{ run.scenarioType }}</p>
          <p>Scope: {{ run.scopeId }}</p>
          <p>
            Scheduled:
            {{ run.scheduledStartAt | date:'dd MMM yyyy HH:mm:ss':'UTC' }}
            –
            {{ run.scheduledEndAt | date:'dd MMM yyyy HH:mm:ss':'UTC' }}
            UTC
          </p>

          @if (run.status === 'STOPPED') {
            <p class="notice">
              Stopped telemetry. This is not evidence of healthy recovery.
            </p>
          }

          @if (run.status === 'FAILED') {
            <p class="notice">
              The run failed. Review missing or partial telemetry.
            </p>
          }

          @if (run.status === 'COMPLETED') {
            <p>
              The schedule completed. Review actual service evidence
              before interpreting recovery.
            </p>
          }

          @if (
            run.status === 'SCHEDULED'
            || run.status === 'RUNNING'
          ) {
            <button
              type="button"
              [disabled]="store.busy()"
              class="danger" (click)="store.stop()"
            >
              Stop telemetry
            </button>
            <button
              type="button"
              [disabled]="store.refreshing()"
              (click)="store.refresh()"
            >
              <app-icon name="refresh" />Refresh status
            </button>
          } @else {
            <button type="button" (click)="store.newCommand()">
              Prepare another command
            </button>
          }
        </section>
      }
    }
  `,
})
export class ScenarioRunnerComponent implements OnDestroy {
  readonly store = inject(RunStore);
  private readonly toast = inject(ToastService);

  private readonly api = inject(TelecomClient);
  private readonly session = inject(SessionStore);
  private clockTimer?: ReturnType<typeof setInterval>;
  private timer?: ReturnType<typeof setInterval>;

  readonly scopes = signal<ServiceSummary[]>([]);
  readonly scopeError = signal('');
  readonly type = signal<ScenarioType>('VOLTE_IMS_OVERLOAD');
  readonly scopeId = signal('');
  readonly seed = signal(42);
  readonly now = signal(Date.now());
  readonly minutes = [1, 2, 3, 4, 5, 6, 7, 8];

  readonly privileged = computed(() =>
    this.session.actor()?.roles.some(
      role => role === 'SUPERVISOR' || role === 'ADMIN',
    ) ?? false,
  );

  readonly availableScopes = computed(() =>
    this.scopes().filter(service => {
      if (
        this.type() === 'VOLTE_IMS_OVERLOAD'
        && service.scope.service !== 'VOLTE'
      ) return false;

      if (
        this.type() === 'SMS_QUEUE_DELAY'
        && service.scope.service !== 'SMS'
      ) return false;

      return true;
    }),
  );

  readonly canStart = computed(() => {
    if (this.store.command() && !this.store.run()) return true;

    return !!this.scopeId()
      && this.availableScopes().some(
        service => service.scope.scopeId === this.scopeId(),
      )
      && Number.isSafeInteger(this.seed())
      && this.seed() >= 0;
  });

  readonly currentMinute = computed(() => {
    const run = this.store.run();
    if (!run) return 0;

    const elapsed = this.now() - Date.parse(run.scheduledStartAt);
    if (elapsed < 0) return 0;

    return Math.min(8, Math.floor(elapsed / 60000) + 1);
  });

  constructor() {
    if (!this.privileged()) return;

    this.store.restore();

    const saved = this.store.command();
    if (saved) {
      this.type.set(saved.type);
      this.scopeId.set(saved.scopeId);
      this.seed.set(saved.seed);
    }

    void this.loadScopes();

    this.clockTimer = setInterval(() => this.now.set(Date.now()), 1000);
    this.timer = setInterval(() => {
      void this.store.refresh();
    }, 5000);

    effect(() => {
      if (this.session.phase() !== 'authenticated') {
        clearInterval(this.timer);
        clearInterval(this.clockTimer);
      }
    });
  }

  async loadScopes(): Promise<void> {
    this.scopeError.set('');

    try {
      this.scopes.set(await this.api.listServices());
    } catch {
      this.scopeError.set(
        'Service scopes could not be loaded. Try again.',
      );
    }
  }

  selectType(event: Event): void {
    this.type.set(
      (event.target as HTMLSelectElement).value as ScenarioType,
    );
    this.scopeId.set('');
  }

  selectScope(event: Event): void {
    this.scopeId.set(
      (event.target as HTMLSelectElement).value,
    );
  }

  editSeed(event: Event): void {
    this.seed.set(
      Number((event.target as HTMLInputElement).value),
    );
  }

  async start(): Promise<void> {
    const saved = this.store.command();

    await this.store.start(
      saved?.type ?? this.type(),
      saved?.seed ?? this.seed(),
      saved?.scopeId ?? this.scopeId(),
    );
    if (this.store.run()) this.toast.show('Scenario scheduled successfully.');
    else if (this.store.error()) this.toast.show(this.store.error()!, 'error');
  }

  readonly progress = computed(() => {
    const run = this.store.run();
    if (!run) return 0;
    if (run.status === 'COMPLETED') return 100;
    const end = Date.parse(run.scheduledEndAt), start = Date.parse(run.scheduledStartAt);
    const instant = this.now();
    return Math.max(0, Math.min(100, 100 * (instant - start) / (end - start)));
  });
  readonly countdown = computed(() => {
    const run = this.store.run();
    if (!run) return 'Ready to schedule';
    if (!['SCHEDULED', 'RUNNING'].includes(run.status)) return run.status.toLowerCase();
    const pending = this.now() < Date.parse(run.scheduledStartAt);
    const seconds = Math.max(0, Math.ceil((Date.parse(pending ? run.scheduledStartAt : run.scheduledEndAt) - this.now()) / 1000));
    return `${Math.floor(seconds / 60)}:${String(seconds % 60).padStart(2, '0')} ${pending ? 'until start' : 'remaining'}`;
  });
  phase(minute: number): string {
    const stage = this.stage(minute);
    return stage.includes('normal') ? 'normal' : minute <= 5 ? 'disruption' : 'recovery';
  }

  stage(minute: number): string {
    const type = this.store.run()?.scenarioType
      ?? this.store.command()?.type
      ?? this.type();

    if (type === 'NORMAL_CONTROL') return 'normal control traffic';

    if (type === 'TELEMETRY_GAP') {
      if (minute <= 2) return 'normal telemetry';
      if (minute <= 5) return 'service telemetry withheld';
      return 'service telemetry resumes';
    }

    if (minute <= 2) return 'normal traffic';
    if (minute <= 5) {
      return type === 'SMS_QUEUE_DELAY'
        ? 'slow delivery and queue backlog'
        : 'IMS overload';
    }

    return 'recovery traffic';
  }

  ngOnDestroy(): void {
    clearInterval(this.timer);
    clearInterval(this.clockTimer);
  }
}
