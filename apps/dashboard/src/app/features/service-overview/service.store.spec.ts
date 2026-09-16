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
});
