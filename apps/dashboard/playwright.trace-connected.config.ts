import {defineConfig} from '@playwright/test';
import original from './playwright.pr77.config';

export default defineConfig({...original,testMatch:['trace-connected.spec.ts','pr77-connected.spec.ts'],globalTimeout:75*60_000});
