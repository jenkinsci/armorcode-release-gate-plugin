package io.jenkins.plugins.armorcode;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.cloudbees.plugins.credentials.CredentialsScope;
import com.cloudbees.plugins.credentials.SystemCredentialsProvider;
import hudson.Extension;
import hudson.model.FreeStyleBuild;
import hudson.model.FreeStyleProject;
import hudson.model.Result;
import hudson.model.TaskListener;
import hudson.util.Secret;
import org.jenkinsci.plugins.plaincredentials.impl.StringCredentialsImpl;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.JenkinsRule;

public class ArmorCodeReleaseGateBuilderTest {

    @Rule
    public JenkinsRule jenkins = new JenkinsRule();

    public static class MockArmorCodeReleaseGateBuilder extends ArmorCodeReleaseGateBuilder {

        private String mockResponse;

        public MockArmorCodeReleaseGateBuilder(String product, Object subProducts, String env) {
            super(product, subProducts, env);
        }

        public void setMockResponse(String mockResponse) {
            this.mockResponse = mockResponse;
        }

        @Override
        protected String postArmorCodeRequest(
                TaskListener listener,
                String token,
                String buildNumber,
                String jobName,
                int current,
                int end,
                String apiUrl,
                String jobUrl)
                throws Exception {
            if (mockResponse != null) {
                return mockResponse;
            }
            return super.postArmorCodeRequest(listener, token, buildNumber, jobName, current, end, apiUrl, jobUrl);
        }

        @Extension
        public static class DescriptorImpl extends hudson.tasks.BuildStepDescriptor<hudson.tasks.Builder> {
            @Override
            public boolean isApplicable(Class jobType) {
                return true;
            }

            @Override
            public String getDisplayName() {
                return "Mock ArmorCode Release Gate";
            }
        }
    }

    @Test
    public void testBlockModeBehavior() throws Exception {
        // Create a credential
        String tokenValue = "my-secret-token";
        StringCredentialsImpl credential = new StringCredentialsImpl(
                CredentialsScope.GLOBAL, "ARMORCODE_TOKEN", "dummy token credential", Secret.fromString(tokenValue));
        SystemCredentialsProvider.getInstance().getCredentials().add(credential);
        SystemCredentialsProvider.getInstance().save();

        // Create a freestyle project
        FreeStyleProject project = jenkins.createFreeStyleProject("test-block-mode");

        // Create the builder with block mode
        MockArmorCodeReleaseGateBuilder builder = new MockArmorCodeReleaseGateBuilder("123", "456", "Production");
        builder.setMode("block");
        builder.setMockResponse("{\"status\":\"FAILED\",\"failureReasonText\":\"SLA violations\"}");

        // Add builder to project
        project.getBuildersList().add(builder);

        // Run the build - it should fail due to FAILED status
        FreeStyleBuild build = project.scheduleBuild2(0).get();

        // Verify the build was marked as failed
        assertEquals("Build should be failed in block mode", Result.FAILURE, build.getResult());
    }

    @Test
    public void testWarnModeBehavior() throws Exception {
        // Create a credential
        String tokenValue = "my-secret-token";
        StringCredentialsImpl credential = new StringCredentialsImpl(
                CredentialsScope.GLOBAL, "ARMORCODE_TOKEN", "dummy token credential", Secret.fromString(tokenValue));
        SystemCredentialsProvider.getInstance().getCredentials().add(credential);
        SystemCredentialsProvider.getInstance().save();

        // Create a freestyle project
        FreeStyleProject project = jenkins.createFreeStyleProject("test-warn-mode");

        // Create the builder with warn mode
        MockArmorCodeReleaseGateBuilder builder = new MockArmorCodeReleaseGateBuilder("123", "456", "Production");
        builder.setMode("warn");
        builder.setMockResponse("{\"status\":\"FAILED\",\"failureReasonText\":\"SLA violations\"}");

        // Add builder to project
        project.getBuildersList().add(builder);

        // Add a shell step that will only execute if the build continues after warning
        project.getBuildersList().add(new hudson.tasks.Shell("echo 'Should reach here in warn mode'"));

        // Save the project configuration
        project.save();

        // Run the build - it should be marked unstable but continue
        FreeStyleBuild build = project.scheduleBuild2(0).get();

        // Verify the build was marked as unstable
        assertEquals("Build should be unstable in warn mode", Result.UNSTABLE, build.getResult());

        // Verify the build continued (shell command ran)
        String log = build.getLog();
        assertTrue("Build should continue in warn mode", log.contains("Should reach here in warn mode"));
    }

