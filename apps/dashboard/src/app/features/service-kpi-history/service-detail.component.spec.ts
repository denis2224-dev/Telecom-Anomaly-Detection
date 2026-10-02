import { TestBed } from '@angular/core/testing';
import { ActivatedRoute, convertToParamMap, provideRouter } from '@angular/router';
import { of } from 'rxjs';
import { TelecomClient } from '../../core/api/telecom-client';
import { LiveUpdates } from '../../core/api/live-updates';
import { ServiceDetailComponent } from './service-detail.component';
import { voiceWindows, voiceIncidents } from '../../../fixtures/voice';
import services from '../../../fixtures/services.json';

describe('Service assurance REST history and refresh', () => {
  const service = { ...services[0], latestWindow: voiceWindows.at(-1), observedAt: '2026-09-15T10:10:10Z' };
  let api: { listServices: ReturnType<typeof vi.fn>; listIncidents: ReturnType<typeof vi.fn>; getServiceKpis: ReturnType<typeof vi.fn> };
  beforeEach(() => {
    api = { listServices: vi.fn().mockResolvedValue([service]), listIncidents: vi.fn().mockResolvedValue({ items: [], total: 0 }),
      getServiceKpis: vi.fn().mockImplementation(async (_id, query) => ({ items: voiceWindows.filter(row => Date.parse(row.windowStart) >= Date.parse(query.from) && Date.parse(row.windowStart) < Date.parse(query.to)), total: 0 })) };
    TestBed.configureTestingModule({ providers: [provideRouter([]), { provide: TelecomClient, useValue: api },
      { provide: ActivatedRoute, useValue: { paramMap: of(convertToParamMap({ scopeId: service.scope.scopeId })), snapshot: { queryParamMap: convertToParamMap({}) } } }] });
  });
  it('loads 48h with legal slices and refreshes only the retained recent tail', async () => {
    const fixture = TestBed.createComponent(ServiceDetailComponent);
    await fixture.whenStable();
    api.getServiceKpis.mockClear(); fixture.componentInstance.selectHours('48');
    await vi.waitFor(() => expect(fixture.componentInstance.loading()).toBe(false));
    expect(api.getServiceKpis).toHaveBeenCalledTimes(2);
    for (const [, query] of api.getServiceKpis.mock.calls) expect(Date.parse(query.to) - Date.parse(query.from)).toBe(86400000);
    api.getServiceKpis.mockClear(); TestBed.inject(LiveUpdates).refresh$.next('current');
    await vi.waitFor(() => expect(api.getServiceKpis).toHaveBeenCalledTimes(1));
    const query = api.getServiceKpis.mock.calls[0][1]; expect(Date.parse(query.to) - Date.parse(query.from)).toBe(180000);
    await fixture.whenStable(); expect(fixture.componentInstance.windows()).toHaveLength(10);
  });
  it('keeps explicit history fixed during live refresh and detaches on destruction', async () => {
    const fixture = TestBed.createComponent(ServiceDetailComponent); await fixture.whenStable();
    fixture.componentInstance.applyRange(new Event('submit'), '2026-09-15T10:00', '2026-09-15T10:10');
    await vi.waitFor(() => expect(fixture.componentInstance.loading()).toBe(false));
    api.getServiceKpis.mockClear(); await fixture.componentInstance.load(true);
    expect(api.getServiceKpis).not.toHaveBeenCalled(); expect(fixture.componentInstance.windows()).toHaveLength(10);
    fixture.destroy(); api.listServices.mockClear(); TestBed.inject(LiveUpdates).refresh$.next('reconnect');
    expect(api.listServices).not.toHaveBeenCalled();
  });
  it('retains current episode health when viewing history before that episode', async () => {
    const fixture = TestBed.createComponent(ServiceDetailComponent); await fixture.whenStable();
    api.listIncidents.mockResolvedValue({ items: [{ ...voiceIncidents[0], technicalState: 'ONGOING' }], total: 1 });
    fixture.componentInstance.applyRange(new Event('submit'), '2026-09-14T10:00', '2026-09-14T10:10');
    await vi.waitFor(() => expect(fixture.componentInstance.loading()).toBe(false));
    expect(fixture.componentInstance.incidents()).toHaveLength(0);
    expect(fixture.componentInstance.health()).toBe('DEGRADED');
    api.listIncidents.mockResolvedValue({ items: [{ ...voiceIncidents[0], technicalState: 'UNKNOWN' }], total: 1 });
    await fixture.componentInstance.load(true);
    expect(fixture.componentInstance.health()).toBe('UNKNOWN');
  });
});
