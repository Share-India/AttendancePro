package com.company.attendance.service;

import com.company.attendance.entity.Employee;
import com.company.attendance.entity.LocationPolicy;
import com.company.attendance.entity.OfficeLocation;
import com.company.attendance.enums.ErrorCode;
import com.company.attendance.enums.WorkMode;
import com.company.attendance.exception.BusinessException;
import com.company.attendance.repository.OfficeLocationRepository;
import com.company.attendance.util.GeoUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Decides whether a punch is close enough to a permitted location.
 *
 * <p>This runs on the server on every punch. The browser may pre-compute a
 * distance to tell the employee "you are 340 m away" before they tap — that is
 * a courtesy, and the server repeats every check regardless of what the client
 * claims.
 */
@Service
public class GeofenceService {

    private static final Logger log = LoggerFactory.getLogger(GeofenceService.class);

    /** Widest circle to consider when building the bounding-box prefilter. */
    private static final int MAX_CONSIDERED_RADIUS_M = 5_000;

    private final OfficeLocationRepository officeLocationRepository;

    public GeofenceService(OfficeLocationRepository officeLocationRepository) {
        this.officeLocationRepository = officeLocationRepository;
    }

    /**
     * The outcome of a location check.
     *
     * @param matchedLocation the circle the punch fell inside, if any
     * @param distanceMeters  distance to the nearest candidate, null when no check ran
     * @param withinGeofence  whether the punch was inside a permitted circle
     * @param checkPerformed  false for work modes that skip the radius entirely
     */
    public record GeofenceResult(
            OfficeLocation matchedLocation,
            BigDecimal distanceMeters,
            boolean withinGeofence,
            boolean checkPerformed,
            String locationLabel
    ) {
        public static GeofenceResult skipped(String label) {
            return new GeofenceResult(null, null, true, false, label);
        }
    }

    /**
     * Validates a punch location, throwing when it must be refused.
     *
     * @param wfhApproved whether an approved work-from-home request covers this date
     */
    @Transactional(readOnly = true)
    public GeofenceResult validate(Employee employee,
                                   BigDecimal latitude,
                                   BigDecimal longitude,
                                   BigDecimal accuracyMeters,
                                   WorkMode workMode,
                                   LocationPolicy policy,
                                   boolean wfhApproved) {

        return switch (workMode) {
            case WFH -> validateWorkFromHome(policy, wfhApproved);
            case FIELD -> validateField(policy, latitude, longitude);
            case OFFICE, CLIENT_SITE ->
                    validateAgainstGeofence(employee, latitude, longitude, accuracyMeters, policy);
        };
    }

    // -----------------------------------------------------------------

    private GeofenceResult validateWorkFromHome(LocationPolicy policy, boolean wfhApproved) {
        if (!wfhApproved) {
            throw new BusinessException(ErrorCode.WFH_NOT_APPROVED,
                    "You do not have an approved work-from-home request for today.");
        }
        if (!policy.isAllowWfhWithoutGeofence()) {
            throw new BusinessException(ErrorCode.POLICY_VIOLATION,
                    "Your location policy requires you to punch from an office location.");
        }
        return GeofenceResult.skipped("Work from home");
    }

