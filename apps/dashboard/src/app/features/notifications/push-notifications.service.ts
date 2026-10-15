import { DestroyRef, Injectable, inject, signal } from '@angular/core';
import { TelecomClient } from '../../core/api/telecom-client';
import { ApiFailure } from '../../core/api/api-errors';
import { SessionStore } from '../login-and-session/session.store';

export type BrowserAlertState = 'idle' | 'busy' | 'enabled' | 'denied' | 'unsupported' | 'insecure' | 'off' | 'error';
export function vapidBytes(key: string): Uint8Array<ArrayBuffer> {
  const raw = atob(key.replace(/-/g, '+').replace(/_/g, '/').padEnd(Math.ceil(key.length / 4) * 4, '='));
  const bytes = Uint8Array.from(raw, char => char.charCodeAt(0));
  if (bytes.length !== 65 || bytes[0] !== 4) throw new Error('Invalid browser-alert public key.');
  return bytes;
}

@Injectable({providedIn:'root'})
export class PushNotificationsService {
  private readonly api = inject(TelecomClient);
  private readonly session = inject(SessionStore);
  readonly state = signal<BrowserAlertState>('idle');
  readonly message = signal('Browser alerts are optional. In-app history remains available.');
  private request?: AbortController;
  private subscription?: PushSubscription;
  private subscriptionId?: string;
  constructor() {
    const ended = this.session.ended$.subscribe(() => {
      this.request?.abort();
      const subscription = this.subscription;
      this.subscription = undefined; this.subscriptionId = undefined;
      void subscription?.unsubscribe().catch(() => {});
      this.state.set('idle');
      this.message.set('Browser alerts are optional. In-app history remains available.');
    });
    inject(DestroyRef).onDestroy(() => { ended.unsubscribe(); this.request?.abort(); });
  }
  // Permission is requested only from the explicit enable button.
  async enable(): Promise<void> {
    if (this.session.phase() !== 'authenticated' || ['busy','enabled'].includes(this.state())) return;
    if (!globalThis.isSecureContext) { this.state.set('insecure'); this.message.set('Browser alerts require HTTPS or a secure local origin.'); return; }
    if (typeof Notification === 'undefined' || typeof PushManager === 'undefined' || !('serviceWorker' in navigator)) {
      this.state.set('unsupported'); this.message.set('This browser does not support browser alerts.'); return;
    }
    if (Notification.permission === 'denied') { this.state.set('denied'); this.message.set('Browser alerts are denied. Change permission in browser settings to enable them.'); return; }
    const revision = this.session.revision;
    const request = this.request = new AbortController();
    const active = () => !request.signal.aborted && revision === this.session.revision && this.session.phase() === 'authenticated';
    this.state.set('busy');
    let subscription: PushSubscription | undefined;
    try {
      const config = await this.api.notificationPushConfig(request.signal);
      if (!active()) return;
      if (!config.enabled || !config.vapidPublicKey) { this.state.set('off'); this.message.set('Browser alerts are disabled by the service.'); return; }
      const applicationServerKey = vapidBytes(config.vapidPublicKey);
      const permission = Notification.permission === 'granted' ? 'granted' : await Notification.requestPermission();
      if (!active()) return;
      if (permission !== 'granted') { this.state.set('denied'); this.message.set('Browser alerts were not enabled. In-app history remains available.'); return; }
      const worker = await navigator.serviceWorker.register('/push-worker.js', {scope:'/'});
      if (!active()) return;
      // Wait for activation without registering any offline cache.
      if (!worker.active) await navigator.serviceWorker.ready;
      if (!active()) return;
      subscription = await worker.pushManager.getSubscription() ?? await worker.pushManager.subscribe({userVisibleOnly:true,applicationServerKey});
      if (!active()) { await subscription.unsubscribe(); return; }
      const json = subscription.toJSON();
      if (!json.endpoint || !json.keys?.['p256dh'] || !json.keys?.['auth']) throw new Error('The browser returned an incomplete subscription.');
      const response = await this.api.savePushSubscription({endpoint:json.endpoint,p256dh:json.keys['p256dh'],auth:json.keys['auth'],
        expirationTime:json.expirationTime ? new Date(json.expirationTime).toISOString() : null}, request.signal);
      if (!active()) { await subscription.unsubscribe(); return; }
      // Deletion requires the server's identity; never fabricate it from an endpoint.
      const location = response.headers.get('Location');
      const id = location?.match(/^\/api\/notifications\/push-subscriptions\/([0-9a-f-]{36})$/i)?.[1];
      if (!id) throw new Error('The service did not return a removable subscription identity. Browser alerts remain disabled.');
      this.subscription = subscription; this.subscriptionId = id;
      this.state.set('enabled'); this.message.set('Browser alerts enabled. Lock-screen alerts contain only generic severity.');
    } catch (error) {
      await subscription?.unsubscribe().catch(() => {});
      if (active()) {
        this.state.set(error instanceof ApiFailure && error.status === 404 ? 'off' : 'error');
        this.message.set(error instanceof ApiFailure && error.status === 404 ? 'Browser alerts are unavailable in this environment.' : error instanceof Error ? error.message : 'Browser alerts could not be enabled.');
      }
    }
  }
  async disable(): Promise<void> {
    if (!this.subscription || !this.subscriptionId || this.state() === 'busy') return;
    const revision = this.session.revision, subscription = this.subscription;
    const request = this.request = new AbortController(); this.state.set('busy');
    try {
      await this.api.deletePushSubscription(this.subscriptionId, request.signal);
      if (request.signal.aborted || revision !== this.session.revision) return;
      if (!await subscription.unsubscribe()) throw new Error('The browser could not unsubscribe. Retry disabling alerts.');
      this.subscription = undefined; this.subscriptionId = undefined;
      this.state.set('idle'); this.message.set('Browser alerts disabled. In-app history remains available.');
    } catch (error) {
      if (!request.signal.aborted && revision === this.session.revision) {
        this.state.set('enabled'); this.message.set(error instanceof Error ? error.message : 'Alerts could not be disabled. Retry.');
      }
    }
  }
}
