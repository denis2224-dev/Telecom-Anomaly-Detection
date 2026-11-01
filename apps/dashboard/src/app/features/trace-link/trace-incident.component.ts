import { IncidentAssistComponent } from '../analyst-support/incident-assist.component';
import { nativeAnalystIncident } from '../analyst-support/analyst-support.model';
import { TraceIncidentCardActionsComponent, nativeIncidentMetric, nativeIncidentContext } from './trace-incident-card-actions.component';
import { TraceEvidenceComponent } from './trace-evidence.component';
import { Component, DestroyRef, computed, inject, signal } from '@angular/core';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { TelecomClient } from '../../core/api/telecom-client';
import type { components } from '../../core/api/schema';
import { IncidentStream } from '../../core/state/incident-stream';
import { TraceQueryStore, traceContextParameters } from '../../core/state/trace-query.store';
import { SessionStore } from '../login-and-session/session.store';
type NativeIncident = components['schemas']['TraceLinkIncidentView'];
type Workflow = components['schemas']['TraceIncidentWorkflow'];
const object = (value: unknown): Record<string, unknown> => value !== null && typeof value === 'object' && !Array.isArray(value) ? value as Record<string, unknown> : {};
@Component({
    selector: 'app-trace-incident', imports: [IncidentAssistComponent, RouterLink, TraceIncidentCardActionsComponent, TraceEvidenceComponent],
    styleUrl: './trace-incident.component.css',
    templateUrl: './trace-incident.component.html',
})
export class TraceIncidentComponent {
    readonly analystItem = computed(() => { const item = this.incident(); return item ? nativeAnalystIncident(item, this.workflow()?.status ?? null) : null; });
    readonly session = inject(SessionStore);
    readonly query = inject(TraceQueryStore);
    private readonly api = inject(TelecomClient);
    private readonly stream = inject(IncidentStream);
    readonly returnMetric = signal('');
    readonly contextParameters = computed(() => ({ ...traceContextParameters(this.query.context()), metric: this.returnMetric() || (this.incident() ? nativeIncidentMetric(this.incident()!) : undefined) }));
    readonly incident = signal<NativeIncident | null>(null);
    readonly workflow = signal<Workflow | null>(null);
    readonly auditPage = signal(0);
    readonly audit = signal<components['schemas']['TraceIncidentAudit'][]>([]);
    readonly analysts = signal<components['schemas']['AnalystSummary'][]>([]);
    readonly target = signal('');
    readonly comment = signal('');
    readonly note = signal('');
    readonly busy = signal(false);
    readonly changing = signal(false);
    readonly error = signal('');
    readonly privileged = computed(() => this.session.actor()?.roles.some(role => role === 'SUPERVISOR' || role === 'ADMIN') ?? false);
    readonly context = computed(() => object(this.incident()?.latestDetection.context));
    readonly domain = computed(() => String(this.context()['domain'] ?? 'Unavailable'));
    readonly scope = computed(() => String(object(this.context()['scope'])['id'] ?? 'Unavailable'));
    readonly domainLabel = computed(() => { const names: Record<string, string> = { VOLTE: 'VoLTE', SMS: 'SMS', ROAMING: 'Roaming', TRANSPORT: 'Transport', MOBILE_DATA: 'Mobile data' }; return names[this.domain()] ?? 'Service'; });
    readonly title=computed(()=>this.domain()==='ROAMING'?'Roaming '+String(object(this.context()['scope'])['procedure']??'procedure').toLowerCase()+' success drop':this.domainLabel()+' anomaly');
    readonly cause = computed(() => { const cause = object(this.incident()?.latestDetection.cause); return ['HYPOTHESIS','SUPPORTED_HYPOTHESIS'].includes(String(cause['status'])) && cause['causeConfidence'] ? `Possible cause: ${cause['probableCause']} · ${cause['causeConfidence']}` : 'Cause undetermined'; });
    private episode = '';
    private generation = 0;
    private request?: AbortController;
    private stop?: () => void;
    private timer?: ReturnType<typeof setTimeout>;
    constructor() {
        const destroy = inject(DestroyRef), route = inject(ActivatedRoute);
        route.queryParams?.pipe(takeUntilDestroyed(destroy)).subscribe(params => { this.returnMetric.set(typeof params['metric'] === 'string' ? params['metric'] : ''); if (Object.keys(params).length && !this.query.restore(params))
            this.error.set('Invalid investigation query context.'); });
        route.paramMap.pipe(takeUntilDestroyed(destroy)).subscribe(params => { this.episode = params.get('episodeId') ?? ''; this.auditPage.set(0); this.incident.set(null); void this.load(); });
        const ended = this.session.ended$.subscribe(() => { this.request?.abort(); this.stop?.(); this.incident.set(null); this.workflow.set(null); this.audit.set([]); this.analysts.set([]); this.comment.set(''); this.note.set(''); this.target.set(''); this.busy.set(false); this.changing.set(false); clearTimeout(this.timer); this.generation++; });
        destroy.onDestroy(() => { this.request?.abort(); this.stop?.(); clearTimeout(this.timer); ended.unsubscribe(); this.generation++; });
    }
    async load(): Promise<void> {
        if (this.session.phase() !== 'authenticated')
            return;
        this.request?.abort();
        const request = this.request = new AbortController(), generation = ++this.generation, revision = this.session.revision, episode = this.episode;
        this.busy.set(true);
        this.error.set('');
        try {
            const [incident, workflow, audit, analysts] = await Promise.all([this.api.traceIncident(episode, request.signal), this.api.traceIncidentWorkflow(episode, request.signal), this.api.traceIncidentAudit(episode, this.auditPage() * 50, request.signal), this.api.listAnalysts()]);
            if (request.signal.aborted || generation !== this.generation || revision !== this.session.revision)
                return;
            if (incident.episodeId !== episode || workflow.episodeId !== episode || audit.length > 50)
                throw new Error('Incident response does not match the selected bounded investigation.');
            this.incident.set(incident);
            this.workflow.set(workflow);
            this.audit.set(audit);
            this.analysts.set(analysts.filter(actor => actor.enabled));
            if (!this.stop)
                this.stop = this.stream.connect(() => this.refresh(), () => this.error.set('Live connection interrupted. Retry refresh to reconcile committed evidence.'), () => this.refresh());
        }
        catch (error) {
            if (!request.signal.aborted && generation === this.generation)
                this.error.set(error instanceof Error ? error.message : 'Incident unavailable.');
        }
        finally {
            if (generation === this.generation)
                this.busy.set(false);
        }
    }
    private refresh(): void { clearTimeout(this.timer); this.timer = setTimeout(() => { if (this.changing() || this.busy())
        this.refresh();
    else
        void this.load(); }, 150); }
    private async action(run: (state: Workflow, signal: AbortSignal) => Promise<Workflow>): Promise<void> {
        const state = this.workflow();
        if (!state || this.changing())
            return;
        const revision = this.session.revision, generation = this.generation, episode = this.episode;
        const request = this.request = new AbortController();
        this.changing.set(true);
        this.error.set('');
        try {
            await run(state, request.signal);
            if (request.signal.aborted || revision !== this.session.revision || episode !== this.episode || generation !== this.generation)
                return;
            await this.load();
        }
        catch (error) {
            if (!request.signal.aborted && revision === this.session.revision)
                this.error.set(error instanceof Error ? error.message : 'Workflow update failed. Refresh and retry.');
        }
        finally {
            if (revision === this.session.revision)
                this.changing.set(false);
        }
    }
    assign(id: string): void { if (id)
        void this.action((state, signal) => this.api.assignTraceIncident(this.episode, { assigneeId: id, version: state.version }, signal)); }
    changeStatus(status: 'INVESTIGATING' | 'RESOLVED'): void { void this.action((state, signal) => this.api.updateTraceIncident(this.episode, { status, version: state.version, resolutionNote: status === 'RESOLVED' ? this.note() : null }, signal)); }
    addComment(): void { void this.action((state, signal) => this.api.commentTraceIncident(this.episode, { version: state.version, note: this.comment() }, signal)); }
    time(value: string): string { return new Intl.DateTimeFormat('en-GB', { timeZone: this.query.context().timezone, dateStyle: 'medium', timeStyle: 'long' }).format(new Date(value)); }
    focusContext(): void {
        const item = this.incident();
        if (!item)
            return;
        try {
            this.query.update({ ...nativeIncidentContext(item, this.query.context()), from: item.firstObservedAt, to: item.latestWindowEnd });
        }
        catch (error) {
            this.error.set(error instanceof Error ? error.message : 'Incident context unavailable.');
        }
    }
}
