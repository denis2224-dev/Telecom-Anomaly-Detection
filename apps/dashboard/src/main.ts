import { bootstrapApplication } from "@angular/platform-browser";
import { provideHttpClient, withInterceptors, withNoXsrfProtection } from "@angular/common/http";
import { provideRouter } from "@angular/router";
import { AppComponent } from "./app/app.component";
import { routes } from "./app/app.routes";
import { sessionInterceptor } from './app/features/login-and-session/session.interceptor';

bootstrapApplication(AppComponent, {
  providers: [provideHttpClient(
    withNoXsrfProtection(),
    withInterceptors([sessionInterceptor]),),
    provideRouter(routes)],
}).catch(() => {
  document.body.textContent =
    "The workspace could not start. Please reload the page.";
});
