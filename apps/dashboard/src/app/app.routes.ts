import { Routes } from "@angular/router";
import { ServiceOverviewComponent } from "./features/service-overview/service-overview.component";
import { LoginComponent } from "./features/login-and-session/login.component";
import { sessionGuard } from "./features/login-and-session/session.guard";
import { ScenarioRunnerComponent } from './features/scenario-runner/scenario-runner.component';


// Dashboard routes and shared components for service assurance application.
export const routes: Routes = [
  { path: "", pathMatch: "full", redirectTo: "dashboard" },
  { path: "login", component: LoginComponent },
  { path: "dashboard", component: ServiceOverviewComponent, canActivate: [sessionGuard] },
  { path: "services/:scopeId", loadComponent: () => import('./features/service-kpi-history/service-detail.component').then(module => module.ServiceDetailComponent), canActivate: [sessionGuard] },
  { path: "incidents/:id", loadComponent: () => import('./features/incident-investigation/incident-detail.component').then(module => module.IncidentDetailComponent), canActivate: [sessionGuard] },
  { path: 'scenarios', component: ScenarioRunnerComponent, canActivate: [sessionGuard] },
  { path: "signed-out", component: LoginComponent },
  { path: "**", redirectTo: "dashboard" },
];
