package io.jenkins.plugins.armorcode.config;

import antlr.ANTLRException;
import hudson.Extension;
import hudson.scheduler.CronTab;
import hudson.util.FormValidation;
import io.jenkins.plugins.armorcode.ArmorCodeJobDiscovery;
import java.net.HttpURLConnection;
import java.net.URL;
import jenkins.model.GlobalConfiguration;
import jenkins.model.Jenkins;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.verb.POST;

@Extension
public class ArmorCodeGlobalConfig extends GlobalConfiguration {

    private String baseUrl = "https://app.armorcode.com";
    private boolean monitorBuilds = false;
    private String jobFilter = ""; // Default to all jobs
    private String includeJobsPattern = ".*"; // Default to all jobs
    private String excludeJobsPattern = ""; // Default to no exclusions

    private String cronExpression = "H H * * *"; // default to daily once per day at a random hour

    public ArmorCodeGlobalConfig() {
        load(); // Load saved config

        // Convert legacy jobFilter to new format if needed
        if (!jobFilter.isEmpty() && (includeJobsPattern.equals(".*") && excludeJobsPattern.isEmpty())) {
            convertLegacyJobFilter();
        }
    }

    private void convertLegacyJobFilter() {
        String[] patterns = jobFilter.split(",");

        StringBuilder includes = new StringBuilder();
        StringBuilder excludes = new StringBuilder();

        for (String pattern : patterns) {
            pattern = pattern.trim();
            if (pattern.startsWith("!")) {
                if (excludes.length() > 0) excludes.append("|");
                excludes.append(pattern.substring(1));
            } else {
                if (includes.length() > 0) includes.append("|");
                includes.append(pattern);
            }
        }

        if (includes.length() > 0) includeJobsPattern = includes.toString();
        if (excludes.length() > 0) excludeJobsPattern = excludes.toString();

        // Clear legacy field
        jobFilter = "";
        save();
    }

    public static ArmorCodeGlobalConfig get() {
        return GlobalConfiguration.all().get(ArmorCodeGlobalConfig.class);
    }

    @Override
    public String getDisplayName() {
        return "ArmorCode Configuration";
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    @DataBoundSetter
    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
        save();
    }

    public boolean isMonitorBuilds() {
        return monitorBuilds;
    }

    @DataBoundSetter
    public void setMonitorBuilds(boolean monitorBuilds) {
        this.monitorBuilds = monitorBuilds;
        save();
    }

    public String getJobFilter() {
        return jobFilter;
    }

    @DataBoundSetter
    public void setJobFilter(String jobFilter) {
        this.jobFilter = jobFilter;
        save();
    }

    public String getIncludeJobsPattern() {
        return includeJobsPattern;
    }

    @DataBoundSetter
    public void setIncludeJobsPattern(String pattern) {
        this.includeJobsPattern = pattern;
        save();
    }

    public String getExcludeJobsPattern() {
        return excludeJobsPattern;
    }

    @DataBoundSetter
    public void setExcludeJobsPattern(String pattern) {
        this.excludeJobsPattern = pattern;
        save();
    }

    // Update shouldMonitorJob to use the new fields
    public boolean shouldMonitorJob(String jobName) {
        // Start by checking exclusions
        if (!excludeJobsPattern.isEmpty() && jobName.matches(excludeJobsPattern)) {
            return false; // Excluded
        }

        // Then check inclusions
        return jobName.matches(includeJobsPattern);
    }

    @POST
    public FormValidation doCheckBaseUrl(@QueryParameter("baseUrl") String baseUrl) {
        Jenkins.get().checkPermission(Jenkins.ADMINISTER);
        if (baseUrl.isEmpty()) {
            return FormValidation.error("Base URL must not be empty");
        }
        if (!baseUrl.startsWith("https://")) {
            return FormValidation.error("Base URL must start with https://");
        }
        return FormValidation.ok();
    }

    @POST
    public FormValidation doTestConnection(@QueryParameter String baseUrl) {
        Jenkins.get().checkPermission(Jenkins.ADMINISTER);
        if (baseUrl.isEmpty()) {
            return FormValidation.error("Base URL is required");
        }

        try {
            URL url = new URL(baseUrl + "/client/ping");
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);

            int responseCode = conn.getResponseCode();
            if (responseCode == 200) {
                return FormValidation.ok("Connection successful!");
            } else {
                return FormValidation.error("Connection failed with status: " + responseCode);
            }
        } catch (Exception e) {
            return FormValidation.error("Connection failed: " + e.getMessage());
        }
    }

    public String getCronExpression() {
        return cronExpression;
    }

    @DataBoundSetter
    public void setCronExpression(String cronExpression) {
        if (cronExpression == null || cronExpression.trim().isEmpty()) {
            this.cronExpression = "H H * * *"; // default to daily once per day at a random hour
        } else {
            this.cronExpression = cronExpression;
        }
        save();

        Jenkins.get().getExtensionList(ArmorCodeJobDiscovery.class).get(0).reschedule();
    }

    /**
     * Validating cron expression
     */
    @POST
    public FormValidation doCheckCronExpression(@QueryParameter String value) {
        Jenkins.get().checkPermission(Jenkins.ADMINISTER);
        if (value == null || value.trim().isEmpty()) {
            return FormValidation.error("Cron Expression must not be empty");
        }

        try {
            new CronTab(value);

            // Check if the cron would run more frequently than hourly
            if (isTooFrequent(value)) {
                return FormValidation.error("Cron expression must not run more frequently than once per hour");
            }

            return FormValidation.ok();
        } catch (ANTLRException e) {
            return FormValidation.error("Invalid Cron Expression: " + e.getMessage());
        }
    }

    /**
     * Checks if a cron expression would run more frequently than once per hour
     */
    private boolean isTooFrequent(String cronExpression) {
        // Split the expression into its components
        String[] parts = cronExpression.trim().split("\\s+");

        // If we don't have enough parts, it's invalid anyway
        if (parts.length < 5) {
            return false; // Let the main validator handle this
        }

        String minutes = parts[0];

        // Check for expressions that would cause multiple runs within an hour

        // If minutes part contains */N where N < 60
        if (minutes.matches("\\*/\\d+")) {
            int step = Integer.parseInt(minutes.substring(2));
            if (step < 60) return true;
        }

        // Check for explicit multiple minutes
        if (minutes.contains(",") || minutes.contains("-") || minutes.equals("*")) {
            return true;
        }

        // H/N means "every N minutes/hours/etc.". If N < 60 in minutes field, it runs multiple times per hour
        if (minutes.matches("H/\\d+")) {
            int step = Integer.parseInt(minutes.substring(2));
            return step < 60;
        }

        return false;
    }
}
