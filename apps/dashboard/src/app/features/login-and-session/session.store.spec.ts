import { TestBed } from "@angular/core/testing";
import { HttpClient, provideHttpClient } from "@angular/common/http";
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
    vi.unstubAllGlobals();
  });

  async function authenticate(): Promise<void> {
    const pending = store.initialize();
    http.expectOne('/api/auth/me').flush({ analystId: 'analyst', displayName: 'Analyst', roles: ['ANALYST'],
      expiresAt: new Date(Date.now() + 30 * 60_000).toISOString() });
    await Promise.resolve();
    http.expectOne('/api/auth/csrf').flush({ token: 'test-csrf', headerName: 'X-CSRF-TOKEN', parameterName: '_csrf' });
    await pending;
  }

  it('expires idle access despite background reads and revokes the server session without provider redirects', async () => {
    vi.useFakeTimers();
    const logout = vi.fn().mockResolvedValue({});
    vi.stubGlobal('fetch', logout);
    await authenticate();
    const ended = vi.fn(); store.ended$.subscribe(ended);
    await vi.advanceTimersByTimeAsync(14 * 60_000);
    const background = TestBed.inject(HttpClient).get('/api/services').subscribe();
    http.expectOne('/api/services').flush([]);
    document.dispatchEvent(new Event('input')); // Synthetic events do not extend activity.
    await vi.advanceTimersByTimeAsync(60_000);
    expect(store.phase()).toBe('expired');
    expect(store.actor()).toBeNull(); expect(store.csrf()).toBeNull();
    expect(ended).toHaveBeenCalledOnce();
    expect(logout).toHaveBeenCalledExactlyOnceWith('/logout', { method: 'POST', credentials: 'same-origin',
      redirect: 'manual', headers: { 'X-CSRF-TOKEN': 'test-csrf' } });
    await vi.advanceTimersByTimeAsync(30 * 60_000);
    expect(logout).toHaveBeenCalledOnce();
    background.unsubscribe();
  });

  it('extends idle access only for trusted analyst input and retains the absolute deadline', async () => {
    vi.useFakeTimers();
    const logout = vi.fn(); vi.stubGlobal('fetch', logout);
    await authenticate();
    await vi.advanceTimersByTimeAsync(14 * 60_000);
    // jsdom cannot create trusted input; exercise the registered handler's trusted branch.
    store['activity']({ isTrusted: true } as Event);
    await vi.advanceTimersByTimeAsync(14 * 60_000);
    expect(store.phase()).toBe('authenticated');
    store['activity']({ isTrusted: true } as Event);
    await vi.advanceTimersByTimeAsync(2 * 60_000);
    expect(store.phase()).toBe('expired'); expect(logout).not.toHaveBeenCalled();
  });

  it('does not revive idle access when browser timers were suspended', async () => {
    vi.useFakeTimers();
    const logout = vi.fn().mockRejectedValue(new Error('offline')); vi.stubGlobal('fetch', logout);
    await authenticate();
    vi.setSystemTime(Date.now() + 15 * 60_000 + 1);
    store['activity']({ isTrusted: true } as Event);
    expect(store.phase()).toBe('expired');
    await Promise.resolve();
    expect(logout).toHaveBeenCalledOnce();
    document.dispatchEvent(new Event('visibilitychange'));
    expect(logout).toHaveBeenCalledOnce();
  });

  it('obtains fresh CSRF for idle revocation when a rejected write cleared the token', async () => {
    vi.useFakeTimers();
    const revoke = vi.fn().mockResolvedValue({ json: async () => ({ token: 'fresh-csrf', headerName: 'X-CSRF-TOKEN' }) });
    vi.stubGlobal('fetch', revoke);
    await authenticate(); store.csrf.set(null);
    await vi.advanceTimersByTimeAsync(15 * 60_000);
    expect(store.phase()).toBe('expired');
    expect(revoke).toHaveBeenNthCalledWith(1, '/api/auth/csrf', { credentials: 'same-origin' });
    expect(revoke).toHaveBeenNthCalledWith(2, '/logout', { method: 'POST', credentials: 'same-origin',
      redirect: 'manual', headers: { 'X-CSRF-TOKEN': 'fresh-csrf' } });
    expect(store.csrf()).toBeNull();
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

  it("shows signed out for 401 and refreshes the public CSRF token", async () => {
    const pending = store.initialize();
    http
      .expectOne("/api/auth/me")
      .flush({}, { status: 401, statusText: "Unauthorized" });
    await Promise.resolve();
    http.expectOne("/api/auth/csrf").flush({
      token: "anonymous-test-csrf",
      headerName: "X-CSRF-TOKEN",
      parameterName: "_csrf",
    });
    await pending;
    expect(store.phase()).toBe("signed-out");
    expect(store.actor()).toBeNull();
    expect(store.csrf()?.token).toBe("anonymous-test-csrf");
  });

  it("shows an error when anonymous CSRF discovery fails", async () => {
    const pending = store.initialize();
    http
      .expectOne("/api/auth/me")
      .flush({}, { status: 401, statusText: "Unauthorized" });
    await Promise.resolve();
    http
      .expectOne("/api/auth/csrf")
      .flush({}, { status: 503, statusText: "Unavailable" });
    await pending;
    expect(store.phase()).toBe("error");
    expect(store.message()).toBe("Session protection could not be loaded. Try again.");
  });

  it("refreshes CSRF before logout even when the cached token was cleared", async () => {
    store.phase.set("authenticated");
    store.csrf.set(null);
    const submit = vi.spyOn(HTMLFormElement.prototype, "submit").mockImplementation(() => {});
    const pending = store.logout();
    http.expectOne("/api/auth/csrf").flush({
      token: "fresh-csrf",
      headerName: "X-CSRF-TOKEN",
      parameterName: "_csrf",
    });
    await pending;
    expect(submit).toHaveBeenCalledOnce();
    expect(document.body.querySelector('form[action="/logout"] input[name="_csrf"]')?.getAttribute("value"))
      .toBe("fresh-csrf");
    expect(store.phase()).toBe("signed-out");
    document.body.querySelector('form[action="/logout"]')?.remove();
    submit.mockRestore();
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
