import {defineConfig} from '@playwright/test';
import original from './playwright.pr77.config';

export default defineConfig({...original,
  testMatch:['volte-sms-demo.spec.ts','pr77-connected.spec.ts'],
  grep:/complete VoLTE\/SMS demo repetition|expires at real|trusted analyst activity cannot extend real/,
  fullyParallel:true, workers:3, globalTimeout:50*60_000,
});
