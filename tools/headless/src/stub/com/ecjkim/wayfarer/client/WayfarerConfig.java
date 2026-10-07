package com.ecjkim.wayfarer.client;

/**
 * Headless stand-in for the real malilib-backed {@code WayfarerConfig}.
 *
 * <p>
 * Only the three navigation getters {@code Router} touches are provided. Default values are copied verbatim from
 * {@code WayfarerConfigs.Generic} so the numbers the harness sees are the shipped ones.
 * </p>
 */
public final class WayfarerConfig {
    private static final WayfarerConfig INSTANCE = new WayfarerConfig();

    private double navDistanceGate = 200.0D;

    private WayfarerConfig() {}

    public static WayfarerConfig getInstance() {
        return INSTANCE;
    }

    public double getNavSnapRadius() {
        return 32.0D;
    }

    public double getNavRerouteThreshold() {
        return 8.0D;
    }

    public double getNavArrivalRadius() {
        return 5.0D;
    }

    public double getNavDistanceGate() {
        return navDistanceGate;
    }

    /** Harness-only knob so both branches of the distance gate can be exercised. */
    public void setNavDistanceGate(double value) {
        this.navDistanceGate = value;
    }

    public double getNavigationSpeed(char classification) {
        switch (classification) {
            case 'G':
                return 5.5D;
            case 'S':
                return 5.0D;
            case 'Y':
                return 4.5D;
            case 'X':
                return 4.0D;
            case 'C':
                return 3.5D;
            default:
                return 4.3D;
        }
    }
}
