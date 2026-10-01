import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { firstValueFrom, type Subscription, timeout } from 'rxjs';
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

@Injectable({ providedIn: 'root' })
export class IncidentStream {
  private readonly http = inject(HttpClient);
  private readonly session = inject(SessionStore);

  connect(refresh: () => void, interrupted: () => void): () => void {
    if (dataSource.fixture || this.session.phase() !== 'authenticated') {
      return () => {};
    }

    const revision = this.session.revision;
    const source = new EventSource('/api/incidents/stream');
    let closed = false;
    let checkingSession = false;
    let ended: Subscription | undefined;

    const active = () => !closed
      && this.session.phase() === 'authenticated'
      && this.session.revision === revision;

    const close = () => {
      if (closed) return;
      closed = true;
      source.close();
      ended?.unsubscribe();
    };

    ended = this.session.ended$.subscribe(close);

    // `open` fires on the first connection and on each healthy reconnect.
    // The stream has no durable replay, so both cases require a REST snapshot.
    source.onopen = () => {
      if (!active()) return close();
      refresh();
    };

    const upsert = (event: Event) => {
      if (!active()) return close();
      try {
        const hint = JSON.parse((event as MessageEvent).data);
        if (typeof hint.id === 'string'
          && Number.isSafeInteger(hint.version)
          && hint.version >= 0) {
          refresh();
        }
      } catch {
        // A malformed event cannot change displayed incident state.
      }
    };

    source.addEventListener('incident-upsert', upsert);

    source.onerror = () => {
      if (!active()) return close();
      interrupted();
      if (checkingSession) return;
      checkingSession = true;

      // EventSource hides the HTTP error status. Probe the existing session;
      // network failures keep native EventSource retry behavior.
      void firstValueFrom(
        this.http.get('/api/auth/me').pipe(timeout(5_000)),
      ).catch(error => {
        if (active()
          && error instanceof HttpErrorResponse
          && error.status === 401) {
          this.session.expire(); // ended$ closes the EventSource.
        }
      }).finally(() => { checkingSession = false; });
    };

    return close;
  }
}
