plugins {
    id("com.android.library")
}

android {
    namespace = "net.osmand.aidlapi"
    compileSdk = 35

    defaultConfig {
        minSdk = 24
    }

    sourceSets["main"].apply {
        java.srcDirs(rootProject.file("OsmAnd/OsmAnd-api/src"))
        aidl.srcDirs(rootProject.file("OsmAnd/OsmAnd-api/src"))
    }

    buildFeatures {
        aidl = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        abortOnError = false
    }
}

dependencies {
    implementation("androidx.annotation:annotation:1.6.0")
}
