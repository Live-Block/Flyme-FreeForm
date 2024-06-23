plugins {
    id("com.android.library")
}

android {
    namespace = "com.freeform.hiddenapi"
    compileSdk = 34
    buildToolsVersion = "34.0.3"

    defaultConfig {
        minSdk = 28
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    buildFeatures {
        aidl = true
        }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
}

dependencies {
    compileOnly("androidx.annotation:annotation:1.6.0")
    annotationProcessor("dev.rikka.tools.refine:annotation-processor:4.1.0")
    compileOnly("dev.rikka.tools.refine:annotation:4.1.0")
}