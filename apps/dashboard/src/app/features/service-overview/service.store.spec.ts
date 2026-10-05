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
});
