import { Routes } from "@angular/router";
import { ServiceOverviewComponent } from "./features/service-overview/service-overview.component";
import { LoginComponent } from "./features/session/login.component";
import { sessionGuard } from "./features/session/session.guard";
import { ServiceDetailComponent } from './features/service-detail/service-detail.component';


// Dashboard routes and shared components for service assurance application.
export const routes: Routes = [
  { path: "", pathMatch: "full", redirectTo: "dashboard" },
  { path: "login", component: LoginComponent },
  { path: "dashboard", component: ServiceOverviewComponent, canActivate: [sessionGuard] },
  { path: "services/:scopeId", component: ServiceDetailComponent, canActivate: [sessionGuard] },
  { path: "signed-out", component: LoginComponent },
  { path: "**", redirectTo: "dashboard" },
];
