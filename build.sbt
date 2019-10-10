name := "Lunt"

version := "1.0"

scalaVersion := "2.12.6"

lazy val akkaVersion = "2.5.25"

libraryDependencies ++= Seq(
  "com.typesafe.akka" %% "akka-actor" % akkaVersion,
//  "com.typesafe.akka" %% "akka-testkit" % akkaVersion % Test,
  "org.scalatest" %% "scalatest" % "3.0.5" % Test,
  "com.typesafe.akka" %% "akka-remote" % akkaVersion
)

lazy val scalacticVersion = "3.0.8"

libraryDependencies ++= Seq(
  "org.scalactic" %% "scalactic" % scalacticVersion
)


//libraryDependencies +=
//  "com.typesafe.akka" %% "akka-actor" % "2.5.3"
//
//libraryDependencies +=
//  "org.scalactic" %% "scalactic" % "3.0.1"
//
//libraryDependencies +=
//  "org.scalatest" %% "scalatest" % "3.0.1" % "test"
//
//libraryDependencies +=
//  "com.typesafe.akka" %% "akka-testkit" % "2.5.3" % "test"
//
//libraryDependencies +=
//  "com.typesafe.akka" %% "akka-remote" % "2.5.3"
//

PB.targets in Compile := Seq(
  scalapb.gen() -> (sourceManaged in Compile).value
)

libraryDependencies += "com.thesamet.scalapb" %% "scalapb-runtime" % scalapb.compiler.Version.scalapbVersion % "protobuf"
