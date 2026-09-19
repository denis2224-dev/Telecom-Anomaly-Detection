import { TestBed } from "@angular/core/testing";
import { provideHttpClient } from "@angular/common/http";
import {
  HttpTestingController,
  provideHttpClientTesting,
} from "@angular/common/http/testing";
import { SessionStore } from "./session.store";

describe("SessionStore", () => {
  let store: SessionStore;
  let http: HttpTestingController;
  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    store = TestBed.inject(SessionStore);
    http = TestBed.inject(HttpTestingController);
  });
  afterEach(() => {
    http.verify();
    TestBed.resetTestingModule();
    vi.useRealTimers();
  });

  it("discovers identity then CSRF before enabling the workspace and clears both on expiry", async () => {
    vi.useFakeTimers();
    const pending = store.initialize();
    http
      .expectOne("/api/auth/me")
      .flush({
        analystId: "81205a34-116c-5d90-ae54-d80e0dee4fb1",
        displayName: "Analyst",
        roles: ["ANALYST"],
        expiresAt: new Date(Date.now() + 60000).toISOString(),
      });
    await Promise.resolve();
    expect(store.phase()).toBe("loading");
    http
      .expectOne("/api/auth/csrf")
      .flush({
        token: "test-csrf",
        headerName: "X-CSRF-TOKEN",
        parameterName: "_csrf",
      });
    await pending;
    expect(store.phase()).toBe("authenticated");
    await vi.advanceTimersByTimeAsync(60001);
    expect(store.phase()).toBe("expired");
    expect(store.actor()).toBeNull();
    expect(store.csrf()).toBeNull();
  });

  it("shows signed out for 401 without fetching protected data", async () => {
    const pending = store.initialize();
    http
      .expectOne("/api/auth/me")
      .flush({}, { status: 401, statusText: "Unauthorized" });
    await pending;
    expect(store.phase()).toBe("signed-out");
    expect(store.actor()).toBeNull();
  });

  it("rejects accidental HTML instead of opening the workspace", async () => {
    const pending = store.initialize();
    http.expectOne("/api/auth/me").flush("<html>Sign in</html>");
    await pending;
    expect(store.phase()).toBe("error");
    expect(store.actor()).toBeNull();
    http.expectNone("/api/auth/csrf");
  });

  it("stops waiting when session discovery never responds", async () => {
    vi.useFakeTimers();
    const pending = store.initialize();
    const request = http.expectOne("/api/auth/me");
    await vi.advanceTimersByTimeAsync(10001);
    await pending;
    expect(request.cancelled).toBe(true);
    expect(store.phase()).toBe("error");
  });

  it("does not authenticate when the CSRF response fails", async () => {
    const pending = store.initialize();
    http
      .expectOne("/api/auth/me")
      .flush({
        analystId: "analyst",
        displayName: "Analyst",
        roles: ["ANALYST"],
        expiresAt: new Date(Date.now() + 60000).toISOString(),
      });
    await Promise.resolve();
    http
      .expectOne("/api/auth/csrf")
      .flush({}, { status: 503, statusText: "Unavailable" });
    await pending;
    expect(store.phase()).toBe("error");
    expect(store.actor()).toBeNull();
  });

  it("does not revive a session when an old bootstrap response arrives after expiry", async () => {
    const pending = store.initialize();
    const request = http.expectOne("/api/auth/me");
    store.expire();
    request.flush({
      analystId: "analyst",
      displayName: "Analyst",
      roles: ["ANALYST"],
      expiresAt: new Date(Date.now() + 60000).toISOString(),
    });
    await pending;
    expect(store.phase()).toBe("expired");
    http.expectNone("/api/auth/csrf");
  });
});
