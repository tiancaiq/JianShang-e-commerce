import { Component } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { By } from '@angular/platform-browser';
import { provideZonelessChangeDetection } from '@angular/core';
import { EmptyStateComponent } from './empty-state.component';

@Component({
  standalone: true,
  imports: [EmptyStateComponent],
  template: `
    <app-ui-empty-state>
      <h2>No drafts yet</h2>
      <p>Create your first draft.</p>
    </app-ui-empty-state>
  `,
})
class EmptyStateHostComponent {}

describe('EmptyStateComponent', () => {
  let fixture: ComponentFixture<EmptyStateHostComponent>;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [EmptyStateHostComponent],
      providers: [provideZonelessChangeDetection()],
    }).compileComponents();

    fixture = TestBed.createComponent(EmptyStateHostComponent);
  });

  it('projects empty-state content into the shared surface', () => {
    fixture.detectChanges();

    const emptyState = fixture.debugElement.query(By.css('.ui-empty-state')).nativeElement as HTMLElement;

    expect(emptyState.textContent).toContain('No drafts yet');
    expect(emptyState.textContent).toContain('Create your first draft.');
  });
});
