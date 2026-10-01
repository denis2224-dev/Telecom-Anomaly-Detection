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
import { RunStore } from './run.store';

@Component({
  selector: 'app-scenario-runner',
  imports: [DatePipe],
  styles: [`
    .scenario-progress { margin: 22px 0; }
    .scenario-progress p { margin: 0 0 10px; font-weight: 700; }
    .progress-segments { display: grid; grid-template-columns: repeat(8, minmax(0, 1fr)); gap: 6px; }
    .progress-segments span { height: 14px; border-radius: 3px; background: var(--telecom-secondary-background); border: 1px solid var(--telecom-border); }
    .progress-segments .complete { background: var(--telecom-primary); border-color: var(--telecom-primary); }
    .progress-segments .current { background: #b9781b; border-color: #9c6111; }
  `],
  template: `
    <h1>Scenario runner</h1>

    @if (!privileged()) {
      <p role="alert">
        A supervisor or admin account is required to run scenarios.
      </p>
    } @else {
      <section class="detail-panel">
        <h2>Schedule a scenario</h2>
        <p>
          The server chooses the next full-minute start and the fixed
          eight-minute duration. The browser sends only a request ID,
          seed, and scope.
        </p>

        @if (scopeError()) {
          <p role="alert">{{ scopeError() }}</p>
          <button type="button" (click)="loadScopes()">
            Retry scopes
          </button>
        }

        <label for="scenario-type">Scenario</label>
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

        <label for="scenario-scope">Service scope</label>
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

        <label for="scenario-seed">Seed</label>
        <input
          id="scenario-seed"
          type="number"
          min="0"
          step="1"
          [value]="seed()"
          [disabled]="!!store.command()"
          (input)="editSeed($event)"
        />

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
            [disabled]="store.busy() || !canStart()"
            (click)="start()"
          >
            {{ store.command() ? 'Retry same command' : 'Start scenario' }}
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
        @if (store.run(); as progressRun) {
          @if (progressRun.status === 'SCHEDULED' || progressRun.status === 'RUNNING' || progressRun.status === 'COMPLETED') {
            <div class="scenario-progress">
              <p>
                @if (progressRun.status === 'SCHEDULED') {
                  Awaiting scheduled start · 0 of 8 minutes
                } @else if (progressRun.status === 'COMPLETED') {
                  Schedule completed · 8 of 8 minutes
                } @else {
                  Current scheduled minute {{ currentMinute() }} of 8
                }
              </p>
              <div class="progress-segments" role="progressbar" aria-label="Scheduled scenario progress" [attr.aria-valuenow]="progressMinute()" aria-valuemin="0" aria-valuemax="8" [attr.aria-valuetext]="progressMinute() + ' of 8 scheduled minutes'">
                @for (minute of minutes; track minute) {
                  <span aria-hidden="true" [class.complete]="minute < progressMinute() || progressRun.status === 'COMPLETED'" [class.current]="progressRun.status === 'RUNNING' && minute === progressMinute()"></span>
                }
              </div>
            </div>
          }
        }
        <ol>
          @for (minute of minutes; track minute) {
            <li>
              Minute {{ minute }}: {{ stage(minute) }}
              @if (
                store.run()?.status === 'RUNNING'
                && currentMinute() === minute
              ) {
                <strong>— current scheduled minute</strong>
              }
            </li>
          }
        </ol>
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
              (click)="store.stop()"
            >
              Stop telemetry
            </button>
            <button
              type="button"
              [disabled]="store.refreshing()"
              (click)="store.refresh()"
            >
              Refresh status
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

  private readonly api = inject(TelecomClient);
  private readonly session = inject(SessionStore);
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

  readonly progressMinute = computed(() => {
    const status = this.store.run()?.status;
    if (status === 'COMPLETED') return 8;
    if (status === 'RUNNING') return this.currentMinute();
    return 0;
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

    this.timer = setInterval(() => {
      this.now.set(Date.now());
      void this.store.refresh();
    }, 5000);

    effect(() => {
      if (this.session.phase() !== 'authenticated') {
        clearInterval(this.timer);
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

  start(): void {
    const saved = this.store.command();

    void this.store.start(
      saved?.type ?? this.type(),
      saved?.seed ?? this.seed(),
      saved?.scopeId ?? this.scopeId(),
    );
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
  }
}
