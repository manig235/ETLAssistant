import { defineConfig } from 'vite';
export default defineConfig({ base: './', build: { target: 'es2022', sourcemap: false, chunkSizeWarningLimit: 1200,
  rolldownOptions: {onwarn(warning, handler) {if (warning.code !== 'MODULE_LEVEL_DIRECTIVE') handler(warning);}}
} });
