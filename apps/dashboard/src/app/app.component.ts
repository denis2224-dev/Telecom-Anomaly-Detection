import { TelecomClient, type ServiceSummary } from './core/api/telecom-client';
import { Component, effect, inject, signal } from "@angular/core";
import { DatePipe } from "@angular/common";
import { Router, RouterLink, RouterLinkActive, RouterOutlet } from "@angular/router";
import { LoginComponent } from "./features/login-and-session/login.component";
import { SessionStore } from "./features/login-and-session/session.store";
import { IconComponent } from "./shared/icon.component";
import { ToastService } from "./shared/toast.service";
import { dataSource } from "./core/api/data-source";

@Component({
  selector: "app-root",
  imports: [RouterOutlet, RouterLink, RouterLinkActive, DatePipe, LoginComponent, IconComponent],
  templateUrl: "./app.component.html",
})
export class AppComponent {
  private readonly api = inject(TelecomClient);
  readonly serviceScopes = signal<ServiceSummary[]>([]);
  serviceScope(service: string): string | undefined {
    return this.serviceScopes().find(item => item.scope.service === service)?.scope.scopeId;
  }
  serviceActive(service: string): boolean {
    return this.serviceScopes().some(item => item.scope.service === service && this.router.url.split('?')[0] === '/services/' + encodeURIComponent(item.scope.scopeId));
  }
  readonly collapsed = signal(false);
  readonly mobileOpen = signal(false);
  readonly toast = inject(ToastService);
  pageTitle(): string {
    const path = this.router.url;
    return path.startsWith('/scenarios') ? 'Scenario runner'
      : path.startsWith('/incidents') ? 'Incident investigation'
      : path.startsWith('/services') ? 'Service investigation' : 'Service overview';
  }
  readonly session = inject(SessionStore);
  readonly fixture = dataSource.fixture;
  readonly router = inject(Router);
  constructor() {
    void this.session.initialize();
    effect(cleanup => {
      const phase = this.session.phase();
      this.serviceScopes.set([]);
      if (phase !== 'authenticated' && phase !== 'fixture') return;
      const controller = new AbortController();
      cleanup(() => controller.abort());
      void this.api.listServices(controller.signal).then(items => {
        if (!controller.signal.aborted) this.serviceScopes.set(items);
      }).catch(() => { /* Service loading errors are presented by the routed page. */ });
    });
    effect(() => {
      if (this.session.phase() === "expired") {
        void this.router.navigateByUrl("/login", { replaceUrl: true });
      }
      if (this.session.phase() === "authenticated" && this.router.url === "/login") {
        void this.router.navigateByUrl("/dashboard", { replaceUrl: true });
      }
    });
  }

  isAccessPage(): boolean {
    return ["/login", "/signed-out"].includes(this.router.url.split("?")[0]);
  }
}
