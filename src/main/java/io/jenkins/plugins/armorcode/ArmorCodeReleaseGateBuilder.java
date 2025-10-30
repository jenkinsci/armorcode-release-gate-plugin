package io.jenkins.plugins.armorcode;

import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.AbortException;
import hudson.Extension;
import hudson.FilePath;
import hudson.Launcher;
import hudson.model.Result;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.model.ParameterValue;
import hudson.model.ParametersAction;
import hudson.model.StringParameterValue;
import hudson.tasks.BuildStepDescriptor;
import hudson.tasks.BuildStepMonitor;
import hudson.tasks.Builder;
import io.jenkins.plugins.armorcode.config.ArmorCodeGlobalConfig;
import io.jenkins.plugins.armorcode.credentials.CredentialsUtils;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UnsupportedEncodingException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import jenkins.model.CauseOfInterruption;
import jenkins.model.InterruptedBuildAction;
import jenkins.tasks.SimpleBuildStep;
import net.sf.json.JSONObject;
import net.sf.json.JSONSerializer;
import org.jenkinsci.Symbol;
import org.jenkinsci.plugins.workflow.steps.FlowInterruptedException;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;

public class ArmorCodeReleaseGateBuilder extends Builder implements SimpleBuildStep {

    // Required parameters
    private final String product;
    private final Object subProducts;
    private final String env;

    // Optional parameters with default values
    private int maxRetries = 5; // How many times to poll before giving up
    private String mode = "block"; // "block" or "warn"

    // The target URL for the ArmorCode build validation endpoint
    private String targetUrl;

    // Optional setter so it can be configured from a Pipeline or via tests.
    @DataBoundSetter
    public void setTargetUrl(String targetUrl) {
        this.targetUrl = targetUrl;
    }

    /**
     * Required constructor parameters.
     *
     * @param product   The ArmorCode product (group) ID.
     * @param subProducts The ArmorCode sub-product (subgroup) ID.
     * @param env  The environment (e.g. "Production", "Staging").
     */
    @DataBoundConstructor
    public ArmorCodeReleaseGateBuilder(String product, Object subProducts, String env) {
        this.product = product;
        this.subProducts = subProducts;
        this.env = env;
    }
    
    /**
     * Optional parameter: how many times to retry the validation check.
     *
     * @param maxRetries The maximum number of retries (default 30).
     */
    @DataBoundSetter
    public void setMaxRetries(int maxRetries) {
        this.maxRetries = maxRetries > 0 ? maxRetries : 5;
    }

    /**
     * Optional parameter: "block" or "warn" mode.
     * - "block" (default) fails the build on SLA failure.
     * - "warn" marks the build UNSTABLE but continues.
     *
     * @param mode The validation mode ("block" or "warn").
     */
    @DataBoundSetter
    public void setMode(String mode) {
        this.mode = mode != null ? mode : "block";
    }

    // Getter methods for use in tests and UI binding
    public String getProduct() {
        return product;
    }

    public Object getSubProducts() {
        return subProducts;
    }

    public String getEnv() {
        return env;
    }

    public int getMaxRetries() {
        return maxRetries;
    }

    public String getMode() {
        return mode;
    }

    public String getTargetUrl() {
        return targetUrl;
    }

    private int retryDelay = 20; // seconds

    @DataBoundSetter
    public void setRetryDelay(int retryDelay) {
        this.retryDelay = retryDelay;
    }

    public int getRetryDelay() {
        return retryDelay;
    }

