package io.github.fatahilahuda.kaskeluarga;

/** Small shared state between the activities and background work. */
final class KasState {
    /** True while the main app is on screen: changes then show in the app, not as a notification. */
    static volatile boolean visible;
    /** Page the app should open on (from a notification); taken once by the web app. */
    static volatile String navTo;

    private KasState() {}
}
