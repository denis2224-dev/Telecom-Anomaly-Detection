import { TestBed } from "@angular/core/testing";
import { ServiceSummary, TelecomClient } from "../../core/api/telecom-client";
import { ServiceStore } from "./service.store";
import services from "../../../fixtures/services.json";

describe("ServiceStore", () => {
  it("derives normal, degraded, stale, and unknown labels from fixture evidence", () => {
    TestBed.configureTestingModule({
      providers: [{ provide: TelecomClient, useValue: {} }],
    });
    const store = TestBed.inject(ServiceStore);

    const fixtureServices = services as ServiceSummary[];
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
    const rate = voice.latestWindow!.kpis.find(kpi => kpi.name === 'Call setup success rate (CSSR)')!;
    rate.baseline = null;
    expect(store.health(voice)).toBe('UNKNOWN');
    rate.baseline = 99.3;
    rate.denominator = 0;
    expect(store.health(voice)).toBe('UNKNOWN');
    const sms = structuredClone(services[1]) as ServiceSummary;
    sms.latestWindow!.kpis.find(kpi => kpi.name === 'deliveredMessages')!.observed = 0;
    expect(store.health(sms)).toBe('UNKNOWN');
  });
});
