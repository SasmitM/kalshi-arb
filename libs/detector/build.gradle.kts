// LAYER 2 — pure, no I/O whatsoever. Depends only on model + book state.

dependencies {
    implementation(project(":libs:core-model"))
    implementation(project(":libs:common"))
    implementation(project(":libs:book"))
}
