import { TestBed } from "@angular/core/testing";
import { provideRouter } from "@angular/router";
import { ServiceOverviewComponent } from "./service-overview.component";
import { TelecomClient } from "../../core/api/telecom-client";
import services from "../../../fixtures/services.json";

describe("Service overview rendering", () => {
  it("renders both fixture services and labels the observation evidence", async () => {
    TestBed.configureTestingModule({
      providers: [
        provideRouter([]),
        {
          provide: TelecomClient,
          useValue: {
            listServices: async () => services,
          },
        },
      ],
    });
    const fixture = TestBed.createComponent(ServiceOverviewComponent);
    await fixture.whenStable();
    const element: HTMLElement = fixture.nativeElement;
    expect(element.textContent).toContain("VOLTE-MD-CENTRAL");
    expect(element.textContent).toContain("SMS-MD-ROUTE-A");
    expect(element.textContent).toContain("Sample volume matters");
    expect(element.querySelectorAll(".support section")).toHaveLength(3);
  });

  it("displays missing telemetry as unknown in a selected scope", async () => {
    const missing = structuredClone(services[0]);
    const data = { ...missing, freshness: "MISSING", latestWindow: null };
    TestBed.configureTestingModule({
      providers: [
        provideRouter([]),
        {
          provide: TelecomClient,
          useValue: {
            listServices: async () => [data],
          },
        },
      ],
    });
    const fixture = TestBed.createComponent(ServiceOverviewComponent);
    await fixture.whenStable();
    fixture.componentInstance.scopeId.set(data.scope.scopeId);
    await fixture.whenStable();
    expect(fixture.nativeElement.textContent).toContain(
      "Service health is unknown",
    );
    expect(fixture.nativeElement.querySelector(".metric-list")).toBeNull();
  });

  it("keeps request failures distinct from successful empty results", async () => {
    TestBed.configureTestingModule({
      providers: [
        provideRouter([]),
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
    const fixture = TestBed.createComponent(ServiceOverviewComponent);
    await fixture.whenStable();
    expect(fixture.nativeElement.textContent).toContain(
      "Services could not be loaded",
    );
    expect(fixture.nativeElement.textContent).not.toContain(
      "No monitored service scopes",
    );
  });
});
