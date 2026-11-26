plugins { id("com.android.library"); id("org.jetbrains.kotlin.android") }

android {
    namespace = "dev.kidpix.glfx"
    compileSdk = 35
    defaultConfig {
        minSdk = 29
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    androidResources { noCompress += listOf("png") }
}

dependencies {
    androidTestImplementation("androidx.test:runner:1.5.0")
    androidTestImplementation("androidx.test:core:1.5.0")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
}

// The comparison test writes PNGs + results.tsv to the additional test output dir; copy them to build/renders.
val copyRenders by tasks.registering(Copy::class) {
    from(layout.buildDirectory.dir("outputs/connected_android_test_additional_output/debugAndroidTest/connected")) {
        include("**/renders/**")
        eachFile { path = path.substringAfter("renders/") }
        includeEmptyDirs = false
    }
    into(layout.buildDirectory.dir("renders"))
}
tasks.configureEach { if (name == "connectedDebugAndroidTest") finalizedBy(copyRenders) }
