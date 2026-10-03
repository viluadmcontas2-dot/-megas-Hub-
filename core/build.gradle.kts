// :core — motor, cérebro, estado e sessão em Kotlin puro (JVM). Sem Android: testa em qualquer máquina.
plugins { id("org.jetbrains.kotlin.jvm") }

java { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
tasks.withType<JavaCompile> { options.release.set(17) }

dependencies {
    testImplementation("junit:junit:4.13.2")
}
tasks.test { useJUnit() }
