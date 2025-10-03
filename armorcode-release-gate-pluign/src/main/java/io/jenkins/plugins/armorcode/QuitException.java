package io.jenkins.plugins.armorcode;

/**
 * A Jenkins build step that polls ArmorCode's build validation endpoint
 * to enforce release gates. It can operate in either "warn" mode (marking
 * the build as UNSTABLE if validation fails) or "block" mode (failing the build).
 */
public class QuitException extends Error {
    public QuitException(String message) {
        super(message);
    }
}
