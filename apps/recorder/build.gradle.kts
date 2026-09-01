// LAYER 3 — runnable. Archives raw WS frames to gzipped JSON Lines.

plugins {
    application
}

dependencies {
    implementation(project(":libs:core-model"))
    implementation(project(":libs:common"))
    implementation(project(":libs:kalshi-client"))
    implementation(project(":libs:book"))
    implementation(project(":libs:detector"))
    implementation(project(":libs:persistence"))
    implementation(libs.jackson.core)
    implementation(libs.jackson.databind)
}

application {
    mainClass.set("com.kalshiarb.recorder.RecorderMain")
    // ZGC per CLAUDE.md section 6.
    applicationDefaultJvmArgs = listOf("-XX:+UseZGC")
}

// Replays an archive through the book layer and verifies reconstruction against every
// periodic snapshot. This is task 6's acceptance criterion.
tasks.register<JavaExec>("bookReplay") {
    group = "verification"
    description = "Replays the frame archive through the book layer and verifies it. " +
        "Args: [archiveDirOrFile]"
    mainClass.set("com.kalshiarb.recorder.BookReplay")
    classpath = sourceSets["main"].runtimeClasspath
    workingDir = rootProject.projectDir
}

tasks.register<JavaExec>("latencyStudy") {
    group = "verification"
    description = "Measures violation lifetimes and survival at 10/50/200/1000ms of latency. " +
        "Args: [archiveDirOrFile] [contracts]"
    mainClass.set("com.kalshiarb.recorder.LatencyStudy")
    classpath = sourceSets["main"].runtimeClasspath
    workingDir = rootProject.projectDir
}

tasks.register<JavaExec>("monotoneScan") {
    group = "verification"
    description = "Scans a live nested-threshold series for monotonicity violations. " +
        "Args: [seriesTicker] [maxMarkets] [contracts]"
    mainClass.set("com.kalshiarb.recorder.MonotoneScan")
    classpath = sourceSets["main"].runtimeClasspath
    workingDir = rootProject.projectDir
}

tasks.register<JavaExec>("detectorReplay") {
    group = "verification"
    description = "Runs the detector over recorded frames and reports fee-adjusted, " +
        "depth-limited violations. Args: [archiveDirOrFile] [maxContracts]"
    mainClass.set("com.kalshiarb.recorder.DetectorReplay")
    classpath = sourceSets["main"].runtimeClasspath
    workingDir = rootProject.projectDir
}

tasks.named<JavaExec>("run") {
    // Gradle defaults a JavaExec's working directory to the module directory, so `data/frames`
    // silently landed in apps/recorder/data/frames. Anchor it at the repo root instead, where
    // .gitignore's data/ entry lives and where anyone would look for it.
    workingDir = rootProject.projectDir
}