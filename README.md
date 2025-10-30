# ArmorCode Release Gate Plugin

The ArmorCode Release Gate plugin for Jenkins enables security teams to enforce security requirements as part of the CI/CD pipeline. It provides a build step that polls ArmorCode's build validation endpoint to either "block" (fail the build) or "warn" (mark the build as unstable) based on security validation results from the ArmorCode platform.

The plugin also includes a job discovery feature that periodically scans for Jenkins jobs, sending their information to ArmorCode for monitoring and analysis. This allows for a comprehensive view of all jobs, not just those actively using the release gate.

## Table of Contents

*   [Features](#features)
*   [Installation](#installation)
*   [Configuration](#configuration)
    *   [Global Configuration](#global-configuration)
    *   [Credentials](#credentials)
*   [Usage](#usage)
    *   [Using the Plugin in a Jenkins Pipeline Project](#using-the-plugin-in-a-jenkins-pipeline-project)
    *   [Using the Plugin in a Jenkins Freestyle Project](#using-the-plugin-in-a-jenkins-freestyle-project)
*   [Job Discovery](#job-discovery)
*   [License](#license)

## Features

*   **Enforce Security Gates:** Integrate security validation directly into your CI/CD pipeline.
*   **Flexible Control:** Choose to either block builds or mark them as unstable on failure.
*   **Job Discovery:** Discover and monitor all Jenkins jobs within your instance.
*   **Broad Project Support:** Works with Pipeline, Freestyle, and Multi-branch projects.
*   **Easy Configuration:** Simple to set up global settings and credentials.
*   **Pipeline as Code:** Use a simple script to integrate with your Jenkins Pipelines.
*   **UI Configuration:** Configure Freestyle projects through the Jenkins UI.

## Installation

1.  Manually download the `.hpi` plugin file from the plugin's release page.
2.  In Jenkins, navigate to **Manage Jenkins > Plugins > Advanced settings**.
3.  In the **Deploy Plugin** section, upload the downloaded `.hpi` file.
4.  Restart Jenkins to complete the installation.

Once installed, the ArmorCode Release Gate plugin will be available as a build step for Freestyle projects and in the Pipeline Syntax Snippet Generator.

## Configuration

### Global Configuration

1.  In Jenkins, go to **Manage Jenkins > System**.
2.  Scroll down to the **ArmorCode Configuration** section.
3.  Enter your ArmorCode instance URL in the **ArmorCode Base URL** field.

### Credentials

To securely store your ArmorCode API token, you need to create a Jenkins credential.

1.  Go to **Manage Jenkins > Credentials**.
2.  Select the `(global)` domain and click **Add Credentials**.
3.  Set the **Kind** to **Secret text**.
4.  Set the **ID** to `ARMORCODE_TOKEN`. This is a required value.
5.  Paste your ArmorCode API token into the **Secret** field.
6.  Click **Create** to save the credential.

## Usage

The ArmorCode Release Gate plugin is compatible with Pipeline, Freestyle, and Multi-branch projects.

### Using the Plugin in a Jenkins Pipeline Project

This method requires a script to be added to your Jenkins Pipeline project.

1.  In the ArmorCode platform, navigate to **Manage > Integrations > Jenkins**.
2.  Select the **Jenkins Plugin** option.
3.  Enter the required values, including **Group**, **Subgroup**, **Environment**, and **Mode** (block or warn). You can also select **All Subgroups** under a particular group if desired.
4.  Copy the generated Groovy script.
5.  In your Jenkins dashboard, create a new Pipeline project and paste the code into the script editor.

You can now run the pipeline, which will use the plugin for real-time validation.

#### Sample Script

```groovy
pipeline {
    agent any
    stages {
        stage('Build') {
            steps {
                script {
                    armorcodeReleaseGate(product: "<product>", "subProducts": ["<sub-product-1>","<sub-product-2>"], env: "Production", mode: "warn")
                }
            }
        }
    }
}
```

#### Parameters

| Parameter    | Required | Description                                                                                               |
| :----------- | :------- | :-------------------------------------------------------------------------------------------------------- |
| `product`    | Yes      | Identifier of the product in ArmorCode.                                                                   |
| `subProduct` | Yes      | Identifier of the sub-product (or group) in ArmorCode.                                                    |
| `env`        | Yes      | Deployment environment (e.g., Production, Staging, QA).                                                   |
| `mode`       | No       | Behavior if the security validation fails: `block` – Block the build on failure. `warn` – Mark as unstable but continues. Default: `block`. |
| `maxRetries` | No       | Number of times to check status before failing. Default: 5.                                               |
| `targetUrl`  | No       | Custom ArmorCode API endpoint (overrides global configuration).                                           |

### Using the Plugin in a Jenkins Freestyle Project

This method allows for direct plugin configuration without needing to write a script.

1.  Create a new **Freestyle project** in Jenkins.
2.  In the **Build Steps** section, click **Add build step** and select **ArmorCode Release Gate**.
3.  Enter the required details for **Group**, **Subgroup**, and **Environment**.
4.  In the **Advanced** settings, you can customize the **Max Retries**, choose the **Mode** (Block or Warn), and define a **Target URL** if needed.
5.  Click **Apply**, then **Save**.
6.  Click **Build Now** and check the console logs for validation results.

## Job Discovery

The ArmorCode Jenkins Plugin allows you to discover and monitor all Jenkins jobs within an instance.

To enable discovery in Jenkins:

1.  Go to **Manage Jenkins > System**.
2.  Scroll down to the **ArmorCode Configuration** section.
3.  Check the **Enable Discovery** checkbox.
4.  You can update the **Discovery Schedule** (a cron expression) to determine how often the list of Jenkins jobs is sent to ArmorCode.

## License

This plugin is licensed under the [MIT License](https://opensource.org/licenses/MIT).