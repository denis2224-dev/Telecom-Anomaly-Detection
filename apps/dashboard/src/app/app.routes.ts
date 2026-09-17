import { Routes } from "@angular/router";
import { ServiceOverviewComponent } from "./features/service-overview/service-overview.component";
import { SignedOutComponent } from "./features/session/signed-out.component";


// Dashboard routes and shared components for service assurance application.
export const routes: Routes = [
  { path: "", pathMatch: "full", redirectTo: "dashboard" },
  { path: "dashboard", component: ServiceOverviewComponent },
  { path: "services/:scopeId", component: ServiceOverviewComponent },
  { path: "signed-out", component: SignedOutComponent },
  { path: "**", redirectTo: "dashboard" },
];
