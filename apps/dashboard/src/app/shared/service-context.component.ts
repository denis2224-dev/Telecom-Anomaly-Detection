import { Component, inject, input } from '@angular/core';
import { Router } from '@angular/router';
import type { Service } from '../features/service-overview/dashboard-geography';

@Component({
  selector: 'app-service-context',
  template: `<label>Service
    <select aria-label="Service" [value]="current()" (change)="choose($any($event.target).value)">
      <option value="VOLTE">VoLTE setup</option><option value="SMS">SMS delivery</option>
    </select>
  </label>`,
  styles: [`
    :host { display: inline-block; }
    label { display: grid; gap: 3px; font-size: 10px; color: var(--text-muted); }
    select { padding: 7px 10px; border-radius: var(--radius-control); background: var(--field); color: var(--text); font-size: 12px; }
  `],
})
export class ServiceContextComponent {
  private readonly router = inject(Router);
  readonly current = input.required<Service>();
  choose(value: string): void {
    if ((value === 'VOLTE' || value === 'SMS') && value !== this.current()) {
      void this.router.navigate(['/dashboard'], { queryParams: { service: value } });
    }
  }
}
