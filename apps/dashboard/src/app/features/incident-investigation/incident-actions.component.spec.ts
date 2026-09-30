import { TestBed } from '@angular/core/testing';
import { IncidentActionsComponent } from './incident-actions.component';
import { TelecomClient } from '../../core/api/telecom-client';
import { SessionStore } from '../login-and-session/session.store';
import { ApiFailure } from '../../core/api/api-errors';
import { voiceIncidents } from '../../../fixtures/voice';

describe('Day 9 incident actions', () => {
  it('keeps a note after a conflict and does not replay the write', async () => {
    const incident = {
      ...structuredClone(voiceIncidents[0]),
      status: 'INVESTIGATING' as const,
      technicalState: 'RECOVERED' as const,
      assigneeId: 'analyst-1',
      version: 7,
    };

    const changeStatus = vi.fn().mockRejectedValue(
      new ApiFailure(409, 'VERSION_CONFLICT'),
    );

    const api = {
      listAnalysts: vi.fn().mockResolvedValue([
        {
          id: 'analyst-1',
          displayName: 'Analyst One',
          enabled: true,
        },
      ]),
      changeStatus,
      getIncident: vi.fn().mockResolvedValue({
        ...incident,
        version: 8,
      }),
    };

    const actor = {
      analystId: 'analyst-1',
      displayName: 'Analyst One',
      roles: ['ANALYST' as const],
      expiresAt: new Date(
        Date.now() + 60000,
      ).toISOString(),
    };

    TestBed.configureTestingModule({
      providers: [
        { provide: TelecomClient, useValue: api },
        {
          provide: SessionStore,
          useValue: { actor: () => actor },
        },
      ],
    });

    const fixture = TestBed.createComponent(
      IncidentActionsComponent,
    );

    fixture.componentRef.setInput('incident', incident);
    fixture.detectChanges();
    await fixture.whenStable();

    fixture.componentInstance.resolutionNote.set(
      'Verified service recovery',
    );

    await fixture.componentInstance.changeStatus('RESOLVED');
    fixture.detectChanges();

    expect(changeStatus).toHaveBeenCalledExactlyOnceWith(
      incident.id,
      {
        status: 'RESOLVED',
        version: 7,
        resolutionNote: 'Verified service recovery',
      },
    );

    expect(
      fixture.componentInstance.resolutionNote(),
    ).toBe('Verified service recovery');

    expect(
      fixture.nativeElement.textContent,
    ).toContain('Reload and review');

    expect(api.getIncident).not.toHaveBeenCalled();
  });
});