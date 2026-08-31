// LAYER 3 — runnable. Archives raw WS frames to gzipped JSON Lines.

plugins {
    application
}

dependencies {
    implementation(project(":libs:core-model"))
    implementation(project(":libs:common"))
    implementation(project(":libs:kalshi-client"))
    implementation(libs.jackson.core)
    implementation(libs.jackson.databind)
}

application {
    mainClass.set("com.kalshiarb.recorder.RecorderMain")
    // ZGC per CLAUDE.md section 6.
    applicationDefaultJvmArgs = listOf("-XX:+UseZGC")
}
