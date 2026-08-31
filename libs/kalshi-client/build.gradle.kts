// LAYER 1 — auth, rest, ws, codec. Knows nothing of arbitrage.
// JDK HttpClient handles transport; Jackson streaming handles JSON.

dependencies {
    implementation(project(":libs:core-model"))
    implementation(project(":libs:common"))
    implementation(libs.jackson.core)
    implementation(libs.jackson.databind)
}
