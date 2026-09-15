import { Component, inject } from "@angular/core";
import { DatePipe } from "@angular/common";
import { RouterLink, RouterOutlet } from "@angular/router";
import { SessionStore } from "./features/session/session.store";
import { dataSource } from "./core/api/data-source";

@Component({
  selector: "app-root",
  imports: [RouterOutlet, RouterLink, DatePipe],
  templateUrl: "./app.component.html",
})
export class AppComponent {
  readonly session = inject(SessionStore);
  readonly fixture = dataSource.fixture;
  constructor() {
    void this.session.initialize();
  }
}
