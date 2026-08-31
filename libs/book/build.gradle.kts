// LAYER 1 — book reconstruction. Makes no network calls, and deliberately does NOT depend on
// libs/kalshi-client: both are layer 1, and dependencies never point sideways within a layer.
// The book layer therefore speaks only core-model types; translating a WsFrame into a book
// update is the app layer's job.

dependencies {
    api(project(":libs:core-model"))
    implementation(project(":libs:common"))
}
