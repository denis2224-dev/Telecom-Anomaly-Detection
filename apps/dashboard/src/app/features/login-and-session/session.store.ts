import { DOCUMENT } from "@angular/common";
import { HttpClient, HttpErrorResponse } from "@angular/common/http";
import { Injectable, OnDestroy, inject, signal } from "@angular/core";
import { firstValueFrom, Subject, timeout } from "rxjs";
import type { components } from "../../core/api/schema";
import { dataSource } from "../../core/api/data-source";

type Session = components["schemas"]["CurrentSession"];
type Csrf = components["schemas"]["CsrfToken"];
type Phase =
  | "loading"
  | "authenticated"
  | "signed-out"
  | "expired"
  | "error"
  | "forbidden"
  | "fixture";

@Injectable({ providedIn: "root" })
export class SessionStore implements OnDestroy {
  private readonly http = inject(HttpClient);
  private readonly document = inject(DOCUMENT);
  readonly phase = signal<Phase>("loading");
  readonly actor = signal<Session | null>(null);
  readonly csrf = signal<Csrf | null>(null);
  readonly message = signal("");
  private readonly endedSubject = new Subject<void>();
  readonly ended$ = this.endedSubject.asObservable();
  private expiryTimer?: ReturnType<typeof setTimeout>;
  private pending?: Promise<void>;
  private generation = 0;
  private loggingOut = false;

  initialize(): Promise<void> {
    if (this.pending) return this.pending;
    this.pending = this.discover().finally(() => {
      this.pending = undefined;
    });
    return this.pending;
  }

  private async discover(): Promise<void> {
    this.clear();
    const generation = this.generation;
    this.phase.set("loading");
    this.message.set("");
    if (dataSource.fixture) {
      this.phase.set("fixture");
      return;
    }
    try {
      const actor = await firstValueFrom(
        this.http.get<Session>("/api/auth/me").pipe(timeout(10000)),
      );
      if (generation !== this.generation) return;
      const deadline = Date.parse(actor.expiresAt);
      if (
        !actor.analystId ||
        !actor.displayName ||
        !Array.isArray(actor.roles) ||
        !actor.roles.length ||
        actor.roles.some(
          (role) => !["ANALYST", "SUPERVISOR", "ADMIN"].includes(role),
        ) ||
        !Number.isFinite(deadline)
      ) {
        throw new Error("Invalid session response");
      }
      if (deadline <= Date.now()) {
        this.expire();
        return;
      }
      await this.refreshCsrf();
      if (generation !== this.generation) return;
      if (deadline <= Date.now()) {
        this.expire();
        return;
      }
      this.actor.set(actor);
      this.phase.set("authenticated");
      this.expiryTimer = setTimeout(
        () => this.expire(),
        Math.min(deadline - Date.now(), 2_147_483_647),
      );
    } catch (error) {
      if (generation !== this.generation) return;
      this.clear();
      if (error instanceof HttpErrorResponse && error.status === 401) {
        this.phase.set("signed-out");
        try {
          await this.refreshCsrf();
        } catch {
          if (this.phase() === "signed-out") {
            this.phase.set("error");
            this.message.set("Session protection could not be loaded. Try again.");
          }
        }
      } else if (error instanceof HttpErrorResponse && error.status === 403) {
        this.phase.set("forbidden");
        this.message.set("This account does not have access to the workspace.");
      } else {
        this.phase.set("error");
        this.message.set(
          "The session could not be verified. Check the connection and try again.",
        );
      }
    }
  }

  async refreshCsrf(): Promise<void> {
    const generation = this.generation;
    const csrf = await firstValueFrom(
      this.http.get<Csrf>("/api/auth/csrf").pipe(timeout(10000)),
    );
    if (generation !== this.generation) return;
    if (
      !csrf.token ||
      csrf.headerName !== "X-CSRF-TOKEN" ||
      csrf.parameterName !== "_csrf"
    ) {
      throw new Error("Invalid CSRF response");
    }
    this.csrf.set(csrf);
  }

  login(): void {
    if (dataSource.fixture) return;
    this.document.defaultView?.location.assign(
      "/oauth2/authorization/keycloak",
    );
  }

  async logout(): Promise<void> {
    if (this.loggingOut || this.phase() !== "authenticated") return;
    this.loggingOut = true;
    try {
      await this.refreshCsrf();
      if (this.phase() !== "authenticated") return;
      const csrf = this.csrf();
      if (!csrf) return;
      const form = this.document.createElement("form");
      form.method = "POST";
      form.action = "/logout";
      const input = this.document.createElement("input");
      input.type = "hidden";
      input.name = csrf.parameterName;
      input.value = csrf.token;
      form.append(input);
      this.document.body.append(form);
      this.clear();
      this.phase.set("signed-out");
      form.submit();
    } catch {
      this.message.set(
        "Sign out could not be started. Check the connection and try again.",
      );
    } finally {
      this.loggingOut = false;
    }
  }

  expire(): void {
    this.clear();
    this.phase.set("expired");
    this.message.set("Your session has expired. Sign in again to continue.");
  }

  private clear(): void {
    this.generation++;
    this.endedSubject.next();
    clearTimeout(this.expiryTimer);
    this.actor.set(null);
    this.csrf.set(null);
  }

  ngOnDestroy(): void {
    this.clear();
  }
}
