# Bill of Materials (BOM)

Software Bill of Materials for the **Interactive CI** Jenkins plugin. It records the exact
components that make up the artifact and the toolchain used to build it, so the build is auditable
and reproducible.

- **Generated:** 2026-07-22
- **Method:** `mvn org.apache.maven.plugins:maven-dependency-plugin:tree` + `help:effective-pom` against the resolved `bom-2.568.x` platform.
- **Regenerate:** see [How to regenerate](#how-to-regenerate).

---

## 1. Artifact identity

| Field | Value |
|---|---|
| Group ID | `io.jenkins.plugins` |
| Artifact ID | `interactive-ci` |
| Version | `${changelist}` → `999999-SNAPSHOT` for local/dev builds (CD assigns the release version via JEP-305 incrementals) |
| Packaging | `hpi` (Jenkins plugin) |
| Artifact | `target/interactive-ci.hpi` |
| Size | 116,613 bytes |
| SHA-256 | `d0382e160127ef73e1528f2cf636d55aea4da0c78f79fa3c6e1b807c2d17ab64` |
| License | MIT |

> The SHA-256 and size above are for the build produced on 2026-07-22; they change on every rebuild.
> Recompute with `sha256sum target/interactive-ci.hpi`.

---

## 2. Build toolchain

| Component | Version | Role |
|---|---|---|
| JDK (build) | Eclipse Temurin / Red Hat OpenJDK **21.0.11 LTS** | compiler + test runtime |
| Bytecode target | **Java 21** (`maven.compiler.release=21`) | minimum runtime (the 2.568 baseline builds on Java 21) |
| Apache Maven | **3.9.9** | build tool |
| Parent POM | `org.jenkins-ci.plugins:plugin` **6.2211.v27f680c93c53** | Jenkins plugin conventions |
| Plugin BOM | `io.jenkins.tools.bom:bom-2.568.x` **6715.v52b_c00222d1e** | dependency alignment |
| `maven-hpi-plugin` | **3.1814.v77d15159f9b_d** (from parent POM) | HPI packaging |
| Jenkins core (target) | **2.568.1** | `provided` platform |

---

## 3. Direct runtime dependencies

These are declared in `pom.xml`. Versions marked *(BOM)* are governed by `bom-2.568.x`; the others
are explicitly pinned.

| Artifact | Version | Scope | License | Why it's here |
|---|---|---|---|---|
| `org.jenkins-ci.plugins.workflow:workflow-step-api` | 724.v538c2362b_dfb_ *(BOM)* | compile | MIT | Defines `Step`/`StepExecution` for `askInteractive`. |
| `org.jenkins-ci.plugins.workflow:workflow-api` | 1413.v2ff1a_5e720fa_ *(BOM)* | compile | MIT | `FlowNode` + `PauseAction` for the anchored console link / "Paused" flow-graph marker. |
| `org.jenkins-ci.plugins.workflow:workflow-job` | 1571.1580.v18e46842c125 *(BOM)* | compile | MIT | `WorkflowRun` / pipeline job model. |
| `org.jenkins-ci.plugins.workflow:workflow-support` | 1015.v785e5a_b_b_8b_22 *(BOM)* | compile | MIT | `AbstractStepExecutionImpl` durable base. |
| `org.jenkins-ci.plugins.workflow:workflow-durable-task-step` | 1479.v56e587f413a_7 *(BOM)* | compile | MIT | Durable-step foundation (survives restart). |
| `org.jenkins-ci.plugins:pipeline-input-step` | 560.v56198a_642157 *(BOM)* | compile | MIT | **Hard requirement** — modal surface + `input` bridge target. |
| `org.jenkins-ci.plugins:structs` | 362.va_b_695ef4fdf9 *(BOM)* | compile | MIT | `@DataBoundConstructor` describable binding. |
| `org.jenkins-ci.plugins:script-security` | 1402.1405.vc96e74964250 *(BOM)* | compile | MIT | Transitive of input-step; sandbox integration. |
| `io.jenkins.plugins:ionicons-api` | 94.vcc3065403257 *(BOM)* | compile | MIT | Theme-aware notification icons (bell / badge / sidebar). |
| `io.jenkins:configuration-as-code` | 2100.vb_fd699d2a_09c *(BOM)* | compile *(optional)* | MIT | JCasC support — **optional** at runtime. |
| `io.jenkins.plugins:markdown-formatter` | **346.v3c6828ddd39e** (pinned) | compile | MIT | **Hard requirement** — provides the commonmark library on the classpath for safe Markdown → HTML (review item B12), so no third-party jar ships in our HPI. Not managed by the BOM; `requiredCore` 2.528.3 is well below our 2.568 baseline. |

---

## 4. Notable transitive runtime dependencies

Pulled in by the direct dependencies above and present at runtime. Each is contributed by its own
plugin (or Jenkins core) per Jenkins' plugin classloading model — **none are bundled into our HPI**
(see §7).

| Artifact | Version | License | Via |
|---|---|---|---|
| `org.jenkins-ci.plugins:scm-api` | 728.vc30dcf7a_0df5 | MIT | workflow-api |
| `io.jenkins.plugins:asm-api` (ASM 9.10.1) | 9.10.1-216.va_9256d3b_844b_ | BSD-3-Clause | scm-api |
| `io.jenkins.plugins:caffeine-api` | 3.2.4-208.v7e2da_a_7db_82b_ | Apache-2.0 | workflow-support |
| `org.jboss.marshalling:jboss-marshalling-river` | 2.3.0 | Apache-2.0 | workflow-support |
| `org.jenkins-ci.plugins:durable-task` | 686.v80ff80875b_82 | MIT | workflow-durable-task-step |
| `io.jenkins.plugins:lib-durable-task` | 91.v991f7ef418ee | MIT | durable-task |
| `org.jenkins-ci.plugins:credentials` | 1506.v948b_b_b_7dec44 | MIT | pipeline-input-step |
| `org.jenkins-ci.plugins:bouncycastle-api` | 2.30.1.84-291.v9f17b_21896e2 | MIT / BC | credentials |
| `org.kohsuke:groovy-sandbox` | 1.34.1 | MIT | script-security |
| `io.jenkins.plugins:commons-lang3-api` (commons-lang3 3.20.0) | 3.20.0-109.ve43756e2d2b_4 | Apache-2.0 | ionicons-api |
| `io.jenkins.plugins:commons-text-api` (commons-text 1.15.0) | 1.15.0-218.va_61573470393 | Apache-2.0 | configuration-as-code (optional) |
| `io.jenkins.plugins:json-api` (org.json 20251224) | 20251224-185.v0cc18490c62c | Public Domain | configuration-as-code (optional) |
| `io.jenkins.plugins:snakeyaml-api` | 2.5-149.v72471e9c6371 | Apache-2.0 | configuration-as-code (optional) |
| `org.jenkins-ci.plugins:antisamy-markup-formatter` | 173.v680e3a_b_69ff3 | MIT | configuration-as-code (optional) |
| `org.commonmark:commonmark` (+ ext-autolink, gfm-strikethrough, gfm-tables, heading-anchor, ins) | 0.29.0 | BSD-2-Clause | markdown-formatter |
| `org.nibor.autolink:autolink` | 0.12.0 | MIT | markdown-formatter |
| `com.vdurmont:emoji-java` | 5.1.1 | MIT | markdown-formatter |

---

## 5. Provided platform (Jenkins core 2.568.1 — not bundled)

The plugin compiles against but does **not** ship these; the controller provides them at runtime.
Selected highlights:

| Artifact | Version | License |
|---|---|---|
| `org.jenkins-ci.main:jenkins-core` | 2.568.1 | MIT |
| `org.jenkins-ci.main:remoting` | 3355.v388858a_47b_33 | MIT |
| `org.kohsuke.stapler:stapler` | 2088.v915606dc8e86 | BSD-3-Clause |
| `com.thoughtworks.xstream:xstream` | 1.4.21 | BSD-3-Clause |
| `com.google.guava:guava` | 33.6.0-jre | Apache-2.0 |
| `org.springframework.security:spring-security-web` | 7.1.0 | Apache-2.0 |
| `org.springframework:spring-core` | 7.0.8 | Apache-2.0 |
| `jakarta.servlet:jakarta.servlet-api` | 5.0.0 | EPL-2.0 / GPL-2.0-CE |
| `org.codehaus.groovy:groovy-all` | 2.4.21 | Apache-2.0 |
| `com.github.spotbugs:spotbugs-annotations` | 4.9.8 | LGPL-2.1 |

---

## 6. Test-only dependencies (not shipped)

Used to compile/run the JUnit 5 suite; excluded from the HPI.

| Artifact | Version | License | Purpose |
|---|---|---|---|
| `org.jenkins-ci.main:jenkins-test-harness` | 2573.vd91b_8a_43e019 | MIT | `JenkinsRule` integration tests |
| `org.jenkins-ci.main:jenkins-war` | 2.568.1 | MIT | boots a real controller for `JenkinsRule` |
| `org.junit.jupiter:junit-jupiter` | 6.1.2 | EPL-2.0 | JUnit 5 engine |
| `org.junit.vintage:junit-vintage-engine` | 6.1.2 | EPL-2.0 | runs harness' JUnit 4 helpers under the JUnit 5 launcher |
| `org.jenkins-ci.plugins.workflow:workflow-cps` | 4350.vcc65d4958821 | MIT | author `CpsFlowDefinition` scripts in tests only |
| `org.jenkins-ci.plugins.workflow:workflow-basic-steps` | 1098.v808b_fd7f8cf4 | MIT | `echo`/`input` steps in test pipelines |
| `io.jenkins.configuration-as-code:test-harness` | 2100.vb_fd699d2a_09c | MIT | JCasC round-trip test support |

> `workflow-cps` is deliberately **test-scoped**: the plugin's runtime is execution-engine agnostic
> (it only needs `workflow-step-api`), so installs are not forced onto a `workflow-cps` floor.

---

## 7. License summary

| License | Applies to |
|---|---|
| **MIT** | This plugin (the only code bundled in the HPI), Jenkins core, and the great majority of Jenkins plugin dependencies. |
| **BSD-2-Clause** | `commonmark` (provided by the markdown-formatter plugin; not bundled). |
| **BSD-3-Clause** | Stapler, XStream, ASM. |
| **Apache-2.0** | Guava, Spring, Caffeine, SnakeYAML, commons-lang3/text, OWASP HTML sanitizer. |
| **EPL / LGPL** | Test-only (JUnit) and build-only (SpotBugs annotations) components. |

**Nothing third-party is bundled into the distributed HPI.** `WEB-INF/lib` contains only the plugin's
own `interactive-ci.jar` (MIT); every runtime library — including `commonmark` — is contributed by
Jenkins core or by a separately-installed plugin dependency. No copyleft (GPL) code is bundled, and the
distribution is compatible with redistribution on the Jenkins Update Center.

---

## How to regenerate

```bash
export PATH="$HOME/.build-tools/apache-maven-3.9.9/bin:$PATH"   # JDK 21 + Maven on PATH
cd interactive-input

# Full resolved tree (all scopes)
mvn -B -ntp dependency:tree -DoutputFile=deptree.txt

# Effective settings (compiler release, jenkins.version, resolved BOM)
mvn -B -ntp help:effective-pom -Doutput=epom.xml

# Artifact hash + size
sha256sum target/interactive-ci.hpi && stat -c '%s bytes' target/interactive-ci.hpi

# What is actually bundled in the HPI
unzip -l target/interactive-ci.hpi 'WEB-INF/lib/*'

# (Optional) CycloneDX machine-readable SBOM
mvn org.cyclonedx:cyclonedx-maven-plugin:makeAggregateBom
# -> target/bom.json  (import into your SCA / vulnerability scanner)
```

For automated supply-chain scanning, generate the CycloneDX `bom.json` and feed it to your SCA
tool (e.g. OWASP Dependency-Track, Grype, Trivy).
