import { inject } from "@angular/core";
import { CanActivateFn, Router } from "@angular/router";
import { SessionStore } from "./session.store";

export const sessionGuard: CanActivateFn = async () => {
  const session = inject(SessionStore);
  const router = inject(Router);
  if (session.phase() === "loading") await session.initialize();
  return session.phase() === "authenticated" || session.phase() === "fixture"
    ? true
    : router.createUrlTree(["/login"]);
};
