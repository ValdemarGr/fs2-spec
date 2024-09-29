lazy val root = project
  .in(file("."))
  .settings(
    scalaVersion := "2.13.14",
    libraryDependencies ++= Seq(
      "org.typelevel" %% "cats-core" % "2.12.0",
      "org.typelevel" %% "cats-effect" % "3.5.4",
      "co.fs2" %% "fs2-core" % "3.11.0",
      "co.fs2" %% "fs2-io" % "3.11.0",
      "org.scalameta" %% "munit" % "1.0.2" % Test,
      "org.typelevel" %% "munit-cats-effect" % "2.0.0" % Test,
      compilerPlugin("org.typelevel" % "kind-projector" % "0.13.3" cross CrossVersion.full)
    )
  )
