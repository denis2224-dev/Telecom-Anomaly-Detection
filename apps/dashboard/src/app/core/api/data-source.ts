export const dataSource = {
  fixture: false,
  loadVoice: async (): Promise<typeof import('../../../fixtures/voice')> => { throw new Error('Sample data is only available in fixture mode.'); },
  loadServices: async (): Promise<unknown> => [],
};
