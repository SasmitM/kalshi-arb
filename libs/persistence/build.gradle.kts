// LAYER 1 — the frame archive, behind FrameWriter / FrameReader.
//
// Lives in libs/ rather than apps/recorder because the reader has more than one consumer: the
// recorder writes, and the replay and verification tools read. An app must never depend on
// another app, so leaving FrameReader inside apps/recorder would have forced exactly that as
// soon as apps/replay exists.

dependencies {
    api(project(":libs:core-model"))
    implementation(project(":libs:common"))
    implementation(libs.jackson.core)
}