    /**
     * Creates a detailed error message with links and context information
     * Handles both severity-based and risk-based release gates
     */
    private String formatDetailedErrorMessage(Run<?, ?> run, JSONObject responseJson)
            throws UnsupportedEncodingException {
        StringBuilder message = new StringBuilder();
        message.append("Group: ").append(product).append("\n");
        message.append("Sub Group: ").append(subProducts).append("\n");
        message.append("Environment: ").append(env).append("\n");

        // Extract findings scope based on the release gate type
        StringBuilder findingsScope = new StringBuilder();
        boolean hasFindings = false;
        boolean isSeverityBased = false;
        boolean isRiskBased = false;

        // Determine if severity-based by checking if any severity value is > 0
        if (responseJson.has("severity") && responseJson.optJSONObject("severity") != null) {
            JSONObject severity = responseJson.getJSONObject("severity");
            if ((severity.has("Critical") && severity.getInt("Critical") > 0)
                    || (severity.has("High") && severity.getInt("High") > 0)
                    || (severity.has("Medium") && severity.getInt("Medium") > 0)
                    || (severity.has("Low") && severity.getInt("Low") > 0)) {
                isSeverityBased = true;
            }
        } else if ((responseJson.has("severity.Critical") && responseJson.getInt("severity.Critical") > 0)
                || (responseJson.has("severity.High") && responseJson.getInt("severity.High") > 0)
                || (responseJson.has("severity.Medium") && responseJson.getInt("severity.Medium") > 0)
                || (responseJson.has("severity.Low") && responseJson.getInt("severity.Low") > 0)) {
            isSeverityBased = true;
        }

        // Determine if risk-based by checking if any otherProperties value is > 0
        if (responseJson.has("otherProperties") && responseJson.optJSONObject("otherProperties") != null) {
            JSONObject riskProperties = responseJson.getJSONObject("otherProperties");
            if ((riskProperties.has("VERY_POOR") && riskProperties.getInt("VERY_POOR") > 0)
                    || (riskProperties.has("POOR") && riskProperties.getInt("POOR") > 0)
                    || (riskProperties.has("FAIR") && riskProperties.getInt("FAIR") > 0)
                    || (riskProperties.has("GOOD") && riskProperties.getInt("GOOD") > 0)) {
                isRiskBased = true;
            }
        } else if ((responseJson.has("otherProperties.VERY_POOR")
                        && responseJson.getInt("otherProperties.VERY_POOR") > 0)
                || (responseJson.has("otherProperties.POOR") && responseJson.getInt("otherProperties.POOR") > 0)
                || (responseJson.has("otherProperties.FAIR") && responseJson.getInt("otherProperties.FAIR") > 0)
                || (responseJson.has("otherProperties.GOOD") && responseJson.getInt("otherProperties.GOOD") > 0)) {
            isRiskBased = true;
        }

        // Process findings based on the determined type
        if (isSeverityBased) {
            // Process severity findings
            if (responseJson.has("severity") && responseJson.optJSONObject("severity") != null) {
                JSONObject severity = responseJson.getJSONObject("severity");

                if (severity.has("Critical") && severity.getInt("Critical") > 0) {
                    findingsScope.append(severity.getInt("Critical")).append(" Critical, ");
                    hasFindings = true;
                }
                if (severity.has("High") && severity.getInt("High") > 0) {
                    findingsScope.append(severity.getInt("High")).append(" High, ");
                    hasFindings = true;
                }
                if (severity.has("Medium") && severity.getInt("Medium") > 0) {
                    findingsScope.append(severity.getInt("Medium")).append(" Medium, ");
                    hasFindings = true;
                }
                if (severity.has("Low") && severity.getInt("Low") > 0) {
                    findingsScope.append(severity.getInt("Low")).append(" Low");
                    hasFindings = true;
                }
            } else {
                // Handle flattened severity format
                if (responseJson.has("severity.Critical") && responseJson.getInt("severity.Critical") > 0) {
                    findingsScope
                            .append(responseJson.getInt("severity.Critical"))
                            .append(" Critical, ");
                    hasFindings = true;
                }
                if (responseJson.has("severity.High") && responseJson.getInt("severity.High") > 0) {
                    findingsScope.append(responseJson.getInt("severity.High")).append(" High, ");
                    hasFindings = true;
                }
                if (responseJson.has("severity.Medium") && responseJson.getInt("severity.Medium") > 0) {
                    findingsScope.append(responseJson.getInt("severity.Medium")).append(" Medium, ");
                    hasFindings = true;
                }
                if (responseJson.has("severity.Low") && responseJson.getInt("severity.Low") > 0) {
                    findingsScope.append(responseJson.getInt("severity.Low")).append(" Low");
                    hasFindings = true;
                }
            }
        } else if (isRiskBased) {
            // Process risk findings
            if (responseJson.has("otherProperties") && responseJson.optJSONObject("otherProperties") != null) {
                JSONObject riskProperties = responseJson.getJSONObject("otherProperties");

                if (riskProperties.has("VERY_POOR") && riskProperties.getInt("VERY_POOR") > 0) {
                    findingsScope.append(riskProperties.getInt("VERY_POOR")).append(" Very Poor, ");
                    hasFindings = true;
                }
                if (riskProperties.has("POOR") && riskProperties.getInt("POOR") > 0) {
                    findingsScope.append(riskProperties.getInt("POOR")).append(" Poor, ");
                    hasFindings = true;
                }
                if (riskProperties.has("FAIR") && riskProperties.getInt("FAIR") > 0) {
                    findingsScope.append(riskProperties.getInt("FAIR")).append(" Fair, ");
                    hasFindings = true;
                }
                if (riskProperties.has("GOOD") && riskProperties.getInt("GOOD") > 0) {
                    findingsScope.append(riskProperties.getInt("GOOD")).append(" Good");
                    hasFindings = true;
                }
            } else {
                // Handle flattened otherProperties format
                if (responseJson.has("otherProperties.VERY_POOR")
                        && responseJson.getInt("otherProperties.VERY_POOR") > 0) {
                    findingsScope
                            .append(responseJson.getInt("otherProperties.VERY_POOR"))
                            .append(" Very Poor, ");
                    hasFindings = true;
                }
                if (responseJson.has("otherProperties.POOR") && responseJson.getInt("otherProperties.POOR") > 0) {
                    findingsScope
                            .append(responseJson.getInt("otherProperties.POOR"))
                            .append(" Poor, ");
                    hasFindings = true;
                }
                if (responseJson.has("otherProperties.FAIR") && responseJson.getInt("otherProperties.FAIR") > 0) {
                    findingsScope
                            .append(responseJson.getInt("otherProperties.FAIR"))
                            .append(" Fair, ");
                    hasFindings = true;
                }
                if (responseJson.has("otherProperties.GOOD") && responseJson.getInt("otherProperties.GOOD") > 0) {
                    findingsScope
                            .append(responseJson.getInt("otherProperties.GOOD"))
                            .append(" Good");
                    hasFindings = true;
                }
            }
        }

        // Trim trailing comma and space if present
        String scopeString = findingsScope.toString();
        if (scopeString.endsWith(", ")) {
            scopeString = scopeString.substring(0, scopeString.length() - 2);
        }

        if (hasFindings) {
            message.append("Findings Scope: ").append(scopeString).append("\n");
        } else {
            message.append("Findings Scope: No findings detected\n");
        }

        // Extract reason from response if available
        String reason = "SLA check failed"; // Default reason
        if (responseJson.has("failureReasonText")) {
            // Get the value as an Object first
            Object reasonObj = responseJson.get("failureReasonText");
            // Only use it if it's not null and it's a non-empty string
            if (reasonObj != null
                    && !reasonObj.toString().equals("null")
                    && !reasonObj.toString().isEmpty()) {
                reason = reasonObj.toString();
            }
        }
        message.append("Reason: ").append(reason).append("\n");

        String buildNumber = String.valueOf(run.getNumber());
        String jobName = run.getParent().getFullName(); // Get the job name
        String baseDetailsLink = responseJson.optString(
                "detailsLink", responseJson.optString("link", "https://app.armorcode.com/client/integrations/jenkins"));
        String detailsLink = baseDetailsLink + (baseDetailsLink.contains("?") ? "&" : "?") + "filters="
                + URLEncoder.encode(
                        "{\"buildNumber\":[\"" + buildNumber + "\"],\"jobName\":[\"" + jobName + "\"]}", StandardCharsets.UTF_8);
        message.append("For more details, please refer to: ").append(detailsLink);

        return message.toString();
    }

