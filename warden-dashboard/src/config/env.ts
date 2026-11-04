/**
 * Centralized environment configuration.
 * All environment variables are accessed through this module.
 */
export const env = {
  apiGatewayUrl: import.meta.env.VITE_API_GATEWAY_URL,
} as const;

if (!env.apiGatewayUrl) {
  throw new Error(
    'VITE_API_GATEWAY_URL is not set - the warden-dashboard build requires the public backend URL (no localhost defaults)'
  );
}
