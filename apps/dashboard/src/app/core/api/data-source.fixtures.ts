export const dataSource = {
  fixture: true,
  loadVoice: async () => import('../../../fixtures/voice'),
  loadSms: async () => import('../../../fixtures/sms'),
  loadRange: async (scopeId: string) => {
    if (scopeId === 'SMS-MD-ROUTE-A') return (await import('../../../fixtures/sms')).smsRange;
    return (await import('../../../fixtures/voice')).voiceRange;
  },
  loadServices: async (): Promise<unknown> => {
    const services = structuredClone((await import('../../../fixtures/services.json')).default);
    const { voiceWindows, voiceRange } = await import('../../../fixtures/voice');
    const voice = services.find(item => item.scope.scopeId === 'VOLTE-MD-CENTRAL')!;
    const { smsWindows, smsRange } = await import('../../../fixtures/sms');
    return services.map(item => item === voice ? { ...item, latestWindow: voiceWindows.at(-1), observedAt: voiceRange.to, openIncidents: 1 }
      : item.scope.scopeId === 'SMS-MD-ROUTE-A' ? { ...item, latestWindow: smsWindows.at(-1), observedAt: smsRange.to, openIncidents: 1 } : item);
  },
};
