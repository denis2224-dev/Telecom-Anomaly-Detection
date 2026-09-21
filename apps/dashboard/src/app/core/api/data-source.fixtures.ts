export const dataSource = {
  fixture: true,
  loadVoice: async () => import('../../../fixtures/voice'),
  loadServices: async (): Promise<unknown> => {
    const services = structuredClone((await import('../../../fixtures/services.json')).default);
    const { voiceWindows, voiceRange } = await import('../../../fixtures/voice');
    const voice = services.find(item => item.scope.scopeId === 'VOLTE-MD-CENTRAL')!;
    return services.map(item => item === voice ? { ...item, latestWindow: voiceWindows.at(-1), observedAt: voiceRange.to, openIncidents: 1 } : item);
  },
};
