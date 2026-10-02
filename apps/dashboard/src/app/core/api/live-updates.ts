import { DOCUMENT } from '@angular/common';
import { Injectable, InjectionToken, OnDestroy, inject, signal } from '@angular/core';
import { Subject } from 'rxjs';

export const REFRESH_MS = 30000;
export const EVENT_SOURCE = new InjectionToken<(url: string) => EventSource>('Session EventSource', {
  providedIn: 'root', factory: () => url => new EventSource(url),
});
@Injectable({ providedIn: 'root' })
export class LiveUpdates implements OnDestroy {
  private readonly create = inject(EVENT_SOURCE);
  private readonly document = inject(DOCUMENT);
  private source?: EventSource;
  private timer?: ReturnType<typeof setInterval>;
  private retry?: ReturnType<typeof setTimeout>;
  private pending?: ReturnType<typeof setTimeout>;
  private running = false;
  readonly state = signal<'OFFLINE' | 'CONNECTING' | 'LIVE' | 'RECONNECTING' | 'FIXTURE'>('OFFLINE');
  readonly now = signal(new Date().toISOString());
  readonly refresh$ = new Subject<'current' | 'incidents' | 'reconnect'>();
  start(fixture = false) {
    if (fixture) { this.stop(); this.state.set('FIXTURE'); return; }
    if (this.running) return;
    this.running = true;
    this.connect();
    this.timer = setInterval(() => {
      this.now.set(new Date().toISOString());
      if (!this.document.hidden) this.refresh$.next('current');
    }, REFRESH_MS);
  }
  private connect() {
    if (!this.running) return;
    this.state.set('CONNECTING');
    const source = this.create('/api/incidents/stream'); this.source = source;
    source.onopen = () => {
      if (this.source !== source || !this.running) return;
      this.state.set('LIVE');
      // Includes first connection: a notification stream has no replay guarantee.
      this.refresh$.next('reconnect');
    };
    const notify = () => {
      if (this.source !== source || !this.running || this.pending) return;
      this.pending = setTimeout(() => { this.pending = undefined; if (this.running) this.refresh$.next('incidents'); }, 250);
    };
    source.onmessage = notify;
    source.addEventListener('incident', notify);
    source.addEventListener('incident.upsert', notify);
    source.onerror = () => {
      if (this.source !== source || !this.running) return;
      source.close(); this.source = undefined; this.state.set('RECONNECTING');
      this.retry = setTimeout(() => { this.retry = undefined; this.connect(); }, REFRESH_MS);
    };
  }
  stop() {
    this.running = false; this.source?.close(); this.source = undefined;
    clearInterval(this.timer); clearTimeout(this.retry); clearTimeout(this.pending);
    this.timer = undefined; this.retry = undefined; this.pending = undefined;
    this.state.set('OFFLINE');
  }
  ngOnDestroy() { this.stop(); this.refresh$.complete(); }
}
