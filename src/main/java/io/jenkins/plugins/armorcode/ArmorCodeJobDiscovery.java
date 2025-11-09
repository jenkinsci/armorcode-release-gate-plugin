package io.jenkins.plugins.armorcode;

import hudson.Extension;
import hudson.FilePath;
import hudson.model.AsyncPeriodicWork;
import hudson.model.Job;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.scheduler.CronTabList;
import io.jenkins.plugins.armorcode.config.ArmorCodeGlobalConfig;
import io.jenkins.plugins.armorcode.credentials.CredentialsUtils;
import java.io.*;
import java.lang.reflect.InvocationTargetException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import jenkins.model.Jenkins;
import net.sf.json.JSONObject;

/**
 * Periodic background task that discovers Jenkins jobs and sends their data to ArmorCode.
 * The task runs every minute and checks if the current time matches the configured cron expression.
 * This approach prevents multiple timers from running when the cron configuration changes.
 */
@Extension
public class ArmorCodeJobDiscovery extends AsyncPeriodicWork {
    private static final Logger LOGGER = Logger.getLogger(ArmorCodeJobDiscovery.class.getName());

    private Calendar lastExecutionTime = null;

    /**
     * Constructor with name for the background task.
     */
    public ArmorCodeJobDiscovery() {
        super("ArmorCode Job Discovery");
    }

    /**
     * Returns a fixed recurrence period of 1 minute.
     * The actual execution logic in execute() checks if monitoring is enabled and
     * if the cron expression matches the current time.
     * Note: This method is only called once at startup by AsyncPeriodicWork.
     */
    @Override
    public long getRecurrencePeriod() {
        // Always return 1 minute since this is only called once at startup
        // The execute() method handles whether to actually run based on config
        return TimeUnit.MINUTES.toMillis(1);
    }

    /**
     * Checks if the current time matches the configured cron expression.
     * This prevents multiple executions within the same minute.
     */
    private boolean shouldExecuteNow(String cronExpression) {
        try {
            Calendar now = Calendar.getInstance();

            // Round down to the start of the current minute for comparison
            Calendar currentMinute = (Calendar) now.clone();
            currentMinute.set(Calendar.SECOND, 0);
            currentMinute.set(Calendar.MILLISECOND, 0);

            // If we've already executed in this minute, skip
            if (lastExecutionTime != null && lastExecutionTime.equals(currentMinute)) {
                LOGGER.fine("[ArmorCode] Already executed in this minute, skipping");
                return false;
            }

            // Use CronTabList to check if current time matches the cron expression
            CronTabList cronTabList = CronTabList.create(cronExpression);
            if (cronTabList.check(currentMinute)) {
                LOGGER.fine("[ArmorCode] Cron expression '" + cronExpression + "' matches current time "
                        + String.format("%tF %<tT", currentMinute) + " - executing discovery");
                lastExecutionTime = currentMinute;
                return true;
            }

            LOGGER.fine("[ArmorCode] Cron expression '" + cronExpression + "' does not match current time "
                    + String.format("%tF %<tT", currentMinute));
            return false;
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Error checking cron schedule: " + cronExpression, e);
            return false;
        }
    }

