// Root build. No source of its own; configures every subproject uniformly.
// Java 21, JUnit 5 + AssertJ everywhere. See CLAUDE.md section 6.

plugins {
    java
}

subprojects {
    // java-library, not plain java: the layers need `api` so that a value type declared in
    // core-model and exposed in a layer-1 signature is visible to a layer-3 caller. With plain
    // `implementation` everywhere, apps/recorder could call a method returning a Price without
    // being able to name Price.
    apply(plugin = "java-library")

    group = "com.kalshiarb"
    version = "0.1.0"

    repositories {
        mavenCentral()
    }

    java {
        toolchain {
            languageVersion.set(JavaLanguageVersion.of(21))
        }
    }

    tasks.withType<JavaCompile>().configureEach {
        options.encoding = "UTF-8"
        // Records, sealed types, pattern matching are all final in 21 — no preview needed.
        options.compilerArgs.add("-Xlint:all")
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
        testLogging {
            events("passed", "skipped", "failed")
        }
        // Pinned real API responses live at the repo root, not in module resources: they are
        // shared documentation of the wire format (CLAUDE.md section 5) as much as test input,
        // and duplicating them per module would let the copies drift.
        systemProperty(
            "kalshi.samples.dir",
            rootProject.layout.projectDirectory.dir("docs/kalshi-api-snapshot/samples")
                .asFile.absolutePath,
        )
    }

    dependencies {
        val versionCatalog = rootProject.extensions
            .getByType<VersionCatalogsExtension>()
            .named("libs")
        "testImplementation"(versionCatalog.findLibrary("junit-jupiter").get())
        "testRuntimeOnly"(versionCatalog.findLibrary("junit-platform-launcher").get())
        "testImplementation"(versionCatalog.findLibrary("assertj-core").get())
    }
}
