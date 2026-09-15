import { TestBed } from "@angular/core/testing";
import { provideHttpClient } from "@angular/common/http";
import {
  HttpTestingController,
  provideHttpClientTesting,
} from "@angular/common/http/testing";
import { SessionStore } from "../../features/session/session.store";
import { TelecomClient } from "./telecom-client";

describe("TelecomClient", () => {
  let client: TelecomClient;
  let http: HttpTestingController;
  let session: SessionStore;
  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    client = TestBed.inject(TelecomClient);
    http = TestBed.inject(HttpTestingController);
    session = TestBed.inject(SessionStore);
    session.phase.set("authenticated");
    session.actor.set({
      analystId: "test",
      displayName: "Test",
      roles: ["ANALYST"],
      expiresAt: new Date(Date.now() + 60000).toISOString(),
    });
    session.csrf.set({
      token: "test-csrf",
      headerName: "X-CSRF-TOKEN",
      parameterName: "_csrf",
    });
  });
  afterEach(() => {
    http.verify();
    TestBed.resetTestingModule();
  });

  it("uses the contract POST method and CSRF header without replaying a conflict", async () => {
    const response = client.changeStatus("incident/1", {
      status: "INVESTIGATING",
      version: 1,
    });
    const expectation = expect(response).rejects.toMatchObject({ status: 409 });
    const request = http.expectOne("/api/incidents/incident%2F1/status");
    expect(request.request.method).toBe("POST");
    expect(request.request.headers.get("X-CSRF-TOKEN")).toBe("test-csrf");
    request.flush(
      { code: "VERSION_CONFLICT" },
      { status: 409, statusText: "Conflict" },
    );
    await expectation;
  });

  it("clears session state on API 401", async () => {
    const response = client.listServices();
    const expectation = expect(response).rejects.toMatchObject({ status: 401 });
    http
      .expectOne("/api/services")
      .flush({}, { status: 401, statusText: "Unauthorized" });
    await expectation;
    expect(session.phase()).toBe("expired");
    expect(session.actor()).toBeNull();
    expect(session.csrf()).toBeNull();
  });

  it("never sends a mutation without CSRF settings", async () => {
    session.csrf.set(null);
    await expect(
      client.changeStatus("1", { status: "INVESTIGATING", version: 1 }),
    ).rejects.toMatchObject({ status: 403, code: "CSRF_INVALID" });
    http.expectNone("/api/incidents/1/status");
  });
});
