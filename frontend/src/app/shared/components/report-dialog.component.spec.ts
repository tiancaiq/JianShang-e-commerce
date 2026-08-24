import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideZonelessChangeDetection } from '@angular/core';
import { of, throwError } from 'rxjs';
import { ReportService } from '../../core/services/report.service';
import { ReportDialogComponent } from './report-dialog.component';

describe('ReportDialogComponent', () => {
  let fixture: ComponentFixture<ReportDialogComponent>; let component: ReportDialogComponent;
  let service: jasmine.SpyObj<ReportService>;
  beforeEach(async () => {
    service=jasmine.createSpyObj<ReportService>('ReportService',['submit']);
    await TestBed.configureTestingModule({imports:[ReportDialogComponent],providers:[provideZonelessChangeDetection(),{provide:ReportService,useValue:service}]}).compileComponents();
    fixture=TestBed.createComponent(ReportDialogComponent);component=fixture.componentInstance;
    fixture.componentRef.setInput('targetType','LISTING');fixture.componentRef.setInput('targetId','01L00000000000000000000001');fixture.detectChanges();
  });

  it('shows only reasons compatible with the target', () => {
    expect(component.reasons.map(value=>value.value)).toContain('MISLEADING_LISTING');
    expect(component.reasons.map(value=>value.value)).not.toContain('HARASSMENT');
  });

  it('requires an explanation for OTHER', () => {
    component.reason='OTHER';component.description='  ';expect(component.valid()).toBeFalse();
    component.description='Something else happened';expect(component.valid()).toBeTrue();
  });

  it('renders a neutral confirmation without promising enforcement', () => {
    service.submit.and.returnValue(of({reportId:'01R',status:'SUBMITTED',createdAt:'2026-08-15T00:00:00Z',supportReference:'RPT-1234'}));
    component.reason='SPAM';component.submit();fixture.detectChanges();
    expect(fixture.nativeElement.textContent).toContain('submitted for review');
    expect(fixture.nativeElement.textContent).not.toContain('will be removed');
  });

  it('shows the duplicate cooldown response', () => {
    service.submit.and.returnValue(throwError(()=>({error:{error:{code:'REPORT_ALREADY_SUBMITTED'}}})));
    component.reason='SPAM';component.submit();fixture.detectChanges();
    expect(component.errorMsg()).toContain('already sent');
  });
});
