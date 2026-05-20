# Sonatype Central Publisher — namespace checklist (**P3-E1-S2a**)

Maintainers complete **before** first **`io.github.*`** publication:

1. **Account:** [central.sonatype.com](https://central.sonatype.com/) — Sign in (GitHub/Google/email).
2. **Namespace:** Register **`groupId`** — for GitHub org/user patterns see **[Register a Namespace](https://central.sonatype.org/register/namespace/)**.
3. **Proof:** DNS TXT or GitHub verification per portal wizard.
4. **Token:** **[Generate User Token](https://central.sonatype.org/publish/generate-portal-token/)** → GitHub repository secrets **`MAVEN_CENTRAL_USERNAME`** / **`MAVEN_CENTRAL_PASSWORD`** (see **`MAVEN_CENTRAL_PUBLISHING.md`** § GitHub Actions).

Cross-links: **`MAVEN_CENTRAL_PUBLISHING.md`**, **`RELEASE.md`**.
