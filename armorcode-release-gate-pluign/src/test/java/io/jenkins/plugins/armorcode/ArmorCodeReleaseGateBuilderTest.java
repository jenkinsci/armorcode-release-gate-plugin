package io.jenkins.plugins.armorcode;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.cloudbees.plugins.credentials.CredentialsScope;
import com.cloudbees.plugins.credentials.SystemCredentialsProvider;
import hudson.model.FreeStyleBuild;
import hudson.model.FreeStyleProject;
import hudson.model.Run;
import hudson.util.Secret;
import io.jenkins.plugins.armorcode.credentials.CredentialsUtils;
import java.lang.reflect.Method;
import net.sf.json.JSONObject;
import org.jenkinsci.plugins.plaincredentials.impl.StringCredentialsImpl;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.JenkinsRule;

/**
 * Test cases for ArmorCodeReleaseGateBuilder.
 */
public class ArmorCodeReleaseGateBuilderTest {

    @Rule
    public JenkinsRule jenkins = new JenkinsRule();

    /**
     * Test that detailed error messages are correctly formatted.
     */
    @Test
    public void testDetailedErrorFormatting() throws Exception {
        // Create an instance of the builder
        ArmorCodeReleaseGateBuilder builder = new ArmorCodeReleaseGateBuilder("123", "456", "Production");

        // Create a simple FreeStyleBuild through JenkinsRule
        FreeStyleProject project = jenkins.createFreeStyleProject("test-formatting");
        FreeStyleBuild build = project.scheduleBuild2(0).get();

        // Create a mock JSON response with failure details
        JSONObject mockResponse = new JSONObject();
        mockResponse.put("status", "FAILED");
        mockResponse.put("failureReasonText", "There are 5 Very Poor Risk Findings, while the maximum allowable is 0.");
        mockResponse.put("groupName", "Demo Group");
        mockResponse.put("subGroupName", "Demo Sub Group");

        // Add severity data for findings scope
        JSONObject severity = new JSONObject();
        severity.put("Critical", 1);
        severity.put("High", 2);
        severity.put("Medium", 3);
        severity.put("Low", 4);
        mockResponse.put("severity", severity);

        mockResponse.put("link", "https://app.armorcode.com/details/456");

        // Use reflection to access the private formatDetailedErrorMessage method
        Method formatMethod = ArmorCodeReleaseGateBuilder.class.getDeclaredMethod(
                "formatDetailedErrorMessage", Run.class, JSONObject.class);
        formatMethod.setAccessible(true);

        // Call the method and verify the result
        String errorMessage = (String) formatMethod.invoke(builder, build, mockResponse);

        // Verify that the message contains key elements
        assertTrue("Error message should contain group ID", errorMessage.contains("Group: 123"));
        assertTrue("Error message should contain sub-group ID", errorMessage.contains("Sub Group: 456"));
        assertTrue("Error message should contain reason", errorMessage.contains("There are 5 Very Poor Risk Findings"));

        // Check for severity information
        assertTrue(
                "Error message should include severity breakdown",
                errorMessage.contains("Findings Scope: 1 Critical, 2 High, 3 Medium, 4 Low"));

        // Check for link
        assertTrue(
                "Error message should include details link",
                errorMessage.contains("https://app.armorcode.com/details/456"));

        // Check if the buildNumber is included in the link
        assertTrue(
                "Error message should include build number in details link",
                errorMessage.contains("filters=%7B%22buildNumber%22"));
    }

