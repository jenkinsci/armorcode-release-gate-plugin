package io.jenkins.plugins.armorcode;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import hudson.util.FormValidation;
import io.jenkins.plugins.armorcode.config.ArmorCodeGlobalConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

/**
 * Test cases for ArmorCode global configuration.
 */
@WithJenkins
class ArmorCodeGlobalConfigTest {

    private JenkinsRule j;

    @BeforeEach
    void beforeEach(JenkinsRule rule) {
        j = rule;
    }

    /**
     * Test that global configuration can be saved and loaded correctly.
     */
    @Test
    void testGlobalConfigSaveAndLoad() {
        // Get the configuration
        ArmorCodeGlobalConfig config = ArmorCodeGlobalConfig.get();

        // Set some test values
        config.setBaseUrl("http://localhost:3000/test");
        config.setMonitorBuilds(false);
        config.setJobFilter("prod.*,!test.*");

        // Save the configuration
        config.save();

        // Reload the configuration (simulating a Jenkins restart)
        ArmorCodeGlobalConfig reloaded = ArmorCodeGlobalConfig.get();

        // Verify values are preserved
        assertEquals("http://localhost:3000/test", reloaded.getBaseUrl(), "Base URL should be preserved");
        assertFalse(reloaded.isMonitorBuilds(), "Monitor builds setting should be preserved");
        assertEquals("prod.*,!test.*", reloaded.getJobFilter(), "Job filter should be preserved");
    }

    /**
     * Test URL validation logic.
     */
    @Test
    void testUrlValidation() {
        ArmorCodeGlobalConfig config = ArmorCodeGlobalConfig.get();

        // Empty URL
        FormValidation result1 = config.doCheckBaseUrl("");
        assertEquals(FormValidation.Kind.ERROR, result1.kind);

        // Invalid URL format
        FormValidation result2 = config.doCheckBaseUrl("not-a-url");
        assertEquals(FormValidation.Kind.ERROR, result2.kind);

        // Valid URL
        FormValidation result3 = config.doCheckBaseUrl("https://app.armorcode.com");
        assertEquals(FormValidation.Kind.OK, result3.kind);
    }
}
