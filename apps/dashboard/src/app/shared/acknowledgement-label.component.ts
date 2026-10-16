import { Component, Injectable, effect, inject, input, signal } from '@angular/core';
import { DatePipe } from '@angular/common';

export function incidentReferencePrefix(domain: string): string {
  const service = domain.toUpperCase();
  return ({VOLTE:'VOL',SMS:'SMS',ROAMING:'ROAM',TRANSPORT:'NET'} as Record<string,string>)[service] ?? service.slice(0,3);
}

@Injectable({providedIn:'root'})
export class AcknowledgementLabels {
  private database?: Promise<IDBDatabase>;
  private readonly pending = new Map<string,Promise<string | null>>();
  forIncident(incident: string): Promise<string | null> {
    const pending = this.pending.get(incident);
    if (pending) return pending;
    const request = this.allocate(incident).catch(() => null).finally(() => this.pending.delete(incident));
    this.pending.set(incident,request);
    return request;
  }
  private async allocate(incident: string): Promise<string> {
    this.database ??= new Promise((resolve,reject) => {
      const request = indexedDB.open('trace-link-display-labels',1);
      request.onupgradeneeded = () => request.result.createObjectStore('acknowledgements',{autoIncrement:true}).createIndex('incident','incident',{unique:true});
      request.onsuccess = () => resolve(request.result);
      request.onerror = () => reject(request.error);
      request.onblocked = () => reject(new Error('Display labels unavailable.'));
    });
    const database = await this.database;
    return new Promise((resolve,reject) => {
      const transaction = database.transaction('acknowledgements','readwrite');
      const store = transaction.objectStore('acknowledgements');
      let number: IDBValidKey | undefined;
      const lookup = store.index('incident').getKey(incident);
      lookup.onsuccess = () => {
        if (lookup.result !== undefined) number = lookup.result;
        else { const add = store.add({incident}); add.onsuccess = () => number = add.result; }
      };
      transaction.oncomplete = () => resolve(String(number).padStart(3,'0'));
      transaction.onerror = transaction.onabort = () => reject(transaction.error);
    });
  }
}

@Component({
  selector:'app-acknowledgement-label', imports:[DatePipe],
  template:`<span class="acknowledgement-label">{{ label() ? prefix() + '-' + label() : 'Acknowledged' }} · {{ at() | date:'dd MMM HH:mm':'UTC' }} UTC</span>`,
})
export class AcknowledgementLabelComponent {
  readonly incidentId = input.required<string>();
  readonly prefix = input.required<string>();
  readonly at = input.required<string>();
  readonly label = signal<string | null>(null);
  private readonly labels = inject(AcknowledgementLabels);
  constructor() {
    effect(cleanup => {
      const id = this.incidentId(); let current = true;
      this.label.set(null); cleanup(() => {current=false;});
      void this.labels.forIncident(id).then(label => {if(current)this.label.set(label);});
    });
  }
}
