import { TestBed } from '@angular/core/testing';
import { ActivatedRoute, convertToParamMap, provideRouter } from '@angular/router';
import { of } from 'rxjs';
import { ServiceDetailComponent } from './service-detail.component';
import { TelecomClient } from '../../core/api/telecom-client';
import services from '../../../fixtures/services.json';

describe('Service detail without telemetry', () => {
  it('shows the empty history while still loading incident episodes', async () => {
    const service = { ...structuredClone(services[0]), freshness: 'MISSING', latestWindow: null };
    const getServiceKpis = vi.fn();
    const listIncidents = vi.fn().mockResolvedValue({ items: [], total: 0, page: 0, size: 100 });
    TestBed.configureTestingModule({
      providers: [
        provideRouter([]),
        { provide: ActivatedRoute, useValue: { paramMap: of(convertToParamMap({ scopeId: service.scope.scopeId })) } },
        { provide: TelecomClient, useValue: { listServices: async () => [service], listIncidents, getServiceKpis } },
      ],
    });

    const fixture = TestBed.createComponent(ServiceDetailComponent);
    await fixture.whenStable();

    expect(getServiceKpis).not.toHaveBeenCalled();
    expect(listIncidents).toHaveBeenCalledOnce();
    expect(fixture.nativeElement.textContent).toContain('No voice KPI history in this time range.');
    expect(fixture.nativeElement.textContent).toContain('No incident episodes overlap this time range.');
  });
});
