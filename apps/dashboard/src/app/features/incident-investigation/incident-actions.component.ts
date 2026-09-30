import {
  Component,
  OnInit,
  computed,
  inject,
  input,
  output,
  signal,
} from '@angular/core';
import {
  TelecomClient,
  type Incident,
} from '../../core/api/telecom-client';
import { incidentActionMessage } from '../../core/api/api-errors';
import type { components } from '../../core/api/schema';
import { dataSource } from '../../core/api/data-source';
import { SessionStore } from '../login-and-session/session.store';

type Analyst = components['schemas']['AnalystSummary'];

@Component({
  selector: 'app-incident-actions',
  template: `
    <section class="detail-panel" aria-labelledby="incident-actions-title">
      <h2 id="incident-actions-title">Assignment and workflow</h2>

      <p>
        Assigned to: {{ assigneeName() }}
        · Workflow: {{ incident().status }}
        · Technical state: {{ incident().technicalState }}
      </p>

      @if (dataSource.fixture) {
        <p class="muted">Actions are unavailable in the fixture preview.</p>
      } @else {
        @if (directoryError()) {
          <p role="alert">{{ directoryError() }}</p>
          <button type="button" (click)="loadAnalysts()">
            Retry analyst directory
          </button>
        }

        @if (canClaim()) {
          <button
            type="button"
            [disabled]="busy()"
            (click)="claim()"
          >
            Claim for myself
          </button>
        }

        @if (privileged() && incident().status !== 'RESOLVED') {
          <label for="assignee-choice">
            Assign to enabled analyst
          </label>

          <select
            id="assignee-choice"
            [value]="targetId()"
            [disabled]="busy() || !analysts().length"
            (change)="selectTarget($event)"
          >
            <option value="">Choose an analyst</option>
            @for (analyst of analysts(); track analyst.id) {
              <option [value]="analyst.id">
                {{ analyst.displayName }}
              </option>
            }
          </select>

          <button
            type="button"
            [disabled]="
              busy()
              || !targetId()
              || targetId() === incident().assigneeId
            "
            (click)="assign(targetId())"
          >
            {{ incident().assigneeId ? 'Reassign' : 'Assign' }}
          </button>
        }

        @if (incident().status === 'OPEN') {
          <p>Assign the incident before starting the investigation.</p>

          @if (canChangeStatus()) {
            <button
              type="button"
              [disabled]="busy()"
              (click)="changeStatus('INVESTIGATING')"
            >
              Start investigation
            </button>
          }
        }

        @if (incident().status === 'INVESTIGATING') {
          <p>
            Resolution requires technical recovery and a nonblank
            investigation note. Current technical state:
            {{ incident().technicalState }}.
          </p>

          @if (canChangeStatus()) {
            <label for="resolution-note">Resolution note</label>
            <textarea
              id="resolution-note"
              rows="4"
              maxlength="2000"
              [value]="resolutionNote()"
              (input)="editNote($event)"
            ></textarea>

            <button
              type="button"
              [disabled]="
                busy()
                || incident().technicalState !== 'RECOVERED'
                || !resolutionNote().trim()
              "
              (click)="changeStatus('RESOLVED')"
            >
              Resolve incident
            </button>
          }
        }

        @if (incident().status === 'RESOLVED') {
          <p>
            Resolved. No further assignment or workflow action is
            available.
          </p>
        }

        @if (message()) {
          <p role="alert">{{ message() }}</p>
          <button
            type="button"
            [disabled]="busy()"
            (click)="reload()"
          >
            Reload incident
          </button>
        }
      }
    </section>
  `,
})
export class IncidentActionsComponent implements OnInit {
  private readonly api = inject(TelecomClient);
  private readonly session = inject(SessionStore);

  readonly dataSource = dataSource;
  readonly incident = input.required<Incident>();
  readonly updated = output<Incident>();

  readonly analysts = signal<Analyst[]>([]);
  readonly directoryError = signal('');
  readonly targetId = signal('');
  readonly resolutionNote = signal('');
  readonly message = signal('');
  readonly busy = signal(false);

