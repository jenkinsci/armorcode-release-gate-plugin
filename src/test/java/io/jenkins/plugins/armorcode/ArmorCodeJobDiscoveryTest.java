package io.jenkins.plugins.armorcode;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.cloudbees.plugins.credentials.CredentialsScope;
import com.cloudbees.plugins.credentials.SystemCredentialsProvider;
import hudson.model.FreeStyleProject;
import hudson.model.TaskListener;
import hudson.util.Secret;
import hudson.util.StreamTaskListener;
import io.jenkins.plugins.armorcode.config.ArmorCodeGlobalConfig;
import java.lang.reflect.Method;
import java.util.List;
import java.util.concurrent.TimeUnit;
import net.sf.json.JSONObject;
import org.jenkinsci.plugins.plaincredentials.impl.StringCredentialsImpl;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

@WithJenkins
class ArmorCodeJobDiscoveryTest {

    private JenkinsRule j;
    private ArmorCodeJobDiscovery discovery;
    private Method shouldMonitorJobMethod;
    private Method isUsingArmorCodePluginMethod;
    private Method collectJobsDataMethod;

    @BeforeEach
    void setUp(JenkinsRule rule) throws Exception {
        j = rule;
        discovery = new ArmorCodeJobDiscovery();
        shouldMonitorJobMethod = ArmorCodeJobDiscovery.class.getDeclaredMethod(
                "shouldMonitorJob", String.class, String.class, String.class);
        shouldMonitorJobMethod.setAccessible(true);

        isUsingArmorCodePluginMethod =
                ArmorCodeJobDiscovery.class.getDeclaredMethod("isUsingArmorCodePlugin", hudson.model.Job.class);
        isUsingArmorCodePluginMethod.setAccessible(true);

        collectJobsDataMethod = ArmorCodeJobDiscovery.class.getDeclaredMethod(
                "collectJobsData", ArmorCodeGlobalConfig.class, TaskListener.class);
        collectJobsDataMethod.setAccessible(true);
    }

    @Test
    void testShouldMonitorJob() throws Exception {
        // Test case 1: No patterns, should include all
        assertTrue(
                (boolean) shouldMonitorJobMethod.invoke(discovery, "any-job", "", ""),
                "Should include with no patterns");

        // Test case 2: Include pattern matches
        assertTrue(
                (boolean) shouldMonitorJobMethod.invoke(discovery, "prod-job-1", "prod-.*", ""),
                "Should include when matching include pattern");

        // Test case 3: Include pattern does not match
        assertFalse(
                (boolean) shouldMonitorJobMethod.invoke(discovery, "dev-job-1", "prod-.*", ""),
                "Should exclude when not matching include pattern");

        // Test case 4: Exclude pattern matches
        assertFalse(
                (boolean) shouldMonitorJobMethod.invoke(discovery, "test-job-1", "", "test-.*"),
                "Should exclude when matching exclude pattern");

        // Test case 5: Both include and exclude patterns match (exclude takes precedence)
        assertFalse(
                (boolean) shouldMonitorJobMethod.invoke(discovery, "prod-test-job", "prod-.*", ".*-test-.*"),
                "Should exclude when both patterns match");

        // Test case 6: Include all, exclude some
        assertTrue(
                (boolean) shouldMonitorJobMethod.invoke(discovery, "prod-job-1", "", "test-.*"),
                "Should include if not matching exclude");
        assertFalse(
                (boolean) shouldMonitorJobMethod.invoke(discovery, "test-job-1", "", "test-.*"),
                "Should exclude if matching exclude");
    }

    @Test
    void testIsUsingArmorCodePlugin_Freestyle() throws Exception {
        // Create a freestyle project with the ArmorCode builder
        FreeStyleProject projectWithPlugin = j.createFreeStyleProject("freestyle-with-plugin");
        projectWithPlugin.getBuildersList().add(new ArmorCodeReleaseGateBuilder("1", "1", "1"));

        // Create a freestyle project without the ArmorCode builder
        FreeStyleProject projectWithoutPlugin = j.createFreeStyleProject("freestyle-without-plugin");

        // Test
        assertTrue(
                (boolean) isUsingArmorCodePluginMethod.invoke(discovery, projectWithPlugin),
                "Should detect plugin in Freestyle project with builder");
        assertFalse(
                (boolean) isUsingArmorCodePluginMethod.invoke(discovery, projectWithoutPlugin),
                "Should not detect plugin in Freestyle project without builder");
    }

