// LAYER 2 — pure, no I/O whatsoever. No clock, no network, no files.
// This is what makes replay possible: the same detector code runs over live books and over
// books rebuilt from an archive, and cannot tell the difference.

dependencies {
    api(project(":libs:core-model"))
    api(project(":libs:book"))
}
