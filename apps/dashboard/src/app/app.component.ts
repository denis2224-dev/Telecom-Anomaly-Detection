import { Component, effect, inject } from "@angular/core";
import { DatePipe } from "@angular/common";
import { Router, RouterLink, RouterOutlet } from "@angular/router";
import { LoginComponent } from "./features/session/login.component";
import { SessionStore } from "./features/session/session.store";
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
  constructor() {
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
