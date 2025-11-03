package io.jenkins.plugins.armorcode;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

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
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.JenkinsRule;

public class ArmorCodeJobDiscoveryTest {

    @Rule
    public JenkinsRule jenkins = new JenkinsRule();

    private ArmorCodeJobDiscovery discovery;
    private Method shouldMonitorJobMethod;
    private Method isUsingArmorCodePluginMethod;
    private Method collectJobsDataMethod;

    @Before
    public void setUp() throws Exception {
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
    public void testShouldMonitorJob() throws Exception {
        // Test case 1: No patterns, should include all
        assertTrue("Should include with no patterns", (boolean)
                shouldMonitorJobMethod.invoke(discovery, "any-job", "", ""));

        // Test case 2: Include pattern matches
        assertTrue("Should include when matching include pattern", (boolean)
                shouldMonitorJobMethod.invoke(discovery, "prod-job-1", "prod-.*", ""));

        // Test case 3: Include pattern does not match
        assertFalse("Should exclude when not matching include pattern", (boolean)
                shouldMonitorJobMethod.invoke(discovery, "dev-job-1", "prod-.*", ""));

        // Test case 4: Exclude pattern matches
        assertFalse("Should exclude when matching exclude pattern", (boolean)
                shouldMonitorJobMethod.invoke(discovery, "test-job-1", "", "test-.*"));

        // Test case 5: Both include and exclude patterns match (exclude takes precedence)
        assertFalse("Should exclude when both patterns match", (boolean)
                shouldMonitorJobMethod.invoke(discovery, "prod-test-job", "prod-.*", ".*-test-.*"));

        // Test case 6: Include all, exclude some
        assertTrue("Should include if not matching exclude", (boolean)
                shouldMonitorJobMethod.invoke(discovery, "prod-job-1", "", "test-.*"));
        assertFalse("Should exclude if matching exclude", (boolean)
                shouldMonitorJobMethod.invoke(discovery, "test-job-1", "", "test-.*"));
    }

    @Test
    public void testIsUsingArmorCodePlugin_Freestyle() throws Exception {
        // Create a freestyle project with the ArmorCode builder
        FreeStyleProject projectWithPlugin = jenkins.createFreeStyleProject("freestyle-with-plugin");
        projectWithPlugin.getBuildersList().add(new ArmorCodeReleaseGateBuilder("1", "1", "1"));

        // Create a freestyle project without the ArmorCode builder
        FreeStyleProject projectWithoutPlugin = jenkins.createFreeStyleProject("freestyle-without-plugin");

        // Test
        assertTrue("Should detect plugin in Freestyle project with builder", (boolean)
                isUsingArmorCodePluginMethod.invoke(discovery, projectWithPlugin));
        assertFalse("Should not detect plugin in Freestyle project without builder", (boolean)
                isUsingArmorCodePluginMethod.invoke(discovery, projectWithoutPlugin));
    }

    @Test
    public void testIsUsingArmorCodePlugin_Pipeline() throws Exception {
        // Create a credential
        String tokenValue = "my-secret-token";
        StringCredentialsImpl credential = new StringCredentialsImpl(
                CredentialsScope.GLOBAL, "ARMORCODE_TOKEN", "dummy token credential", Secret.fromString(tokenValue));
        SystemCredentialsProvider.getInstance().getCredentials().add(credential);
        SystemCredentialsProvider.getInstance().save();

        // Create a pipeline project with the ArmorCode step
        // Don't run the build - just check if the config XML contains the step
        WorkflowJob pipelineWithPlugin = jenkins.createProject(WorkflowJob.class, "pipeline-with-plugin");
        pipelineWithPlugin.setDefinition(new CpsFlowDefinition(
                "node { armorcodeReleaseGate(product: '1', subProducts: ['1'], env: '1') }",
                true));

        // Create a pipeline project without the ArmorCode step
        WorkflowJob pipelineWithoutPlugin = jenkins.createProject(WorkflowJob.class, "pipeline-without-plugin");
        pipelineWithoutPlugin.setDefinition(new CpsFlowDefinition("node { echo 'hello' }", true));

        // Test
        assertTrue("Should detect plugin in Pipeline project with step", (boolean)
                isUsingArmorCodePluginMethod.invoke(discovery, pipelineWithPlugin));
        assertFalse("Should not detect plugin in Pipeline project without step", (boolean)
                isUsingArmorCodePluginMethod.invoke(discovery, pipelineWithoutPlugin));
    }

    @Test
    public void testCollectJobsData() throws Exception {
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
        FreeStyleProject job1 = jenkins.createFreeStyleProject("prod-job-1");
        ArmorCodeReleaseGateBuilderTest.MockArmorCodeReleaseGateBuilder mockBuilder =
            new ArmorCodeReleaseGateBuilderTest.MockArmorCodeReleaseGateBuilder("1", "1", "1");
        mockBuilder.setMockResponse("{\"status\":\"SUCCESS\"}");
        job1.getBuildersList().add(mockBuilder);

        // 2. Should be excluded (matches exclude)
        FreeStyleProject job2 = jenkins.createFreeStyleProject("prod-job-ignore");
        job2.getBuildersList().add(new ArmorCodeReleaseGateBuilder("1", "1", "1"));

        // 3. Should be excluded (does not match include)
        FreeStyleProject job3 = jenkins.createFreeStyleProject("dev-job-1");
        job3.getBuildersList().add(new ArmorCodeReleaseGateBuilder("1", "1", "1"));

        // 4. Should be included (matches include, no plugin)
        FreeStyleProject job4 = jenkins.createFreeStyleProject("prod-job-2");

        // Run a build for job1 to have build data
        jenkins.buildAndAssertSuccess(job1);

        // Call collectJobsData
        TaskListener listener = new StreamTaskListener(System.out, null);
        List<JSONObject> jobsData = (List<JSONObject>) collectJobsDataMethod.invoke(discovery, config, listener);

        // Assertions
        assertEquals("Should collect 2 jobs", 2, jobsData.size());

        boolean foundJob1 = false;
        boolean foundJob4 = false;

        for (JSONObject jobData : jobsData) {
            String jobName = jobData.getString("jobName");
            if (jobName.equals("prod-job-1")) {
                foundJob1 = true;
                assertTrue("Job 1 should be marked as mapped", jobData.getBoolean("jobMapped"));
                assertEquals("Job 1 should have build number 1", "1", jobData.getString("buildNumber"));
            } else if (jobName.equals("prod-job-2")) {
                foundJob4 = true;
                assertFalse("Job 4 should not be marked as mapped", jobData.getBoolean("jobMapped"));
                assertEquals("Job 4 should have build number 0", "0", jobData.getString("buildNumber"));
            }
        }

        assertTrue("Job 'prod-job-1' should be in the collected data", foundJob1);
        assertTrue("Job 'prod-job-2' should be in the collected data", foundJob4);
    }

    @Test
    public void testGetRecurrencePeriod() throws Exception {
        ArmorCodeGlobalConfig config = ArmorCodeGlobalConfig.get();

        // Test case 1: Monitoring disabled - should return 1 hour
        config.setMonitorBuilds(false);
        config.save();
        assertEquals(
                "Should be 1 hour when disabled",
                TimeUnit.HOURS.toMillis(1),
                discovery.getRecurrencePeriod());

        // Test case 2: Monitoring enabled - should return 1 minute (fixed interval)
        // The actual execution is controlled by cron matching in execute()
        config.setMonitorBuilds(true);
        config.save();
        assertEquals(
                "Should be 1 minute when enabled",
                TimeUnit.MINUTES.toMillis(1),
                discovery.getRecurrencePeriod());
    }
}
