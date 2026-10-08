import { TestBed } from "@angular/core/testing";
import { ServiceSummary, TelecomClient } from "../../core/api/telecom-client";
import { ServiceStore } from "./service.store";
import services from "../../../fixtures/services.json";
import { voiceIncidents } from "../../../fixtures/voice";

describe("ServiceStore", () => {
  it("derives normal, degraded, stale, and unknown labels from fixture evidence", () => {
    TestBed.configureTestingModule({
      providers: [{ provide: TelecomClient, useValue: {} }],
    });
    const store = TestBed.inject(ServiceStore);

    const fixtureServices = services as ServiceSummary[];
    store.incidents.set([{ ...voiceIncidents[0], service: "SMS", scopeId: "SMS-MD-ROUTE-A", technicalState: "ONGOING" }]);
    expect(store.health(fixtureServices[0])).toBe("NORMAL");
    expect(store.health(fixtureServices[1])).toBe("DEGRADED");
    expect(store.health(fixtureServices[2])).toBe("STALE");
    expect(store.health(fixtureServices[3])).toBe("UNKNOWN");
  });

  it("keeps a failed request distinct from an empty successful response", async () => {
    TestBed.configureTestingModule({
      providers: [
        {
          provide: TelecomClient,
          useValue: {
            listServices: async () => {
              throw new Error("Connection unavailable");
            },
          },
        },
      ],
    });
    const store = TestBed.inject(ServiceStore);
    await store.load();

    expect(store.error()).toBe("Connection unavailable");
    expect(store.loading()).toBe(false);
  });

  it('does not call missing baselines or unsupported samples normal', () => {
    TestBed.configureTestingModule({ providers: [{ provide: TelecomClient, useValue: {} }] });
    const store = TestBed.inject(ServiceStore);
    const voice = structuredClone(services[0]) as ServiceSummary;
    const rate = voice.latestWindow!.kpis.find(kpi => kpi.name === 'cssrPct')!;
    rate.baseline = null;
    expect(store.health(voice)).toBe('UNKNOWN');
    rate.baseline = 99.3;
    rate.denominator = 0;
    expect(store.health(voice)).toBe('UNKNOWN');
    const sms = structuredClone(services[1]) as ServiceSummary;
    sms.latestWindow!.kpis.find(kpi => kpi.name === 'deliveredMessages')!.observed = 0;
    expect(store.health(sms)).toBe('UNKNOWN');
  });

  it('keeps newer incident versions while replacing the authoritative snapshot membership', async () => {
    const incident = voiceIncidents[0];
    const listIncidents = vi.fn().mockResolvedValue({ items: [{ ...incident, version: 3 }], total: 1 });
    TestBed.configureTestingModule({ providers: [{ provide: TelecomClient,
      useValue: { listServices: async () => services, listIncidents } }] });
    const store = TestBed.inject(ServiceStore);
    store.incidents.set([{ ...incident, version: 4, technicalState: 'ONGOING' }]);
    await store.load();
    expect(store.incidents()[0].version).toBe(4);
    expect(store.health(services[0] as ServiceSummary)).toBe('DEGRADED');
    listIncidents.mockResolvedValue({ items: [], total: 0 });
    await store.load();
    expect(store.incidents()).toEqual([]);
  });

  it('retains service measurements when incident context fails to load', async () => {
    TestBed.configureTestingModule({ providers: [{ provide: TelecomClient, useValue: {
      listServices: async () => services,
      listIncidents: async () => { throw new Error('Incident API unavailable'); },
    } }] });
    const store = TestBed.inject(ServiceStore);
    await store.load();
    expect(store.services()).toHaveLength(services.length);
    expect(store.error()).toBe('');
    expect(store.incidentError()).toBe('Incident API unavailable');
  });
});
