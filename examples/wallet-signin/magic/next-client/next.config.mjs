// Local integration example. Review the fixed backend target and deployed origin before use.
// These are the only proxied routes; never add a catch-all API rewrite.
const nextConfig = {
  async rewrites() {
    return [
      { source: '/api/v1/auth/wallet/nonce', destination: 'http://127.0.0.1:8080/api/v1/auth/wallet/nonce' },
      { source: '/api/v1/auth/wallet/verify', destination: 'http://127.0.0.1:8080/api/v1/auth/wallet/verify' }
    ];
  }
};

export default nextConfig;
