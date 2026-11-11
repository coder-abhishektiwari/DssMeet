import { defineConfig, type Plugin } from 'vite';
import react from '@vitejs/plugin-react';
import path from 'path';
import pkg from './package.json';

// Production-only CSP: blocks injected inline/event-handler XSS and rogue
// script loads. Injected only for builds (ctx.server) so Vite/React inline
// dev scripts and HMR keep working locally. Google Fonts allowed because
// index.html loads Material Icons from fonts.googleapis.com/gstatic.com.
const CSP =
  "default-src 'self'; script-src 'self'; " +
  "style-src 'self' 'unsafe-inline' https://fonts.googleapis.com; " +
  "img-src 'self' data: blob: https:; media-src 'self' blob: https:; " +
  "font-src 'self' data: https://fonts.gstatic.com; " +
  "connect-src 'self' https: wss: ws:; worker-src 'self' blob:; " +
  "object-src 'none'; base-uri 'self'; form-action 'self'";

function securityMetas(): Plugin {
  return {
    name: 'security-metas',
    transformIndexHtml(html, ctx) {
      if (ctx.server) return html;
      return [
        { tag: 'meta', attrs: { 'http-equiv': 'Content-Security-Policy', content: CSP }, injectTo: 'head-prepend' },
        { tag: 'meta', attrs: { name: 'referrer', content: 'no-referrer' }, injectTo: 'head' },
      ];
    },
  };
}

// https://vitejs.dev/config/
export default defineConfig({
  plugins: [react(), securityMetas()],
  define: {
    // The app's own version + the platform this build targets — the update
    // check compares them with the published release registry.
    'import.meta.env.VITE_APP_VERSION': JSON.stringify(pkg.version),
    'import.meta.env.VITE_APP_PLATFORM': JSON.stringify(
      process.env.DSM_TARGET_PLATFORM ||
        (process.platform === 'win32' ? 'windows' : process.platform === 'darwin' ? 'mac' : 'linux')
    ),
  },
  resolve: {
    alias: {
      '@': path.resolve(__dirname, './src'),
    },
  },
  server: {
    port: 3001,
    host: true,
    // Allow cloudflared quick-tunnel hostnames (random subdomain each run).
    allowedHosts: ['.trycloudflare.com'],
  },
});
