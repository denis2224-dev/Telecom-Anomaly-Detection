import { Component } from "@angular/core";
import { TestBed } from "@angular/core/testing";
import { provideRouter, Router } from "@angular/router";
import { RouterTestingHarness } from "@angular/router/testing";
import { provideHttpClient } from "@angular/common/http";
import { SessionStore } from "./session.store";
import { sessionGuard } from "./session.guard";

@Component({ template: "Protected evidence" })
class ProtectedPage {}
@Component({ template: "Sign in" })
class LoginPage {}

describe("Session route protection", () => {
  beforeEach(() => {
    TestBed.configureTestingModule({ providers: [
      provideHttpClient(),
      provideRouter([
        { path: "services/:id", component: ProtectedPage, canActivate: [sessionGuard] },
        { path: "login", component: LoginPage },
      ]),
    ] });
  });

  it.each(["signed-out", "expired", "error", "forbidden"] as const)(
    "redirects a %s deep link without creating the protected page", async (phase) => {
      TestBed.inject(SessionStore).phase.set(phase);
      const harness = await RouterTestingHarness.create();
      await harness.navigateByUrl("/services/VOLTE-MD-CENTRAL", LoginPage);
      expect(TestBed.inject(Router).url).toBe("/login");
      expect(harness.routeNativeElement?.textContent).toBe("Sign in");
    },
  );

  it.each(["authenticated", "fixture"] as const)("allows %s access", async (phase) => {
    TestBed.inject(SessionStore).phase.set(phase);
    const harness = await RouterTestingHarness.create();
    await harness.navigateByUrl("/services/VOLTE-MD-CENTRAL", ProtectedPage);
    expect(harness.routeNativeElement?.textContent).toBe("Protected evidence");
  });

  it("waits for session discovery before deciding access", async () => {
    const session = TestBed.inject(SessionStore);
    vi.spyOn(session, "initialize").mockImplementation(async () => {
      session.phase.set("signed-out");
    });
    const harness = await RouterTestingHarness.create();
    await harness.navigateByUrl("/services/VOLTE-MD-CENTRAL", LoginPage);
    expect(session.initialize).toHaveBeenCalledOnce();
  });
});
