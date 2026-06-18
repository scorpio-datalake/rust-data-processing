# Maven Central publishing (OSS) — maintainer FAQ

Short answers for **open-source** projects publishing to **[Maven Central](https://central.sonatype.com/)**. Sonatype’s own docs are authoritative; links below stay current at their site.

---

## Is the “Maven repo” free to join?

- **Signing up for the Central Publisher Portal** ([`central.sonatype.com`](https://central.sonatype.com/)) is **free**. You authenticate with GitHub/Google or email/password ([register](https://central.sonatype.org/register/central-portal/)).
- **Publishing OSS components** follows Sonatype’s **producer terms** and normal Central rules (“what you publish is immutable after release”; see their [FAQ](https://central.sonatype.org/faq/) and [terms](https://central.sonatype.org/publish/producer-terms/)). There is **no paywall** analogous to buying a Maven “subscription” for typical community OSS—you still own **credentials, builds, signing, support, compliance**.

---

## Do I need tokens or IDs?

**Yes, for uploads from CI.**

1. **Namespace (`groupId`)** — Prove you control **`io.github.scorpio-datalake`** (GitHub org **`scorpio-datalake`**). This repo publishes under the sub-group **`io.github.scorpio-datalake.rust-data-processing`**. Follow **[Register a Namespace](https://central.sonatype.org/register/namespace/)** and **`SONATYPE_NAMESPACE_CHECKLIST.md`**.
2. **User Token (recommended for automation)** — In the Portal, **[Generate User Token](https://central.sonatype.org/publish/generate-portal-token/)**. Maven/Gradle authenticate with **`username`** + **`password`** (= token). Treat it like any API secret (**GitHub Actions secrets**, not in git).
3. **GPG signing** — Central expects **cryptographically signed** artifacts for publication (configure in `pom.xml` / Gradle; keys via maintainers—not covered in depth here).

**Important:** Tokens are **shown once**—regenerate + rotate via the Portal if lost. **Never commit tokens or paste them into workflow YAML**—store them only as GitHub repository secrets (or your CI vault).

Legacy **OSSRH** was **[sunset 30 June 2025](https://central.sonatype.org/pages/ossrh-eol/)**; new flows use **Central Publisher Portal** (+ compatible APIs/plugins per [Publishing via the Portal](https://central.sonatype.org/publish/publish-portal-guide/)).

---

## GitHub Actions (this repo)

Release checklist stories: **`P3-E1-S2d`** / **`P3-E1-S2e`** in **`Planning/PHASE3_EPICS.md`**.

Workflow **`jvm_maven_central_release.yml`** runs only when a **GitHub Release is published**. It writes **`~/.m2/settings.xml`** at runtime so Maven’s server id **`central`** matches Sonatype’s **[Publishing with Maven](https://central.sonatype.org/publish/publish-portal-maven/)** expectations (same as a local `settings.xml` `<server>` block—**no credentials in the repo**).

Configure these **repository secrets**:

| Secret | Purpose |
| --- | --- |
| **`MAVEN_CENTRAL_USERNAME`** | Portal **user token** username (short id) |
| **`MAVEN_CENTRAL_PASSWORD`** | Portal **user token** password |
| **`MAVEN_GPG_PRIVATE_KEY`** | ASCII-armored **GPG private key** used to sign artifacts |
| **`MAVEN_GPG_PASSPHRASE`** | Passphrase for that key (used by the import-GPG action) |

**Release alignment:** `bindings/java/VERSION` must be a **release** version (**no `-SNAPSHOT`**). The GitHub Release **tag** must be **`v` + VERSION** (example: VERSION `0.1.0` → tag **`v0.1.0`**). Other releases (for example a Rust-only tag) **skip** Maven deploy automatically.

### Trigger (CI/CD)

1. Bump **`bindings/java/VERSION`** and mirror **`pom.xml`** / **`gradle.properties`** (run **`python scripts/check_java_version_consistency.py`**).
2. Merge to **`main`**; ensure JVM CI is green.
3. Create a **GitHub Release** on **`main`** with tag **`v{VERSION}`** (Publish release, not draft).
4. Workflow **[`.github/workflows/jvm_maven_central_release.yml`](../../.github/workflows/jvm_maven_central_release.yml)** runs automatically:
   - Confirms the tag is on **`main`**
   - Builds **`rdp_jvm_sys`**, runs JVM tests + manifest checks
   - **`mvn -DcentralRelease=true deploy`** → Central Publisher Portal → **`autoPublish`** + **`waitUntil=published`**
5. After success, artifacts appear as **`io.github.scorpio-datalake.rust-data-processing:rust-data-processing-jvm:{VERSION}`** on [Maven Central](https://central.sonatype.com/).

**Native classifiers:** the same **`v{VERSION}`** GitHub Release also triggers **`jvm_native_maven_release.yml`**, which publishes **`rdp-jvm-sys:{VERSION}:{classifier}`** JARs (one platform binary each under **`META-INF/native/`**). See **[`NATIVE_ARTIFACT_PACKAGING.md`](NATIVE_ARTIFACT_PACKAGING.md)** and **`scripts/deploy_rdp_jvm_sys_native_jars.sh`**.

**GPG:** Upload the signing public key to a supported keyserver ([Central GPG requirements](https://central.sonatype.org/publish/requirements/gpg/)) so consumers can validate signatures.

---

## Further reading

- [Central Publisher Portal Guide](https://central.sonatype.org/publish/publish-portal-guide/)
- [Publish with Maven](https://central.sonatype.org/publish/publish-portal-maven/)
- Gradle: use **`maven-publish`** + Portal-compatible Gradle Central Publishing tooling (Sonatype/third-party docs; align with **`P3-E1-S3c`**).

Phase 3 release checklist stubs: **`Planning/PHASE3_EPICS.md`** **`P3-E1-S2c`**, root **`docs/RELEASE_CHECKLIST.md`**.
