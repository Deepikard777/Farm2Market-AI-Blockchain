/**
 * QR Code & Traceability URL Resolution Utilities
 *
 * Handles local development sensibly by avoiding hardcoded 5173/5174 ports,
 * seamlessly adapting localhost URLs when switching ports, and supporting
 * LAN IP addresses for mobile testing on the same Wi-Fi.
 */

export function getProductQrUrl(
  product: { id?: string; qrCode?: string } | null | undefined,
  overrideOrigin?: string
): string {
  const productId = product?.id?.trim() || '';
  if (!productId) return '';

  const currentOrigin =
    overrideOrigin || (typeof window !== 'undefined' ? window.location.origin : '');

  // 1. Explicit production/public URL from environment variables
  const envBase = (
    typeof import.meta !== 'undefined' && import.meta.env
      ? (import.meta.env.VITE_APP_URL || import.meta.env.VITE_PUBLIC_URL || '')
      : ''
  ).replace(/\/$/, '');

  if (envBase) {
    return `${envBase}/product/${productId}`;
  }

  // 2. Resolve existing qrCode if available
  if (product?.qrCode) {
    const raw = product.qrCode.trim();

    // Relative path (/product/:id) -> prefix with current origin
    if (raw.startsWith('/')) {
      return `${currentOrigin}${raw}`;
    }

    try {
      const parsed = new URL(raw);
      const host = parsed.hostname.toLowerCase();

      // If the QR was generated pointing to localhost / 127.0.0.1 / 0.0.0.0,
      // dynamically adapt the origin to current origin so port changes
      // (5173 vs 5174) or host transitions (localhost -> LAN IP) work automatically!
      if (['localhost', '127.0.0.1', '0.0.0.0', '::1'].includes(host)) {
        return `${currentOrigin}/product/${productId}`;
      }

      // External or production URL -> preserve
      return raw;
    } catch {
      // Fallback if parsing fails
      return `${currentOrigin}/product/${productId}`;
    }
  }

  // 3. Default fallback
  return `${currentOrigin}/product/${productId}`;
}

/**
 * Checks if a URL points to localhost or a loopback address.
 */
export function isLocalhostUrl(urlStr: string): boolean {
  if (!urlStr) return false;
  try {
    const parsed = new URL(urlStr);
    const host = parsed.hostname.toLowerCase();
    return ['localhost', '127.0.0.1', '0.0.0.0', '::1'].includes(host);
  } catch {
    return urlStr.includes('localhost') || urlStr.includes('127.0.0.1');
  }
}
