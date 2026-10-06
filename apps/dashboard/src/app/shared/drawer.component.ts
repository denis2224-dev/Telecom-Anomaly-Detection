import { Component, ElementRef, input, output, viewChild } from '@angular/core';

@Component({
  selector: 'app-drawer',
  styles: [':host { display: contents; }'],
  template: `<dialog #dialog [class]="'drawer-panel ' + panelClass()" [id]="drawerId()" [attr.aria-labelledby]="titleId() || drawerId() + '-title'" (click)="closeOutside($event)" (close)="closed.emit()">
    <header class="drawer-heading"><h3 [id]="titleId() || drawerId() + '-title'" tabindex="-1" autofocus>{{ title() }}</h3><button class="drawer-close primary" type="button" [attr.aria-label]="closeLabel()" (click)="close()">Close</button></header>
    <ng-content select="[drawer-controls]" />
    <div class="drawer-content" tabindex="0" role="region" [attr.aria-label]="title() + ' content'"><ng-content /></div>
    <ng-content select="[drawer-footer]" />
  </dialog>`,
})
export class DrawerComponent {
  readonly drawerId = input.required<string>();
  readonly title = input.required<string>();
  readonly titleId = input('');
  readonly panelClass = input('');
  readonly closeLabel = input('Close drawer');
  readonly closed = output<void>();
  private readonly dialog = viewChild.required<ElementRef<HTMLDialogElement>>('dialog');
  open(): void {
    const dialog = this.dialog().nativeElement;
    dialog.showModal();
    dialog.querySelector('.drawer-content')!.scrollTop = 0;
  }
  close(): void { this.dialog().nativeElement.close(); }
  closeOutside(event: MouseEvent): void {
    const dialog = this.dialog().nativeElement;
    if (event.target !== dialog) return;
    const rect = dialog.getBoundingClientRect();
    if (event.clientX < rect.left || event.clientX > rect.right || event.clientY < rect.top || event.clientY > rect.bottom) this.close();
  }
}
