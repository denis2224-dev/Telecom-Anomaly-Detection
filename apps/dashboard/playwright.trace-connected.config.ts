import {defineConfig} from '@playwright/test';
import original from './playwright.pr77.config';

export default defineConfig({...original,testMatch:'trace-connected.spec.ts',timeout:180_000,globalTimeout:600_000});
