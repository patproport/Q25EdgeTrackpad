plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val releaseSigningVariableNames = listOf(
    "Q25_RELEASE_STORE_FILE",
    "Q25_RELEASE_STORE_PASSWORD",
    "Q25_RELEASE_KEY_ALIAS",
    "Q25_RELEASE_KEY_PASSWORD"
)
val releaseSigningValues = releaseSigningVariableNames.associateWith { System.getenv(it) }
val releaseSigningConfigured = releaseSigningValues.values.all { !it.isNullOrBlank() }

android {
    namespace = "tech.shroyer.q25trackpadcustomizer"
    compileSdk = 34

    defaultConfig {
        // Public release ID; the Kotlin namespace remains unchanged.
        applicationId = "io.github.patproport.q25edgetrackpad"
        minSdk = 21
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"
    }

    if (releaseSigningConfigured) {
        signingConfigs {
            create("release") {
                storeFile = file(releaseSigningValues.getValue("Q25_RELEASE_STORE_FILE")!!)
                storePassword = releaseSigningValues.getValue("Q25_RELEASE_STORE_PASSWORD")
                keyAlias = releaseSigningValues.getValue("Q25_RELEASE_KEY_ALIAS")
                keyPassword = releaseSigningValues.getValue("Q25_RELEASE_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        getByName("release") {
            if (releaseSigningConfigured) {
                signingConfig = signingConfigs.getByName("release")
            }
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

gradle.taskGraph.whenReady {
    if (allTasks.any { task -> task.name.matches(Regex(".*Release($|[A-Z].*)")) }
        && !releaseSigningConfigured) {
        throw GradleException(
            "Release signing requires all four environment variables: " +
                releaseSigningVariableNames.joinToString(", ")
        )
    }
}

dependencies {
    // Compile-time signatures only; Android supplies these framework classes at runtime.
    compileOnly(project(":hidden-api-stubs"))
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
}
