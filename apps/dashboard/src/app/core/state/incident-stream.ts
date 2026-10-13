import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { DestroyRef, Injectable, inject } from '@angular/core';
import { firstValueFrom, Subject, takeUntil, type Subscription, timeout } from 'rxjs';
import type { Incident } from '../api/telecom-client';
import { dataSource } from '../api/data-source';
import { SessionStore } from '../../features/login-and-session/session.store';

const MAX_INCIDENTS = 10_000; // The existing loader permits 100 pages of 100.

export function mergeIncidentVersions(
  current: Incident[],
  incoming: Incident[],
): Incident[] {
  const byId = new Map<string, Incident>();

  const accept = (item: Incident) => {
    const previous = byId.get(item.id);
    if (!previous || item.version > previous.version) {
      byId.set(item.id, item);
    }
    if (byId.size > MAX_INCIDENTS) {
      throw new Error('Too many incidents to display. Narrow the time range.');
    }
  };

  for (const item of current) accept(item);
  for (const item of incoming) accept(item);

  return [...byId.values()];
}

export function mergeIncidentPage(current: Incident[], incoming: Incident[]): Incident[] {
  const merged = new Map(
    mergeIncidentVersions(current, incoming).map(item => [item.id, item]),
  );
  return [...new Set(incoming.map(item => item.id))].map(id => merged.get(id)!);
}

@Injectable({ providedIn: 'root' })
export class IncidentStream {
  private readonly http = inject(HttpClient);
  private readonly session = inject(SessionStore);
  private readonly subscribers = new Set<{ refresh: () => void; interrupted: () => void; connected: () => void }>();
  private transport?: () => void;
  private revision = -1;
  private state: 'connecting' | 'connected' | 'interrupted' = 'connecting';

  constructor() {
    const ended = this.session.ended$.subscribe(() => this.closeAll());
    inject(DestroyRef).onDestroy(() => { this.closeAll(); ended.unsubscribe(); });
  }

  connect(refresh: () => void, interrupted: () => void, connected: () => void = () => {}): () => void {
    if (dataSource.fixture || this.session.phase() !== 'authenticated') return () => {};
    if (this.revision !== this.session.revision) this.closeAll();
    this.revision = this.session.revision;
    const subscriber = { refresh, interrupted, connected };
    this.subscribers.add(subscriber);
    if (!this.transport) {
      this.transport = this.open(
        () => this.fanOut('refresh'), () => this.fanOut('interrupted'), () => this.fanOut('connected'),
      );
    }
    if (this.state === 'interrupted') interrupted();
    else if (this.state === 'connected') connected();
    return () => {
      this.subscribers.delete(subscriber);
      if (!this.subscribers.size) this.closeAll();
    };
  }

  private fanOut(kind: 'refresh' | 'interrupted' | 'connected'): void {
    if(kind !== 'refresh')this.state=kind;
    for (const subscriber of [...this.subscribers]) {
      // One failed view cannot prevent other consumers from reconciling.
      try { subscriber[kind](); } catch { /* The view owns its request errors. */ }
    }
  }

  private closeAll(): void {
    this.transport?.();
    this.transport = undefined;
    this.subscribers.clear();
    this.revision = -1;
    this.state = 'connecting';
  }

  private open(refresh: () => void, interrupted: () => void, connected: () => void): () => void {
    if (dataSource.fixture || this.session.phase() !== 'authenticated') {
      return () => {};
    }

    const revision = this.session.revision;
    let source = new EventSource('/api/incidents/stream');
    let reconnectTimer: ReturnType<typeof setTimeout> | undefined;
    let refreshTimer: ReturnType<typeof setTimeout> | undefined;
    const stopProbe = new Subject<void>();
    let closed = false;
    let checkingSession = false;
    let ended: Subscription | undefined;

    const active = () => !closed
      && this.session.phase() === 'authenticated'
      && this.session.revision === revision;

    const close = () => {
      if (closed) return;
      closed = true;
      clearTimeout(reconnectTimer);
      clearTimeout(refreshTimer);
      stopProbe.next();
      stopProbe.complete();
      source.close();
      ended?.unsubscribe();
    };

    ended = this.session.ended$.subscribe(close);

    // `open` fires on the first connection and on each healthy reconnect.
    // The stream has no durable replay, so both cases require a REST snapshot.
    const onOpen = () => {
      if (!active()) return close();
      clearTimeout(refreshTimer);
      refreshTimer = undefined;
      connected();
      refresh();
    };

    // Hints invalidate REST data; one fixed batch per second keeps sustained
    // bursts responsive without issuing a snapshot request for every event.
    const queueRefresh = () => {
      if (refreshTimer !== undefined) return;
      refreshTimer = setTimeout(() => {
        refreshTimer = undefined;
        if (active()) refresh();
      }, 1000);
    };

    const upsert = (event: Event) => {
      if (!active()) return close();
      try {
        const hint = JSON.parse((event as MessageEvent).data);
        if (typeof hint.id === 'string'
          && Number.isSafeInteger(hint.version)
          && hint.version >= 0) {
          queueRefresh();
        }
      } catch {
        // A malformed event cannot change displayed incident state.
      }
    };

    const onError = () => {
      if (!active()) return close();
      interrupted();
      // A normal server lease ending uses native retry. Only a terminal failure
      // needs a session probe; routine reconnects must not extend idle activity.
      if (source.readyState === 0 /* CONNECTING */) return;
      if (checkingSession) return;
      checkingSession = true;

      // EventSource hides the HTTP error status. Probe the existing session;
      // network failures keep native EventSource retry behavior.
      void firstValueFrom(
        this.http.get('/api/auth/me').pipe(timeout(5_000), takeUntil(stopProbe)),
      ).catch(error => {
        if (active()
          && error instanceof HttpErrorResponse
          && error.status === 401) {
          this.session.expire(); // ended$ closes the EventSource.
        }
      }).finally(() => {
        checkingSession = false;
        // HTTP 503 can permanently close EventSource instead of triggering its
        // native retry. Reopen only that terminal state after checking the session.
        if (active() && source.readyState === 2 /* CLOSED */) {
          clearTimeout(reconnectTimer);
          reconnectTimer = setTimeout(() => {
            if (!active()) return;
            source.close();
            source = new EventSource('/api/incidents/stream');
            attach();
          }, 3000);
        }
      });
    };

    const attach = () => {
      source.onopen = onOpen;
      source.onerror = onError;
      source.addEventListener('incident-upsert', upsert);
      // Registration closes the initial snapshot race; the stream has no replay.
      source.addEventListener('ready', () => { if (active()) refresh(); });
    };
    attach();

    return close;
  }
}
