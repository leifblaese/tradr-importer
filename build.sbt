
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



// Docker commands
//packageName in Docker := packageName.value
//version in Docker := version.value

//dockerBaseImage := "java"
//dockerRepository := Some("lbl")
//dockerExposedPorts := Seq(9000, 9443)
//dockerExposedVolumes := Seq("/opt/tradr-importer/logs")
//defaultLinuxInstallLocation in Docker := "/opt/tradr-importer"

//dockerCommands := Seq(
//  Cmd("FROM", "java"),
//  Cmd("WORKDIR", defaultLinuxInstallLocation.value),
//  Cmd("ADD", Array("/home/leifblaese/Dropbox/Privat/Tradr/production.conf", "/opt/production.conf"),
//  Cmd("RUN","List(chown, -R, daemon:daemon, .)"),
//  Cmd("EXPOSE",WrappedArray(9000 9443)),
//  Cmd(RUN,List(mkdir, -p, /opt/tradr-importer/logs)),
//  Cmd(RUN,List(chown, -R, daemon:daemon, /opt/tradr-importer/logs)),
//  Cmd(VOLUME,List(/opt/tradr-importer/logs))
//)

// Adds additional packages into Twirl
//TwirlKeys.templateImports += "tradr.controllers._"

// Adds additional packages into conf/routes
// play.sbt.routes.RoutesKeys.routesImport += "tradr.binders._"
