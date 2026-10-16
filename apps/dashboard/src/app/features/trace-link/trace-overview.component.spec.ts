import { TestBed } from '@angular/core/testing';
import { signal } from '@angular/core';
import { ActivatedRoute, Router } from '@angular/router';
import { Subject } from 'rxjs';
import { TelecomClient, type TraceMetricSeries } from '../../core/api/telecom-client';
import { IncidentStream } from '../../core/state/incident-stream';
import { TraceQueryStore } from '../../core/state/trace-query.store';
import { SessionStore } from '../login-and-session/session.store';
import { TraceOverviewComponent } from './trace-overview.component';

const baseSeries: TraceMetricSeries = {
  datasetId: 'trace-link-md-30d-v1',
  domain: 'volte',
  metric: 'volte.setup.success',
  scopeType: 'CITY',
  scopeId: 'chi',
  resolution: '5m',
  requestedFrom: '2026-09-10T09:00:00Z',
  requestedTo: '2026-09-10T09:15:00Z',
  effectiveFrom: '2026-09-10T09:00:00Z',
  effectiveTo: '2026-09-10T09:15:00Z',
  partialStart: false,
  partialEnd: false,
  sourceResolutionSeconds: 300,
  unit: 'PERCENT',
  aggregation: 'RATIO_OF_SUMS',
  policyVersion: 'trace-link-kpi-v1',
  topologyVersion: 'trace-link-md-topology-v1',
  synthetic: true,
  nextCursor: null,
  points: [
    {
      from: '2026-09-10T09:00:00Z',
      to: '2026-09-10T09:05:00Z',
      value: 98.5,
      coverage: 1,
      quality: 'INSUFFICIENT_EVIDENCE',
      numerator: 985,
      denominator: 1000,
      sumValue: 985,
      sampleCount: 1000,
      nullReason: null,
      qualityReason: 'PARTIAL_INTERVAL',
      expectedSlots: 1,
      receivedSlots: 1,
      sourceQuality: 'PARTIAL',
      sourceCoverage: 0.8,
      projectionIds: ['proj-1'],
      provenance: [],
    },
    {
      from: '2026-09-10T09:05:00Z',
      to: '2026-09-10T09:10:00Z',
      value: null,
      coverage: 1,
      quality: 'INSUFFICIENT_EVIDENCE',
      numerator: 0,
      denominator: 0,
      sumValue: 0,
      sampleCount: 0,
      nullReason: 'ZERO_DENOMINATOR',
      qualityReason: 'ZERO_DENOMINATOR',
      expectedSlots: 1,
      receivedSlots: 1,
      sourceQuality: 'VALID',
      sourceCoverage: 1,
      projectionIds: ['proj-2'],
      provenance: [],
    },
    {
      from: '2026-09-10T09:10:00Z',
      to: '2026-09-10T09:15:00Z',
      value: 99.0,
      coverage: 1,
      quality: 'VALID',
      numerator: 990,
      denominator: 1000,
      sumValue: 990,
      sampleCount: 1000,
      nullReason: null,
      qualityReason: null,
      expectedSlots: 1,
      receivedSlots: 1,
      sourceQuality: 'VALID',
      sourceCoverage: 1,
      projectionIds: ['proj-3'],
      provenance: [],
    },
  ],
};

async function createHarness(seriesResponse: TraceMetricSeries) {
  const ended = new Subject<void>();
  const phase = signal('authenticated');
  let revision = 1;
  const session = {
    phase,
    actor: signal({ analystId: 'trace-test' }),
    ended$: ended.asObservable(),
    get revision() { return revision; },
  };

  const cities = { catalogueVersion: 'cat-v1', cities: [{ id: 'chi', name: 'Chisinau', active: true, latitude: 47, longitude: 28, synthetic: true, demandShare: 1 }] };
  const links = { catalogueVersion: 'cat-v1', links: [] };
  const partners = { catalogueVersion: 'cat-v1', partners: [] };
  const definitions = [{
    domain: 'volte',
    metric: 'volte.setup.success',
    unit: 'PERCENT',
    aggregation: 'RATIO_OF_SUMS',
    population: 'VoLTE setup attempts',
    primitiveFields: ['numerator', 'denominator'],
    policyVersion: 'trace-link-kpi-v1',
  }];

  const api = {
    traceDatasets: vi.fn().mockResolvedValue([]),
    traceIncidents: vi.fn().mockResolvedValue({items:[],total:0,limit:50,offset:0}),
    traceCities: vi.fn().mockResolvedValue(cities),
    traceLinks: vi.fn().mockResolvedValue(links),
    tracePartners: vi.fn().mockResolvedValue(partners),
    traceDefinitions: vi.fn().mockResolvedValue(definitions),
    traceMetricSeries: vi.fn().mockResolvedValue(seriesResponse),
    traceMetricCsv: vi.fn().mockResolvedValue(''),
  };

  TestBed.configureTestingModule({
    providers: [
      TraceQueryStore,
      { provide: IncidentStream, useValue: {connect:vi.fn(()=>()=>{})} },
      { provide: TelecomClient, useValue: api },
      { provide: SessionStore, useValue: session },
      { provide: Router, useValue: { navigate: vi.fn().mockResolvedValue(true) } },
      {
        provide: ActivatedRoute,
        useValue: {
          snapshot: {
            queryParams: {
              datasetId: 'trace-link-md-30d-v1',
              from: '2026-09-10T09:00:00Z',
              to: '2026-09-10T09:15:00Z',
              resolution: '5m',
              cityId: 'chi',
              domain: 'volte',
            },
          },
        },
      },
    ],
  });

  const fixture = TestBed.createComponent(TraceOverviewComponent);
  fixture.detectChanges();
  await new Promise(r => setTimeout(r, 20));
  fixture.detectChanges();
  await new Promise(r => setTimeout(r, 20));
  fixture.detectChanges();
  return { fixture, component: fixture.componentInstance, api };
}

