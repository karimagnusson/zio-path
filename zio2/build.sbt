
inThisBuild(List(
  organization := "io.github.karimagnusson",
  homepage := Some(url("https://kuzminki.info/")),
  licenses := List("Apache-2.0" -> url("http://www.apache.org/licenses/LICENSE-2.0")),
  developers := List(
    Developer(
      "karimagnusson",
      "Kari Magnusson",
      "kotturinn@gmail.com",
      url("https://github.com/karimagnusson")
    )
  )
))

ThisBuild / version := "2.0.2-RC3"
ThisBuild / versionScheme := Some("early-semver")

scalaVersion := "3.3.1"

lazy val scala3 = "3.3.1"
lazy val scala213 = "2.13.12"
lazy val scala212 = "2.12.18"
lazy val supportedScalaVersions = List(scala212, scala213, scala3)

lazy val root = (project in file("."))
  .aggregate(zioPath)
  .settings(
    crossScalaVersions := Nil,
    publish / skip := true
  )

lazy val zioPath = (project in file("zio-path"))
  .settings(
    name := "zio-path",
    crossScalaVersions := supportedScalaVersions,
    libraryDependencies ++= Seq(
      "dev.zio" %% "zio" % "2.0.21",
      "dev.zio" %% "zio-streams" % "2.0.21",
      "org.apache.commons" % "commons-compress" % "1.26.1",
      "org.scala-lang.modules" %% "scala-collection-compat" % "2.11.0"
    ),
    scalacOptions ++= Seq(
      "-encoding", "utf8",
      "-feature",
      "-language:higherKinds",
      "-language:existentials",
      "-language:implicitConversions",
      "-deprecation",
      "-unchecked"
    ),
    scalacOptions ++= {
      CrossVersion.partialVersion(scalaVersion.value) match {
        case Some((3, _))  => Seq("-rewrite")
        case _             => Seq("-Xlint")
      }
    }
  )

