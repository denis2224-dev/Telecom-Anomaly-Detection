export const dataSource = {
  fixture: false,
  loadConnectedDashboard: async (): Promise<typeof import('../../../fixtures/connected-dashboard')> => { throw new Error('City design fixtures are only available in fixture mode.'); },
  loadVoice: async (): Promise<typeof import('../../../fixtures/voice')> => { throw new Error('Sample data is only available in fixture mode.'); },
  loadSms: async (): Promise<typeof import('../../../fixtures/sms')> => { throw new Error('Sample data is only available in fixture mode.'); },
  loadServices: async (): Promise<unknown> => [],
  loadRange: async (_scopeId: string): Promise<{ from: string; to: string }> => { throw new Error('Sample data is only available in fixture mode.'); },
};