    /**
     * Executes the periodic task with better error handling and logging.
     * This is called by Jenkins' AsyncPeriodicWork framework every minute.
     * It checks if the current time matches the cron expression before executing.
     */
    @Override
    protected void execute(TaskListener listener) {
        try {
            ArmorCodeGlobalConfig config = ArmorCodeGlobalConfig.get();

            // Config might be null during plugin initialization
            if (config == null) {
                LOGGER.fine("[ArmorCode] Global config not yet loaded, skipping discovery");
                return;
            }

            // Skip if discovery is disabled
            if (!config.isMonitorBuilds()) {
                LOGGER.fine("[ArmorCode] Job discovery is disabled in configuration");
                return;
            }

            // Check if we should execute based on cron expression
            String cronExpression = config.getCronExpression();
            if (cronExpression == null || cronExpression.isBlank()) {
                cronExpression = "H H * * 0"; // Default to weekly
            }

            // Check if current time matches cron - this logs at INFO level when match found
            if (!shouldExecuteNow(cronExpression)) {
                // Not time to execute yet, return silently
                return;
            }

            // Time to execute - log and proceed
            LOGGER.fine("[ArmorCode] Starting job discovery scan at " + new Date());
            listener.getLogger().println("[ArmorCode] Starting job discovery scan");

            // Get ArmorCode token
            final String token = CredentialsUtils.getSystemToken();
            if (token == null) {
                LOGGER.warning("[ArmorCode] No ArmorCode token found - skipping job discovery");
                listener.getLogger().println("[ArmorCode] No ArmorCode token found - skipping job discovery");
                return;
            }

            // Collect job data
            List<JSONObject> jobsData = collectJobsData(config, listener);

            if (jobsData.isEmpty()) {
                LOGGER.fine("[ArmorCode] No matching jobs found during discovery");
                listener.getLogger().println("[ArmorCode] No matching jobs found during discovery");
                return;
            }

            // Log collected job data at FINE level
            LOGGER.fine("[ArmorCode] Collected " + jobsData.size() + " jobs for discovery");

            // Send data to ArmorCode in batches
            boolean success = sendJobsDataInBatches(config, token, jobsData, listener);

            if (success) {
                LOGGER.fine("[ArmorCode] Successfully sent " + jobsData.size() + " jobs to ArmorCode");
                listener.getLogger().println("[ArmorCode] Successfully sent " + jobsData.size() + " jobs to ArmorCode");
            } else {
                LOGGER.warning("[ArmorCode] Failed to send job discovery data");
                listener.getLogger().println("[ArmorCode] Failed to send job discovery data");
            }

        } catch (Exception e) {
            listener.error("[ArmorCode] Error during job discovery. See Jenkins system log for details.");
            LOGGER.log(Level.SEVERE, "[ArmorCode] Error during job discovery", e);
        }
    }

    /**
     * Collect data about Jenkins jobs.
     */
    private List<JSONObject> collectJobsData(ArmorCodeGlobalConfig config, TaskListener listener)
            throws IOException, InvocationTargetException, IllegalAccessException {
        List<JSONObject> jobsData = new ArrayList<>();

        // Get the include/exclude patterns
        String includePattern = config.getIncludeJobsPattern();
        String excludePattern = config.getExcludeJobsPattern();

        // Get all jobs from Jenkins
        for (Job<?, ?> job : Jenkins.get().getAllItems(Job.class)) {
            String jobName = job.getFullName();

            // Apply to include/exclude patterns
            if (!shouldMonitorJob(jobName, includePattern, excludePattern)) {
                continue;
            }

            JSONObject jobData = new JSONObject();
            jobData.put("jobName", jobName);

            Run<?, ?> lastBuild = job.getLastBuild();
            if (lastBuild != null) {
                jobData.put("buildNumber", String.valueOf(lastBuild.getNumber()));
                jobData.put("lastBuildTimestamp", lastBuild.getTimeInMillis());
            } else {
                jobData.put("buildNumber", "0");
                jobData.put("lastBuildTimestamp", 0);
            }

            jobData.put("buildTool", "JENKINS");

            // Add job URL
            String jobUrl = job.getAbsoluteUrl();
            if (jobUrl != null && !jobUrl.isEmpty()) {
                jobData.put("jobURL", jobUrl);
            } else {
                // Fallback
                String jenkinsRootUrl =
                        jenkins.model.JenkinsLocationConfiguration.get().getUrl();
                if (jenkinsRootUrl != null && !jenkinsRootUrl.isEmpty()) {
                    jobData.put("jobURL", jenkinsRootUrl + "job/" + jobName + "/");
                } else {
                    LOGGER.warning(
                            "[ArmorCode] Could not determine Jenkins job URL. Please configure the Jenkins URL in the Jenkins global configuration.");
                    jobData.put("jobURL", "");
                }
            }

            Boolean isJobMapped = isUsingArmorCodePlugin(job);

            jobData.put("jobMapped", isJobMapped);

            jobsData.add(jobData);
        }

        return jobsData;
    }

