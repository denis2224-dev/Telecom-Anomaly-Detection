import { TestBed } from '@angular/core/testing';
import { ActivatedRoute, convertToParamMap, provideRouter } from '@angular/router';
import { Subject } from 'rxjs';
import type { ParamMap } from '@angular/router';
import { TelecomClient } from '../../core/api/telecom-client';
import { voiceIncidents } from '../../../fixtures/voice';
import { IncidentStream } from '../../core/state/incident-stream';
import { IncidentDetailComponent } from './incident-detail.component';

describe('Incident investigation evidence', () => {
  const incident = voiceIncidents[0];
  const latest = incident.latestDetection;
  let params: Subject<ParamMap>;
  let api: { getIncident: ReturnType<typeof vi.fn>; getDetections: ReturnType<typeof vi.fn>; getTimeline: ReturnType<typeof vi.fn> };

  beforeEach(() => {
    params = new Subject<ParamMap>();
    api = {
      getTimeline: vi.fn().mockResolvedValue({ items: [], total: 0 }),
      getIncident: vi.fn().mockResolvedValue(incident),
      getDetections: vi.fn().mockResolvedValue({ items: [latest], total: 1 }),
    };
    TestBed.configureTestingModule({
      providers: [
        provideRouter([]),
        { provide: IncidentStream, useValue: { connect: vi.fn().mockReturnValue(() => {}) } },
        { provide: ActivatedRoute, useValue: { paramMap: params } },
        { provide: TelecomClient, useValue: api },
      ],
    });
  });

  async function open(id = incident.id) {
    const fixture = TestBed.createComponent(IncidentDetailComponent);
    params.next(convertToParamMap({ id }));
    await vi.waitFor(() => expect(fixture.componentInstance.loading()).toBe(false));
    fixture.detectChanges();
    return fixture;
  }

  it('loads all evidence pages and renders each update in sequence with its own cause', async () => {
    const earlier = {
      ...latest, detectionId: 'a'.repeat(64), sequence: 1, phase: 'UNKNOWN',
      probableCause: 'Earlier capacity hypothesis', causeConfidence: 'LOW',
      recommendedChecks: ['Inspect the earlier window'],
    };
    api.getDetections
      .mockResolvedValueOnce({ items: [latest], total: 2 })
      .mockResolvedValueOnce({ items: [earlier], total: 2 });
    const fixture = await open();

    expect(api.getDetections.mock.calls).toEqual([[incident.id, 0], [incident.id, 1]]);
    const updates = fixture.nativeElement.querySelectorAll('article');
    expect(updates).toHaveLength(2);
    expect(updates[0].textContent).toContain('Update 1');
    expect(updates[0].textContent).toContain('Earlier capacity hypothesis');
    expect(updates[0].textContent).toContain('does not prove recovery');
    expect(updates[1].textContent).toContain(`Update ${latest.sequence}`);
    expect(updates[1].textContent).toContain(latest.probableCause);
    expect(fixture.nativeElement.textContent).toContain('not a confirmed root cause');
  });

  it('shows an error for interrupted pagination and loads clean evidence on retry', async () => {
    api.getDetections
      .mockResolvedValueOnce({ items: [latest], total: 2 })
      .mockResolvedValueOnce({ items: [], total: 2 });
    const fixture = await open();

    expect(fixture.nativeElement.querySelector('[role="alert"]').textContent).toContain('Please retry');
    expect(fixture.componentInstance.incident()).toBeNull();
    expect(fixture.nativeElement.querySelectorAll('article')).toHaveLength(0);
    await fixture.componentInstance.load();
    fixture.detectChanges();
    expect(fixture.componentInstance.error()).toBe('');
    expect(fixture.nativeElement.querySelectorAll('article')).toHaveLength(1);
  });

  it('does not replace a newly selected incident with an older pending response', async () => {
    let finish!: (value: unknown) => void;
    api.getIncident.mockImplementation(async (id: string) => ({ ...incident, id }));
    api.getDetections.mockImplementation((id: string) => id === 'old'
      ? new Promise(resolve => { finish = resolve; })
      : Promise.resolve({ items: [latest], total: 1 }));
    const fixture = TestBed.createComponent(IncidentDetailComponent);
    params.next(convertToParamMap({ id: 'old' }));
    await vi.waitFor(() => expect(api.getDetections).toHaveBeenCalledWith('old', 0));
    params.next(convertToParamMap({ id: 'new' }));
    await vi.waitFor(() => expect(fixture.componentInstance.incident()?.id).toBe('new'));
    finish({ items: [], total: 0 });
    await Promise.resolve();
    expect(fixture.componentInstance.incident()?.id).toBe('new');
    expect(fixture.componentInstance.detections()).toEqual([latest]);
    expect(fixture.componentInstance.loading()).toBe(false);
  });

  it('renders an empty history without inventing evidence', async () => {
    api.getDetections.mockResolvedValue({ items: [], total: 0 });
    const fixture = await open();
    expect(fixture.nativeElement.textContent).toContain('No evidence updates available');
    expect(fixture.nativeElement.querySelectorAll('article')).toHaveLength(0);
    expect(fixture.componentInstance.incident()).toEqual(incident);
  });

  it('keeps partial evidence hidden while loading and after a later HTTP failure', async () => {
    let fail!: (reason: Error) => void;
    api.getDetections
      .mockResolvedValueOnce({ items: [latest], total: 2 })
      .mockImplementationOnce(() => new Promise((_resolve, reject) => { fail = reject; }));
    const fixture = TestBed.createComponent(IncidentDetailComponent);
    params.next(convertToParamMap({ id: incident.id }));
    await vi.waitFor(() => expect(api.getDetections).toHaveBeenCalledWith(incident.id, 1));
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('[role="status"]').textContent).toContain('Loading incident evidence');
    expect(fixture.nativeElement.querySelectorAll('article')).toHaveLength(0);
    expect(fixture.componentInstance.detections()).toEqual([]);
    fail(new Error('History is unavailable'));
    await vi.waitFor(() => expect(fixture.componentInstance.loading()).toBe(false));
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('[role="alert"]').textContent).toContain('History is unavailable');
    expect(fixture.nativeElement.querySelectorAll('article')).toHaveLength(0);
  });

  it('bounds pagination without publishing an incomplete timeline', async () => {
    api.getDetections.mockResolvedValue({ items: [latest], total: 101 });
    const fixture = await open();
    expect(api.getDetections).toHaveBeenCalledTimes(100);
    expect(fixture.componentInstance.error()).toContain('Too much evidence');
    expect(fixture.componentInstance.detections()).toEqual([]);
  });

  it('keeps technical recovery separate from workflow and links to the source service', async () => {
    const fixture = await open();
    const text = fixture.nativeElement.textContent as string;
    expect(text).toContain('Technical state: RECOVERED');
    expect(text).toContain('Workflow state: OPEN');
    expect(text).toContain(`Severity: ${incident.severity}`);
    expect(text).toContain('The service has recovered.');
    expect(fixture.nativeElement.querySelector('a').getAttribute('href')).toBe(`/services/${incident.scopeId}`);
  });

  it('refreshes an ongoing incident to show later recovery', async () => {
    api.getIncident
      .mockResolvedValueOnce({ ...incident, technicalState: 'ONGOING' })
      .mockResolvedValueOnce(incident);
    const fixture = await open();
    expect(fixture.nativeElement.textContent).toContain('The issue is still ongoing');
    const refresh = [...fixture.nativeElement.querySelectorAll('button')]
      .find(button => button.textContent.trim() === 'Refresh incident');
    expect(refresh).toBeDefined();
    refresh.click();
    await vi.waitFor(() => expect(fixture.componentInstance.incident()?.technicalState).toBe('RECOVERED'));
    fixture.detectChanges();
    expect(fixture.nativeElement.textContent).toContain('The service has recovered.');
  });
  it('refreshes an equal-version comment without hiding current evidence', async () => {
    const fixture = await open();
    const comment = { id: 'comment-1', action: 'COMMENT', note: 'Inspecting the IMS node',
      occurredAt: '2026-10-02T09:00:00Z', actorKind: 'ANALYST' };
    api.getTimeline.mockResolvedValue({ items: [comment], total: 1 });
    await fixture.componentInstance.load(true);
    fixture.detectChanges();
    expect(fixture.componentInstance.incident()?.version).toBe(incident.version);
    expect(fixture.nativeElement.textContent).toContain(comment.note);
    expect(fixture.componentInstance.detections()).toEqual([latest]);
  });

  it('keeps a newer action result when a background request returns an older version', async () => {
    const fixture = await open();
    fixture.componentInstance.incident.set({ ...incident, version: incident.version + 1 });
    await fixture.componentInstance.load(true);
    expect(fixture.componentInstance.incident()?.version).toBe(incident.version + 1);
    fixture.destroy();
  });

});
