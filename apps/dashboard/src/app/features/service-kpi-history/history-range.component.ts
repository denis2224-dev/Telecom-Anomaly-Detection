import { Component, input, output, signal } from '@angular/core';
import { IconComponent } from '../../shared/icon.component';

export type HistoryRange = { from: string; to: string };

@Component({
  selector: 'app-history-range',
  imports: [IconComponent],
  template: `
    <section class="range-toolbar" [class.compact-range]="compact()" aria-label="History time range">
      <details class="range-options" [open]="!compact()"><summary>Range options</summary><div class="range-toolbar-heading"><span><app-icon name="clock" />Observation range</span>
        <div class="segmented" aria-label="Quick time ranges">
          @for (range of ranges; track range.minutes) {
            <button type="button" [class.selected]="duration() === range.minutes" [attr.aria-pressed]="duration() === range.minutes" (click)="quickRange(range.minutes)">{{ range.label }}</button>
          }
        </div>
        <span class="helper range-hint" title="A request covers at most 24 hours. The end instant is excluded."><app-icon name="info" />Up to 24 hours · end excluded</span>
      </div>
      </details>
      <form class="time-filter" (submit)="apply($event, start.value, end.value)">
        <label class="field">From (UTC)<input #start type="datetime-local" [value]="draftFrom() ?? from().slice(0, 16)" (input)="draftFrom.set(start.value)" required /></label>
        <label class="field">To (UTC, exclusive)<input #end type="datetime-local" [value]="draftTo() ?? to().slice(0, 16)" (input)="draftTo.set(end.value)" required /></label>
        <div class="button-row"><button type="submit" class="primary"><app-icon name="check" />Apply time range</button>
        <button type="button" (click)="refresh.emit()" aria-label="Refresh selected range" title="Refresh selected range"><app-icon name="refresh" /><span>Refresh</span></button>
        <button type="button" class="ghost" (click)="resetDrafts(); latest.emit()"><app-icon name="clock" />Latest hour</button></div>
      </form>
      @if (error()) { <p role="alert">{{ error() }}</p> }
    </section>
  `,
})
export class HistoryRangeComponent {
  readonly compact = input(false);
  readonly from = input.required<string>();
  readonly to = input.required<string>();
  readonly changed = output<HistoryRange>();
  readonly refresh = output<void>();
  readonly latest = output<void>();
  readonly error = signal('');
  readonly draftFrom = signal<string | null>(null);
  readonly draftTo = signal<string | null>(null);
  resetDrafts(): void { this.draftFrom.set(null); this.draftTo.set(null); }
  readonly ranges = [{ label: '15m', minutes: 15 }, { label: '1h', minutes: 60 }, { label: '6h', minutes: 360 }, { label: '24h', minutes: 1440 }];
  duration(): number { return (Date.parse(this.to()) - Date.parse(this.from())) / 60_000; }
  quickRange(minutes: number): void {
    const end = Date.parse(this.to());
    if (!Number.isFinite(end)) return;
    this.error.set('');
    this.resetDrafts();
    this.changed.emit({ from: new Date(end - minutes * 60_000).toISOString(), to: this.to() });
  }
  apply(event: Event, start: string, end: string): void {
    event.preventDefault();
    const from = Date.parse(start + 'Z');
    const to = Date.parse(end + 'Z');
    if (!Number.isFinite(from) || !Number.isFinite(to) || to <= from || to - from > 86_400_000) {
      this.error.set('Choose an end after the start, with a range of at most 24 hours.');
      return;
    }
    this.error.set('');
    this.resetDrafts();
    this.changed.emit({ from: new Date(from).toISOString(), to: new Date(to).toISOString() });
  }
}
