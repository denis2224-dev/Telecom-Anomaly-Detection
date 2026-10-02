import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { SessionStore } from '../../features/login-and-session/session.store';
import { voiceIncidents } from '../../../fixtures/voice';
import { IncidentStream, mergeIncidentPage } from './incident-stream';

describe('Bounded incident stream', () => {
  class FakeSource {
    static latest: FakeSource;
    onopen: ((event: Event) => void) | null = null;
    onerror: ((event: Event) => void) | null = null;
    addEventListener = vi.fn();
    close = vi.fn();
    constructor() { FakeSource.latest = this; }
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
});
