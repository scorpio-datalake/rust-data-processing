import me.champeau.jmh.JmhBytecodeGeneratorTask
import org.gradle.api.tasks.JavaExec

plugins {
    java
    `maven-publish`
    id("me.champeau.jmh") version "0.7.2"
    id("com.diffplug.spotless") version "7.0.4"
}

spotless {
    java {
        googleJavaFormat("1.28.0")
        target(
            "src/main/java/**/*.java",
            "src/test/java/**/*.java",
            "src/jmh/java/**/*.java",
        )
    }
}

val rdpVersion =
    layout.projectDirectory.file("../VERSION").asFile.readText(Charsets.UTF_8).trim()

group = "io.github.scorpio-datalake.rust-data-processing"
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
    } else {
        options.release.set(21)
    }
    // Panama FFM: preview on JDK 21; JDK 22+ finalized FFM but harmless if kept until baseline bumps.
    options.compilerArgs.add("--enable-preview")
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.json:json:20250107")
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.11.4")
}

tasks.withType<Test> {
    useJUnitPlatform()
    jvmArgs("--enable-preview")
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

tasks.named("check") { dependsOn(tasks.named("spotlessCheck")) }

jmh {
    jmhVersion.set("1.37")
    warmupIterations.set(1)
    iterations.set(1)
    fork.set(1)
    // Panama FFM on JDK 21; also required by jmhRunBytecodeGenerator (not a JavaExec task).
    jvmArgs.add("--enable-preview")
    jvmArgs.add("--enable-native-access=ALL-UNNAMED")
    val lib = providers.environmentVariable("RDP_JVM_SYS").orElse("")
    environment.put("RDP_JVM_SYS", lib)
}

tasks.named<JmhBytecodeGeneratorTask>("jmhRunBytecodeGenerator") {
    jvmArgs.add("--enable-preview")
    jvmArgs.add("--enable-native-access=ALL-UNNAMED")
}

// Gradle ``jmh`` JavaExec (runs org.openjdk.jmh.Main) — in addition to ``jmh { jvmArgs }`` fork args.
afterEvaluate {
    tasks.named<JavaExec>("jmh") {
        jvmArgs("--enable-preview", "--enable-native-access=ALL-UNNAMED")
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
                url.set("https://github.com/scorpio-datalake/rust-data-processing")
                licenses {
                    license {
                        name.set("MIT License")
                        url.set("https://opensource.org/licenses/MIT")
                    }
                    license {
                        name.set("Apache License, Version 2.0")
                        url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                    }
                }
            }
        }
    }
}
