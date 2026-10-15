import { Component, inject } from '@angular/core';
import { PushNotificationsService } from './push-notifications.service';
@Component({
  selector:'app-notification-settings',
  template:`<section aria-label="Browser alert settings"><h3>Browser alerts</h3><p role="status">{{push.message()}}</p>
    @if(push.state()==='enabled'){<button type="button" (click)="push.disable()">Disable browser alerts</button>}
    @else{<button type="button" [disabled]="push.state()==='busy' || push.state()==='denied'" (click)="push.enable()">Enable browser alerts</button>}
  </section>`,
})
export class NotificationSettingsComponent { readonly push=inject(PushNotificationsService); }
