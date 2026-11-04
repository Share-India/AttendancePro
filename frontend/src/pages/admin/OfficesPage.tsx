import { useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Alert } from '../../components/common/Alert';
import { Icon } from '../../components/common/Icon';
import { Modal } from '../../components/common/Modal';
import { PageHeader } from '../../components/common/PageHeader';
import { Spinner } from '../../components/common/Spinner';
import { StatusChip } from '../../components/common/StatusChip';
import { masterApi } from '../../features/admin/masterApi';
import { useAuthStore } from '../../store/authStore';
import { ApiError } from '../../types/api';
import { LOCATION_TYPES, type Office, type OfficeLocation } from '../../types/master';

/** A short, real list beats making someone type an IANA identifier from memory. */
const COMMON_TIMEZONES = [
  'Asia/Kolkata',
  'Asia/Dubai',
  'Asia/Singapore',
  'Europe/London',
  'Europe/Berlin',
  'America/New_York',
  'America/Chicago',
  'America/Los_Angeles',
  'Australia/Sydney',
  'UTC',
];

export const OfficesPage = () => {
  const queryClient = useQueryClient();
  const hasPermission = useAuthStore((state) => state.hasPermission);
  const canManageOffices = hasPermission('OFFICE_MANAGE');
  const canManageGeofences = hasPermission('GEOFENCE_MANAGE');

  const [officeDialog, setOfficeDialog] = useState<{ open: boolean; office: Office | null }>({
    open: false,
    office: null,
  });
  const [geofenceDialog, setGeofenceDialog] = useState<{
    open: boolean;
    officeId: number | null;
    location: OfficeLocation | null;
  }>({ open: false, officeId: null, location: null });

  const { data: offices, isLoading, error } = useQuery({
    queryKey: ['master', 'offices', false],
    queryFn: () => masterApi.offices.list(false),
  });

  const invalidate = () => queryClient.invalidateQueries({ queryKey: ['master', 'offices'] });

  const saveOffice = useMutation({
    mutationFn: (payload: Record<string, unknown>) =>
      officeDialog.office
        ? masterApi.offices.update(officeDialog.office.id, payload)
        : masterApi.offices.create(payload),
    onSuccess: () => {
      invalidate();
      setOfficeDialog({ open: false, office: null });
    },
  });

  const saveGeofence = useMutation({
    mutationFn: (payload: Record<string, unknown>) => {
      const officeId = geofenceDialog.officeId as number;
      return geofenceDialog.location
        ? masterApi.offices.updateLocation(officeId, geofenceDialog.location.id, payload)
        : masterApi.offices.addLocation(officeId, payload);
    },
    onSuccess: () => {
      invalidate();
      setGeofenceDialog({ open: false, officeId: null, location: null });
    },
  });

  const removeGeofence = useMutation({
    mutationFn: ({ officeId, locationId }: { officeId: number; locationId: number }) =>
      masterApi.offices.deactivateLocation(officeId, locationId),
    onSuccess: invalidate,
  });

  if (isLoading) {
    return (
      <div className="flex justify-center py-xl">
        <Spinner className="h-8 w-8 text-primary" />
      </div>
    );
  }

  return (
    <div className="mx-auto max-w-[1280px]">
      <PageHeader
        title="Offices & Geofences"
        description="Each office holds one or more geofence circles. A campus with three gates is three circles, not one oversized radius."
        actions={
          canManageOffices ? (
            <button
              type="button"
              className="btn-primary"
              onClick={() => {
                saveOffice.reset();
                setOfficeDialog({ open: true, office: null });
              }}
            >
              <Icon name="add_business" className="text-[18px]" />
              Add office
            </button>
          ) : null
        }
      />

      {error && (
        <Alert variant="error" title="Could not load offices" className="mb-lg">
          Please try again in a moment.
        </Alert>
      )}

      {removeGeofence.isError && (
        <Alert variant="error" title="Could not deactivate that geofence" className="mb-lg">
          {removeGeofence.error instanceof ApiError
            ? removeGeofence.error.message
            : 'Please try again.'}
        </Alert>
      )}

      <div className="flex flex-col gap-gutter">
        {(offices ?? []).map((office) => (
          <section key={office.id} className="card overflow-hidden">
            <header className="flex flex-wrap items-start justify-between gap-md border-b border-outline-variant/60 px-lg py-md">
              <div className="min-w-0">
                <div className="flex flex-wrap items-center gap-sm">
                  <h2 className="text-headline-md text-on-surface">{office.name}</h2>
                  <span className="font-mono text-body-sm text-on-surface-variant">{office.code}</span>
                  <StatusChip
                    label={office.active ? 'Active' : 'Inactive'}
                    tone={office.active ? 'success' : 'neutral'}
                  />
                </div>
                <p className="mt-xs text-body-sm text-on-surface-variant">
                  {[office.city, office.state, office.country].filter(Boolean).join(', ') || 'No address'}
                  <span className="mx-2 text-outline-variant">·</span>
                  <span className="font-mono">{office.timezone}</span>
                  <span className="mx-2 text-outline-variant">·</span>
                  {office.employeeCount} employee{office.employeeCount === 1 ? '' : 's'}
                </p>
              </div>

              <div className="flex gap-sm">
                {canManageOffices && (
                  <button
                    type="button"
                    className="btn-secondary"
                    onClick={() => {
                      saveOffice.reset();
                      setOfficeDialog({ open: true, office });
                    }}
                  >
                    <Icon name="edit" className="text-[18px]" />
                    Edit
                  </button>
                )}
                {canManageGeofences && (
                  <button
                    type="button"
                    className="btn-primary"
                    onClick={() => {
                      saveGeofence.reset();
                      setGeofenceDialog({ open: true, officeId: office.id, location: null });
                    }}
                  >
                    <Icon name="add_location_alt" className="text-[18px]" />
                    Add geofence
                  </button>
                )}
              </div>
            </header>

            {office.locations.length === 0 ? (
              <div className="px-lg py-lg">
                <Alert variant="warning">
                  This office has no geofence. Nobody assigned here can punch in until one is added.
                </Alert>
              </div>
            ) : (
              <div className="overflow-x-auto">
                <table className="w-full min-w-[640px] border-collapse">
                  <thead>
                    <tr className="bg-surface-container-low">
                      <th className="px-lg py-2.5 text-left text-label-md uppercase text-on-surface-variant">
                        Location
                      </th>
                      <th className="px-md py-2.5 text-left text-label-md uppercase text-on-surface-variant">
                        Coordinates
                      </th>
                      <th className="px-md py-2.5 text-right text-label-md uppercase text-on-surface-variant">
                        Radius
                      </th>
                      <th className="px-md py-2.5 text-left text-label-md uppercase text-on-surface-variant">
                        Type
                      </th>
                      <th className="px-md py-2.5 text-left text-label-md uppercase text-on-surface-variant">
                        Status
                      </th>
                      {canManageGeofences && <th className="px-md py-2.5" />}
                    </tr>
                  </thead>
                  <tbody>
                    {office.locations.map((location) => (
                      <tr
                        key={location.id}
                        className="border-t border-outline-variant/40 even:bg-surface-container-low/40"
                      >
                        <td className="px-lg py-3 text-body-md text-on-surface">
                          {location.locationName}
                          {location.address && (
                            <span className="mt-0.5 block text-body-sm text-on-surface-variant">
                              {location.address}
                            </span>
                          )}
                        </td>
                        <td className="px-md py-3 font-mono text-body-sm tabular-nums text-on-surface-variant">
                          {Number(location.latitude).toFixed(6)}, {Number(location.longitude).toFixed(6)}
                        </td>
                        <td className="px-md py-3 text-right font-mono text-body-md tabular-nums text-on-surface">
                          {location.allowedRadiusMeters} m
                        </td>
                        <td className="px-md py-3 text-body-sm text-on-surface-variant">
                          {location.locationType.replace('_', ' ')}
                          {location.openToAll && (
                            <span className="mt-0.5 block text-body-sm text-primary">Open to all staff</span>
                          )}
                        </td>
                        <td className="px-md py-3">
                          <StatusChip
                            label={location.active ? 'Active' : 'Inactive'}
                            tone={location.active ? 'success' : 'neutral'}
                          />
                        </td>
                        {canManageGeofences && (
                          <td className="px-md py-3 text-right">
                            <div className="flex justify-end gap-1">
                              <button
                                type="button"
                                onClick={() => {
                                  saveGeofence.reset();
                                  setGeofenceDialog({ open: true, officeId: office.id, location });
                                }}
                                className="rounded-sm p-1.5 text-on-surface-variant transition-colors hover:bg-surface-container hover:text-primary"
                                aria-label={`Edit ${location.locationName}`}
                              >
                                <Icon name="edit" className="text-[18px]" />
                              </button>
                              {location.active && (
                                <button
                                  type="button"
                                  onClick={() =>
                                    removeGeofence.mutate({
                                      officeId: office.id,
                                      locationId: location.id,
                                    })
                                  }
                                  className="rounded-sm p-1.5 text-on-surface-variant transition-colors hover:bg-error-container hover:text-error"
                                  aria-label={`Deactivate ${location.locationName}`}
                                >
                                  <Icon name="block" className="text-[18px]" />
                                </button>
                              )}
                            </div>
                          </td>
                        )}
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}
          </section>
        ))}
      </div>

      {/* --- Office dialog --- */}
      <Modal
        open={officeDialog.open}
        onClose={() => setOfficeDialog({ open: false, office: null })}
        title={officeDialog.office ? 'Edit office' : 'Add office'}
        description="The timezone anchors every business date for employees posted here."
        footer={
          <>
            <button
              type="button"
              className="btn-secondary"
              onClick={() => setOfficeDialog({ open: false, office: null })}
            >
              Cancel
            </button>
            <button type="submit" form="office-form" className="btn-primary" disabled={saveOffice.isPending}>
              {saveOffice.isPending ? <Spinner className="h-4 w-4" /> : <Icon name="check" className="text-[18px]" />}
              Save
            </button>
          </>
        }
      >
        {saveOffice.isError && (
          <Alert variant="error" title="Could not save" className="mb-md">
            {saveOffice.error instanceof ApiError ? saveOffice.error.message : 'Please try again.'}
          </Alert>
        )}

        <form
          id="office-form"
          className="grid gap-md sm:grid-cols-2"
          onSubmit={(event) => {
            event.preventDefault();
            const formData = new FormData(event.currentTarget);
            saveOffice.mutate({
              code: String(formData.get('code') ?? '').trim().toUpperCase(),
              name: String(formData.get('name') ?? '').trim(),
              addressLine1: String(formData.get('addressLine1') ?? '') || null,
              city: String(formData.get('city') ?? '') || null,
              state: String(formData.get('state') ?? '') || null,
              country: String(formData.get('country') ?? '') || null,
              postalCode: String(formData.get('postalCode') ?? '') || null,
              timezone: String(formData.get('timezone') ?? 'Asia/Kolkata'),
              active: true,
            });
          }}
        >
          <div>
            <label htmlFor="office-code" className="field-label">Code</label>
            <input id="office-code" name="code" required maxLength={30} pattern="[A-Za-z0-9_-]+"
              defaultValue={officeDialog.office?.code ?? ''} className="field-input font-mono uppercase" />
          </div>
          <div>
            <label htmlFor="office-name" className="field-label">Name</label>
            <input id="office-name" name="name" required maxLength={120}
              defaultValue={officeDialog.office?.name ?? ''} className="field-input" />
          </div>
          <div className="sm:col-span-2">
            <label htmlFor="office-address" className="field-label">Address</label>
            <input id="office-address" name="addressLine1" maxLength={255}
              defaultValue={officeDialog.office?.addressLine1 ?? ''} className="field-input" />
          </div>
          <div>
            <label htmlFor="office-city" className="field-label">City</label>
            <input id="office-city" name="city" maxLength={100}
              defaultValue={officeDialog.office?.city ?? ''} className="field-input" />
          </div>
          <div>
            <label htmlFor="office-state" className="field-label">State</label>
            <input id="office-state" name="state" maxLength={100}
              defaultValue={officeDialog.office?.state ?? ''} className="field-input" />
          </div>
          <div>
            <label htmlFor="office-country" className="field-label">Country</label>
            <input id="office-country" name="country" maxLength={100}
              defaultValue={officeDialog.office?.country ?? ''} className="field-input" />
          </div>
          <div>
            <label htmlFor="office-postal" className="field-label">Postal code</label>
            <input id="office-postal" name="postalCode" maxLength={20}
              defaultValue={officeDialog.office?.postalCode ?? ''} className="field-input" />
          </div>
          <div className="sm:col-span-2">
            <label htmlFor="office-timezone" className="field-label">Timezone</label>
            <select id="office-timezone" name="timezone" required
              defaultValue={officeDialog.office?.timezone ?? 'Asia/Kolkata'} className="field-input">
              {COMMON_TIMEZONES.map((zone) => (
                <option key={zone} value={zone}>{zone}</option>
              ))}
            </select>
            <p className="mt-unit text-body-sm text-on-surface-variant">
              A punch at 00:30 local belongs to the local date, wherever the server runs.
            </p>
          </div>
        </form>
      </Modal>

      {/* --- Geofence dialog --- */}
      <Modal
        open={geofenceDialog.open}
        onClose={() => setGeofenceDialog({ open: false, officeId: null, location: null })}
        title={geofenceDialog.location ? 'Edit geofence' : 'Add geofence'}
        description="Coordinates are validated on the server on every punch, never only in the browser."
        footer={
          <>
            <button
              type="button"
              className="btn-secondary"
              onClick={() => setGeofenceDialog({ open: false, officeId: null, location: null })}
            >
              Cancel
            </button>
            <button type="submit" form="geofence-form" className="btn-primary" disabled={saveGeofence.isPending}>
              {saveGeofence.isPending ? <Spinner className="h-4 w-4" /> : <Icon name="check" className="text-[18px]" />}
              Save
            </button>
          </>
        }
      >
        {saveGeofence.isError && (
          <Alert variant="error" title="Could not save" className="mb-md">
            {saveGeofence.error instanceof ApiError ? saveGeofence.error.message : 'Please try again.'}
          </Alert>
        )}

        <form
          id="geofence-form"
          className="grid gap-md sm:grid-cols-2"
          onSubmit={(event) => {
            event.preventDefault();
            const formData = new FormData(event.currentTarget);
            saveGeofence.mutate({
              locationName: String(formData.get('locationName') ?? '').trim(),
              address: String(formData.get('address') ?? '') || null,
              latitude: Number(formData.get('latitude')),
              longitude: Number(formData.get('longitude')),
              allowedRadiusMeters: Number(formData.get('allowedRadiusMeters')),
              locationType: String(formData.get('locationType') ?? 'OFFICE'),
              openToAll: formData.get('openToAll') === 'on',
              active: true,
            });
          }}
        >
          <div className="sm:col-span-2">
            <label htmlFor="geo-name" className="field-label">Location name</label>
            <input id="geo-name" name="locationName" required maxLength={150}
              placeholder="e.g. Tower B - Main Gate"
              defaultValue={geofenceDialog.location?.locationName ?? ''} className="field-input" />
          </div>
          <div>
            <label htmlFor="geo-lat" className="field-label">Latitude</label>
            <input id="geo-lat" name="latitude" type="number" step="0.0000001" min={-90} max={90} required
              defaultValue={geofenceDialog.location?.latitude ?? ''} className="field-input font-mono" />
          </div>
          <div>
            <label htmlFor="geo-lng" className="field-label">Longitude</label>
            <input id="geo-lng" name="longitude" type="number" step="0.0000001" min={-180} max={180} required
              defaultValue={geofenceDialog.location?.longitude ?? ''} className="field-input font-mono" />
          </div>
          <div>
            <label htmlFor="geo-radius" className="field-label">Allowed radius (metres)</label>
            <input id="geo-radius" name="allowedRadiusMeters" type="number" min={10} max={5000} required
              defaultValue={geofenceDialog.location?.allowedRadiusMeters ?? 200} className="field-input" />
            <p className="mt-unit text-body-sm text-on-surface-variant">
              Capped at 5000 m. A wide circle is the easiest way to defeat a geofence.
            </p>
          </div>
          <div>
            <label htmlFor="geo-type" className="field-label">Type</label>
            <select id="geo-type" name="locationType"
              defaultValue={geofenceDialog.location?.locationType ?? 'OFFICE'} className="field-input">
              {LOCATION_TYPES.map((type) => (
                <option key={type} value={type}>{type.replace('_', ' ')}</option>
              ))}
            </select>
          </div>
          <div className="sm:col-span-2">
            <label htmlFor="geo-address" className="field-label">Address</label>
            <input id="geo-address" name="address" maxLength={500}
              defaultValue={geofenceDialog.location?.address ?? ''} className="field-input" />
          </div>
          <label className="flex cursor-pointer items-center gap-2 sm:col-span-2">
            <input type="checkbox" name="openToAll"
              defaultChecked={geofenceDialog.location?.openToAll ?? false}
              className="h-4 w-4 rounded-sm border-outline-variant text-primary focus:ring-primary" />
            <span className="text-body-md text-on-surface">
              Open to all staff
              <span className="block text-body-sm text-on-surface-variant">
                Any employee can punch here, not just those posted to this office. Use for client sites.
              </span>
            </span>
          </label>
        </form>
      </Modal>
    </div>
  );
};
