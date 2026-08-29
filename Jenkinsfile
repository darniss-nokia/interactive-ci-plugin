/*
 * Build configuration for ci.jenkins.io.
 * See the pipeline library docs for all options:
 *   https://github.com/jenkins-infra/pipeline-library/
 *
 * Runs the full Maven build (compile, unit tests, SpotBugs, HPI) across the
 * platforms/JDKs below. The Jenkins hosting checker requires jdk 21 or 25 in
 * buildPlugin; both platforms build on JDK 21 (the 2.568 baseline requires Java 21).
 */
buildPlugin(
  useContainerAgent: true,
  configurations: [
    [platform: 'linux',   jdk: 21],
    [platform: 'windows', jdk: 21],
  ]
)
