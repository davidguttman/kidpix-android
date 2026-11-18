plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }

android {
    namespace = "dev.kidpix.tablet"
    compileSdk = 35
    defaultConfig {
        applicationId = "dev.kidpix.tablet"
        minSdk = 29
        targetSdk = 35
        versionCode = 6
        versionName = "0.5.0"
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    androidResources { noCompress += listOf("mp3", "png") }
}

val buildWeb by tasks.registering(Exec::class) {
    group = "build"
    description = "Builds JS Kid Pix + Android overlay into assets"
    commandLine("bash", rootProject.file("scripts/build-web.sh").absolutePath)
    inputs.dir(rootProject.file("upstream"))
    inputs.dir(rootProject.file("overlay"))
    outputs.dir(file("src/main/assets/kidpix"))
}
tasks.named("preBuild") { dependsOn(buildWeb) }

dependencies {
    implementation("androidx.webkit:webkit:1.13.0")
}
