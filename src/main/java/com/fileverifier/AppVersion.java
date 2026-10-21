package com.fileverifier;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/**
 * The version this build was made from. It comes from a resource the build stamps with
 * {@code ${project.version}}, so the number in the window is the number the artifact was built with -
 * not a constant that someone has to remember to change, and not something that can quietly disagree with
 * the jar name, the tag, or the changelog.
 */
public final class AppVersion {

    private static final String RESOURCE = "com/fileverifier/version.properties";
    private static final String VERSION = describeSafely();

    private AppVersion() {
    }

    /** e.g. "1.3.0", or null if this build carries no version stamp. */
    public static String version() {
        return VERSION;
    }

    /** What the window prints. A build with no stamp says so instead of showing a wrong number. */
    public static String display() {
        String version = VERSION;
        return version == null ? "version unknown" : "v" + version;
    }

    private static String describeSafely() {
        try {
            java.net.URL stamped = AppVersion.class.getClassLoader().getResource(RESOURCE);
            if (stamped == null) {
                return null;
            }
            Properties properties = new Properties();
            try (InputStream in = stamped.openStream()) {
                properties.load(in);
            }
            String version = properties.getProperty("version", "").trim();
            // A resource that was never filtered still contains the placeholder, which is not a version.
            return version.isEmpty() || version.contains("${") ? null : version;
        } catch (IOException e) {
            return null;
        }
    }
}
