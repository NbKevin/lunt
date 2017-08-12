name := "Lunt"

version := "1.0"

scalaVersion := "2.12.1"

libraryDependencies +=
  "com.typesafe.akka" %% "akka-actor" % "2.5.3"

libraryDependencies +=
  "org.scalactic" %% "scalactic" % "3.0.1"

libraryDependencies +=
  "org.scalatest" %% "scalatest" % "3.0.1" % "test"

libraryDependencies +=
  "com.typesafe.akka" %% "akka-testkit" % "2.5.3" % "test"

libraryDependencies +=
  "com.typesafe.akka" %% "akka-remote" % "2.5.3"

libraryDependencies += "com.trueaccord.scalapb" %% "scalapb-runtime" % com.trueaccord.scalapb.compiler.Version.scalapbVersion % "protobuf"

PB.targets in Compile := Seq(
  scalapb.gen() -> (sourceManaged in Compile).value
)
