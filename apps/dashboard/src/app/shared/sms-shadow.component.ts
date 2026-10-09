import { Component, DestroyRef, effect, inject, input, signal } from '@angular/core';
import { DatePipe } from '@angular/common';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { TelecomClient } from '../core/api/telecom-client';
import type { components } from '../core/api/schema';
import { SessionStore } from '../features/login-and-session/session.store';
import { ApiFailure } from '../core/api/api-errors';

@Component({
  selector: 'app-sms-shadow', imports: [DatePipe],
  template: `<details class="evidence-disclosure" (toggle)="open.set($any($event.target).open)"><summary>SMS classifier shadow evidence</summary>
    <p>Shadow classifier scores are uncalibrated and do not change the incident verdict.</p>
    @if (error()) { <p role="status">{{ error() }}</p> }
    <div class="chart-scroll" tabindex="0" aria-label="SMS shadow evidence" [attr.aria-busy]="loading()">
      <table class="kpi-table"><thead><tr><th>UTC window</th><th>ML status</th><th>Score</th><th>Cutoff</th><th>Fault class</th><th>Model</th></tr></thead><tbody>
        @for (item of result()?.items ?? []; track item.evidenceId) {
          <tr [attr.data-shadow-id]="item.evidenceId" [title]="'Window ' + item.windowId + ' · ' + item.topologyVersion + ' · Completed ' + item.completedAt"><th scope="row">{{ item.windowStart | date:'dd MMM HH:mm':'UTC' }}</th><td>{{ item.mlStatus }}</td><td>{{ item.classifierScore ?? 'Unavailable' }}</td><td>{{ item.threshold }}</td><td>{{ item.detection === null ? 'Unavailable' : item.detection ? 'Yes' : 'No' }}</td><td>{{ item.modelVersion ?? 'Unavailable' }}</td></tr>
        } @empty { <tr><td colspan="6">{{ loading() ? 'Loading shadow evidence…' : error() ? 'Unavailable' : 'No SMS shadow evidence in this range.' }}</td></tr> }
      </tbody></table>
    </div>
    <nav class="pagination" aria-label="SMS shadow pages"><button type="button" (click)="page.set(page() - 1)" [disabled]="loading() || page() === 0">Previous</button><span>Page {{ page() + 1 }} · {{ result()?.total ?? 0 }} total</span><button type="button" (click)="page.set(page() + 1)" [disabled]="loading() || (page() + 1) * 20 >= (result()?.total ?? 0)">Next</button></nav>
  </details>`,
})
export class SmsShadowComponent {
  readonly scopeId = input(''); readonly incidentId = input('');
  readonly from = input(''); readonly to = input('');
  readonly revision = input('');
  readonly open = signal(false); readonly page = signal(0);
  readonly loading = signal(false); readonly error = signal('');
  readonly result = signal<components['schemas']['MlShadowPage'] | null>(null);
  private readonly api = inject(TelecomClient);
  private stopped = false;
  private controller?: AbortController;
  constructor() {
    const destroy = inject(DestroyRef);
    const stop = () => { this.stopped = true; this.controller?.abort(); this.result.set(null); };
    destroy.onDestroy(stop);
    inject(SessionStore).ended$.pipe(takeUntilDestroyed(destroy)).subscribe(stop);
    effect(() => { this.scopeId(); this.incidentId(); this.from(); this.to(); this.page.set(0); this.result.set(null); });
    effect(cleanup => {
      const open = this.open(), scope = this.scopeId(), incident = this.incidentId(), from = this.from(), to = this.to(), page = this.page();
      this.revision();
      const controller = this.controller = new AbortController();
      cleanup(() => controller.abort());
      if (!open || this.stopped) return;
      this.loading.set(true); this.error.set('');
      void (incident ? this.api.getIncidentSmsShadow(incident, page, controller.signal)
        : this.api.getSmsShadow(scope, { from, to, page, size: 20 }, controller.signal)).then(result => {
        if (controller.signal.aborted || this.stopped) return;
        if (result.page !== page || result.items.length > 20 || !Number.isInteger(result.total)
          || result.total < result.items.length || result.items.some(item => item.scopeId !== scope)) throw new Error('Unexpected SMS shadow page.');
        this.result.set(result);
      }).catch(error => {
        if (!controller.signal.aborted && !this.stopped) {
          this.result.set(null);
          this.error.set(error instanceof ApiFailure && error.status === 404
            ? 'SMS classifier evidence is unavailable for this scope.'
            : error instanceof Error ? error.message : 'SMS shadow evidence unavailable.');
        }
      }).finally(() => { if (!controller.signal.aborted && !this.stopped) this.loading.set(false); });
    });
  }
}
