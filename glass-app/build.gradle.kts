plugins {
    id("com.android.application")
}

android {
    namespace = "dev.glass.glass"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.glass.glass"
        minSdk = 19
        targetSdk = 19
        versionCode = 1
        versionName = "0.1"
        multiDexEnabled = false

        val transportKind = (project.findProperty("transportKind") as String?) ?: "tcp"
        buildConfigField("String", "TRANSPORT_KIND", "\"" + transportKind + "\"")
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            buildConfigField("String", "TRANSPORT_KIND", "\"tcp\"")
        }
        release {
            isMinifyEnabled = false
            buildConfigField("String", "TRANSPORT_KIND", "\"rfcomm\"")
        }
    }

    lint {
        disable += setOf("OldTargetApi", "GoogleAppIndexingWarning", "ExpiredTargetSdkVersion")
    }

    packaging {
        resources {
            excludes += setOf("META-INF/*.kotlin_module")
        }
    }
}

dependencies {
    implementation(project(":protocol"))
    compileOnly(files("libs/gdk-stub.jar"))
    implementation("androidx.localbroadcastmanager:localbroadcastmanager:1.1.0")
}

tasks.register<Jar>("rejarGdk") {
    description = "Re-package the local Glass GDK class tree at ../gdk into libs/gdk-stub.jar"
    group = "build setup"
    from(rootProject.file("gdk"))
    include("com/**/*.class")
    archiveFileName.set("gdk-stub.jar")
    destinationDirectory.set(layout.projectDirectory.dir("libs"))
}
