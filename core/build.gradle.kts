plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.sqldelight)
}

kotlin { jvmToolchain(17) }

sqldelight {
    databases {
        create("TaskBabaDatabase") {
            packageName.set("app.taskbaba.db")
        }
    }
}

dependencies {
    api(libs.coroutines.core)
    api(libs.serialization.json)
    api(libs.sqldelight.sqlite)
    implementation(libs.sqldelight.coroutines)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.coroutines.test)
}

tasks.test { useJUnitPlatform() }
