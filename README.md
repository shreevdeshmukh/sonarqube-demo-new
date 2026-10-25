# SonarQube Demo

Minimal Java 17 + Maven project for testing Jenkins and SonarQube.

## Jenkins prerequisites
- JDK 17 or newer (JDK 21 is suitable)
- Maven installed on the Jenkins agent and available as `mvn`
- Pipeline and SonarQube Scanner for Jenkins plugins
- In **Manage Jenkins -> System**, configure a SonarQube installation named exactly `SonarQube`, with a token credential

Push this folder to your own Git repository. Create a Pipeline job using **Pipeline script from SCM**, choose Git, enter your repository URL and branch, and set **Script Path** to `Jenkinsfile`.

The Jenkins agent must be able to reach the SonarQube URL configured in Jenkins. Prefer a private IP or HTTPS endpoint.

## Local build
Run `mvn -B clean verify`.
