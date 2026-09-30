export class ApiFailure extends Error {
  constructor(
    readonly status: number,
    readonly code?: string,
  ) {
    super(
      status === 401
        ? 'Your session has expired. Please sign in again.'
        : status === 403
          ? code === 'CSRF_INVALID'
            ? 'Session protection needs to be refreshed. Review and retry your action.'
            : 'You do not have permission to access this content.'
          : status === 409
            ? 'This item changed. Reload and review it before trying again.'
            : status === 404
              ? 'This item could not be found.'
              : 'The service could not be reached. Try again.',
    );
  }
}

export function incidentActionMessage(error: unknown): string {
  if (!(error instanceof ApiFailure)) {
    return error instanceof Error
      ? error.message
      : 'The action could not be completed.';
  }

  if (error.status === 401) {
    return 'Your session expired. Sign in again before making changes.';
  }

  if (error.status === 403) {
    return error.code === 'CSRF_INVALID'
      ? 'Session protection expired. Sign in again, then review the incident before retrying.'
      : 'You are not allowed to make this change. Your entered note is still here.';
  }

  if (error.status === 409) {
    if (error.code === 'INVALID_TRANSITION') {
      return 'This workflow change is unavailable now. Check assignment, investigation, and technical recovery, then reload the incident.';
    }

    if (error.code === 'ASSIGNMENT_CONFLICT') {
      return 'The assignment changed or is no longer allowed. Reload the incident before choosing another action.';
    }

    return 'The incident changed since you opened it. Reload and review it before trying again.';
  }

  return error.message;
}