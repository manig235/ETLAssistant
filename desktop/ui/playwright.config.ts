import {defineConfig} from '@playwright/test';
export default defineConfig({testDir:'tests', testMatch:'**/ui.spec.ts', timeout:20000, fullyParallel:true, reporter:'list',
  use:{baseURL:'http://127.0.0.1:4178', headless:true, viewport:{width:1560,height:980}},
  webServer:{command:'node tests/serve.cjs',url:'http://127.0.0.1:4178',reuseExistingServer:false},
});
