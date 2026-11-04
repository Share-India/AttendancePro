/**
 * Browser geolocation, wrapped so failures become messages a person can act on.
 *
 * The raw API reports failures as numeric codes on a non-Error object, and
 * "PERMISSION_DENIED" on its own tells someone nothing about what to do next.
 */

export type GeolocationFailureKind = 'DENIED' | 'UNAVAILABLE' | 'TIMEOUT' | 'UNSUPPORTED';

export class GeolocationFailure extends Error {
  readonly kind: GeolocationFailureKind;
  /** What the person should do, in their terms. */
  readonly guidance: string;

  constructor(kind: GeolocationFailureKind, message: string, guidance: string) {
    super(message);
    this.name = 'GeolocationFailure';
    this.kind = kind;
    this.guidance = guidance;
  }
}

export interface Position {
  latitude: number;
  longitude: number;
  accuracyMeters: number;
  capturedAt: string;
}

const HIGH_ACCURACY_OPTIONS: PositionOptions = {
  enableHighAccuracy: true,
  timeout: 15_000,
  // No cached fix: a stale position from another building would be recorded
  // as if the person were standing there now.
  maximumAge: 0,
};

/** A retry after a timeout accepts a recent cached fix rather than failing outright. */
const RETRY_OPTIONS: PositionOptions = {
  enableHighAccuracy: true,
  timeout: 20_000,
  maximumAge: 30_000,
};

const describe = (error: GeolocationPositionError): GeolocationFailure => {
  switch (error.code) {
    case error.PERMISSION_DENIED:
      return new GeolocationFailure(
        'DENIED',
        'Location access was denied.',
        'Your attendance is verified against your office location, so this cannot be skipped. ' +
          'Allow location for this site in your browser settings, then try again.',
      );
    case error.POSITION_UNAVAILABLE:
      return new GeolocationFailure(
        'UNAVAILABLE',
        'Your location could not be determined.',
        'Move somewhere with a clearer view of the sky, or connect to Wi-Fi, and try again.',
      );
    case error.TIMEOUT:
      return new GeolocationFailure(
        'TIMEOUT',
        'Finding your location took too long.',
        'This usually means a weak GPS signal. Try again in a moment.',
      );
    default:
      return new GeolocationFailure(
        'UNAVAILABLE',
        'Your location could not be read.',
        'Try again, or contact HR if this keeps happening.',
      );
  }
};

const read = (options: PositionOptions): Promise<Position> =>
  new Promise((resolve, reject) => {
    navigator.geolocation.getCurrentPosition(
      (position) =>
        resolve({
          latitude: position.coords.latitude,
          longitude: position.coords.longitude,
          accuracyMeters: position.coords.accuracy,
          capturedAt: new Date(position.timestamp).toISOString(),
        }),
      (error) => reject(describe(error)),
      options,
    );
  });

/**
 * Reads the current position, retrying once on timeout with a looser cache
 * window — a first attempt often times out indoors while a second succeeds.
 */
export const getCurrentPosition = async (): Promise<Position> => {
  if (!('geolocation' in navigator)) {
    throw new GeolocationFailure(
      'UNSUPPORTED',
      'This browser cannot report your location.',
      'Use a different browser, or ask HR to record your attendance another way.',
    );
  }

  try {
    return await read(HIGH_ACCURACY_OPTIONS);
  } catch (error) {
    if (error instanceof GeolocationFailure && error.kind === 'TIMEOUT') {
      return read(RETRY_OPTIONS);
    }
    throw error;
  }
};

/**
 * Whether location has already been granted, without prompting.
 *
 * Used to warn before the person taps rather than after, so the permission
 * dialog is not the first they hear of the requirement. Not every browser
 * supports the Permissions API, hence the 'unknown'.
 */
export const checkPermission = async (): Promise<'granted' | 'denied' | 'prompt' | 'unknown'> => {
  if (!('permissions' in navigator)) {
    return 'unknown';
  }
  try {
    const result = await navigator.permissions.query({ name: 'geolocation' as PermissionName });
    return result.state;
  } catch {
    return 'unknown';
  }
};

/** A short, readable device string for the audit record. */
export const describeDevice = (): string => {
  const ua = navigator.userAgent;
  const browser =
    /Edg\//.test(ua) ? 'Edge'
    : /Chrome\//.test(ua) ? 'Chrome'
    : /Firefox\//.test(ua) ? 'Firefox'
    : /Safari\//.test(ua) ? 'Safari'
    : 'Browser';

  const platform =
    /Windows/.test(ua) ? 'Windows'
    : /Android/.test(ua) ? 'Android'
    : /iPhone|iPad/.test(ua) ? 'iOS'
    : /Mac OS X/.test(ua) ? 'macOS'
    : /Linux/.test(ua) ? 'Linux'
    : 'Unknown';

  return `${browser} on ${platform}`;
};