describe('TraceOverviewComponent history quality validator', () => {
  it('accepts INSUFFICIENT_EVIDENCE with calculated value and zero-denominator null value', async () => {
    const { fixture, component } = await createHarness(baseSeries);
    await fixture.whenStable();

    // Verify series is accepted and populated
    expect(component.series()).not.toBeNull();
    expect(component.seriesError()).toBe('');
    const points = component.series()!.points;
    expect(points).toHaveLength(3);

    // Verify non-null INSUFFICIENT_EVIDENCE point preserves value and quality metadata
    expect(points[0].quality).toBe('INSUFFICIENT_EVIDENCE');
    expect(points[0].value).toBe(98.5);
    expect(points[0].numerator).toBe(985);
    expect(points[0].denominator).toBe(1000);
    expect(points[0].sourceQuality).toBe('PARTIAL');
    expect(points[0].qualityReason).toBe('PARTIAL_INTERVAL');

    // Verify zero-denominator INSUFFICIENT_EVIDENCE point preserves null value and reason
    expect(points[1].quality).toBe('INSUFFICIENT_EVIDENCE');
    expect(points[1].value).toBeNull();
    expect(points[1].denominator).toBe(0);
    expect(points[1].nullReason).toBe('ZERO_DENOMINATOR');

    fixture.destroy();
  });

  it('rejects genuinely malformed response with unknown quality state', async () => {
    const malformed = {
      ...baseSeries,
      points: [
        {
          ...baseSeries.points[0],
          quality: 'UNKNOWN_QUALITY' as any,
        },
      ],
    };
    const { fixture, component } = await createHarness(malformed);
    await fixture.whenStable();

    expect(component.series()).toBeNull();
    expect(component.seriesError()).toBe('Metric response contains incompatible quality or primitives.');
    fixture.destroy();
  });

  it('rejects response with non-null value for MISSING quality', async () => {
    const malformed = {
      ...baseSeries,
      points: [
        {
          ...baseSeries.points[0],
          quality: 'MISSING' as const,
          value: 99.0,
        },
      ],
    };
    const { fixture, component } = await createHarness(malformed);
    await fixture.whenStable();

    expect(component.series()).toBeNull();
    expect(component.seriesError()).toBe('Metric response contains incompatible quality or primitives.');
    fixture.destroy();
  });

  it('rejects response with non-null value and zero denominator for RATIO_OF_SUMS', async () => {
    const malformed = {
      ...baseSeries,
      points: [
        {
          ...baseSeries.points[0],
          value: 99.0,
          denominator: 0,
        },
      ],
    };
    const { fixture, component } = await createHarness(malformed);
    await fixture.whenStable();

    expect(component.series()).toBeNull();
    expect(component.seriesError()).toBe('Metric response contains incompatible quality or primitives.');
    fixture.destroy();
  });

  it('rejects response with invalid coverage outside [0, 1]', async () => {
    const malformed = {
      ...baseSeries,
      points: [
        {
          ...baseSeries.points[0],
          coverage: 1.5,
        },
      ],
    };
    const { fixture, component } = await createHarness(malformed);
    await fixture.whenStable();

    expect(component.series()).toBeNull();
    expect(component.seriesError()).toBe('Metric response contains incompatible quality or primitives.');
    fixture.destroy();
  });

  it('rejects response with mismatched query parameters', async () => {
    const malformed = {
      ...baseSeries,
      datasetId: 'different-dataset-id',
    };
    const { fixture, component } = await createHarness(malformed);
    await fixture.whenStable();

    expect(component.series()).toBeNull();
    expect(component.seriesError()).toBe('Metric response does not match the selected query.');
    fixture.destroy();
  });
});
