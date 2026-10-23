import { AfterViewInit, Directive, ElementRef, HostListener, OnDestroy } from '@angular/core';

@Directive({selector: '[intiDialog]', standalone: true})
export class DialogFocus implements AfterViewInit, OnDestroy {
  private previous: HTMLElement | null = null;
  constructor(private element: ElementRef<HTMLElement>) {}
  ngAfterViewInit(): void {
    this.previous = document.activeElement instanceof HTMLElement ? document.activeElement : null;
    this.element.nativeElement.focus();
  }
  ngOnDestroy(): void { this.previous?.focus(); }
  @HostListener('keydown', ['$event'])
  onKey(event: KeyboardEvent): void {
    if (event.key !== 'Tab') return;
    const controls = Array.from(this.element.nativeElement.querySelectorAll<HTMLElement>('button:not(:disabled), a[href], input:not(:disabled), select:not(:disabled), textarea:not(:disabled), summary'))
      .filter(element => element.getClientRects().length > 0);
    if (!controls.length) { event.preventDefault(); return; }
    const first = controls[0], last = controls[controls.length - 1];
    if (event.shiftKey && (document.activeElement === first || document.activeElement === this.element.nativeElement)) {
      event.preventDefault(); last.focus();
    } else if (!event.shiftKey && (document.activeElement === last || document.activeElement === this.element.nativeElement)) {
      event.preventDefault(); first.focus();
    }
  }
}
