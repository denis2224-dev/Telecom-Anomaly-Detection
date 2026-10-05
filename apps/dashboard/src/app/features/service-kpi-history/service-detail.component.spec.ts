import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { ActivatedRoute, convertToParamMap, provideRouter } from '@angular/router';
import { of } from 'rxjs';
import { TelecomClient } from '../../core/api/telecom-client';
import { IncidentStream } from '../../core/state/incident-stream';
import { SessionStore } from '../login-and-session/session.store';
import { ServiceDetailComponent } from './service-detail.component';
import { voiceWindows, voiceIncidents } from '../../../fixtures/voice';
import services from '../../../fixtures/services.json';

describe('Reconciled assurance history and current state', () => {
  const service = { ...services[0], latestWindow: voiceWindows.at(-1), observedAt: '2026-09-15T10:10:10Z' };
  let refresh: () => void;
  let close: ReturnType<typeof vi.fn>;
  let api: { listServices: ReturnType<typeof vi.fn>; listIncidents: ReturnType<typeof vi.fn>; getServiceKpis: ReturnType<typeof vi.fn> };
  beforeEach(() => {
    close = vi.fn();
    api = { listServices: vi.fn().mockResolvedValue([service]), listIncidents: vi.fn().mockResolvedValue({ items: [], total: 0 }),
      getServiceKpis: vi.fn().mockImplementation(async (_id, query) => {
        const items = voiceWindows.filter(row => Date.parse(row.windowStart) >= Date.parse(query.from) && Date.parse(row.windowStart) < Date.parse(query.to));
        return { items, total: items.length, observedAt: service.observedAt };
      }) };
    TestBed.configureTestingModule({ providers: [provideRouter([]), provideHttpClient(), { provide: TelecomClient, useValue: api },
      { provide: IncidentStream, useValue: { connect: (callback: () => void) => { refresh = callback; return close; } } },
      { provide: ActivatedRoute, useValue: { paramMap: of(convertToParamMap({ scopeId: service.scope.scopeId })) } }] });
    TestBed.inject(SessionStore).phase.set('authenticated');
  });
  it('preserves the 24h bound and refuses the superseded 48h read', async () => {
    const fixture = TestBed.createComponent(ServiceDetailComponent); await fixture.whenStable();
    api.getServiceKpis.mockClear();
    fixture.componentInstance.applyRange({ from: '2026-09-13T10:00:00Z', to: '2026-09-15T10:00:00Z' });
    await fixture.whenStable();
    expect(fixture.componentInstance.error()).toContain('at most 24 hours');
    expect(api.getServiceKpis).not.toHaveBeenCalled();
  });
  it('refreshes current health over SSE without refetching or changing fixed history', async () => {
    const fixture = TestBed.createComponent(ServiceDetailComponent); await fixture.whenStable();
    const from = fixture.componentInstance.from(), to = fixture.componentInstance.to();
    api.getServiceKpis.mockClear();
    api.listIncidents.mockResolvedValue({ items: [{ ...voiceIncidents[0], technicalState: 'ONGOING' }], total: 1 });
    refresh();
    await vi.waitFor(() => expect(fixture.componentInstance.health()).toBe('DEGRADED'));
    expect(api.getServiceKpis).not.toHaveBeenCalled();
    expect(fixture.componentInstance.from()).toBe(from); expect(fixture.componentInstance.to()).toBe(to);
    api.listIncidents.mockResolvedValue({ items: [{ ...voiceIncidents[0], version: 99, technicalState: 'UNKNOWN' }], total: 1 });
    await fixture.componentInstance.refreshIncidents();
    expect(fixture.componentInstance.health()).toBe('UNKNOWN');
  });
  it('rejects oversized history instead of rendering a partial success', async () => {
    api.getServiceKpis.mockResolvedValue({ items: voiceWindows, total: 1441 });
    const fixture = TestBed.createComponent(ServiceDetailComponent); await fixture.whenStable();
    expect(fixture.componentInstance.error()).toContain('limit');
    expect(fixture.componentInstance.windows()).toEqual([]);
  });
  it('aborts an old range immediately and discards its delayed response', async () => {
    const fixture = TestBed.createComponent(ServiceDetailComponent); await fixture.whenStable();
    let release!: (value: unknown) => void;
    api.getServiceKpis.mockImplementationOnce(() => new Promise(resolve => { release = resolve; }));
    fixture.componentInstance.applyRange({ from: '2026-09-15T10:00:00Z', to: '2026-09-15T10:05:00Z' });
    await vi.waitFor(() => expect(release).toBeDefined());
    const signal = api.getServiceKpis.mock.calls.at(-1)![2] as AbortSignal;
    fixture.componentInstance.applyRange({ from: '2026-09-15T10:05:00Z', to: '2026-09-15T10:10:00Z' });
    expect(signal.aborted).toBe(true);
    release({ items: voiceWindows.slice(0, 5), total: 5 });
    await vi.waitFor(() => expect(fixture.componentInstance.windows().map(item => item.windowStart))
      .toEqual(voiceWindows.slice(5).map(item => item.windowStart)));
  });
  it('session expiry closes its only stream and stops current refresh', async () => {
    const fixture = TestBed.createComponent(ServiceDetailComponent); await fixture.whenStable();
    TestBed.inject(SessionStore).expire();
    expect(close).toHaveBeenCalledTimes(1);
    fixture.destroy();
    expect(close).toHaveBeenCalledTimes(1);
  });
});