    private boolean testMode = false;

    @DataBoundSetter
    public void setTestMode(boolean testMode) {
        this.testMode = testMode;
    }

    public boolean getTestMode() {
        return testMode;
    }

    // Add transient keyword to prevent serialization
    private final transient JSONObject testResponseOverride = null;

    private transient String testResponseString;

    @DataBoundSetter
    public void setTestResponseString(String testResponseString) {
        this.testResponseString = testResponseString;
    }

    public String getTestResponseString() {
        return testResponseString;
    }

    private boolean isNullOrEmpty(Object value) {
        if (value == null) return true;
        if (value instanceof String) {
            return ((String) value).trim().isEmpty();
        }
        if (value instanceof java.util.Collection) {
            return ((java.util.Collection<?>) value).isEmpty();
        }
        return value.toString().trim().isEmpty();
    }

    private void validateSecurityPrerequisites(Run<?, ?> run, TaskListener listener, String token)
            throws AbortException {
        // Strict parameter validation
        if (isNullOrEmpty(product) || isNullOrEmpty(subProducts) || isNullOrEmpty(env)) {
            throw new AbortException("Incomplete security configuration");
        }

        if (isNullOrEmpty(token)) {
            throw new AbortException("Missing security authentication");
        }
    }

    /**
     * Determines how to handle a FAILED status depending on the mode.
     */
    private void saveGateInfoToProperties(Run<?, ?> run, String gateResult) {
        List<ParameterValue> newParams = new ArrayList<>();
        newParams.add(new StringParameterValue("ArmorCode.GateUsed", "true"));
        newParams.add(new StringParameterValue("ArmorCode.Product", product));
        newParams.add(new StringParameterValue("ArmorCode.SubProducts", subProducts.toString()));
        newParams.add(new StringParameterValue("ArmorCode.Env", env));
        newParams.add(new StringParameterValue("ArmorCode.GateResult", gateResult));

        List<String> safeParameterNames = new ArrayList<>();
        safeParameterNames.add("ArmorCode.GateUsed");
        safeParameterNames.add("ArmorCode.Product");
        safeParameterNames.add("ArmorCode.SubProducts");
        safeParameterNames.add("ArmorCode.Env");
        safeParameterNames.add("ArmorCode.GateResult");

        run.addAction(new ParametersAction(newParams, safeParameterNames));
    }

