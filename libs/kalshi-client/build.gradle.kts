// LAYER 1 — auth, rest, ws, codec. Knows nothing of arbitrage.
// JDK HttpClient handles transport; Jackson streaming handles JSON.

dependencies {
    // api, not implementation: these types appear in this module's public signatures, so
    // downstream layers must be able to name them.
    api(project(":libs:core-model"))
    api(project(":libs:common"))
    implementation(libs.jackson.core)
    implementation(libs.jackson.databind)
}

// Diagnostic entry point. Deliberately not the `application` plugin: this is a library, and
// this main is an operator tool rather than a deliverable app.
tasks.register<JavaExec>("smokeCheck") {
    group = "verification"
    description = "Signs GET /portfolio/balance and asserts Kalshi returns 200. Read-only."
    mainClass.set("com.kalshiarb.kalshi.diag.SignatureSmokeCheck")
    classpath = sourceSets["main"].runtimeClasspath

    // Forward the credentials explicitly. A long-lived Gradle daemon can hold the environment
    // of whichever shell first started it, so relying on plain inheritance means `export
    // KALSHI_KEY_ID=...` in the calling shell may not reach the forked JVM. Reading through
    // `providers` takes the value from the invoking client and registers it as a configuration
    // input, so a changed value re-runs the task instead of silently reusing the old one.
    providers.environmentVariable("KALSHI_KEY_ID").orNull
            ?.let { environment("KALSHI_KEY_ID", it) }
    providers.environmentVariable("KALSHI_PRIVATE_KEY").orNull
            ?.let { environment("KALSHI_PRIVATE_KEY", it) }
}

tasks.register<JavaExec>("orderbookDemo") {
    group = "verification"
    description = "Prints live books for one event with derived asks, spreads and the " +
        "crossed-book invariant. Unauthenticated. Args: [EVENT_TICKER]"
    mainClass.set("com.kalshiarb.kalshi.diag.OrderbookDemo")
    classpath = sourceSets["main"].runtimeClasspath
    // ./gradlew orderbookDemo --args="KXHIGHLAX-26SEP01"
}
