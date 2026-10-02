import { Component, input } from '@angular/core';

const paths: Record<string, string> = {
  grid: 'M3 3h7v7H3z M14 3h7v7h-7z M3 14h7v7H3z M14 14h7v7h-7z',
  signal: 'M4 18v3 M9 13v8 M14 8v13 M19 3v18',
  activity: 'M2 12h4l3-8 5 16 3-8h5',
  play: 'm8 5 11 7-11 7Z',
  refresh: 'M20 7v5h-5 M4 17v-5h5 M6 7a7 7 0 0 1 11-2l3 3 M4 16l3 3a7 7 0 0 0 11-2',
  clock: 'M12 8v4l3 2 M21 12a9 9 0 1 1-18 0 9 9 0 0 1 18 0',
  left: 'm14 6-6 6 6 6',
  right: 'm10 6 6 6-6 6',
  check: 'm5 12 4 4L19 6',
  user: 'M16 7a4 4 0 1 1-8 0 4 4 0 0 1 8 0 M4 21v-2a6 6 0 0 1 6-6h4a6 6 0 0 1 6 6v2',
  message: 'M21 11a8 8 0 0 1-8 8H7l-4 3V5a2 2 0 0 1 2-2h8a8 8 0 0 1 8 8Z M7 8h9 M7 12h6',
  calendar: 'M4 5h16v16H4z M4 10h16 M8 3v4 M16 3v4',
  alert: 'm12 3 10 18H2Z M12 9v5 M12 17v.1',
  menu: 'M4 6h16 M4 12h16 M4 18h16',
  close: 'm6 6 12 12 M18 6 6 18',
  logout: 'M9 4H4v16h5 M10 12h11 M17 8l4 4-4 4',
  filter: 'M4 7h16 M7 12h10 M10 17h4',
  shield: 'm12 3 8 3v6c0 5-8 9-8 9s-8-4-8-9V6Z m-4 9 3 3 5-6',
  trend: 'm3 17 6-6 4 4 8-10 M15 5h6v6',
  info: 'M12 11v6 M12 7v.1 M21 12a9 9 0 1 1-18 0 9 9 0 0 1 18 0',
  bolt: 'm13 2-9 12h7l-1 8 10-13h-7Z',
};

@Component({
  selector: 'app-icon',
  host: { 'aria-hidden': 'true', class: 'icon' },
  template: `<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round"><path [attr.d]="paths[name()] || paths['activity']" /></svg>`,
})
export class IconComponent {
  readonly name = input('activity');
  readonly paths = paths;
}
