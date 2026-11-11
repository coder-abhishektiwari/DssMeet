/// <reference types="vite/client" />

interface ImportMetaEnv {
  readonly VITE_API_GATEWAY_URL: string;
  readonly VITE_APP_VERSION: string;
  readonly VITE_APP_PLATFORM: string;
}

interface ImportMeta {
  readonly env: ImportMetaEnv;
}
