import { Component, input, output } from '@angular/core';

@Component({
  selector: 'app-status-banner',
  template: `
    <section class="notice connection-banner" aria-label="Application connection">
      <div role="status" aria-live="polite" aria-atomic="true">
        <strong>Application connection</strong>
        <p>{{ message() }}</p>
        @if (!compact()) { <p>This describes the dashboard connection. Use the recorded evidence to assess telecom service health.</p> }
      </div>
      <button type="button" title="Refreshing evidence retries the API read. Live updates reconnect automatically." [disabled]="busy()" (click)="retry.emit()">
        Refresh evidence
      </button>
      @if (!compact()) { <p class="helper">Refreshing evidence retries the API read. Live updates reconnect automatically.</p> }
    </section>
  `,
})
export class StatusBannerComponent {
  readonly compact = input(false);
  readonly message = input.required<string>();
  readonly busy = input(false);
  readonly retry = output<void>();
}
