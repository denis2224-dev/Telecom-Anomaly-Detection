import { NotificationSettingsComponent } from './notification-settings.component';
import { DatePipe, DOCUMENT } from '@angular/common';
import { Component, ElementRef, ViewChild, inject } from '@angular/core';
import { RouterLink } from '@angular/router';
import { NotificationStore } from './notification.store';

@Component({
  selector: 'app-notification-center',
  providers: [NotificationStore],
  imports: [DatePipe, RouterLink, NotificationSettingsComponent],
  styles: [`
    dialog { color:var(--text); background:var(--surface,#fff); border:1px solid var(--border,#bbb); border-radius:12px; width:min(620px,calc(100vw - 32px)); max-height:85vh; padding:20px; }
    dialog::backdrop { background:#0006; } header,footer { display:flex; gap:12px; align-items:center; justify-content:space-between; }
    ul { list-style:none; padding:0; } li { padding:16px 0; border-bottom:1px solid var(--border,#bbb); overflow-wrap:anywhere; } li p { margin:6px 0; }
    button { min-height:44px; } .actions { display:flex; gap:12px; flex-wrap:wrap; align-items:center; }
  `],
  template: `
    <button #bell class="ghost" type="button" [attr.aria-label]="'Notifications, ' + store.unread() + ' unread'" aria-haspopup="dialog" (click)="show()">Notifications <span aria-hidden="true">{{ store.unread() }}</span></button>
    <dialog #panel aria-labelledby="notifications-title" (close)="closed()" (cancel)="closed()">
      <header><h2 id="notifications-title">Notification history</h2><button type="button" aria-label="Close notifications" (click)="hide()">Close</button></header>
      <p>Durable recipient history · {{ store.unread() }} unread · UTC. Recovery does not resolve analyst work.</p>
      <app-notification-settings />
      @if (store.busy()) { <p role="status">Loading notification history…</p> }
      @if (store.error()) { <p role="alert">{{ store.error() }}</p><button type="button" (click)="store.load()">Retry notification history</button> }
      @if (!store.busy() && !store.error() && !store.items().length) { <p>No recorded notifications.</p> }
      <ul>@for (item of store.items(); track item.id) {
        <li><strong>{{ item.severity }} · {{ item.kind }} · {{ item.readAt ? 'Read' : 'Unread' }}</strong>
          <p>{{ item.service }} · {{ item.scopeId }}</p><p>{{ item.summary }}</p>
          <p><time [attr.datetime]="item.occurredAt">{{ item.occurredAt | date:'dd MMM yyyy HH:mm:ss':'UTC' }} UTC</time></p>
          @if (item.causeStatus === 'HYPOTHESIS' && item.probableCause) { <p>Possible cause: {{ item.probableCause }} · confidence {{ item.causeConfidence ?? 'unavailable' }}</p> }
          @else { <p>Cause undetermined</p> }
          <div class="actions"><a [routerLink]="['/incidents',item.incidentId]" (click)="hide()">Investigate incident</a>
          @if (!item.readAt) { <button type="button" [disabled]="store.marking().has(item.id)" (click)="store.markRead(item.id)">Mark read</button> }</div>
        </li>
      }</ul>
      <footer><button type="button" [disabled]="store.page() === 0 || store.busy()" (click)="store.load(store.page()-1)">Newer</button><span>Page {{ store.page()+1 }} · {{ store.total() }} total</span><button type="button" [disabled]="(store.page()+1)*20 >= store.total() || store.busy()" (click)="store.load(store.page()+1)">Older</button></footer>
    </dialog>
  `,
})
export class NotificationCenterComponent {
  readonly store = inject(NotificationStore);
  private readonly document = inject(DOCUMENT);
  @ViewChild('panel') panel!: ElementRef<HTMLDialogElement>;
  @ViewChild('bell') bell!: ElementRef<HTMLButtonElement>;
  show(): void { this.store.open.set(true); this.panel.nativeElement.showModal(); void this.store.load(); }
  hide(): void { this.panel.nativeElement.close(); this.closed(); }
  closed(): void { this.store.open.set(false); if (this.document.contains(this.bell.nativeElement)) this.bell.nativeElement.focus(); }
}
