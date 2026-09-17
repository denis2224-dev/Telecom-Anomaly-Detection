export const dataSource = {
  fixture: true,
  loadServices: async (): Promise<unknown> =>
    (await import("../../../fixtures/services.json")).default,
};
