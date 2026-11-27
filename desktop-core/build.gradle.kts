plugins { kotlin("jvm") }
kotlin { jvmToolchain(17) }
dependencies {
    // Pure-Java Opus codec (BSD-3-Clause, 1:1 libopus port) with VOIP mode, In-band FEC, DTX, and PLC.
    implementation("io.github.jaredmdobson:concentus:1.0.2")
    // Android ships org.json; the JVM tests bring their own copy.
    compileOnly("org.json:json:20240303")
    testImplementation("org.json:json:20240303")
    testImplementation("junit:junit:4.13.2")
}
tasks.test { testLogging { events("passed", "skipped", "failed") } }
