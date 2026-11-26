plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }

android {
    namespace = "dev.kidpix.paint"
    compileSdk = 35
    defaultConfig {
        applicationId = "dev.kidpix.tablet"
        minSdk = 29
        targetSdk = 35
        versionCode = 11
        versionName = "1.2.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
        // Release code under its own applicationId (dev.kidpix.tablet.perf) for measuring on a
        // tablet without touching the real app's picture, state or undo history.
        create("perf") {
            initWith(getByName("release"))
            applicationIdSuffix = ".perf"
            versionNameSuffix = "-perf"
            matchingFallbacks += listOf("release")
            isProfileable = true
        }
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    androidResources { noCompress += listOf("mp3", "png", "wav") }
    sourceSets["main"].assets.srcDir(layout.buildDirectory.dir("generated/kidpixAssets"))
    testOptions { unitTests { isIncludeAndroidResources = true } }
}

// ---- assets: copied from the main checkout's upstream/ and overlay/ (never committed) ----
fun findUpstream(): File {
    val candidates = listOfNotNull(
        (findProperty("kidpix.upstream") as String?)?.let { File(it) },
        rootProject.file("upstream"),
        rootProject.file("../../upstream"), // .worktrees/<name> -> main checkout
    )
    return candidates.firstOrNull { File(it, "index.html").exists() }
        ?: throw GradleException("upstream/ not found (tried $candidates); run git submodule update --init or pass -Pkidpix.upstream=<dir>")
}
fun findOverlay(): File {
    val candidates = listOf(rootProject.file("overlay"), rootProject.file("../../overlay"))
    return candidates.firstOrNull { File(it, "opening.png").exists() } ?: candidates.first()
}
val kidpixAssets = layout.buildDirectory.dir("generated/kidpixAssets")
val copyKidpixAssets by tasks.registering(Sync::class) {
    group = "build"
    description = "Copies upstream img/snd/sndmp3/static and overlay images into the module assets"
    val up = findUpstream()
    val ov = findOverlay()
    from(up) { include("img/**", "snd/**", "sndmp3/**", "static/**") }
    from(ov) { include("img/**", "opening.png"); into("android") }
    into(kidpixAssets)
}
tasks.named("preBuild") { dependsOn(copyKidpixAssets) }

// ---- headless harness: ./gradlew :kidpix:renderScenarios -Pscenarios=f.json -Pout=dir [-Ponly=glob] ----
val renderRequested = gradle.startParameter.taskNames.any { it.endsWith("renderScenarios") }
// ./gradlew :kidpix:replayLog -Plog=<exported dir> -Pout=<png>: an action log pulled off a tablet, replayed headless
val replayRequested = gradle.startParameter.taskNames.any { it.endsWith("replayLog") }
tasks.withType<Test>().configureEach {
    maxHeapSize = "3g"
    systemProperty("robolectric.graphicsMode", "NATIVE")
    systemProperty("kidpix.assets", kidpixAssets.get().asFile.absolutePath)
    systemProperty("kidpix.defaultScenarios", file("scenarios/pencil.json").absolutePath)
    systemProperty("kidpix.defaultOut", layout.buildDirectory.dir("scenario-out").get().asFile.absolutePath)
    dependsOn(copyKidpixAssets)
    testLogging { events("failed"); showStandardStreams = true; exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL }
    if (renderRequested) {
        filter { includeTestsMatching("dev.kidpix.paint.harness.RenderScenariosTest") }
        (findProperty("scenarios") as String?)?.let { systemProperty("kidpix.scenarios", File(it).absolutePath) }
        (findProperty("out") as String?)?.let { systemProperty("kidpix.out", File(it).absolutePath) }
        (findProperty("only") as String?)?.let { systemProperty("kidpix.only", it) }
        outputs.upToDateWhen { false }
    }
    if (replayRequested) {
        filter { includeTestsMatching("dev.kidpix.paint.harness.ReplayLogTest") }
        (findProperty("log") as String?)?.let { systemProperty("kidpix.log", File(it).absolutePath) }
        (findProperty("out") as String?)?.let { systemProperty("kidpix.out", File(it).absolutePath) }
        outputs.upToDateWhen { false }
    }
}
tasks.register("replayLog") {
    group = "verification"
    description = "Replays an exported action log headless to a PNG"
    dependsOn("testDebugUnitTest")
}
tasks.register("renderScenarios") {
    group = "verification"
    description = "Renders a scenario file headless to PNGs"
    dependsOn("testDebugUnitTest")
}

dependencies {
    implementation(project(":glfx"))
    implementation("androidx.graphics:graphics-core:1.0.4") // front-buffered pen layer (app/FrontInk.kt)
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
    androidTestImplementation("androidx.test:runner:1.5.0")
    androidTestImplementation("androidx.test:core:1.5.0")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
}
