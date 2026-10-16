import {incidentReferencePrefix} from './acknowledgement-label.component';

describe('Readable incident reference',()=>{
  it('uses a short service code',()=>{
    expect(incidentReferencePrefix('VOLTE')).toBe('VOL');
    expect(incidentReferencePrefix('SMS')).toBe('SMS');
    expect(incidentReferencePrefix('ROAMING')).toBe('ROAM');
    expect(incidentReferencePrefix('TRANSPORT')).toBe('NET');
    expect(incidentReferencePrefix('INCIDENT')).toBe('INC');
  });
});
