# armorcode-release-gate-plugin

## Introduction

ArmorCode Release Gate plugin for Jenkins enables security teams to enforce security requirements as part of the CI/CD pipeline.
It provides capabilities to block or warn builds based on security validation results from the ArmorCode platform.
Features include build monitoring, flexible release gate controls, and detailed feedback for developers.

## Getting started

### Prerequisites

- Maven 3.6.3 or later
- Java 17 or later

### Building the plugin

1. Clone the repository
2. For running the plugin in a local Jenkins instance, run `mvn hpi:run -Dport=5000`
3. For installing the plugin in a Jenkins instance, run `mvn package` and upload the generated `hpi` file in the Jenkins plugin manager.

Or  simply run this command `mvn clean package -DskipTests -Dscminfo.skip=true -Drevision=1.0.11 -Dchangelist= -Djgit.skip=true` to build the plugin.