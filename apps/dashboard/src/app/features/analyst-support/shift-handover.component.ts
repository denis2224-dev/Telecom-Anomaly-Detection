import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { SessionStore } from '../login-and-session/session.store';
import { Component, inject, input, signal, viewChild } from '@angular/core';
import { DrawerComponent } from '../../shared/drawer.component';
import { handoverText, type AnalystIncident } from './analyst-support.model';

@Component({
  selector:'app-shift-handover',imports:[DrawerComponent],
  styles:[`:host{display:block;margin:10px 0;}button{font-size:12px;min-height:36px;}textarea{box-sizing:border-box;width:100%;min-height:50vh;padding:12px;font:12px/1.6 var(--font-family);resize:vertical;}p{font-size:12px;color:var(--text-muted);}nav{display:flex;flex-wrap:wrap;gap:8px;}`],
  template:`<button type="button" [disabled]="loading()||!!error()" (click)="capture();drawer.open()">Shift handover</button>
    <app-drawer #drawer drawerId="shift-handover" title="Shift handover" closeLabel="Close shift handover">
      <p>Snapshot of the current view · {{sample()?'Sample data':'Recorded incidents'}}</p>
      @if(error()){<p role="alert">Refresh failed. This snapshot has not changed.</p>}
      <nav aria-label="Handover actions"><button type="button" [disabled]="loading()||!!error()" (click)="capture()">Refresh snapshot</button><button type="button" (click)="copy()">Copy summary</button><button type="button" (click)="download()">Download summary</button></nav>
      <p role="status">{{message()}}</p><label>Handover summary<textarea readonly [value]="summary()" aria-label="Handover summary"></textarea></label>
    </app-drawer>`,
})
export class ShiftHandoverComponent {
  readonly items=input.required<AnalystIncident[]>();readonly total=input(0);readonly atLeast=input(false);readonly scope=input('Current queue view');
  readonly from=input('');readonly to=input('');readonly sample=input(false);readonly loading=input(false);readonly error=input('');
  readonly summary=signal('');readonly message=signal('');readonly drawer=viewChild<DrawerComponent>('drawer');
  private readonly session=inject(SessionStore);
  constructor(){this.session.ended$?.pipe(takeUntilDestroyed()).subscribe(()=>{this.summary.set('');this.message.set('');this.drawer()?.close();});}
  capture(){
    const coverage=`${this.items().length} shown · ${this.atLeast()?'at least ':''}${this.total()} episodes in the current queue${this.items().length<this.total()?' · partial snapshot':''}${this.sample()?' · SAMPLE DATA':''}`;
    this.summary.set(handoverText(this.items(),this.scope(),this.from(),this.to(),coverage,new Date().toISOString()));this.message.set('');
  }
  async copy(){const revision=this.session.revision;try{await navigator.clipboard.writeText(this.summary());if(revision===this.session.revision)this.message.set('Summary copied.');}catch{if(revision===this.session.revision)this.message.set('Clipboard unavailable. Select and copy the summary below.');}}
  download(){const url=URL.createObjectURL(new Blob([this.summary()],{type:'text/plain;charset=utf-8'}));const link=document.createElement('a');link.href=url;link.download='trace-link-handover.txt';link.click();setTimeout(()=>URL.revokeObjectURL(url),1000);}
}
