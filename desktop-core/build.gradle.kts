plugins { kotlin("jvm") }
kotlin { jvmToolchain(17) }
dependencies {
    // Android ships org.json; the JVM tests bring their own copy.
    compileOnly("org.json:json:20240303")
    testImplementation("org.json:json:20240303")
    testImplementation("junit:junit:4.13.2")
}
tasks.test { testLogging { events("passed", "skipped", "failed") } }
