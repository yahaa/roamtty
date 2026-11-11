// Syntax highlighting and Markdown parsing for the file preview: plain Kotlin, no Android, so it tests on the JVM.
plugins { kotlin("jvm") }
kotlin { jvmToolchain(17) }
dependencies {
    testImplementation("junit:junit:4.13.2")
}
tasks.test { testLogging { events("passed", "skipped", "failed") } }
