package io.jenkins.plugins.armorcode.credentials;

import com.cloudbees.plugins.credentials.CredentialsMatchers;
import com.cloudbees.plugins.credentials.CredentialsProvider;
import com.cloudbees.plugins.credentials.domains.DomainRequirement;
import hudson.model.Run;
import hudson.security.ACL;
import java.util.Collections;
import java.util.List;
import org.jenkinsci.plugins.plaincredentials.StringCredentials;

public class CredentialsUtils {
    /**
     * Retrieves a secret text credential by its ID.
     *
     * @param run           the current build run, needed for credentials lookup
     * @param credentialsId the credentials ID as configured in Jenkins Credentials
     * @return the secret text or null if not found
     */
    public static String getSecretText(Run<?, ?> run, String credentialsId) {
        List<DomainRequirement> domainRequirements = Collections.emptyList();
        StringCredentials credential = CredentialsMatchers.firstOrNull(
                CredentialsProvider.lookupCredentials(
                        StringCredentials.class, run.getParent(), ACL.SYSTEM, domainRequirements),
                CredentialsMatchers.withId(credentialsId));
        return credential != null ? credential.getSecret().getPlainText() : null;
    }

    /**
     * Convenience method to get the ArmorCode Token.
     */
    public static String getArmorCodeToken(Run<?, ?> run) {
        // Ensure you have a credential in Jenkins with the ID 'ARMORCODE_TOKEN'
        return getSecretText(run, "ARMORCODE_TOKEN");
    }

    /**
     * Gets the ArmorCode token from system credentials.
     * Used for system-level operations like job discovery.
     */
    public static String getSystemToken() {
        List<DomainRequirement> domainRequirements = Collections.emptyList();
        StringCredentials credential = CredentialsMatchers.firstOrNull(
                CredentialsProvider.lookupCredentials(
                        StringCredentials.class, jenkins.model.Jenkins.get(), ACL.SYSTEM, domainRequirements),
                CredentialsMatchers.withId("ARMORCODE_TOKEN"));
        return credential != null ? credential.getSecret().getPlainText() : null;
    }
}
