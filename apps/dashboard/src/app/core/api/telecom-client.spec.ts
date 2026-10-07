import { TestBed } from "@angular/core/testing";
import { provideHttpClient } from "@angular/common/http";
import { withInterceptors } from '@angular/common/http';
import { sessionInterceptor } from '../../features/login-and-session/session.interceptor';
import {
  HttpTestingController,
  provideHttpClientTesting,
} from "@angular/common/http/testing";
import { SessionStore } from "../../features/login-and-session/session.store";
import { TelecomClient } from "./telecom-client";


describe("TelecomClient", () => {
  let client: TelecomClient;
  let http: HttpTestingController;
  let session: SessionStore;
  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(withInterceptors([sessionInterceptor])),
        provideHttpClientTesting(),
      ],
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

  it("cancels a pending protected request when the session ends", async () => {
    const response = client.listServices();
    const request = http.expectOne("/api/services");
    session.expire();
    await expect(response).rejects.toMatchObject({ status: 401 });
    expect(request.cancelled).toBe(true);
  });

  it("never sends a mutation without CSRF settings", async () => {
    session.csrf.set(null);
    await expect(
      client.changeStatus("1", { status: "INVESTIGATING", version: 1 }),
    ).rejects.toMatchObject({ status: 403, code: "CSRF_INVALID" });
    http.expectNone("/api/incidents/1/status");
  });

  it('cancels a read when its screen aborts it', async () => {
    const controller = new AbortController();
    const response = client.listIncidents({ page: 0, size: 20 }, controller.signal);
    const rejected = expect(response).rejects.toMatchObject({ name: 'AbortError' });
    const request = http.expectOne('/api/incidents?page=0&size=20');
    controller.abort();
    await rejected;
    expect(request.cancelled).toBe(true);
    expect(session.phase()).toBe('authenticated');
  });

  it('sends the requested evidence page size', async () => {
    const response = client.getDetections('incident/1', 2, 20);
    const request = http.expectOne('/api/incidents/incident%2F1/detections?page=2&size=20');
    request.flush({ items: [], total: 40, page: 2, size: 20 });
    expect(await response).toEqual({ items: [], total: 40, page: 2, size: 20 });
  });

  it('uses the protected geography catalogue and UTC city history endpoints', async () => {
    const catalogue = client.listGeographyCities();
    http.expectOne('/api/geography/cities').flush({ cities: [], generatedAt: '2026-10-06T12:00:00Z' });
    expect((await catalogue).cities).toEqual([]);
    const history = client.getGeographyCityKpis('CHI', { service: 'SMS', from: '2026-10-06T11:00:00Z', to: '2026-10-06T12:00:00Z', page: 2, size: 20 });
    const request = http.expectOne(request => request.url === '/api/geography/cities/CHI/kpis');
    expect(request.request.params.get('service')).toBe('SMS');
    expect(request.request.params.get('from')).toBe('2026-10-06T11:00:00Z');
    expect(request.request.params.get('page')).toBe('2');
    request.flush({ cityId: 'CHI', service: 'SMS', page: 2, size: 20, hasNext: false, points: [] });
    expect((await history).points).toEqual([]);
  });

  it('never falls back to fixtures when geography is unavailable or forbidden', async () => {
    for (const status of [503, 403]) {
      const result = client.listGeographyCities();
      const failure = expect(result).rejects.toMatchObject({ status });
      http.expectOne('/api/geography/cities').flush({}, { status, statusText: 'Unavailable' });
      await failure;
    }
  });
});