    private void handleFailureMode(Run<?, ?> run, TaskListener listener) throws InterruptedException, AbortException {
        if ("block".equalsIgnoreCase(mode)) {
            listener.getLogger().println("[BLOCK] SLA check FAILED => Terminating build with failure.");
            run.setResult(Result.FAILURE);

            CauseOfInterruption.UserInterruption cause = new CauseOfInterruption.UserInterruption(
                    "ArmorCode release gate failed: Security check did not pass");
            run.addAction(new InterruptedBuildAction(Collections.singleton(cause)));

            throw new QuitException("__ARMORCODE_ENFORCE_FAILURE__");
        } else if ("warn".equalsIgnoreCase(mode)) {
            listener.getLogger()
                    .println(
                            "[WARN] SLA check FAILED but 'warn' mode is active => Marking build as UNSTABLE and continuing...");
            run.setResult(Result.UNSTABLE);
        } else if (testMode) {
            listener.getLogger().println("[TEST MODE] Maximum retries reached; forcing build success for testing.");
            run.setResult(Result.SUCCESS);
        } else {
            listener.getLogger()
                    .println("[BLOCK] Release gate is running in BLOCK mode => Terminating build with failure.");

            // Force ABORTED result which typically can't be caught/overridden in pipelines
            run.setResult(Result.FAILURE);

            // Throw the error that will terminate execution
            CauseOfInterruption.UserInterruption cause = new CauseOfInterruption.UserInterruption(
                    "ArmorCode release gate failed: Security check did not pass");
            throw new FlowInterruptedException(Result.FAILURE, cause);
        }
    }