    /**
     * Test behavior in block mode when validation fails.
     */
    //    @Test
    //    public void testBlockModeBehavior() throws Exception {
    //        // Create a credential
    //        String tokenValue = "my-secret-token";
    //        StringCredentialsImpl credential = new StringCredentialsImpl(
    //                CredentialsScope.GLOBAL,
    //                "ARMORCODE_TOKEN",
    //                "dummy token credential",
    //                Secret.fromString(tokenValue)
    //        );
    //        SystemCredentialsProvider.getInstance().getCredentials().add(credential);
    //        SystemCredentialsProvider.getInstance().save();
    //
    //        // Create a freestyle project
    //        FreeStyleProject project = jenkins.createFreeStyleProject("test-block-mode");
    //
    //        // Create the builder with block mode
    //        ArmorCodeReleaseGateBuilder builder = new ArmorCodeReleaseGateBuilder("123", "456", "Production");
    //        builder.setMode("block");
    //        builder.setTestMode(false); // Explicitly disable test mode
    //
    //        // Use string instead of JSONObject - update the JSON to include the status field
    //        builder.setTestResponseString("{\"status\":\"FAILED\",\"failureReasonText\":\"SLA violations\"}");
    //
    //        // Add builder to project
    //        project.getBuildersList().add(builder);
    //
    //        // Run the build - it should fail due to FAILED status
    //        FreeStyleBuild build = project.scheduleBuild2(0).get();
    //
    //        // Verify the build was marked as failed
    //        assertEquals("Build should be failed in block mode", Result.FAILURE, build.getResult());
    //    }
    //
    //    @Test
    //    public void testWarnModeBehavior() throws Exception {
    //        // Create a credential
    //        String tokenValue = "my-secret-token";
    //        StringCredentialsImpl credential = new StringCredentialsImpl(
    //                CredentialsScope.GLOBAL,
    //                "ARMORCODE_TOKEN",
    //                "dummy token credential",
    //                Secret.fromString(tokenValue)
    //        );
    //        SystemCredentialsProvider.getInstance().getCredentials().add(credential);
    //        SystemCredentialsProvider.getInstance().save();
    //
    //        // Create a freestyle project
    //        FreeStyleProject project = jenkins.createFreeStyleProject("test-warn-mode");
    //
    //        // Create the builder with warn mode
    //        ArmorCodeReleaseGateBuilder builder = new ArmorCodeReleaseGateBuilder("123", "456", "Production");
    //        builder.setMode("warn");
    //        builder.setTestMode(false); // Explicitly disable test mode
    //
    //        // Use string instead of JSONObject - update the JSON to include the status field
    //        builder.setTestResponseString("{\"status\":\"FAILED\",\"failureReasonText\":\"SLA violations\"}");
    //
    //        // Add builder to project
    //        project.getBuildersList().add(builder);
    //
    //        // Add a shell step that will only execute if the build continues after warning
    //        project.getBuildersList().add(new hudson.tasks.Shell("echo 'Should reach here in warn mode'"));
    //
    //        // Run the build - it should be marked unstable but continue
    //        FreeStyleBuild build = project.scheduleBuild2(0).get();
    //
    //        // Verify the build was marked as unstable
    //        assertEquals("Build should be unstable in warn mode", Result.UNSTABLE, build.getResult());
    //
    //        // Verify the build continued (shell command ran)
    //        String log = build.getLog();
    //        assertTrue("Build should continue in warn mode", log.contains("Should reach here in warn mode"));
    //    }

    /**
     * Test getting ArmorCode token from credentials.
     */
    @Test
    public void testGetArmorCodeToken() throws Exception {
        // Create a dummy credential
        String tokenValue = "dummy-token";
        StringCredentialsImpl credential = new StringCredentialsImpl(
                CredentialsScope.GLOBAL, "ARMORCODE_TOKEN", "dummy token credential", Secret.fromString(tokenValue));
        SystemCredentialsProvider.getInstance().getCredentials().add(credential);
        SystemCredentialsProvider.getInstance().save();

        // Create a dummy job and run a build
        FreeStyleProject project = jenkins.createFreeStyleProject("test-credential");
        FreeStyleBuild build = project.scheduleBuild2(0).get();

        // Verify token is retrieved correctly
        String retrievedToken = CredentialsUtils.getArmorCodeToken(build);
        assertEquals("Token should match", tokenValue, retrievedToken);
    }
}