  readonly privileged = computed(() =>
    this.session.actor()?.roles.some(
      role => role === 'SUPERVISOR' || role === 'ADMIN',
    ) ?? false,
  );

  readonly canClaim = computed(() => {
    const id = this.session.actor()?.analystId;

    return this.incident().status !== 'RESOLVED'
      && this.incident().assigneeId === null
      && !!id
      && this.analysts().some(analyst => analyst.id === id);
  });

  readonly canChangeStatus = computed(() => {
    const assignee = this.incident().assigneeId;

    return !!assignee
      && (
        this.privileged()
        || assignee === this.session.actor()?.analystId
      );
  });

  readonly assigneeName = computed(() => {
    const id = this.incident().assigneeId;

    return id === null
      ? 'Unassigned'
      : this.analysts().find(analyst => analyst.id === id)
          ?.displayName ?? id;
  });

  ngOnInit(): void {
    if (!dataSource.fixture) void this.loadAnalysts();
  }

  async loadAnalysts(): Promise<void> {
    this.directoryError.set('');

    try {
      const analysts = await this.api.listAnalysts();
      this.analysts.set(
        analysts.filter(analyst => analyst.enabled),
      );
    } catch (error) {
      this.directoryError.set(incidentActionMessage(error));
    }
  }

  selectTarget(event: Event): void {
    this.targetId.set(
      (event.target as HTMLSelectElement).value,
    );
  }

  editNote(event: Event): void {
    this.resolutionNote.set(
      (event.target as HTMLTextAreaElement).value,
    );
  }

  claim(): void {
    const id = this.session.actor()?.analystId;
    if (id && this.canClaim()) void this.assign(id);
  }

  async assign(analystId: string): Promise<void> {
    if (
      dataSource.fixture
      || this.busy()
      || this.incident().status === 'RESOLVED'
    ) return;

    if (
      !this.analysts().some(
        analyst => analyst.id === analystId,
      )
    ) return;

    if (
      !this.privileged()
      && (
        !this.canClaim()
        || analystId !== this.session.actor()?.analystId
      )
    ) return;

    await this.submit(() =>
      this.api.assignIncident(this.incident().id, {
        analystId,
        version: this.incident().version,
      }),
    );
  }

  async changeStatus(
    status: 'INVESTIGATING' | 'RESOLVED',
  ): Promise<void> {
    const item = this.incident();

    if (
      dataSource.fixture
      || this.busy()
      || !this.canChangeStatus()
    ) return;

    if (
      status === 'INVESTIGATING'
      && item.status !== 'OPEN'
    ) return;

    if (
      status === 'RESOLVED'
      && (
        item.status !== 'INVESTIGATING'
        || item.technicalState !== 'RECOVERED'
        || !this.resolutionNote().trim()
      )
    ) return;

    await this.submit(() =>
      this.api.changeStatus(item.id, {
        status,
        version: item.version,
        ...(status === 'RESOLVED'
          ? { resolutionNote: this.resolutionNote().trim() }
          : {}),
      }),
    );

    if (status === 'RESOLVED' && !this.message()) {
      this.resolutionNote.set('');
    }
  }

  async reload(): Promise<void> {
    if (this.busy()) return;

    this.busy.set(true);

    try {
      const latest = await this.api.getIncident(
        this.incident().id,
      );
      this.updated.emit(latest);
      this.message.set(
        'Incident refreshed. Review it before making another change.',
      );
    } catch (error) {
      this.message.set(incidentActionMessage(error));
    } finally {
      this.busy.set(false);
    }
  }

  private async submit(
    write: () => Promise<Incident>,
  ): Promise<void> {
    this.busy.set(true);
    this.message.set('');

    try {
      const updated = await write();
      this.updated.emit(updated);
      this.targetId.set('');
    } catch (error) {
      this.message.set(incidentActionMessage(error));
    } finally {
      this.busy.set(false);
    }
  }
}