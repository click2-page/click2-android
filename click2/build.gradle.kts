plugins {
    id("com.android.library")
    id("com.vanniktech.maven.publish")
}

// Released from a git tag (vX.Y.Z) by .github/workflows/release.yml; keep in sync with CHANGELOG.md.
group = "page.click2"
version = "0.3.0"

android {
    namespace = "page.click2.sdk"
    compileSdk = 37

    defaultConfig {
        minSdk = 23
        consumerProguardFiles("consumer-rules.pro")
        buildConfigField("String", "SDK_VERSION", "\"${project.version}\"")
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests.all {
            // Shared test cases, identical to the ones in click2-ios.
            val fixtures = rootProject.file("fixtures")
            it.inputs.dir(fixtures).withPropertyName("click2Fixtures")
            it.systemProperty("click2.fixtures", fixtures.absolutePath)
        }
    }
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    implementation("com.android.installreferrer:installreferrer:2.2")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
    // Real org.json for JVM unit tests (android.jar only has stubs).
    testImplementation("org.json:json:20260814")
}

mavenPublishing {
    publishToMavenCentral(automaticRelease = true)
    // Signing keys come from ORG_GRADLE_PROJECT_signingInMemoryKey* (CI secrets); local builds don't sign.
    if (providers.gradleProperty("signingInMemoryKey").isPresent) signAllPublications()
    coordinates("page.click2", "click2-android", version.toString())
    pom {
        name.set("click2 Android SDK")
        description.set("Deep links, deferred deep links and install attribution for click2.page links.")
        inceptionYear.set("2026")
        url.set("https://github.com/click2-page/click2-android")
        licenses {
            license {
                name.set("The Apache License, Version 2.0")
                url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                distribution.set("repo")
            }
        }
        developers {
            developer {
                id.set("click2")
                name.set("click2")
                url.set("https://click2.page")
            }
        }
        scm {
            url.set("https://github.com/click2-page/click2-android")
            connection.set("scm:git:https://github.com/click2-page/click2-android.git")
            developerConnection.set("scm:git:ssh://git@github.com/click2-page/click2-android.git")
        }
    }
}
