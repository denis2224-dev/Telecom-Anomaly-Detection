import { defineConfig } from 'vitest/config';

// The local telecom stack shares this machine with browser and JVM tests.
export default defineConfig({ test: { maxWorkers: 2 } });