    /**
     * Executes the release gate check. Polls ArmorCode up to maxRetries times,
     * parsing the status each time. Depending on the mode, the build either fails
     * or continues if an SLA violation is found.
     */
    @Override
    public void perform(
            @NonNull Run<?, ?> run,
            @NonNull FilePath workspace,
            @NonNull Launcher launcher,
            @NonNull TaskListener listener)
            throws InterruptedException, Error, AbortException {

        // Gather Jenkins context info
        final String buildNumber = String.valueOf(run.getNumber());
        final String jobName = run.getParent().getName();

        // Get global config if available
        String apiBaseUrl = targetUrl; // from the job config
        ArmorCodeGlobalConfig globalConfig = ArmorCodeGlobalConfig.get();

        // If the job-specific URL is not provided, use the global one.
        if (apiBaseUrl == null || apiBaseUrl.trim().isEmpty()) {
            if (globalConfig != null) {
                apiBaseUrl = globalConfig.getBaseUrl();
            }
        }

        // If no URL is configured anywhere, use the default.
        if (apiBaseUrl == null || apiBaseUrl.trim().isEmpty()) {
            apiBaseUrl = "https://app.armorcode.com";
        }

        // Now, construct the final URL with the /client/build path.
        String finalUrl = apiBaseUrl;
        if (!finalUrl.endsWith("/client/build")) {
            finalUrl += "/client/build";
        }

        final String token = CredentialsUtils.getArmorCodeToken(run);
        validateSecurityPrerequisites(run, listener, token);

        String jobUrl = run.getParent().getAbsoluteUrl();
        if (jobUrl == null || jobUrl.isEmpty()) {
            // Only use fallback if Jenkins API fails
            String jenkinsRootUrl =
                    jenkins.model.JenkinsLocationConfiguration.get().getUrl();
            if (jenkinsRootUrl != null && !jenkinsRootUrl.isEmpty()) {
                jobUrl = jenkinsRootUrl + "/job/" + jobName + "/";
            } else {
                listener.getLogger().println("[WARN] Could not determine Jenkins job URL. Please configure the Jenkins URL in the Jenkins global configuration.");
                jobUrl = "";
            }
        }

        // Log initial context
        listener.getLogger().println("=== Starting ArmorCode Release Gate Check ===");

        // Poll up to maxRetries times
        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            try {
                // Process response - either from test override or from actual API call
                JSONObject json;
                if (testMode && testResponseString != null && !testResponseString.isEmpty()) {
                    json = (JSONObject) JSONSerializer.toJSON(testResponseString);
                } else {
                    // Make the HTTP POST request and parse the response
                    String responseStr = postArmorCodeRequest(
                            listener, token, buildNumber, jobName, attempt, maxRetries, finalUrl, jobUrl);
                    json = (JSONObject) JSONSerializer.toJSON(responseStr);
                }
                String status = json.optString("status", "UNKNOWN");
                listener.getLogger().println("=== ArmorCode Release Gate ===");
                listener.getLogger().println("Status: " + status);

                if ("HOLD".equalsIgnoreCase(status)) {
                    // On HOLD => wait 20 seconds, then retry
                    listener.getLogger().println("[INFO] SLA is on HOLD. Sleeping " + retryDelay + "s...");
                    listener.getLogger()
                            .println(
                                    "[INFO] Sleeping " + retryDelay + " seconds before trying again. You can temporarily release the build from ArmorCode console");
                    TimeUnit.SECONDS.sleep(retryDelay);
                } else if ("FAILED".equalsIgnoreCase(status)) {
                    // SLA failure => provide detailed error with links
                    String detailedError = formatDetailedErrorMessage(run, json);
                    listener.getLogger().println(detailedError);
                    saveGateInfoToProperties(run, "FAIL");
                    TimeUnit.SECONDS.sleep(retryDelay);
                    handleFailureMode(run, listener);
                    if (!"warn".equalsIgnoreCase(mode)) {
                        return;
                    } else {
                        // In warn mode, just break the retry loop and continue pipeline execution
                        break;
                    }
                } else {
                    // SUCCESS or RELEASE or other statuses => pass and break out
                    listener.getLogger().println("[INFO] ArmorCode check passed! Proceeding...");
                    saveGateInfoToProperties(run, "PASS");
                    TimeUnit.SECONDS.sleep(retryDelay);
                    return;
                }
            } catch (QuitException | FlowInterruptedException e) {
                // Rethrow to allow Jenkins to handle the interruption
                throw e;
            } catch (AbortException e) {
                run.setResult(Result.FAILURE);
                throw new FlowInterruptedException(
                        Result.FAILURE,
                        new CauseOfInterruption.UserInterruption(
                                "ArmorCode release gate failed: Security check did not pass"));
            } catch (Exception e) {
                // Special handling for our enforcement signal
                if (e.getMessage() != null && e.getMessage().equals("__ARMORCODE_ENFORCE_FAILURE__")) {
                    throw new RuntimeException("ArmorCode release gate failed: Security check did not pass");
                }

                listener.getLogger().println("[ERROR] ArmorCode request failed: " + e.getMessage());

                // If we've tried all retries, fail the build
                if (attempt == maxRetries) {
                    throw new AbortException("ArmorCode request error after maximum retries.");
                }

                // Otherwise wait and retry
                listener.getLogger().println("Waiting " + retryDelay + "s before retry...");
                TimeUnit.SECONDS.sleep(retryDelay);
            }
        }

