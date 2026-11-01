import { ShiftHandoverComponent } from '../analyst-support/shift-handover.component';
import { nativeAnalystIncident, type AnalystIncident } from '../analyst-support/analyst-support.model';
import { TraceIncidentCardActionsComponent, nativeIncidentMetric } from './trace-incident-card-actions.component';
import { Component, DestroyRef, computed, effect, inject, input, output, signal, untracked, viewChild } from '@angular/core';
import { DrawerComponent } from '../../shared/drawer.component';
import { TraceEvidenceComponent } from './trace-evidence.component';
import { DatePipe } from '@angular/common';
import { RouterLink } from '@angular/router';
import { TelecomClient, type TracePartner } from '../../core/api/telecom-client';
import { ApiFailure } from '../../core/api/api-errors';
import type { components } from '../../core/api/schema';
import { IncidentStream } from '../../core/state/incident-stream';
import { TraceQueryStore, traceContextParameters, type TraceQueryContext } from '../../core/state/trace-query.store';
import { SessionStore } from '../login-and-session/session.store';
@Component({
    styles: [`
      .episode-heading{display:flex;align-items:center;justify-content:space-between;gap:var(--space-2)}h2{font-size:var(--font-size-subheading, 15px);margin:0}.incident-filters{display:flex;flex-wrap:wrap;gap:var(--space-2);margin:var(--space-2) 0}.incident-filters label{display:grid;gap:var(--space-1);font-size:var(--font-size-caption, 12px)}.incident-filters select{min-height:34px}.episode-card[data-severity=CRITICAL],.episode-card[data-severity=HIGH]{box-shadow:inset 2px 0 var(--danger)}.episode-card[data-technical-state=RECOVERED]{box-shadow:inset 2px 0 var(--success)}.episode-card[data-technical-state=UNKNOWN]{box-shadow:inset 2px 0 var(--warning)}.episode-header{display:flex;align-items:baseline;justify-content:space-between;gap:var(--space-2);flex-wrap:wrap}.episode-header h3{margin:0;display:flex;gap:var(--space-2);align-items:center;font-size:var(--font-size-subheading, 15px)}.episode-time,.episode-location,.episode-info{color:var(--text-muted);font-size:var(--font-size-caption, 12px)}.episode-info{display:flex;gap:var(--space-3);flex-wrap:wrap}.pagination{display:flex;gap:var(--space-2);align-items:center;flex-wrap:wrap}.queue-view{background:transparent;padding:0;margin:0}
header{display:flex;flex-wrap:wrap;align-items:center;gap:var(--space-2);}header time{margin-left:auto;color:var(--text-muted);}p{margin:0;}ul{list-style:none;padding:0;display:grid;gap:var(--space-2);}li{display:grid;gap:var(--space-2);padding:var(--space-4);background:var(--surface-raised);border-radius:var(--radius);box-shadow:inset 4px 0 var(--warning);font-size:var(--font-size-table, 13px);}`],
    selector: 'app-trace-incidents', imports: [ShiftHandoverComponent, RouterLink, DatePipe, TraceIncidentCardActionsComponent, DrawerComponent, TraceEvidenceComponent],
    templateUrl: './trace-incidents.component.html',
})
export class TraceIncidentsComponent {
    readonly snapshotChanged=output<{items:AnalystIncident[];total:number;loading:boolean;error:string}>();
    readonly incidentsChanged=output<components['schemas']['TraceLinkIncidentView'][]>();
    readonly queueView=input(false);
    readonly severityFilter=input('');
    readonly stateFilter=input('');
    readonly severity=signal('');
    readonly state=signal('');
    readonly rows=computed(()=>this.items().filter(item=>(!(this.severityFilter()||this.severity()) || item.severity===(this.severityFilter()||this.severity()))
      && (!(this.stateFilter()||this.state()) || item.technicalState===(this.stateFilter()||this.state()))));
    readonly context = input<TraceQueryContext | null>(null);
    readonly partners = input<readonly TracePartner[]>([]);
    readonly selectedContext = computed(() => this.context() ?? this.query.context());
    readonly selected = signal<components['schemas']['TraceLinkIncidentView'] | null>(null);
    readonly evidenceDrawer = viewChild<DrawerComponent>('evidenceDrawer');
    openEvidence(item: components['schemas']['TraceLinkIncidentView']): void { this.selected.set(item); this.evidenceDrawer()?.open(); }
    workflowStatus(id: string): string { return this.workflows().get(id)?.status ?? 'Unavailable'; }
    population(item: components['schemas']['TraceLinkIncidentView']): string {
        const scope = item.latestDetection.context['scope'] as Record<string, unknown> | undefined, context = this.selectedContext();
        const country = this.partners().find(partner => partner.id === scope?.['partnerId'] && partner.direction === scope?.['directionId'])?.country
            ?? scope?.['countryId'] ?? (context.domain === 'roaming' && context.countryId !== 'all' ? context.countryId : null);
        return [country, scope?.['partnerId'] ?? scope?.['id'], scope?.['directionId'], scope?.['procedure']].filter(value => typeof value === 'string').join(' · ');
    }
    title(item: components['schemas']['TraceLinkIncidentView']): string { const domain = String(item.latestDetection.context['domain'] ?? 'Service'); return domain === 'ROAMING' ? 'Roaming ' + String((item.latestDetection.context['scope'] as Record<string,unknown>)?.['procedure']??'procedure').toLowerCase() + ' success drop' : domain === 'VOLTE' ? 'VoLTE anomaly' : domain === 'SMS' ? 'SMS anomaly' : domain.replaceAll('_', ' ') + ' anomaly'; }
    description(item: components['schemas']['TraceLinkIncidentView']): string { const cause = item.latestDetection.cause; return ['HYPOTHESIS','SUPPORTED_HYPOTHESIS'].includes(String(cause['status'])) && typeof cause['probableCause'] === 'string' ? 'Possible cause: ' + cause['probableCause'] : 'Cause undetermined'; }
    measurement(item:components['schemas']['TraceLinkIncidentView']):string {
      const evidence=item.latestDetection.context['evidence'];
      const value=Array.isArray(evidence)?evidence[0] as Record<string,unknown>:undefined;
      if(typeof value?.['actual']!=='number')return 'Latest measurement unavailable';
      const format=(number:number)=>number.toLocaleString('en',{maximumFractionDigits:2});
      return `${format(value['actual'])}${value['unit']==='PERCENT'?'%':''}`
        +(typeof value['baseline']==='number'?` · Baseline ${format(value['baseline'])}% · ${format(Number(value['actual'])-value['baseline'])} pp`:'');
    }
    private readonly api = inject(TelecomClient);
    private readonly session = inject(SessionStore);
    private readonly query = inject(TraceQueryStore);
    private readonly stream = inject(IncidentStream);
    private readonly workflows = signal(new Map<string, components['schemas']['TraceIncidentWorkflow']>());
    acceptWorkflow(state: components['schemas']['TraceIncidentWorkflow']): void {
        if (!this.items().some(item => item.episodeId === state.episodeId))
            return;
        this.workflows.update(rows => { if ((rows.get(state.episodeId)?.version ?? -1) > state.version)
            return rows; return new Map(rows).set(state.episodeId, state); });
    }
    readonly handoverItems = computed(() => this.rows().map(item => nativeAnalystIncident(item, this.workflows().get(item.episodeId)?.status ?? null)));
    readonly queryLabel = computed(() => { const context = this.selectedContext(); return ['Archive ' + context.datasetId, context.domain, context.cityId === 'all' ? 'All cities' : context.cityId, context.countryId !== 'all' ? context.countryId : null, context.domain === 'roaming' ? 'Direction ' + context.roamingDirection : null, context.linkDirectionId, context.partnerDirectionId].filter(Boolean).join(' · '); });
    readonly rangeFrom = computed(() => this.selectedContext().visibleFrom ?? this.selectedContext().from);
    readonly rangeTo = computed(() => this.selectedContext().visibleTo ?? this.selectedContext().to);
    readonly items = signal<components['schemas']['TraceLinkIncidentView'][]>([]);
    readonly total = signal(0);
    readonly page = signal(0);
    readonly busy = signal(false);
    readonly error = signal('');
    readonly notEnabled = signal(false);
    private request?: AbortController;
    private timer?: ReturnType<typeof setTimeout>;
    private generation = 0;
    parameters(item?: components['schemas']['TraceLinkIncidentView']) { return { ...traceContextParameters(this.selectedContext()), metric: item ? nativeIncidentMetric(item) : undefined }; }
    constructor() {
        effect(()=>this.incidentsChanged.emit(this.items()));
        effect(()=>{if(this.queueView())this.snapshotChanged.emit({items:this.handoverItems(),total:this.total(),loading:this.busy(),error:this.error()});});
        effect(cleanup => {
            this.selectedContext();
            this.session.phase();
            this.session.actor();
            untracked(() => { if (this.selected())
                this.evidenceDrawer()?.close(); this.selected.set(null); this.page.set(0); void this.load(); });
            if (this.session.phase() === 'authenticated')
                cleanup(this.stream.connect(() => this.refresh(), () => this.error.set('Live connection interrupted. Refresh to reconcile committed incidents.'), () => this.refresh()));
        });
        inject(DestroyRef).onDestroy(() => { this.generation++; this.request?.abort(); clearTimeout(this.timer); });
    }
    private refresh() { clearTimeout(this.timer); this.timer = setTimeout(() => void this.load(), 150); }
    move(delta: number) { this.page.update(value => Math.max(0, value + delta)); void this.load(); }
    async load(): Promise<void> {
        this.request?.abort();
        const generation = ++this.generation, revision = this.session.revision;
        this.items.set([]);
        this.workflows.set(new Map());
        this.total.set(0);
        this.error.set('');
        this.notEnabled.set(false);
        this.busy.set(false);
        if (this.session.phase() !== 'authenticated' || !this.selectedContext().datasetId)
            return;
        const context = this.selectedContext(), request = this.request = new AbortController();
        this.busy.set(true);
        try {
            const result = await this.api.traceIncidents({ datasetId: context.datasetId, domain: context.domain, cityId: context.cityId, linkDirectionId: context.linkDirectionId, countryId: context.countryId, roamingDirection: context.roamingDirection, from: context.visibleFrom ?? context.from, to: context.visibleTo ?? context.to, limit: 50, offset: this.page() * 50 }, request.signal);
            if (request.signal.aborted || revision !== this.session.revision || context !== this.selectedContext() || generation !== this.generation)
                return;
            if (result.items.length > 50 || result.offset !== this.page() * 50)
                throw new Error('Incident history does not match this bounded page.');
            this.items.set(result.items.filter(item => context.domain !== 'roaming' || !context.partnerDirectionId || this.partnerMatches(item, context.partnerDirectionId)));
            this.total.set(result.total);
        }
        catch (error) {
            if (!request.signal.aborted && generation === this.generation) {
                if (error instanceof ApiFailure && error.status === 404)
                    this.notEnabled.set(true);
                else
                    this.error.set(error instanceof Error ? error.message : 'Incident history unavailable.');
            }
        }
        finally {
            if (generation === this.generation)
                this.busy.set(false);
        }
    }
    private partnerMatches(item: components['schemas']['TraceLinkIncidentView'], partner: string): boolean { const scope = item.latestDetection.context['scope'] as Record<string, unknown> | undefined; return !!scope && `${scope['partnerId']}:${scope['directionId']}` === partner; }
}