    @Test
    void testIsUsingArmorCodePlugin_Pipeline() throws Exception {
        // Create a credential
        String tokenValue = "my-secret-token";
        StringCredentialsImpl credential = new StringCredentialsImpl(
                CredentialsScope.GLOBAL, "ARMORCODE_TOKEN", "dummy token credential", Secret.fromString(tokenValue));
        SystemCredentialsProvider.getInstance().getCredentials().add(credential);
        SystemCredentialsProvider.getInstance().save();

        // Create a pipeline project with the ArmorCode step
        // Don't run the build - just check if the config XML contains the step
        WorkflowJob pipelineWithPlugin = j.createProject(WorkflowJob.class, "pipeline-with-plugin");
        pipelineWithPlugin.setDefinition(new CpsFlowDefinition(
                "node { armorcodeReleaseGate(product: '1', subProducts: ['1'], env: '1') }", true));

        // Create a pipeline project without the ArmorCode step
        WorkflowJob pipelineWithoutPlugin = j.createProject(WorkflowJob.class, "pipeline-without-plugin");
        pipelineWithoutPlugin.setDefinition(new CpsFlowDefinition("node { echo 'hello' }", true));

        // Test
        assertTrue(
                (boolean) isUsingArmorCodePluginMethod.invoke(discovery, pipelineWithPlugin),
                "Should detect plugin in Pipeline project with step");
        assertFalse(
                (boolean) isUsingArmorCodePluginMethod.invoke(discovery, pipelineWithoutPlugin),
                "Should not detect plugin in Pipeline project without step");
    }

    @Test
    void testCollectJobsData() throws Exception {
        // Create a credential
        String tokenValue = "my-secret-token";
        StringCredentialsImpl credential = new StringCredentialsImpl(
                CredentialsScope.GLOBAL, "ARMORCODE_TOKEN", "dummy token credential", Secret.fromString(tokenValue));
        SystemCredentialsProvider.getInstance().getCredentials().add(credential);
        SystemCredentialsProvider.getInstance().save();

        // Configure global settings
        ArmorCodeGlobalConfig config = ArmorCodeGlobalConfig.get();
        config.setIncludeJobsPattern("prod-.*");
        config.setExcludeJobsPattern(".*-ignore");
        config.save();

        // Create jobs
        // 1. Should be included (matches include, not exclude, has plugin)
        FreeStyleProject job1 = j.createFreeStyleProject("prod-job-1");
        ArmorCodeReleaseGateBuilderTest.MockArmorCodeReleaseGateBuilder mockBuilder =
                new ArmorCodeReleaseGateBuilderTest.MockArmorCodeReleaseGateBuilder("1", "1", "1");
        mockBuilder.setMockResponse("{\"status\":\"SUCCESS\"}");
        job1.getBuildersList().add(mockBuilder);

        // 2. Should be excluded (matches exclude)
        FreeStyleProject job2 = j.createFreeStyleProject("prod-job-ignore");
        job2.getBuildersList().add(new ArmorCodeReleaseGateBuilder("1", "1", "1"));

        // 3. Should be excluded (does not match include)
        FreeStyleProject job3 = j.createFreeStyleProject("dev-job-1");
        job3.getBuildersList().add(new ArmorCodeReleaseGateBuilder("1", "1", "1"));

        // 4. Should be included (matches include, no plugin)
        FreeStyleProject job4 = j.createFreeStyleProject("prod-job-2");

        // Run a build for job1 to have build data
        j.buildAndAssertSuccess(job1);

        // Call collectJobsData
        TaskListener listener = new StreamTaskListener(System.out, null);
        List<JSONObject> jobsData = (List<JSONObject>) collectJobsDataMethod.invoke(discovery, config, listener);

        // Assertions
        assertEquals(2, jobsData.size(), "Should collect 2 jobs");

        boolean foundJob1 = false;
        boolean foundJob4 = false;

        for (JSONObject jobData : jobsData) {
            String jobName = jobData.getString("jobName");
            if (jobName.equals("prod-job-1")) {
                foundJob1 = true;
                assertTrue(jobData.getBoolean("jobMapped"), "Job 1 should be marked as mapped");
                assertEquals("1", jobData.getString("buildNumber"), "Job 1 should have build number 1");
            } else if (jobName.equals("prod-job-2")) {
                foundJob4 = true;
                assertFalse(jobData.getBoolean("jobMapped"), "Job 4 should not be marked as mapped");
                assertEquals("0", jobData.getString("buildNumber"), "Job 4 should have build number 0");
            }
        }

        assertTrue(foundJob1, "Job 'prod-job-1' should be in the collected data");
        assertTrue(foundJob4, "Job 'prod-job-2' should be in the collected data");
    }

    @Test
    void testGetRecurrencePeriod() throws Exception {
        ArmorCodeGlobalConfig config = ArmorCodeGlobalConfig.get();

        // getRecurrencePeriod() is only called once at startup by AsyncPeriodicWork
        // So it always returns 1 minute regardless of config
        // The execute() method handles whether to actually run based on config and cron

        // Test case 1: Monitoring disabled - should still return 1 minute
        // (execute() method will skip execution when monitoring is disabled)
        config.setMonitorBuilds(false);
        config.save();
        assertEquals(
                TimeUnit.MINUTES.toMillis(1),
                discovery.getRecurrencePeriod(),
                "Should always be 1 minute (called once at startup)");

        // Test case 2: Monitoring enabled - should return 1 minute
        // (execute() method checks cron expression for actual execution)
        config.setMonitorBuilds(true);
        config.save();
        assertEquals(
                TimeUnit.MINUTES.toMillis(1),
                discovery.getRecurrencePeriod(),
                "Should always be 1 minute (called once at startup)");
    }
}
