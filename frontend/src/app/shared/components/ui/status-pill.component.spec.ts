import { Component } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { By } from '@angular/platform-browser';
import { provideZonelessChangeDetection } from '@angular/core';
import { StatusPillComponent } from './status-pill.component';

@Component({
  standalone: true,
  imports: [StatusPillComponent],
  template: `<app-ui-status-pill>ACTIVE</app-ui-status-pill>`,
})
class StatusPillHostComponent {}

describe('StatusPillComponent', () => {
  let fixture: ComponentFixture<StatusPillHostComponent>;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [StatusPillHostComponent],
      providers: [provideZonelessChangeDetection()],
    }).compileComponents();

    fixture = TestBed.createComponent(StatusPillHostComponent);
  });

  it('projects status text into the shared pill', () => {
    fixture.detectChanges();

    const pill = fixture.debugElement.query(By.css('.ui-status-pill')).nativeElement as HTMLElement;

    expect(pill.textContent?.trim()).toBe('ACTIVE');
  });
});
