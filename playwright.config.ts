import { defineConfig } from '@playwright/test';
import { existsSync, readFileSync } from 'node:fs';
import { parseEnv } from 'node:util';
const e={...(existsSync('.env')?parseEnv(readFileSync('.env','utf8')):{}),...process.env};
const admin='http://127.0.0.1:'+(e.ADMIN_HOST_PORT||4200);
const learner='http://127.0.0.1:'+(e.LEARNER_HOST_PORT||5173);
process.env.DV_ADMIN_URL=admin;
process.env.DV_LEARNER_URL=learner;
process.env.DV_CORE_URL=e.DV_INTERNAL_CORE_URL||('http://127.0.0.1:'+(e.CORE_HOST_PORT||8080));
process.env.DV_MAIL_URL=e.DV_INTERNAL_MAIL_URL||('http://127.0.0.1:'+(e.MAILPIT_HOST_PORT||8025));
process.env.DV_AUTH_ORIGIN='http://127.0.0.1:'+(e.CORE_HOST_PORT||8080);
export default defineConfig({
  testDir:'./tests/e2e', fullyParallel:false, workers:1, timeout:180000, retries:0,
  reporter:[['list'],['html',{open:'never'}]], outputDir:'test-results',
  use:{browserName:'chromium',headless:true,trace:'retain-on-failure',screenshot:'only-on-failure'},
  webServer:[
    {command:'node scripts/dev.mjs admin',url:admin,reuseExistingServer:false,timeout:120000},
    {command:'node scripts/dev.mjs learner',url:learner,reuseExistingServer:false,timeout:120000},
  ],
});
