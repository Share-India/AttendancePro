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

/** How long to keep sampling before settling for the best fix so far. */
const SAMPLE_WINDOW_MS = 8_000;

/**
 * Accurate enough to stop early. Below this, waiting longer buys nothing a
 * geofence can use, and the person is standing there watching a spinner.
 */
const GOOD_ENOUGH_METRES = 25;

/**
 * Samples for a few seconds and keeps the most accurate fix.
 *
 * <p>The first answer a browser gives is usually its worst. It replies
 * immediately from whatever it already knows — a coarse Wi-Fi or cell
 * estimate — then refines as more access points are scanned or more
 * satellites are acquired. A laptop with no GPS typically walks down from
 * a couple of hundred metres to a few tens over about five seconds.
 *
 * <p>Taking one reading therefore throws away the good fix that was moments
 * away, and on a laptop that is the difference between a punch the geofence
 * can verify and one it has to refuse. Nothing here loosens any rule: it
 * sends a better measurement, not a more forgiving one.
 *
 * <p>Resolves as soon as a reading beats {@link GOOD_ENOUGH_METRES}, so a
 * phone with real GPS still answers almost at once.
 */
const sampleBestPosition = (): Promise<Position> =>
  new Promise((resolve, reject) => {
    let best: Position | null = null;
    let settled = false;
    let watchId: number | null = null;
    let timer: ReturnType<typeof setTimeout> | null = null;

    const stop = () => {
      if (watchId !== null) {
        navigator.geolocation.clearWatch(watchId);
        watchId = null;
      }
      if (timer !== null) {
        clearTimeout(timer);
        timer = null;
      }
    };

    const settle = () => {
      if (settled) return;
      settled = true;
      stop();
      if (best) {
        resolve(best);
      } else {
        // The window closed with no reading at all. Treated as a timeout so
        // the caller's existing retry — which accepts a cached fix — applies.
        reject(
          new GeolocationFailure(
            'TIMEOUT',
            'Finding your location took too long.',
            'This usually means a weak signal. Try again in a moment.',
          ),
        );
      }
    };

    watchId = navigator.geolocation.watchPosition(
      (position) => {
        const candidate: Position = {
          latitude: position.coords.latitude,
          longitude: position.coords.longitude,
          accuracyMeters: position.coords.accuracy,
          capturedAt: new Date(position.timestamp).toISOString(),
        };

        if (!best || candidate.accuracyMeters < best.accuracyMeters) {
          best = candidate;
        }

        if (best.accuracyMeters <= GOOD_ENOUGH_METRES) {
          settle();
        }
      },
      (error) => {
        // A later error does not discard an earlier good reading: watchPosition
        // can fail once the fix has already been obtained, and refusing the
        // punch at that point would be throwing away the answer.
        if (best) {
          settle();
          return;
        }
        if (settled) return;
        settled = true;
        stop();
        reject(describe(error));
      },
      HIGH_ACCURACY_OPTIONS,
    );

    timer = setTimeout(settle, SAMPLE_WINDOW_MS);
  });

/**
 * The position to record against a punch: the best of a few seconds of
 * readings, falling back to a single cached-tolerant read if sampling
 * produced nothing at all.
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
    return await sampleBestPosition();
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
