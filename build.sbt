
lazy val libdeps = Seq(
  guice,
  "org.scalatestplus.play" %% "scalatestplus-play" % "3.1.0" % Test,
  specs2 % Test ,
  "com.msilb" % "scalanda-v20_2.12" % "0.1.2",
  "com.typesafe" % "config" % "1.3.1",
  "com.typesafe.akka" %% "akka-stream-kafka" % "0.16"
)


lazy val root = (project in file("."))
  .settings(
      Seq(
        name := "tradr-importer",
        organization := "tradr",
        version := "1.0.0",
        scalaVersion := "2.12.2",
        libraryDependencies ++= libdeps

      )
    )
  .enablePlugins(PlayScala)
  .enablePlugins(sbtdocker.DockerPlugin, JavaAppPackaging)



val productionConfFileSource = new File("/home/leifblaese/Dropbox/Privat/Tradr/production.conf")
dockerfile in docker := {
  val appDir: File = stage.value
  val targetDir = "/opt/tradr-importer"
  new Dockerfile {
    from("java")
    copy(appDir, targetDir)
    expose(9000)
    copy(new File("/home/leifblaese/logs"), targetDir)
    copy(productionConfFileSource, targetDir)
    entryPoint(s"$targetDir/bin/${executableScriptName.value}", s"-Dconfig.file=$targetDir/production.conf")
  }
}

