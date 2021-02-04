name := "streaming-ocr"
version := "0.1"

val javacppVersion = "1.5"
version      := javacppVersion
scalaVersion := "2.13.3"
scalacOptions ++= Seq("-unchecked", "-deprecation", "-Xlint")

val akkaVersion = "2.6.10"
val akkaHTTPVersion = "10.2.2"
val hapiFHIRVersion = "5.2.1"

// Platform classifier for native library dependencies
val platform = org.bytedeco.javacpp.Loader.getPlatform
// Libraries with native dependencies
val bytedecoPresetLibs = Seq(
  "opencv" -> s"4.0.1-$javacppVersion",
  "ffmpeg" -> s"4.1.3-$javacppVersion").flatMap {
  case (lib, ver) => Seq(
    // Add both: dependency and its native binaries for the current `platform`
    "org.bytedeco" % lib % ver withSources() withJavadoc(),
    "org.bytedeco" % lib % ver classifier platform
  )
}

libraryDependencies ++= bytedecoPresetLibs

libraryDependencies += "org.scala-lang.modules" %% "scala-parallel-collections" % "1.0.0"

libraryDependencies += "net.sourceforge.tess4j" % "tess4j" % "4.5.4"
libraryDependencies += "com.typesafe.akka" %% "akka-stream" % akkaVersion
libraryDependencies += "com.typesafe.akka" %% "akka-slf4j" % akkaVersion
libraryDependencies += "com.typesafe.akka" %% "akka-http"   % akkaHTTPVersion
libraryDependencies += "com.typesafe.akka" %% "akka-http-spray-json" % akkaHTTPVersion
libraryDependencies += "com.atlascopco" % "hunspell-bridj" % "1.0.4"
libraryDependencies += "org.apache.opennlp" % "opennlp-tools" % "1.9.1"
libraryDependencies += "com.joestelmach" % "natty" % "0.13"
libraryDependencies += "ch.qos.logback" % "logback-classic" % "1.2.3"

libraryDependencies += "ca.uhn.hapi.fhir" % "hapi-fhir-structures-r4" % hapiFHIRVersion
libraryDependencies += "ca.uhn.hapi.fhir" % "hapi-fhir-client" % hapiFHIRVersion
libraryDependencies += "org.apache.commons" % "commons-text" % "1.8"

resolvers += "Sonatype OSS Snapshots" at "https://oss.sonatype.org/content/repositories/snapshots"
resolvers += Resolver.mavenLocal

autoCompilerPlugins := true

// add a JVM option to use when forking a JVM for 'run'
javaOptions += "-Xmx1G"
