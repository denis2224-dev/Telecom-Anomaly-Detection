import { DOCUMENT } from '@angular/common';
import { Injectable, computed, inject, signal } from '@angular/core';
import { IncidentStream } from './incident-stream';
import { SessionStore } from '../../features/login-and-session/session.store';
import { dataSource } from '../api/data-source';

@Injectable({ providedIn: 'root' })
export class LiveUpdates {
  private readonly document = inject(DOCUMENT);
  private readonly stream = inject(IncidentStream);
  private readonly session = inject(SessionStore);
  private stopWatching?: () => void;
  readonly monitored = signal(false);
  readonly lastUpdated = signal<number | null>(null);
  private readonly now = signal(Date.now());
  readonly stale = computed(() => this.lastUpdated() === null || this.now() - this.lastUpdated()! >= 120_000);
  visible(): boolean { return this.monitored() && !this.document.hidden; }

  updated(): void {
    if (!this.monitored() || this.session.phase() !== 'authenticated') return;
    this.now.set(Date.now());
    this.lastUpdated.set(Date.now());
  }

  watch(refresh: () => void, interrupted: () => void, connected: () => void): () => void {
    this.stopWatching?.();
    if (dataSource.fixture || this.session.phase() !== 'authenticated') return () => {};
    this.monitored.set(true);
    this.lastUpdated.set(null);
    let stopped = false, streamAvailable = false;
    let timer: ReturnType<typeof setTimeout> | undefined;
    const visibleRefresh = () => { if (!stopped && !this.document.hidden) refresh(); };
    const schedule = () => {
      clearTimeout(timer);
      if (stopped || streamAvailable || this.document.hidden) return;
      timer = setTimeout(() => { visibleRefresh(); schedule(); }, 60_000 - Date.now() % 60_000);
    };
    const visibility = () => {
      clearTimeout(timer);
      this.now.set(Date.now());
      if (!this.document.hidden) { visibleRefresh(); schedule(); }
    };
    this.document.addEventListener('visibilitychange', visibility);
    const clock = setInterval(() => this.now.set(Date.now()), 1000);
    let close = () => {};
    try {
      close = this.stream.connect(visibleRefresh,
        () => { streamAvailable = false; interrupted(); schedule(); },
        () => { streamAvailable = true; clearTimeout(timer); connected(); });
    } catch {
      interrupted(); // Browsers without EventSource use the same REST fallback.
    }
    const stop = () => {
      if (stopped) return;
      stopped = true;
      clearTimeout(timer);
      clearInterval(clock);
      this.document.removeEventListener('visibilitychange', visibility);
      close();
      ended.unsubscribe();
      this.monitored.set(false);
      if (this.stopWatching === stop) this.stopWatching = undefined;
    };
    const ended = this.session.ended$.subscribe(stop);
    this.stopWatching = stop;
    schedule();
    return stop;
  }
}