    /*
     * Enhanced isUsingArmorCodePlugin method with better multibranch pipeline detection.
     * This method determines if a Jenkins job is using the ArmorCode plugin.
     */
    private boolean isUsingArmorCodePlugin(Job<?, ?> job) {
        // Method 1: For FreeStyle projects
        try {
            if (job instanceof hudson.model.Project project) {
                for (Object builder : project.getBuildersList()) {
                    if (builder instanceof ArmorCodeReleaseGateBuilder) {
                        return true;
                    }
                }
            }

            // For other AbstractProject types (Matrix, etc.)
            if (job instanceof hudson.model.AbstractProject project) {

                // Use reflection to access builders if getBuildersList() exists
                try {
                    java.lang.reflect.Method getBuildersMethod =
                            project.getClass().getMethod("getBuildersList");
                    Object buildersList = getBuildersMethod.invoke(project);

                    if (buildersList instanceof hudson.util.DescribableList) {
                        for (Object builder : (hudson.util.DescribableList) buildersList) {
                            if (builder instanceof ArmorCodeReleaseGateBuilder) {
                                return true;
                            }
                        }
                    }
                } catch (NoSuchMethodException e) {
                    // Method doesn't exist, continue with other checks
                }
            }
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Error while inspecting job " + job.getFullName(), e);
        }

        // Method 2: Check for ArmorCode parameters in last build
        try {
            Run<?, ?> lastBuild = job.getLastBuild();
            if (lastBuild != null) {
                hudson.model.ParametersAction params = lastBuild.getAction(hudson.model.ParametersAction.class);
                if (params != null) {
                    for (hudson.model.ParameterValue param : params.getParameters()) {
                        if (param.getName().startsWith("ArmorCode.")) {
                            return true;
                        }
                    }
                }

                // New Method 2.5: Check for ArmorCode marker file in build directory
                try {
                    FilePath markerFile = new FilePath(lastBuild.getRootDir()).child("armorcode-gate-used.txt");
                    if (markerFile.exists()) {
                        return true;
                    }
                } catch (Exception e) {
                    LOGGER.log(Level.FINE, "Error checking for marker file in " + job.getFullName(), e);
                }
            }
        } catch (Exception e) {
            // Log and continue with other methods
            LOGGER.log(Level.FINE, "Error checking build parameters for " + job.getFullName(), e);
        }

        // Method 3: Check Pipeline jobs for ArmorCode step
        try {
            // Determine if we're in a workflow job (pipeline)
            boolean isPipelineJob = job.getClass().getName().contains("org.jenkinsci.plugins.workflow.job.WorkflowJob");

            if (isPipelineJob) {
                // Method 3.1: Check config XML for ArmorCode
                try {
                    String configXml = job.getConfigFile().asString();
                    if (configXml.contains("armorcodeReleaseGate(")
                            || // Step call with parenthesis
                            configXml.contains("new ArmorCodeReleaseGateBuilder(")
                            || // Builder instance
                            configXml.contains("step $class: 'ArmorCodeReleaseGateBuilder'")
                            || // Pipeline syntax
                            configXml.contains("ArmorCode.GateUsed")
                            || // Parameters
                            configXml.matches(
                                    "(?s).*\\bArmorCode\\b.*\\bReleaseGate\\b.*")) { // Loosely coupled references
                        return true;
                    }
                } catch (Exception e) {
                    LOGGER.log(Level.FINE, "Error checking XML config for " + job.getFullName(), e);
                }

                // Method 3.2: For multibranch pipelines, also check build logs
                // This is critical for multibranch pipelines where the Jenkinsfile might use variables
                try {
                    Run<?, ?> lastBuild = job.getLastBuild();
                    if (lastBuild != null) {
                        // Check if the build log contains evidence of ArmorCode execution
                        try (BufferedReader reader = new BufferedReader(
                                new InputStreamReader(lastBuild.getLogInputStream(), StandardCharsets.UTF_8))) {
                            String line;
                            while ((line = reader.readLine()) != null) {
                                if (line.contains("=== Starting ArmorCode Release Gate Check ===")
                                        || line.contains("=== ArmorCode Release Gate ===")
                                        || line.matches(".*\\[INFO\\]\\s+ArmorCode check passed.*")
                                        || line.matches(".*\\[BLOCK\\]\\s+SLA check.*")) {
                                    return true;
                                }
                            }
                        }

                        // Also check build actions for evidence of ArmorCode
                        for (Object action : lastBuild.getAllActions()) {
                            if (action.getClass().getName().contains("ArmorCode")) {
                                return true;
                            }
                        }
                    }
                } catch (Exception e) {
                    LOGGER.log(Level.FINE, "Error checking build logs for " + job.getFullName(), e);
                }
            }
        } catch (Exception e) {
            // Log and continue
            LOGGER.log(Level.FINE, "Error checking pipeline config for " + job.getFullName(), e);
        }

        //        Script detection logic
        try {
            String configXml = job.getConfigFile().asString();

            // Look for API endpoint patterns in the config
            if (configXml.contains("armorcode.ai/client/buildvalidation")
                    || configXml.contains("armorcode.com/client/buildvalidation")
                    || configXml.contains("curl")
                            && configXml.contains("Authorization: Bearer")
                            && (configXml.contains("armorcode.ai") || configXml.contains("ArmorCode"))) {
                return true;
            }
        } catch (Exception e) {
            LOGGER.log(Level.FINE, "Error checking config for script-based integration", e);
        }

        // Additional method specifically for multibranch pipeline projects
        try {
            // Get the parent using job.getParent() - works for folders and multibranch jobs
            hudson.model.ItemGroup<?> parent = job.getParent();

            // For multibranch pipelines, the parent is the multibranch project itself
            if (parent.getClass().getName().contains("MultiBranchProject")) {
                // Check if other branches in the same project use ArmorCode
                for (Job<?, ?> siblingJob : parent.getAllItems(Job.class)) {
                    if (siblingJob != job) {
                        try {
                            Run<?, ?> siblingBuild = siblingJob.getLastBuild();
                            if (siblingBuild != null) {
                                try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                                        siblingBuild.getLogInputStream(), StandardCharsets.UTF_8))) {
                                    String line;
                                    while ((line = reader.readLine()) != null) {
                                        if (line.contains("ArmorCode")
                                                || line.contains("armorcode")
                                                || line.contains("armorcodeReleaseGate")) {
                                            // If a sibling branch uses ArmorCode, this one likely does too
                                            return true;
                                        }
                                    }
                                }
                            }
                        } catch (Exception e) {
                            // Just log and continue
                            LOGGER.log(Level.FINE, "Error checking sibling branch for " + job.getFullName(), e);
                        }
                    }
                }
            }
        } catch (Exception e) {
            LOGGER.log(Level.FINE, "Error checking parent job for " + job.getFullName(), e);
        }

        return false;
    }

    /**
     * Send job data to ArmorCode in batches to handle large numbers of jobs.
     */
    private boolean sendJobsDataInBatches(
            ArmorCodeGlobalConfig config, String token, List<JSONObject> allJobsData, TaskListener listener) {
        // Define batch size - adjust based on your performance needs
        final int BATCH_SIZE = 50;
        int totalJobs = allJobsData.size();
        int successCount = 0;

        // Calculate how many batches we'll need
        int batchCount = (int) Math.ceil((double) totalJobs / BATCH_SIZE);

        // Process each batch
        for (int i = 0; i < batchCount; i++) {
            // Calculate start and end indices for this batch
            int startIndex = i * BATCH_SIZE;
            int endIndex = Math.min(startIndex + BATCH_SIZE, totalJobs);

            // Get the current batch of jobs
            List<JSONObject> batchJobs = allJobsData.subList(startIndex, endIndex);

            // Send the batch
            boolean batchSuccess = sendBatch(config, token, batchJobs, listener);
            if (batchSuccess) {
                successCount += batchJobs.size();
            }

            // Add a small delay between batches to avoid overwhelming the server
            if (i < batchCount - 1) {
                try {
                    Thread.sleep(1000); // 1-second delay between batches
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }

        // Report overall results
        LOGGER.fine("[ArmorCode] Successfully sent " + successCount + " of " + totalJobs + " jobs to ArmorCode");

        return successCount == totalJobs;
    }

    /**
     * Send a single batch of job data to ArmorCode.
     */
    private boolean sendBatch(
            ArmorCodeGlobalConfig config, String token, List<JSONObject> batchJobs, TaskListener listener) {
        HttpURLConnection conn = null;
        try {
            // Create batch payload
            JSONObject batchPayload = new JSONObject();
            batchPayload.put("jobsCount", batchJobs.size());
            batchPayload.put("jobs", batchJobs);

            // Build endpoint URL
            String uri = config.getBaseUrl() + "/client/builds/jobs/discovery/monitoring";

            // Set up connection
            URL url = new URL(uri);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("Authorization", "Bearer " + token);
            conn.setRequestProperty("Accept-Charset", "UTF-8");
            conn.setDoOutput(true);
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(30000);

            // Write payload
            try (OutputStream os = conn.getOutputStream()) {
                os.write(batchPayload.toString().getBytes(StandardCharsets.UTF_8));
                os.flush();
            }

            // Check response
            int responseCode = conn.getResponseCode();
            if (responseCode != 200) {
                String errorDetails = "";
                try (BufferedReader errorReader =
                        new BufferedReader(new InputStreamReader(conn.getErrorStream(), StandardCharsets.UTF_8))) {
                    StringBuilder errorResponse = new StringBuilder();
                    String line;
                    while ((line = errorReader.readLine()) != null) {
                        errorResponse.append(line);
                    }
                    errorDetails = errorResponse.toString();
                } catch (IOException ex) {
                    listener.error("[ArmorCode] Could not read error response: " + ex.getMessage());
                    LOGGER.log(Level.WARNING, "Could not read error response", ex);
                }

                LOGGER.log(
                        Level.WARNING,
                        "Batch send failed with HTTP " + responseCode
                                + (errorDetails.isEmpty() ? "" : ": " + errorDetails));
                return false;
            }

            // Read successful response (for logging if needed)
            try (BufferedReader reader =
                    new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                StringBuilder response = new StringBuilder();
                while ((line = reader.readLine()) != null) {
                    response.append(line);
                }
                LOGGER.fine("[ArmorCode] Batch sent successfully. Response: " + response.toString());
            }

            return true;

        } catch (IOException e) {
            listener.error("[ArmorCode] Error sending batch: " + e.getMessage());
            LOGGER.log(Level.WARNING, "Error sending batch to ArmorCode", e);
            return false;
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    /**
     * Check if job should be monitored based on patterns.
     */
    private boolean shouldMonitorJob(String jobName, String includePattern, String excludePattern) {
        // Check exclusions first (they take precedence)
        if (excludePattern != null && !excludePattern.isEmpty() && jobName.matches(excludePattern)) {
            return false;
        }

        // Then check inclusions
        return includePattern == null || includePattern.isEmpty() || jobName.matches(includePattern);
    }
}
