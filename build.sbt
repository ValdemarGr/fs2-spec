lazy val root = project
  .in(file("."))
  .settings(
    scalaVersion := "2.13.14",
    libraryDependencies ++= Seq(
      "org.typelevel" %%% "cats-core" % "2.12.0",
      "org.typelevel" %%% "cats-effect" % "3.5.4",
      "org.scalameta" %%% "munit" % "1.0.2" % Test,
      "org.typelevel" %%% "munit-cats-effect" % "2.0.0" % Test
    )
  )
