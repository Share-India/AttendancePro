package com.company.attendance.enums;

/**
 * How wide a principal's data access is for a given resource.
 * SELF -> own rows, TEAM -> the reporting subtree, ORG -> unrestricted.
 */
public enum AccessScope {
    SELF, TEAM, ORG
}
