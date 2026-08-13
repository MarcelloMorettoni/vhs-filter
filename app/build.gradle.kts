import com.android.build.api.artifact.SingleArtifact
import java.io.File
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Android refuses to install an unsigned APK, so the release variant always gets a
// signing config. Put a keystore.properties next to settings.gradle.kts to sign with
// your own key; without one we fall back to the standard debug keystore, which is
// enough to sideload and matches how the other apps here are shipped.
val keystoreProperties = Properties().apply {
    val file = rootProject.file("keystore.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}

val debugKeystore = File(System.getProperty("user.home"), ".android/debug.keystore")

val releaseKeystore: File? = keystoreProperties.getProperty("storeFile")
    ?.let { rootProject.file(it) }
    ?.takeIf { it.exists() }
    ?: debugKeystore.takeIf { it.exists() }

val usingOwnKey = keystoreProperties.getProperty("storeFile") != null && releaseKeystore != debugKeystore

android {
    namespace = "com.retro.vhs"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.retro.vhs"
        minSdk = 26
        targetSdk = 35
        versionCode = 4
        versionName = "1.3"
    }

    signingConfigs {
        if (releaseKeystore != null) {
            create("release") {
                storeFile = releaseKeystore
                storePassword = keystoreProperties.getProperty("storePassword")
                    ?: if (usingOwnKey) null else "android"
                keyAlias = keystoreProperties.getProperty("keyAlias")
                    ?: if (usingOwnKey) null else "androiddebugkey"
                keyPassword = keystoreProperties.getProperty("keyPassword")
                    ?: if (usingOwnKey) null else "android"
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfigs.findByName("release")?.let { signingConfig = it }
        }
        debug {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

/** Drops the installable APK at the project root, the way the other apps here ship. */
androidComponents {
    onVariants(selector().withBuildType("release")) { variant ->
        val apkDirectory = variant.artifacts.get(SingleArtifact.APK)
        val target = rootProject.layout.projectDirectory.file("vhs-88.apk").asFile
        tasks.register("exportApk") {
            description = "Copies the signed release APK to vhs-88.apk in the project root."
            group = "build"
            // A single declared output file, so this does not claim the whole project
            // directory and collide with every other task's outputs.
            inputs.dir(apkDirectory)
            outputs.file(target)
            doLast {
                val apk = apkDirectory.get().asFile.listFiles()
                    ?.firstOrNull { it.extension == "apk" }
                    ?: error("the release build produced no APK")
                apk.copyTo(target, overwrite = true)
                logger.lifecycle("exported ${target.path}")
            }
        }
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")

    val cameraX = "1.4.1"
    implementation("androidx.camera:camera-core:$cameraX")
    implementation("androidx.camera:camera-camera2:$cameraX")
    implementation("androidx.camera:camera-lifecycle:$cameraX")

    debugImplementation("androidx.compose.ui:ui-tooling")
}
