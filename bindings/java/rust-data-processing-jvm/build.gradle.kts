plugins {
    java
    `maven-publish`
    id("me.champeau.jmh") version "0.7.2"
}

val rdpVersion =
    layout.projectDirectory.file("../VERSION").asFile.readText(Charsets.UTF_8).trim()

group = "io.github.vihangdesai2018_png"
version = rdpVersion

java {
    val major = Runtime.version().feature()
    val javaEnums =
        when {
            major >= 24 -> JavaVersion.toVersion("$major")
            else -> JavaVersion.VERSION_21
        }
    sourceCompatibility = javaEnums
    targetCompatibility = javaEnums
}

tasks.withType<JavaCompile>().configureEach {
    val major = Runtime.version().feature()
    if (major >= 24) {
        options.release.set(major)
        options.compilerArgs.add("--enable-preview")
    } else {
        options.release.set(21)
    }
}

repositories {
    mavenCentral()
}

dependencies {
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testImplementation("org.json:json:20250107")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.11.4")
}

tasks.withType<Test> {
    useJUnitPlatform()
    val major = Runtime.version().feature()
    if (major >= 24) {
        jvmArgs("--enable-preview")
    }
    jvmArgs("--enable-native-access=ALL-UNNAMED")
    val lib = providers.environmentVariable("RDP_JVM_SYS")
    environment("RDP_JVM_SYS", lib.orElse("").get())
}

tasks.named<Jar>("jar") {
    manifest {
        attributes(
            mapOf(
                "Implementation-Title" to "rust-data-processing-jvm",
                "Implementation-Version" to rdpVersion,
            ),
        )
    }
}

jmh {
    jmhVersion.set("1.37")
    warmupIterations.set(1)
    iterations.set(1)
    fork.set(1)
}

afterEvaluate {
    tasks.named<JavaExec>("jmh") {
        jvmArgs("--enable-native-access=ALL-UNNAMED")
    }
}

publishing {
    publications {
        create<MavenPublication>("mavenJava") {
            artifactId = "rust-data-processing-jvm"
            from(components["java"])
            pom {
                name.set("rust-data-processing-jvm")
                description.set("JVM bindings for rust-data-processing (Phase 3 — Panama)")
                licenses {
                    license {
                        name.set("MIT OR Apache-2.0")
                    }
                }
            }
        }
    }
}
