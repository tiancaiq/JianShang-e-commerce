import { provideZonelessChangeDetection } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { ActivatedRoute, convertToParamMap, ParamMap, provideRouter } from '@angular/router';
import { BehaviorSubject, of } from 'rxjs';
import { SupportService } from '../../core/services/support.service';
import { AdminSupportListComponent } from './admin-support-list.component';

describe('AdminSupportListComponent analytics query filters', () => {
  it('hydrates waiting-for-user and resets on query removal for browser history', async () => {
    const api=jasmine.createSpyObj<SupportService>('SupportService',['search']);
    api.search.and.returnValue(of({items:[],page:0,size:25,totalElements:0,totalPages:0,sort:'updatedAt,desc'}));
    const query=new BehaviorSubject<ParamMap>(convertToParamMap({status:'WAITING_FOR_USER',assignment:'ALL'}));
    await TestBed.configureTestingModule({imports:[AdminSupportListComponent],providers:[
      provideZonelessChangeDetection(),provideRouter([]),{provide:SupportService,useValue:api},
      {provide:ActivatedRoute,useValue:{queryParamMap:query.asObservable()}},
    ]}).compileComponents();

    const fixture=TestBed.createComponent(AdminSupportListComponent);fixture.detectChanges();
    expect(api.search.calls.mostRecent().args[0]).toEqual(jasmine.objectContaining({
      status:'WAITING_FOR_USER',assignment:'ALL',
    }));
    query.next(convertToParamMap({}));
    expect(api.search.calls.mostRecent().args[0]).toEqual(jasmine.objectContaining({status:'',assignment:'ALL'}));
  });
});
