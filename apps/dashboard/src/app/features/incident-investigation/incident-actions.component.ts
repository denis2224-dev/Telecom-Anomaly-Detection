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
import { requestId } from '../../core/api/request-id';

import { IconComponent } from '../../shared/icon.component';
import { ToastService } from '../../shared/toast.service';

type Analyst = components['schemas']['AnalystSummary'];

@Component({
  selector: 'app-incident-actions',
  imports: [IconComponent],
  template: `
    <section class="detail-panel workflow-card" aria-labelledby="incident-actions-title">
      <h2 id="incident-actions-title">Assignment and workflow</h2>
      <div class="assignee"><span class="avatar">{{ assigneeName().slice(0, 2).toUpperCase() }}</span><p>Assigned to<strong>{{ assigneeName() }}</strong></p><span class="badge" [attr.data-state]="incident().status">{{ incident().status }}</span></div>
      @if (dataSource.fixture) { <p class="muted">Actions are unavailable in the fixture preview.</p> } @else {
        @if (directoryError()) { <p role="alert">{{ directoryError() }}</p><button type="button" (click)="loadAnalysts()">Retry analyst directory</button> }
        @if (canClaim()) { <button class="primary" type="button" [disabled]="busy()" [attr.aria-busy]="busy()" (click)="claim()"><app-icon name="user" />Claim for myself</button> }
        @if (privileged() && incident().status !== 'RESOLVED') {
          <div class="assignment-picker"><div class="field"><label for="assignee-choice">Assign to enabled analyst</label><select id="assignee-choice" [value]="targetId()" [disabled]="busy() || !analysts().length" (change)="selectTarget($event)"><option value="">Choose an analyst</option>@for (analyst of analysts(); track analyst.id) { <option [value]="analyst.id">{{ analyst.displayName }}</option> }</select></div><button type="button" [disabled]="busy() || !targetId() || targetId() === incident().assigneeId" (click)="assign(targetId())"><app-icon name="user" />{{ incident().assigneeId ? 'Reassign' : 'Assign' }}</button></div>
        }
        @if (!incident().assigneeId) { <p class="notice" id="assignment-help"><app-icon name="info" />Assign the incident before starting the investigation or adding a comment.</p> }
        @if (incident().status === 'OPEN') { <button type="button" [disabled]="busy() || !canChangeStatus()" (click)="changeStatus('INVESTIGATING')"><app-icon name="play" />Start investigation</button> }
        @if (incident().status === 'INVESTIGATING') {
          <p class="helper">Resolution requires technical recovery and a nonblank investigation note. Current technical state: {{ incident().technicalState }}.</p>
          @if (canChangeStatus()) { <div class="field"><label for="resolution-note">Resolution note</label><textarea id="resolution-note" rows="4" maxlength="2000" [value]="resolutionNote()" (input)="editNote($event)"></textarea></div><button type="button" [disabled]="busy() || incident().technicalState !== 'RECOVERED' || !resolutionNote().trim()" (click)="changeStatus('RESOLVED')"><app-icon name="check" />Resolve incident</button> }
        }
        @if (incident().status === 'RESOLVED') { <p class="notice">Resolved. No further assignment or workflow action is available.</p> }
        <div class="comment-composer" [class.is-muted]="!incident().assigneeId || !canComment()">
          <div class="field"><label for="investigation-comment">Investigation comment</label><textarea id="investigation-comment" rows="4" maxlength="2000" placeholder="Add findings, context, or your next step…" [value]="commentText()" [disabled]="busy() || !incident().assigneeId || !canComment()" [attr.aria-describedby]="!incident().assigneeId ? 'assignment-help' : null" (input)="editComment($event)"></textarea></div>
          <button type="button" [disabled]="busy() || !incident().assigneeId || !canComment() || !commentText().trim()" [attr.aria-busy]="busy()" (click)="addComment()"><app-icon name="message" />Add comment</button>
          @if (commentNotice()) { <p role="status">{{ commentNotice() }}</p> }
        </div>
        @if (message()) { <p role="alert">{{ message() }}</p><button type="button" [disabled]="busy()" (click)="reload()"><app-icon name="refresh" />Reload incident</button> }
      }
    </section>
  `,
})
export class IncidentActionsComponent implements OnInit {
  private readonly toast = inject(ToastService);
  private readonly api = inject(TelecomClient);
  private readonly session = inject(SessionStore);

  readonly dataSource = dataSource;
  readonly incident = input.required<Incident>();
  readonly updated = output<Incident>();

  readonly analysts = signal<Analyst[]>([]);
  readonly directoryError = signal('');
  readonly targetId = signal('');
  readonly resolutionNote = signal('');
  readonly commentText = signal('');
  readonly commentNotice = signal('');
  private pendingComment?: { id: string; text: string; requestId: string };
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

  readonly canComment = computed(() =>
    this.privileged() || this.canChangeStatus(),
  );

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

  editComment(event: Event): void {
    const next = (event.target as HTMLTextAreaElement).value;
    if (next !== this.commentText()) this.pendingComment = undefined;
    this.commentText.set(next);
    this.commentNotice.set('');
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
      this.toast.show(incidentActionMessage(error), 'error');
    } finally {
      this.busy.set(false);
    }
  }

  async addComment(): Promise<void> {
    const item = this.incident();
    const text = this.commentText().trim();
    if (dataSource.fixture || this.busy() || !this.canComment()
      || !text || text.length > 2000) return;
    if (this.pendingComment?.id !== item.id || this.pendingComment.text !== text) {
      this.pendingComment = { id: item.id, text, requestId: requestId() };
    }
    this.busy.set(true);
    this.message.set('');
    this.commentNotice.set('');

    try {
      const updated = await this.api.commentIncident(item.id, {
        text,
        version: item.version,
        requestId: this.pendingComment.requestId,
      });
      this.updated.emit(updated);
      this.commentText.set('');
      this.pendingComment = undefined;
      this.commentNotice.set('Comment saved.');
      this.toast.show('Comment added to the investigation.');
    } catch (error) {
      this.message.set(incidentActionMessage(error));
      this.toast.show(incidentActionMessage(error), 'error');
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
      this.toast.show('Incident updated successfully.');
    } catch (error) {
      this.message.set(incidentActionMessage(error));
      this.toast.show(incidentActionMessage(error), 'error');
    } finally {
      this.busy.set(false);
    }
  }
}