    @Test
    public void testSuccessModeBehavior() throws Exception {
        // Create a credential
        String tokenValue = "my-secret-token";
        StringCredentialsImpl credential = new StringCredentialsImpl(
                CredentialsScope.GLOBAL, "ARMORCODE_TOKEN", "dummy token credential", Secret.fromString(tokenValue));
        SystemCredentialsProvider.getInstance().getCredentials().add(credential);
        SystemCredentialsProvider.getInstance().save();

        // Create a freestyle project
        FreeStyleProject project = jenkins.createFreeStyleProject("test-success-mode");

        // Create the builder
        MockArmorCodeReleaseGateBuilder builder = new MockArmorCodeReleaseGateBuilder("123", "456", "Production");
        builder.setMockResponse("{\"status\":\"SUCCESS\"}");

        // Add builder to project
        project.getBuildersList().add(builder);

        // Run the build - it should succeed
        FreeStyleBuild build = project.scheduleBuild2(0).get();

        // Verify the build was marked as success
        assertEquals("Build should be successful", Result.SUCCESS, build.getResult());
    }

    @Test
    public void testHoldModeBehavior() throws Exception {
        // Create a credential
        String tokenValue = "my-secret-token";
        StringCredentialsImpl credential = new StringCredentialsImpl(
                CredentialsScope.GLOBAL, "ARMORCODE_TOKEN", "dummy token credential", Secret.fromString(tokenValue));
        SystemCredentialsProvider.getInstance().getCredentials().add(credential);
        SystemCredentialsProvider.getInstance().save();

        // Create a freestyle project
        FreeStyleProject project = jenkins.createFreeStyleProject("test-hold-mode");

        // Create the builder
        MockArmorCodeReleaseGateBuilder builder = new MockArmorCodeReleaseGateBuilder("123", "456", "Production");
        builder.setMode("block");
        builder.setMaxRetries(2);
        builder.setRetryDelay(1); // Use a short delay for testing
        builder.setMockResponse("{\"status\":\"HOLD\"}");

        // Add builder to project
        project.getBuildersList().add(builder);

        // Run the build - it should fail after retries
        FreeStyleBuild build = project.scheduleBuild2(0).get();

        // Verify the build was marked as failed
        assertEquals("Build should fail after exhausting retries on HOLD", Result.FAILURE, build.getResult());
        String log = build.getLog();
        assertTrue(log.contains("SLA is on HOLD. Sleeping 1s..."));
        assertTrue(log.contains("ArmorCode check did not pass after 2 retries (last status was HOLD)."));
    }

    @Test
    public void testInvalidResponseFailure() throws Exception {
        // Create a credential
        String tokenValue = "my-secret-token";
        StringCredentialsImpl credential = new StringCredentialsImpl(
                CredentialsScope.GLOBAL, "ARMORCODE_TOKEN", "dummy token credential", Secret.fromString(tokenValue));
        SystemCredentialsProvider.getInstance().getCredentials().add(credential);
        SystemCredentialsProvider.getInstance().save();

        // Create a freestyle project
        FreeStyleProject project = jenkins.createFreeStyleProject("test-invalid-response");

        // Create the builder
        MockArmorCodeReleaseGateBuilder builder = new MockArmorCodeReleaseGateBuilder("123", "456", "Production");
        builder.setMaxRetries(2);
        builder.setRetryDelay(1);
        builder.setMockResponse("this is not json");

        // Add builder to project
        project.getBuildersList().add(builder);

        // Run the build - it should fail
        FreeStyleBuild build = project.scheduleBuild2(0).get();

        // Verify the build was marked as failed
        assertEquals("Build should fail with invalid JSON response", Result.FAILURE, build.getResult());
        String log = build.getLog();
        assertTrue(log.contains("ArmorCode request failed:"));
        assertTrue(log.contains("ArmorCode request error after maximum retries."));
    }
}
