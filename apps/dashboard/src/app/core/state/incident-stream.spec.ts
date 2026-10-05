import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { SessionStore } from '../../features/login-and-session/session.store';
import { voiceIncidents } from '../../../fixtures/voice';
import { IncidentStream, mergeIncidentPage } from './incident-stream';

describe('Bounded incident stream', () => {
  class FakeSource extends EventTarget {
    static latest: FakeSource;
    onopen: ((event: Event) => void) | null = null;
    onerror: ((event: Event) => void) | null = null;
    close = vi.fn();
    constructor() { super(); FakeSource.latest = this; }
  }
  afterEach(() => {
    vi.unstubAllGlobals();
    TestBed.resetTestingModule();
  });

  it('retains newer versions only for IDs on the incoming page', () => {
    const item = voiceIncidents[0];
    const retained = { ...item, version: 8, technicalState: 'ONGOING' as const };
    const old = { ...item, version: 7, technicalState: 'RECOVERED' as const };
    const other = { ...item, id: 'other', episodeId: 'other' };
    expect(mergeIncidentPage([retained, other], [old])).toEqual([retained]);
    expect(mergeIncidentPage([retained], [other])).toEqual([other]);
  });

  it('cancels an in-flight session probe when the stream closes', async () => {
    vi.stubGlobal('EventSource', FakeSource);
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] });
    const session = TestBed.inject(SessionStore);
    session.phase.set('authenticated');
    const http = TestBed.inject(HttpTestingController);
    const close = TestBed.inject(IncidentStream).connect(vi.fn(), vi.fn());
    FakeSource.latest.onerror?.(new Event('error'));
    const request = http.expectOne('/api/auth/me');
    close();
    expect(request.cancelled).toBe(true);
    expect(FakeSource.latest.close).toHaveBeenCalledTimes(1);
    await Promise.resolve();
    expect(session.phase()).toBe('authenticated');
    http.verify();
  });

  it('refreshes after registration and every valid upsert', () => {
    vi.stubGlobal('EventSource', FakeSource);
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] });
    TestBed.inject(SessionStore).phase.set('authenticated');
    const refresh = vi.fn();
    const close = TestBed.inject(IncidentStream).connect(refresh, vi.fn());
    const source = FakeSource.latest;
    source.dispatchEvent(new Event('ready'));
    source.dispatchEvent(new MessageEvent('incident-upsert', { data: '{"id":"a","version":7}' }));
    source.dispatchEvent(new MessageEvent('incident-upsert', { data: '{"id":"a","version":7}' }));
    expect(refresh).toHaveBeenCalledTimes(3);
    close();
    source.dispatchEvent(new Event('ready'));
    expect(refresh).toHaveBeenCalledTimes(3);
  });

  it('expires an unauthorized session and closes the stream', async () => {
    vi.stubGlobal('EventSource', FakeSource);
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] });
    const session = TestBed.inject(SessionStore);
    session.phase.set('authenticated');
    const http = TestBed.inject(HttpTestingController);
    TestBed.inject(IncidentStream).connect(vi.fn(), vi.fn());
    FakeSource.latest.onerror?.(new Event('error'));
    http.expectOne('/api/auth/me').flush({}, { status: 401, statusText: 'Unauthorized' });
    await vi.waitFor(() => expect(session.phase()).toBe('expired'));
    expect(FakeSource.latest.close).toHaveBeenCalledTimes(1);
    http.verify();
  });
});
