import {
  HttpErrorResponse,
  type HttpInterceptorFn,
} from '@angular/common/http';
import { inject } from '@angular/core';
import { catchError, takeUntil, throwError } from 'rxjs';
import { ApiFailure } from '../../core/api/api-errors';
import { SessionStore } from './session.store';

export const sessionInterceptor: HttpInterceptorFn = (
  request,
  next,
) => {
  const path = request.url.split('?')[0];

  // Session discovery has to work while signed out.
  if (
    !path.startsWith('/api/')
    || path === '/api/auth/me'
    || path === '/api/auth/csrf'
  ) {
    return next(request);
  }

  const session = inject(SessionStore);
  session.checkIdle();
  const actor = session.actor();

  if (
    session.phase() !== 'authenticated'
    || !actor
    || Date.parse(actor.expiresAt) <= Date.now()
  ) {
    if (session.phase() === 'authenticated') {
      session.expire();
    }

    return throwError(() => new ApiFailure(401));
  }

  const mutation = ['POST', 'PUT', 'PATCH', 'DELETE']
    .includes(request.method);

  if (mutation) {
    const csrf = session.csrf();

    if (!csrf) {
      return throwError(
        () => new ApiFailure(403, 'CSRF_INVALID'),
      );
    }

    request = request.clone({
      setHeaders: {
        [csrf.headerName]: csrf.token,
      },
    });
  }

  return next(request).pipe(
    takeUntil(session.ended$),

    catchError(error => {
      if (error instanceof HttpErrorResponse) {
        if (error.status === 401) {
          session.expire();
          return throwError(() => new ApiFailure(401));
        }

        if (
          error.status === 403
          && error.error?.code === 'CSRF_INVALID'
        ) {
          session.csrf.set(null);
        }
      }

      return throwError(() => error);
    }),
  );
};
