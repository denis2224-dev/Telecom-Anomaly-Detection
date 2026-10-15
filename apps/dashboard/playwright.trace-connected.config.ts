import {defineConfig} from '@playwright/test';
import original from './playwright.pr77.config';

export default defineConfig({...original,testMatch:['trace-connected.spec.ts','pr77-connected.spec.ts'],grep:/populated|integrated production roles/,globalTimeout:30*60_000});