    private GeofenceResult validateField(LocationPolicy policy, BigDecimal latitude, BigDecimal longitude) {
        if (!policy.isAllowFieldPunch()) {
            throw new BusinessException(ErrorCode.POLICY_VIOLATION,
                    "Field attendance is not enabled for you. Punch from an office location, "
                            + "or ask HR to enable field attendance.");
        }
        if (!GeoUtils.isValidCoordinate(latitude, longitude)) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "A valid location is required for field attendance.");
        }
        // Coordinates are recorded; no radius applies. Reverse geocoding to a
        // readable place name happens asynchronously, if a provider is configured.
        return GeofenceResult.skipped("Field location");
    }

    private GeofenceResult validateAgainstGeofence(Employee employee,
                                                   BigDecimal latitude,
                                                   BigDecimal longitude,
                                                   BigDecimal accuracyMeters,
                                                   LocationPolicy policy) {

        if (!policy.isEnforceGeofence()) {
            return GeofenceResult.skipped("Geofence not enforced");
        }

        if (!GeoUtils.isValidCoordinate(latitude, longitude)) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "Location is required to punch. Allow location access and try again.");
        }

        // A fix vaguer than the policy allows cannot be placed inside a radius
        // with any confidence, so it is refused rather than guessed at.
        if (accuracyMeters != null && accuracyMeters.intValue() > policy.getMaxAccuracyMeters()) {
            throw new BusinessException(ErrorCode.INSUFFICIENT_LOCATION_ACCURACY,
                    "Your location is accurate to only " + accuracyMeters.intValue()
                            + " m; " + policy.getMaxAccuracyMeters() + " m or better is required. "
                            + "Move somewhere with a clearer view of the sky and try again.",
                    Map.of("accuracyMeters", accuracyMeters,
                            "requiredAccuracyMeters", policy.getMaxAccuracyMeters()));
        }

        Long officeId = employee.getOffice() != null ? employee.getOffice().getId() : null;
        if (officeId == null) {
            throw new BusinessException(ErrorCode.POLICY_VIOLATION,
                    "You are not assigned to an office, so no location can be verified. Contact HR.");
        }

        List<OfficeLocation> candidates = findCandidates(officeId, latitude, longitude);

        if (candidates.isEmpty()) {
            // Either the office has no circles at all, or none is remotely near.
            // Both mean the same thing to the employee, but the message must not
            // pretend the office is nearby when it is not.
            List<OfficeLocation> anyActive = officeLocationRepository.findByOfficeIdAndActiveTrue(officeId);
            if (anyActive.isEmpty()) {
                log.error("Office {} has no active geofence; nobody assigned there can punch in", officeId);
                throw new BusinessException(ErrorCode.POLICY_VIOLATION,
                        "No office location is configured for your office. Contact HR — "
                                + "this is a setup problem, not something you can fix.");
            }
            throw outsideGeofence(nearest(anyActive, latitude, longitude));
        }

        Nearest nearest = nearest(candidates, latitude, longitude);

        if (nearest.distanceMeters() > nearest.location().getAllowedRadiusMeters()) {
            throw outsideGeofence(nearest);
        }

        return new GeofenceResult(
                nearest.location(),
                BigDecimal.valueOf(nearest.distanceMeters()).setScale(2, RoundingMode.HALF_UP),
                true,
                true,
                nearest.location().getLocationName());
    }

    /**
     * Narrows candidates with a bounding box in SQL before any trigonometry.
     * Matters once an organisation has hundreds of sites.
     */
    private List<OfficeLocation> findCandidates(Long officeId, BigDecimal latitude, BigDecimal longitude) {
        BigDecimal latDelta = GeoUtils.latitudeDelta(MAX_CONSIDERED_RADIUS_M);
        BigDecimal lngDelta = GeoUtils.longitudeDelta(latitude.doubleValue(), MAX_CONSIDERED_RADIUS_M);

        return officeLocationRepository.findCandidatesInBoundingBox(
                officeId,
                latitude.subtract(latDelta),
                latitude.add(latDelta),
                longitude.subtract(lngDelta),
                longitude.add(lngDelta));
    }

    private record Nearest(OfficeLocation location, double distanceMeters) {
    }

    private Nearest nearest(List<OfficeLocation> locations, BigDecimal latitude, BigDecimal longitude) {
        OfficeLocation best = null;
        double bestDistance = Double.MAX_VALUE;

        for (OfficeLocation location : locations) {
            double distance = GeoUtils.distanceInMeters(
                    latitude, longitude, location.getLatitude(), location.getLongitude());
            if (distance < bestDistance) {
                bestDistance = distance;
                best = location;
            }
        }
        return new Nearest(best, bestDistance);
    }

    /**
     * Rejection carries the distance and the radius. Telling the employee how
     * far away they are turns a dead end into something they can act on, and it
     * is their own position relative to their own office — not sensitive.
     */
    private BusinessException outsideGeofence(Nearest nearest) {
        // Office and location are separate fields rather than concatenated:
        // a site named "Head Office - Main Gate" would otherwise render as
        // "Head Office - Head Office - Main Gate".
        Map<String, Object> details = new HashMap<>();
        details.put("nearestOffice", nearest.location().getOffice().getName());
        details.put("nearestLocation", nearest.location().getLocationName());
        details.put("distanceMeters", BigDecimal.valueOf(nearest.distanceMeters())
                .setScale(1, RoundingMode.HALF_UP));
        details.put("allowedRadiusMeters", nearest.location().getAllowedRadiusMeters());

        return new BusinessException(ErrorCode.OUTSIDE_GEOFENCE,
                "You are outside the permitted office location", details);
    }
}
