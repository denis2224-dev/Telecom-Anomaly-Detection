import { bootstrapApplication } from "@angular/platform-browser";
import { provideHttpClient, withNoXsrfProtection } from "@angular/common/http";
import { provideRouter } from "@angular/router";
import { AppComponent } from "./app/app.component";
import { routes } from "./app/app.routes";

bootstrapApplication(AppComponent, {
  providers: [provideHttpClient(withNoXsrfProtection()), provideRouter(routes)],
}).catch(() => {
  document.body.textContent =
    "The workspace could not start. Please reload the page.";
});
