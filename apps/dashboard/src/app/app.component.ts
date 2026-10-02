import { Component, DestroyRef, effect, inject } from "@angular/core";
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { LiveUpdates } from './core/api/live-updates';
import { DatePipe } from "@angular/common";
import { Router, RouterLink, RouterOutlet } from "@angular/router";
import { LoginComponent } from "./features/login-and-session/login.component";
import { SessionStore } from "./features/login-and-session/session.store";
import { dataSource } from "./core/api/data-source";

@Component({
  selector: "app-root",
  imports: [RouterOutlet, RouterLink, DatePipe, LoginComponent],
  templateUrl: "./app.component.html",
})
export class AppComponent {
  readonly session = inject(SessionStore);
  readonly fixture = dataSource.fixture;
  readonly router = inject(Router);
  readonly live = inject(LiveUpdates);
  constructor() {
    const destroy = inject(DestroyRef);
    this.session.ended$.pipe(takeUntilDestroyed(destroy)).subscribe(() => this.live.stop());
    destroy.onDestroy(() => this.live.stop());
    effect(() => {
      if (this.session.phase() === 'authenticated') this.live.start();
      else if (this.session.phase() === 'fixture') this.live.start(true);
      else this.live.stop();
    });
    void this.session.initialize();
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