        // If the loop completes without returning, it means max retries were hit on HOLD
        listener.getLogger().println("[ERROR] ArmorCode check did not pass after " + maxRetries + " retries (last status was HOLD).");
        saveGateInfoToProperties(run, "FAIL");
        handleFailureMode(run, listener);
    }

    /**
     * Sends a POST request to ArmorCode's build validation endpoint
     * with the given parameters, then returns the raw JSON response.
     */
    protected String postArmorCodeRequest(
            @NonNull TaskListener listener,
            String token,
            String buildNumber,
            String jobName,
            int current,
            int end,
            String apiUrl,
            String jobUrl)
            throws Exception {

        // Format current and end as strings to match the curl command exactly
        String subProductsJsonArray;
        if (subProducts == null) {
            subProductsJsonArray = "[]";
        } else if (subProducts instanceof java.util.List) {
            subProductsJsonArray =
                    net.sf.json.JSONArray.fromObject(subProducts).toString();
        } else {
            java.util.List<String> subProductList = java.util.Arrays.stream(((String) subProducts).split("\\r?\\n"))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .collect(java.util.stream.Collectors.toList());
            subProductsJsonArray =
                    net.sf.json.JSONArray.fromObject(subProductList).toString();
        }

        final String payload = String.format(
                "{ \"env\": \"%s\", \"product\": \"%s\", \"subProducts\": %s, "
                        + "\"buildNumber\": \"%s\", \"jobName\": \"%s\", \"current\": \"%s\", \"end\": \"%s\" , \"jobURL\": \"%s\"}",
                env,
                product,
                subProductsJsonArray,
                buildNumber,
                jobName,
                String.valueOf(current),
                String.valueOf(end),
                jobUrl);

        // Configure HTTP connection
        URL url = new URL(apiUrl);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setRequestProperty("Authorization", "Bearer " + token);
        conn.setDoOutput(true);

        // Set character encoding explicitly
        conn.setRequestProperty("Accept-Charset", "UTF-8");

        // Write payload with explicit UTF-8 encoding
        conn.getOutputStream().write(payload.getBytes(StandardCharsets.UTF_8));
        conn.getOutputStream().flush();

        // Check response code before reading
        int responseCode = conn.getResponseCode();

        if (responseCode >= 200 && responseCode < 300) {
            // Success case
            try (BufferedReader in = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = in.readLine()) != null) {
                    sb.append(line);
                }
                return sb.toString();
            }
        } else {
            // Error case - extract and log the error response
            StringBuilder errorResponse = new StringBuilder();
            try (BufferedReader errorReader =
                         new BufferedReader(new InputStreamReader(conn.getErrorStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = errorReader.readLine()) != null) {
                    errorResponse.append(line);
                }
            } catch (Exception ex) {
                errorResponse.append("Failed to read error stream: ").append(ex.getMessage());
            }

            throw new IOException("Server returned HTTP response code: " + responseCode + " for URL: " + apiUrl
                    + " with message: " + errorResponse);
        }
    }

    @Override
    public BuildStepMonitor getRequiredMonitorService() {
        return BuildStepMonitor.NONE;
    }

    /**
     * Descriptor that tells Jenkins how to display and instantiate this step.
     */
    @Extension
    @Symbol("armorcodeReleaseGate")
    public static class DescriptorImpl extends BuildStepDescriptor<Builder> {

        @Override
        public boolean isApplicable(Class jobType) {
            return true;
        }

        @NonNull
        @Override
        public String getDisplayName() {
            return "ArmorCode Release Gate";
        }

        @Override
        public String getId() {
            return "armorcodeReleaseGate";
        }
    }
}
