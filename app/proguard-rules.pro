# JSch instantiates ciphers, KEX, MACs and signatures from class names in its config.
-keep class com.jcraft.jsch.** { *; }
# The Bouncy Castle JCA provider registers its algorithm implementations by class name.
-keep class org.bouncycastle.jcajce.provider.** { *; }
-keep class org.bouncycastle.jce.provider.** { *; }

# Optional integrations JSch and Bouncy Castle probe for but Android does not ship.
-dontwarn com.sun.jna.**
-dontwarn org.newsclub.net.unix.**
-dontwarn org.apache.logging.log4j.**
-dontwarn org.slf4j.**
-dontwarn org.ietf.jgss.**
-dontwarn javax.naming.**
-dontwarn java.lang.System$Logger**
