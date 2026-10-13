/* Generic browser alerts only. Authentication is required after click-through. */
self.addEventListener('activate', event => event.waitUntil(self.clients.claim()));
self.addEventListener('push', event => {
  event.waitUntil((async () => {
    let data;
    try { data = event.data?.json(); } catch { return; }
    const windows = await self.clients.matchAll({type:'window',includeUncontrolled:false});
    const visible = windows.filter(client => client.visibilityState === 'visible');
    if (visible.length) {
      for (const client of visible) client.postMessage({type:'refresh-notifications'});
      return;
    }
    const severity = ['MEDIUM','HIGH','CRITICAL'].includes(data?.severity) ? data.severity : 'Alert';
    await self.registration.showNotification('Trace Link alert', {
      body:severity,tag:typeof data?.id==='string' ? data.id.slice(0,64) : 'trace-link-alert',
      data:{url:typeof data?.url==='string' ? data.url : ''},
    });
  })());
});
self.addEventListener('notificationclick', event => {
  event.notification.close();
  event.waitUntil((async () => {
    let target;
    try { target = new URL(event.notification.data?.url, self.location.origin); } catch { return; }
    if (target.origin !== self.location.origin || !/^\/incidents\/[0-9a-f-]{36}$/i.test(target.pathname)
      || target.search || target.hash || target.username || target.password) return;
    const windows = await self.clients.matchAll({type:'window',includeUncontrolled:false});
    const client = windows.find(client => new URL(client.url).origin === target.origin);
    if (client) { await client.navigate(target.href); await client.focus(); }
    else await self.clients.openWindow(target.href);
  })());
});
