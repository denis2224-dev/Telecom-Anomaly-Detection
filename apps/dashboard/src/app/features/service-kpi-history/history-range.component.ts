import { Component, input, output, signal } from '@angular/core';

export type HistoryRange = { from: string; to: string };

@Component({
  selector: 'app-history-range',
  template: `
    <form class="time-filter" (submit)="apply($event, start.value, end.value)">
      <label>From (UTC)
        <input #start type="datetime-local" [value]="from().slice(0, 16)" required />
      </label>
      <label>To (UTC, exclusive)
        <input #end type="datetime-local" [value]="to().slice(0, 16)" required />
      </label>
      <button type="submit">Apply time range</button>
      <button type="button" (click)="refresh.emit()">Refresh selected range</button>
      <button type="button" (click)="latest.emit()">Latest hour</button>
    </form>
    <p class="muted">Choose up to 24 hours. The end time is excluded.</p>
    @if (error()) { <p role="alert">{{ error() }}</p> }
  `,
})
export class HistoryRangeComponent {
  readonly from = input.required<string>();
  readonly to = input.required<string>();
  readonly changed = output<HistoryRange>();
  readonly refresh = output<void>();
  readonly latest = output<void>();
  readonly error = signal('');

  apply(event: Event, start: string, end: string): void {
    event.preventDefault();
    const from = Date.parse(start + 'Z');
    const to = Date.parse(end + 'Z');
    if (!Number.isFinite(from) || !Number.isFinite(to)
      || to <= from || to - from > 86_400_000) {
      this.error.set('Choose an end after the start, with a range of at most 24 hours.');
      return;
    }
    this.error.set('');
    this.changed.emit({
      from: new Date(from).toISOString(), to: new Date(to).toISOString(),
    });
  }
}
