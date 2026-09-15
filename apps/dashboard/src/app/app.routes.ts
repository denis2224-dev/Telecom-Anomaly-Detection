import { Routes } from "@angular/router";
import { ServiceOverviewComponent } from "./features/service-overview/service-overview.component";
import { SignedOutComponent } from "./features/session/signed-out.component";
import { IncidentsComponent } from "./features/incidents/incidents.component";

// Dashboard routes and shared components for service assurance application.
export const routes: Routes = [
  { path: "", pathMatch: "full", redirectTo: "dashboard" },
  { path: "dashboard", component: ServiceOverviewComponent },
  { path: "services/:scopeId", component: ServiceOverviewComponent },
  { path: "incidents", component: IncidentsComponent },
  { path: "incidents/:id", component: IncidentsComponent },
  { path: "signed-out", component: SignedOutComponent },
  { path: "**", redirectTo: "dashboard" },
];
