package com.homebay.sbt.webpack

import sbt._
import sbt.Keys._
import com.typesafe.sbt.web.SbtWeb
import com.typesafe.sbt.web.pipeline.Pipeline
import com.typesafe.sbt.jse.{SbtJsEngine, SbtJsTask}

import scala.concurrent.duration.FiniteDuration

object Import {

  val webpack = TaskKey[Seq[File]]("webpack", "Run the webpack module bundler.")
  val webpackStage = TaskKey[Pipeline.Stage]("webpack-stage", "Run the webpack module bundler as a pipeline stage.")

  object WebpackKeys {

    val webpackExecutable = SettingKey[File]("webpack-executable", "Webpack JS script")
    //val sourceDir = SettingKey[File]("webpack-source-dir", "The top level directory that contains your app js files. This is the source folder that webpack reads from.")
    //val buildDir = SettingKey[File]("webpack-build-dir", "Where webpack should will write to.")
  }
}

object SbtWebpack extends AutoPlugin {

  override def requires = SbtJsTask

  override def trigger = AllRequirements

  val autoImport = Import

  import SbtWeb.autoImport._
  import WebKeys._
  import SbtJsEngine.autoImport.JsEngineKeys._
  import SbtJsTask.autoImport.JsTaskKeys._
  import autoImport._
  import WebpackKeys._


  override def projectSettings = Seq(
    includeFilter in webpack := GlobFilter("*.js") || GlobFilter("*.jsx"),
    excludeFilter in webpack := HiddenFileFilter,

    (nodeModuleDirectories in webpack in Plugin) += baseDirectory.value / "node_modules",

    // TODO Currently can't use the webjar because there are a ton of transitive dependency issues. See
    // https://groups.google.com/forum/#!topic/play-framework/m2X8NQFk5bk for a discussion on the topic
    webpackExecutable := baseDirectory.value / "node_modules" / "webpack" / "bin" / "webpack.js",

    webpack := runWebpack(Assets).dependsOn(webJarsNodeModules in Assets).value,
    //webpack in TestAssets := runWebpack(TestAssets).dependsOn(webJarsNodeModules in Plugin).value,

    webpackStage := runWebpackStage.dependsOn(webJarsNodeModules in Assets).value,

    resourceGenerators in Assets <+= webpack in Assets,
    //resourceGenerators in TestAssets <+= webpack in TestAssets,

    resourceManaged in webpack := webTarget.value / webpack.key.label,
    //resourceManaged in webpack in TestAssets := webTarget.value / webpack.key.label / "test-js-built",

    resourceDirectories in Assets += (resourceManaged in webpack in Assets).value
    //resourceDirectories in TestAssets += (resourceManaged in webpack in TestAssets).value
  )

  /**
   * This caching logic was poached from
   * https://github.com/matthewrennie/sbt-autoprefixer/blob/master/src/main/scala/net/matthewrennie/sbt/autoprefixer/SbtAutoprefixer.scala
   * @param config
   * @return
   */
  private def runWebpack(config: Configuration): Def.Initialize[Task[Seq[File]]] = Def.task {
    val sourceDir = (sourceDirectory in webpack in config).value
    val outputDir = (resourceManaged in webpack).value

    val include = (includeFilter in webpack).value
    val exclude = (excludeFilter in webpack).value

    val mappings = (sourceDir ** (include -- exclude)).pair(f => Some(f.getPath))

    SbtWeb.syncMappings(
      streams.value.cacheDirectory,
      mappings,
      outputDir
    )

    val buildMappings = mappings.map(o => outputDir / o._2)

    // FIXME support all of the arguments mentioned here: http://webpack.github.io/docs/cli.html
    val args = Seq("--output-path", outputDir.getAbsolutePath)

    // Running webpack as a node module for now
    val nodeModulePaths: Seq[String] = (nodeModuleDirectories in webpack in Plugin).value.map(_.getPath)

    val cacheDirectory = streams.value.cacheDirectory / webpack.key.label

    val runWebpack = FileFunction.cached(cacheDirectory, FilesInfo.hash) { inputFiles =>
      webpackRunner(inputFiles,
                    outputDir,
                    state.value,
                    (engineType in webpack).value,
                    (command in webpack).value,
                    nodeModulePaths,
                    webpackExecutable.value,
                    (timeoutPerSource in webpack).value,
                    streams.value.log,
                    args)
    }

    val webpackedMappings = runWebpack(buildMappings.toSet).pair(relativeTo(outputDir))
    (mappings.toSet -- mappings ++ webpackedMappings).toSeq

    webpackedMappings.map(_._1)
  }


  private def runWebpackStage: Def.Initialize[Task[Pipeline.Stage]] = Def.task { mappings =>
    Nil
  }


  private def webpackRunner(inputFiles: Set[File],
                            outputDir: File,
                            state: State,
                            engineType: EngineType.Value,
                            command: Option[File],
                            nodeModulePaths: Seq[String],
                            webpackExecutable: File,
                            timeout: FiniteDuration,
                            logger: Logger,
                            args: Seq[String]) = {

    if (inputFiles.size > 0) {
      logger.info(s"Optimizing ${inputFiles.size} Javascript file(s) with Webpack")

      try {

        SbtJsTask.executeJs(
          state,
          engineType,
          command,
          nodeModulePaths,
          webpackExecutable,
          args,
          timeout * inputFiles.size
        )
      } catch {

        // FIXME get error handling to work
        case failure: SbtJsTask.JsTaskFailure =>
          failure.printStackTrace()
        //CompileProblems.report(reporter.value, problems)
      }
    }

    outputDir.***.get.filter(!_.isDirectory).toSet

  }
}
