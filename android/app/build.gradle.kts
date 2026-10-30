plugins {
    id("com.android.application")
}

// Release signing is supplied only by the environment, normally from GitHub
// Actions secrets, so no keystore or password is ever stored in the repository.
val releaseKeystoreFile = providers.environmentVariable("BLEMESHCTL_KEYSTORE_FILE")

android {
    namespace = "io.github.topiautio.blemeshctl"
    compileSdk = 37

    defaultConfig {
        applicationId = "io.github.topiautio.blemeshctl"
        minSdk = 26
        targetSdk = 37
        versionCode = 4
        versionName = "0.3.1"
    }

    signingConfigs {
        if (releaseKeystoreFile.isPresent) {
            create("release") {
                storeFile = file(releaseKeystoreFile.get())
                storePassword = providers.environmentVariable("BLEMESHCTL_KEYSTORE_PASSWORD").get()
                keyAlias = providers.environmentVariable("BLEMESHCTL_KEY_ALIAS").get()
                keyPassword = providers.environmentVariable("BLEMESHCTL_KEY_PASSWORD").get()
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (releaseKeystoreFile.isPresent) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
}
