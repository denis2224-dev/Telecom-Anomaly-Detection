import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting, HttpTestingController } from '@angular/common/http/testing';
import { IncidentStream } from './incident-stream';
import { SessionStore } from '../../features/login-and-session/session.store';

class FakeSource extends EventTarget {
  static latest: FakeSource;
  onopen?: () => void;
  onerror?: () => void;
  close = vi.fn();
  constructor(readonly url: string) { super(); FakeSource.latest = this; }
}

describe('Incident stream session and reconciliation', () => {
  let session: SessionStore;
  let http: HttpTestingController;
  beforeEach(() => {
    vi.stubGlobal('EventSource', FakeSource);
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] });
    session = TestBed.inject(SessionStore);
    session.phase.set('authenticated');
    http = TestBed.inject(HttpTestingController);
  });
  afterEach(() => { http.verify(); TestBed.resetTestingModule(); vi.unstubAllGlobals(); });

  it('refreshes each registered connection and equal-version comment hints', () => {
    const refresh = vi.fn();
    const close = TestBed.inject(IncidentStream).connect(refresh, vi.fn());
    const source = FakeSource.latest;
    source.dispatchEvent(new Event('ready'));
    source.dispatchEvent(new MessageEvent('incident-upsert', { data: JSON.stringify({ id: 'a', version: 7 }) }));
    source.dispatchEvent(new MessageEvent('incident-upsert', { data: JSON.stringify({ id: 'a', version: 7 }) }));
    source.dispatchEvent(new Event('ready'));
    expect(refresh).toHaveBeenCalledTimes(4);
    close();
    source.dispatchEvent(new Event('ready'));
    expect(refresh).toHaveBeenCalledTimes(4);
    expect(source.close).toHaveBeenCalledTimes(1);
  });

  it('closes when the session expires and ignores malformed hints', () => {
    const refresh = vi.fn();
    TestBed.inject(IncidentStream).connect(refresh, vi.fn());
    const source = FakeSource.latest;
    source.dispatchEvent(new MessageEvent('incident-upsert', { data: 'invalid' }));
    expect(refresh).not.toHaveBeenCalled();
    session.expire();
    expect(source.close).toHaveBeenCalledTimes(1);
    source.dispatchEvent(new Event('ready'));
    expect(refresh).not.toHaveBeenCalled();
  });

  it('checks transport errors once and stops unauthorized reconnection', async () => {
    const interrupted = vi.fn();
    TestBed.inject(IncidentStream).connect(vi.fn(), interrupted);
    const source = FakeSource.latest;
    source.onerror?.(); source.onerror?.();
    http.expectOne('/api/auth/me').flush({}, { status: 401, statusText: 'Unauthorized' });
    await vi.waitFor(() => expect(session.phase()).toBe('expired'));
    expect(source.close).toHaveBeenCalledTimes(1);
  });
});
