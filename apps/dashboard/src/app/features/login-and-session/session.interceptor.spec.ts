import { TestBed } from '@angular/core/testing';
import {
  HttpClient,
  provideHttpClient,
  withInterceptors,
} from '@angular/common/http';
import {
  HttpTestingController,
  provideHttpClientTesting,
} from '@angular/common/http/testing';
import { firstValueFrom } from 'rxjs';
import { SessionStore } from './session.store';
import { sessionInterceptor } from './session.interceptor';

describe('Session request lifecycle', () => {
  let client: HttpClient;
  let http: HttpTestingController;
  let session: SessionStore;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(
          withInterceptors([sessionInterceptor]),
        ),
        provideHttpClientTesting(),
      ],
    });

    client = TestBed.inject(HttpClient);
    http = TestBed.inject(HttpTestingController);
    session = TestBed.inject(SessionStore);

    session.phase.set('authenticated');
    session.actor.set({
      analystId: 'test-analyst',
      displayName: 'Test analyst',
      roles: ['ADMIN'],
      expiresAt: new Date(
        Date.now() + 60000,
      ).toISOString(),
    });

    session.csrf.set({
      token: 'test-csrf',
      headerName: 'X-CSRF-TOKEN',
      parameterName: '_csrf',
    });
  });

  afterEach(() => {
    http.verify();
    TestBed.resetTestingModule();
  });

  it('expires on 401, cancels another request, and blocks later work', async () => {
    const first = firstValueFrom(
      client.get('/api/services'),
    );

    const rejected = expect(first).rejects.toMatchObject({
      status: 401,
    });

    const second = firstValueFrom(
      client.get('/api/incidents'),
    ).catch(() => null);

    const services = http.expectOne('/api/services');
    const incidents = http.expectOne('/api/incidents');

    services.flush(
      { code: 'UNAUTHENTICATED' },
      { status: 401, statusText: 'Unauthorized' },
    );

    await rejected;
    await second;

    expect(incidents.cancelled).toBe(true);
    expect(session.phase()).toBe('expired');
    expect(session.actor()).toBeNull();
    expect(session.csrf()).toBeNull();

    await expect(
      firstValueFrom(client.get('/api/services')),
    ).rejects.toMatchObject({ status: 401 });

    http.expectNone('/api/services');
  });

  it('sends returned CSRF settings and never replays a rejected write', async () => {
    const result = firstValueFrom(
      client.post('/api/incidents/example/status', {
        status: 'INVESTIGATING',
        version: 1,
      }),
    );

    const rejected = expect(result).rejects.toMatchObject({
      status: 403,
    });

    const request = http.expectOne(
      '/api/incidents/example/status',
    );

    expect(
      request.request.headers.get('X-CSRF-TOKEN'),
    ).toBe('test-csrf');

    request.flush(
      { code: 'CSRF_INVALID' },
      { status: 403, statusText: 'Forbidden' },
    );

    await rejected;

    expect(session.csrf()).toBeNull();
    expect(session.phase()).toBe('authenticated');

    http.expectNone('/api/incidents/example/status');
    http.expectNone('/api/auth/csrf');
  });
});