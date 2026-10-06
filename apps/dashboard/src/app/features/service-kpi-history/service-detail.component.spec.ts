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
  it('rolls a full 24-hour live range without exceeding the 1440-window bound', async () => {
    const end = Date.parse(service.latestWindow!.windowEnd);
    const rows = Array.from({ length: 1440 }, (_, index) => ({ ...voiceWindows[0], windowId: `minute-${index}`,
      windowStart: new Date(end - (1440 - index) * 60_000).toISOString(),
      windowEnd: new Date(end - (1439 - index) * 60_000).toISOString() }));
    api.getServiceKpis.mockImplementation(async (_id, query) => {
      const items = rows.filter(row => Date.parse(row.windowStart) >= Date.parse(query.from) && Date.parse(row.windowStart) < Date.parse(query.to));
      return { items: items.slice(query.page * 100, (query.page + 1) * 100), total: items.length };
    });
    const fixture = TestBed.createComponent(ServiceDetailComponent); await fixture.whenStable();
    fixture.componentInstance.applyRange({ from: rows[0].windowStart, to: fixture.componentInstance.to() });
    await fixture.whenStable();
    expect(fixture.componentInstance.windows()).toHaveLength(1440);
    const next = { ...rows.at(-1)!, windowId: 'minute-1440', windowStart: new Date(end).toISOString(), windowEnd: new Date(end + 60_000).toISOString() };
    rows.push(next);
    api.listServices.mockResolvedValue([{ ...service, latestWindow: next }]);
    await fixture.componentInstance.refreshIncidents();
    expect(fixture.componentInstance.streamError()).toBe('');
    expect(fixture.componentInstance.windows()).toHaveLength(1440);
    expect(fixture.componentInstance.windows()[0].windowId).toBe('minute-1');
    expect(fixture.componentInstance.windows().at(-1)!.windowId).toBe('minute-1440');
  });
  it('keeps an explicitly historical range fixed when new current evidence arrives', async () => {
    const fixture = TestBed.createComponent(ServiceDetailComponent); await fixture.whenStable();
    const range = { from: voiceWindows[0].windowStart, to: voiceWindows[4].windowEnd };
    fixture.componentInstance.applyRange(range); await fixture.whenStable();
    api.getServiceKpis.mockClear();
    api.listServices.mockResolvedValue([{ ...service, latestWindow: { ...service.latestWindow,
      windowEnd: new Date(Date.parse(service.latestWindow!.windowEnd) + 60_000).toISOString() } }]);
    await fixture.componentInstance.refreshIncidents();
    expect(api.getServiceKpis).not.toHaveBeenCalled();
    expect(fixture.componentInstance.from()).toBe(range.from);
    expect(fixture.componentInstance.to()).toBe(range.to);
  });
});
