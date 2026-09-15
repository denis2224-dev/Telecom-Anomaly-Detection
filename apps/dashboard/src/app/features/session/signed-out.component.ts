import { Component, inject } from "@angular/core";
import { RouterLink } from "@angular/router";
import { SessionStore } from "./session.store";

@Component({
  selector: "app-signed-out",
  imports: [RouterLink],
  template: `
    <section class="state-panel">
      <p class="eyebrow">Analyst workspace</p>
      <h1>Service investigation</h1>
      @if (
        session.phase() === "authenticated" || session.phase() === "fixture"
      ) {
        <p>Your workspace is ready.</p>
        <a class="button primary" routerLink="/dashboard">Open workspace</a>
      } @else {
        <p>Sign in with your organization to review service evidence.</p>
        <button class="primary" (click)="session.login()">
          Continue to sign in
        </button>
      }
    </section>
  `,
})
export class SignedOutComponent {
  readonly session = inject(SessionStore);
}
