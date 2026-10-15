import {notificationTime} from './notification-center.component';
it('presents alert timestamps through IANA summer and winter offsets',()=>{
  expect(notificationTime('2026-07-15T09:00:00Z','Europe/Chisinau')).toContain('12:00:00');
  expect(notificationTime('2026-01-15T09:00:00Z','Europe/Chisinau')).toContain('11:00:00');
});
it('retains the original UTC instant when UTC presentation is selected',()=>{
  expect(notificationTime('2026-07-15T09:00:00Z','UTC')).toContain('09:00:00');
});
