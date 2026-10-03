import { DestroyRef, Injectable, inject, signal } from '@angular/core';

@Injectable({ providedIn: 'root' })
export class ToastService {
  readonly current = signal<{ message: string; kind: 'success' | 'error' } | null>(null);
  private timer?: ReturnType<typeof setTimeout>;
  constructor() { inject(DestroyRef).onDestroy(() => clearTimeout(this.timer)); }
  show(message: string, kind: 'success' | 'error' = 'success'): void {
    clearTimeout(this.timer);
    this.current.set({ message, kind });
    this.timer = setTimeout(() => this.current.set(null), 6000);
  }
}
