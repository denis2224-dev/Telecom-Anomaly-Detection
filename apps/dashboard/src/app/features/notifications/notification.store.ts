import { DestroyRef, Injectable, effect, inject, signal, untracked } from '@angular/core';
import { TelecomClient, type NotificationItem } from '../../core/api/telecom-client';
import { IncidentStream } from '../../core/state/incident-stream';
import { SessionStore } from '../login-and-session/session.store';
import { ToastService } from '../../shared/toast.service';

const SEEN_LIMIT = 500;

@Injectable({ providedIn: 'root' })
export class NotificationStore {
  private readonly api = inject(TelecomClient);
  private readonly session = inject(SessionStore);
  private readonly stream = inject(IncidentStream);
  private readonly toast = inject(ToastService);
  readonly items = signal<NotificationItem[]>([]);
  readonly total = signal(0);
  readonly unread = signal(0);
  readonly page = signal(0);
  readonly busy = signal(false);
  readonly error = signal('');
  readonly open = signal(false);
  readonly marking = signal<ReadonlySet<string>>(new Set());
  private seen = new Set<string>();
  private latestPage = new Set<string>();
  private initialized = false;
  private generation = 0;
  private request?: AbortController;
  private writes = new Set<AbortController>();
  private channel?: BroadcastChannel;
  private disconnect?: () => void;
  private claims = new Map<string, string>();
  private timers = new Set<ReturnType<typeof setTimeout>>();

  constructor() {
    const ended = this.session.ended$.subscribe(() => this.clear());
    effect(cleanup => {
      const phase = this.session.phase(), actor = this.session.actor()?.analystId;
      if (phase !== 'authenticated' || !actor) return;
      untracked(() => {
        this.clear();
        if (typeof BroadcastChannel !== 'undefined') {
          this.channel = new BroadcastChannel('trace-link-notifications');
          this.channel.onmessage = event => {
            const hint = event.data;
            if (hint?.actor !== actor || typeof hint.id !== 'string' || hint.id.length > 64 || typeof hint.token !== 'string' || hint.token.length > 64) return;
            if (hint.kind === 'shown') {
              this.seen.add(hint.id);this.claims.delete(hint.id);
              while (this.seen.size > SEEN_LIMIT) this.seen.delete(this.seen.values().next().value!);
              return;
            }
            if(hint.kind !== 'claim')return;
            this.claims.set(hint.id, [this.claims.get(hint.id) ?? hint.token, hint.token].sort()[0]);
            while (this.claims.size > SEEN_LIMIT) this.claims.delete(this.claims.keys().next().value!);
          };
        }
        void this.load(0);
      });
      const disconnect = this.disconnect = this.stream.connect(() => { void this.load(0, true); }, () => {});
      cleanup(() => { disconnect(); this.clear(); });
    });
    inject(DestroyRef).onDestroy(() => { ended.unsubscribe(); this.disconnect?.(); this.clear(); });
  }

  async load(page = this.page(), hints = false): Promise<void> {
    if (this.session.phase() !== 'authenticated') return;
    const revision = this.session.revision, generation = ++this.generation;
    this.request?.abort();
    const request = this.request = new AbortController();
    this.busy.set(true); this.error.set('');
    try {
      const result = await this.api.listNotifications(page, request.signal);
      if (!this.active(revision) || generation !== this.generation || request.signal.aborted) return;
      const items = [...new Map(result.items.slice(0, 20).map(item => [item.id, item])).values()]
        .sort((a, b) => b.occurredAt.localeCompare(a.occurredAt) || b.id.localeCompare(a.id));
      if (this.initialized && hints) {
        for (const item of items) if (!this.seen.has(item.id) && !this.latestPage.has(item.id) && item.readAt === null
            && ['OPEN', 'ESCALATED'].includes(item.kind) && ['HIGH', 'CRITICAL'].includes(item.severity)) {
          this.announce(item, revision);
        }
      }
      for (const item of items) this.seen.add(item.id);
      if(result.page===0)this.latestPage=new Set(items.map(item=>item.id));
      while (this.seen.size > SEEN_LIMIT) this.seen.delete(this.seen.values().next().value!);
      this.initialized = true;
      this.items.set(items); this.total.set(result.total); this.unread.set(result.unreadCount); this.page.set(result.page);
    } catch (error) {
      if (this.active(revision) && generation === this.generation && !request.signal.aborted)
        this.error.set(error instanceof Error ? error.message : 'Notification history is unavailable. Retry to reconcile.');
    } finally {
      if (generation === this.generation) this.busy.set(false);
    }
  }

  async markRead(id: string): Promise<void> {
    if (this.session.phase() !== 'authenticated' || this.marking().has(id)) return;
    const revision = this.session.revision, request = new AbortController();
    this.writes.add(request); this.marking.update(ids => new Set([...ids, id])); this.error.set('');
    try {
      await this.api.markNotificationRead(id, request.signal);
      if (this.active(revision) && !request.signal.aborted) await this.load(this.page());
    } catch (error) {
      if (this.active(revision) && !request.signal.aborted)
        this.error.set(error instanceof Error ? error.message : 'Read state was not saved. Retry the action.');
    } finally {
      this.writes.delete(request);
      if (this.active(revision)) this.marking.update(ids => new Set([...ids].filter(item => item !== id)));
    }
  }

  private announce(item: NotificationItem, revision: number): void {
    const actor = this.session.actor()!.analystId, token = crypto.randomUUID();
    this.claims.set(item.id, [this.claims.get(item.id) ?? token, token].sort()[0]);
    this.channel?.postMessage({ kind:'claim', actor, id: item.id, token });
    const timer = setTimeout(() => {
      this.timers.delete(timer);
      if (this.active(revision) && this.claims.get(item.id) === token) {
        this.channel?.postMessage({kind:'shown',actor,id:item.id,token});
        this.toast.show(`${item.severity} ${item.kind === 'ESCALATED' ? 'escalation' : 'incident opened'} · ${item.service}. Review notification history.`);
      }
      this.claims.delete(item.id);
    }, 200);
    this.timers.add(timer);
  }

  private active(revision: number): boolean {
    return this.session.phase() === 'authenticated' && revision === this.session.revision;
  }
  private clear(): void {
    ++this.generation; this.request?.abort(); this.request = undefined;
    for (const request of this.writes) request.abort(); this.writes.clear();
    for (const timer of this.timers) clearTimeout(timer); this.timers.clear();
    this.channel?.close(); this.channel = undefined; this.claims.clear(); this.seen.clear(); this.latestPage.clear(); this.initialized = false;
    this.items.set([]); this.total.set(0); this.unread.set(0); this.page.set(0); this.error.set(''); this.busy.set(false);
    this.open.set(false); this.marking.set(new Set());
    this.toast.current.set(null);
  }
}
