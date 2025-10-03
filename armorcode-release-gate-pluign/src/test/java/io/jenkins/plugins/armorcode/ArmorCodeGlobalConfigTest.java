package io.jenkins.plugins.armorcode;

import hudson.util.FormValidation;
import io.jenkins.plugins.armorcode.config.ArmorCodeGlobalConfig;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.JenkinsRule;

import static org.junit.Assert.*;

/**
 * Test cases for ArmorCode global configuration.
 */
public class ArmorCodeGlobalConfigTest {

    @Rule
    public JenkinsRule jenkins = new JenkinsRule();

    /**
     * Test that global configuration can be saved and loaded correctly.
     */
    @Test
    public void testGlobalConfigSaveAndLoad() {
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
        assertEquals("Base URL should be preserved", "http://localhost:3000/test", reloaded.getBaseUrl());
        assertFalse("Monitor builds setting should be preserved", reloaded.isMonitorBuilds());
        assertEquals("Job filter should be preserved", "prod.*,!test.*", reloaded.getJobFilter());
    }

    /**
     * Test URL validation logic.
     */
    @Test
    public void testUrlValidation() {
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