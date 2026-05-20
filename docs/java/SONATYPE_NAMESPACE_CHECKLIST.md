# Sonatype Central Publisher — namespace checklist (**P3-E1-S2a**)

Maintainers complete **before** first **`io.github.*`** publication:

1. **Account:** [central.sonatype.com](https://central.sonatype.com/) — Sign in (GitHub/Google/email).
2. **Namespace:** Register and verify **`io.github.scorpio-datalake`** (GitHub org **`scorpio-datalake`**) — see **[Register a Namespace](https://central.sonatype.org/register/namespace/)**. Do **not** register the full project `groupId` as the namespace; use a temporary public repo `scorpio-datalake/<verification-key>` only for org verification.
3. **Proof:** GitHub verification repo (or DNS TXT for domain namespaces).
4. **Token:** **[Generate User Token](https://central.sonatype.org/publish/generate-portal-token/)** → GitHub repository secrets **`MAVEN_CENTRAL_USERNAME`** / **`MAVEN_CENTRAL_PASSWORD`** (see **`MAVEN_CENTRAL_PUBLISHING.md`** § GitHub Actions).

**Maven `groupId` for this repo:** **`io.github.scorpio-datalake.rust-data-processing`** (sub-group under the verified org namespace).

**Java packages** use underscores (not hyphens): **`io.github.scorpio_datalake.rust_data_processing.*`**.

Cross-links: **`MAVEN_CENTRAL_PUBLISHING.md`**, **`RELEASE.md`**.
