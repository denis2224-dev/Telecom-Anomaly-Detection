import { Component, inject } from "@angular/core";
import { Router, RouterLink } from "@angular/router";
import { SessionStore } from "./session.store";

@Component({
  selector: "app-login",
  imports: [RouterLink],
  template: `
    <section class="login-layout" aria-labelledby="login-title">
      <div class="login-intro">
        <p class="eyebrow">Telecom service assurance</p>
        <h1 id="login-title">{{ title() }}</h1>
        <p>Understand service health. Investigate the evidence. Keep track of your next action.</p>
        <div class="login-capabilities">
          <p><strong>01 · Service overview</strong><br />See VoLTE and SMS service observations in one workspace.</p>
          <p><strong>02 · Evidence in context</strong><br />Read measured values alongside expected values and sample counts.</p>
          <p><strong>03 · Clear next steps</strong><br />Keep technical recovery separate from investigation progress.</p>
        </div>
      </div>
      <section class="login-card" aria-labelledby="access-title">
        <p class="eyebrow">Organization access</p>
        <h2 id="access-title">{{ session.phase() === 'fixture' ? 'Explore the preview' : 'Your analyst workspace' }}</h2>
        @switch (session.phase()) {
          @case ('fixture') {
            <p>This preview uses synthetic sample data. It does not sign you in or connect to live telemetry.</p>
            <a class="button primary" routerLink="/dashboard">Open sample workspace</a>
          }
          @case ('authenticated') {
            <p>You are signed in as {{ session.actor()?.displayName }}.</p>
            <a class="button primary" routerLink="/dashboard">Open workspace</a>
          }
          @case ('error') {
            <p role="alert">{{ session.message() }}</p>
            <button class="primary" (click)="session.initialize()">Retry connection</button>
            <p class="muted">If the problem continues, ask your team to check that the service is available.</p>
          }
          @case ('forbidden') {
            <p role="alert">Your account does not have access to this workspace. Ask your administrator to enable your analyst account and assign a role.</p>
            <button class="primary" (click)="session.login()">Continue to organization sign-in</button>
          }
          @default {
            @if (session.phase() === 'expired') {
              <p role="status">Your session has ended. Sign in again to continue reviewing service evidence.</p>
            } @else {
              <p>Use your organization account to access service evidence and investigations.</p>
            }
            <button class="primary" (click)="session.login()">Continue to sign in</button>
            <p class="muted">You will continue to your organization’s sign-in page, then return to the service overview.</p>
          }
        }
        <p class="login-help">Need access? Contact your project administrator.</p>
      </section>
    </section>
  `,
})
export class LoginComponent {
  readonly session = inject(SessionStore);
  private readonly router = inject(Router);

  title(): string {
    if (this.router.url.split("?")[0] === "/signed-out")
      return "You’re signed out";
    if (this.session.phase() === "expired") return "Your session has expired";
    if (this.session.phase() === "error") return "We couldn’t connect";
    if (this.session.phase() === "forbidden") return "Access unavailable";
    return "Sign in to investigate service issues";
  }
}
