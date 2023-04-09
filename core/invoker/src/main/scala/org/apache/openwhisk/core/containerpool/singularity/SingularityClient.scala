/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.openwhisk.core.containerpool.singularity

import java.io.FileNotFoundException
import java.nio.file.Files
import java.nio.file.Paths
import java.util.concurrent.Semaphore

import java.io.File
import java.io.PrintWriter

import akka.actor.ActorSystem

import scala.collection.concurrent.TrieMap
import scala.concurrent.blocking
import scala.concurrent.ExecutionContext
import scala.concurrent.{Await, Future}
import scala.util.Failure
import scala.util.Success
import pureconfig.generic.auto._
import scala.util.Try
import akka.event.Logging.{ErrorLevel, InfoLevel}
import pureconfig.loadConfigOrThrow
import org.apache.openwhisk.common.{Logging, LoggingMarkers, MetricEmitter, TransactionId}
import org.apache.openwhisk.core.ConfigKeys
import org.apache.openwhisk.core.containerpool.ContainerId
import org.apache.openwhisk.core.containerpool.ContainerAddress
import spray.json._

import scala.concurrent.duration.Duration

object SingularityContainerId {

  val containerIdRegex = """^([0-9a-f]{64})$""".r

  def parse(id: String): Try[ContainerId] = {
    id match {
      case containerIdRegex(_) => 
        Success(ContainerId(id))
      case _                   => 
        Failure(new IllegalArgumentException(s"Does not comply with Singularity container ID format: ${id}"))
    }
  }
}

/**
 * Configuration for singularity client command timeouts.
 */
case class SingularityClientTimeoutConfig(run: Duration,
                                     rm: Duration,
                                     pull: Duration,
                                     ps: Duration,
                                     pause: Duration,
                                     unpause: Duration,
                                     version: Duration,
                                     inspect: Duration)

/**
 * Configuration for singularity client
 */
case class SingularityClientConfig(parallelRuns: Int, timeouts: SingularityClientTimeoutConfig)

/**
 * Image path for singularity client
 */
case class SingularityImagePathConfig(imagePath: String)

case class InstanceData(instance: String, pid: Int, img: String, logErrPath: String, logOutPath: String)

protected object InstanceData extends DefaultJsonProtocol {
  implicit val serdes = jsonFormat5(InstanceData.apply)
}

case class InstanceDataList(instances: List[InstanceData])

protected object InstanceDataList extends DefaultJsonProtocol {
  implicit val serdes = jsonFormat1(InstanceDataList.apply)
}

/**
 * Serves as interface to the singularity CLI tool.
 *
 * Be cautious with the ExecutionContext passed to this, as the
 * calls to the CLI are blocking.
 *
 * You only need one instance (and you shouldn't get more).
 */
class SingularityClient(singularityHost: Option[String] = None,
                   config: SingularityClientConfig = loadConfigOrThrow[SingularityClientConfig](ConfigKeys.singularityClient))(
  executionContext: ExecutionContext)(implicit log: Logging, as: ActorSystem)
    extends SingularityApi
    with ProcessRunner {
  implicit private val ec = executionContext

  val imagePathConfig: SingularityImagePathConfig =
    loadConfigOrThrow[SingularityImagePathConfig]("whisk.singularity.client")

  // Determines how to run singularity. Failure to find a Singularity binary implies
  // a failure to initialize this instance of SingularityClient.
  protected val singularityCmd: Seq[String] = {
    val alternatives = List("/opt/singularity/bin/singularity","/usr/local/bin/singularity","/usr/bin/singularity")

    val singularityBin = Try {
      alternatives.find(a => Files.isExecutable(Paths.get(a))).get
    } getOrElse {
      throw new FileNotFoundException(s"Couldn't locate singularity binary (tried: ${alternatives.mkString(", ")}).")
    }

    Seq(singularityBin)
  }

  // Invoke singularity CLI to determine client version.
  // If the singularity client version cannot be determined, an exception will be thrown and instance initialization will fail.
  // Rationale: if we cannot invoke `singularity version` successfully, it is unlikely subsequent `singularity` invocations will succeed.
  protected def getClientVersion(): String = {
    val vf = executeProcess(singularityCmd ++ Seq("--version"), config.timeouts.version)
      .andThen {
        case Success(version) => log.info(this, s"Detected singularity client version $version")
        case Failure(e) =>
          log.error(this, s"Failed to determine singularity client version: ${e.getClass} - ${e.getMessage}")
      }
    Await.result(vf, 2 * config.timeouts.version)
  }
  val clientVersion: String = getClientVersion()

  protected val maxParallelRuns = 1 //config.parallelRuns
  protected val runSemaphore =
    new Semaphore( /* permits= */ if (maxParallelRuns > 0) maxParallelRuns else Int.MaxValue, /* fair= */ true)

  var containerIdToPortMap = Map[String,Int]()
  var port = 9000
  val r = new scala.util.Random

  def run(image: String, args: Seq[String] = Seq.empty[String], name: Option[String] = None)(
    implicit transid: TransactionId): Future[ContainerId] = {
      blocking {
        // Acquires a permit from this semaphore, blocking until one is available, or the thread is interrupted.
        // Throws InterruptedException if the current thread is interrupted
        runSemaphore.acquire()
      }

      val id = name.getOrElse({
        val randomId = r.nextInt(1000000000)
        "c" ++ randomId.toString
      })

      while(containerIdToPortMap.values.exists(_ == port) && port < 9500) {
        port = port + 1
        if (port == 9500) port = 9000
      }

      containerIdToPortMap += id -> port

      val portConfigFilePath = s"/tmp/" + id.toString + s"_port.conf"

      val writer = new PrintWriter(new File(portConfigFilePath))
      writer.write(port.toString)
      writer.close()

      val bindString = portConfigFilePath.toString + s":/port.conf"
      
      val directory = runCmdWithoutSingularity("mktemp", "-d").andThen {
        case directory => directory
      }
      val bindString2 = directory.concat(":/action")

      var imagePath = (imagePathConfig.imagePath ++ image ++ ".simg")
      if (image == "python3aiaction") {
        runCmd(Seq("instance", "start", "--nv", "--bind", bindString, "--bind", bindString2, imagePath, id.toString), config.timeouts.run)
        .andThen {
          case _ =>
            runSemaphore.release()
        }
      }
      else {
        runCmd(Seq("instance", "start", "-c", "--bind", bindString, "--bind", bindString2, imagePath, id.toString), config.timeouts.run)
        .andThen {
          case _ => 
            runSemaphore.release()
        }
      }

      Future.successful(ContainerId(id.toString))
  }

  def inspectIPAddress(id: ContainerId)(implicit transid: TransactionId): Future[ContainerAddress] =
    Future.successful(ContainerAddress("localhost", containerIdToPortMap(id.asString))) 

  def pause(id: ContainerId)(implicit transid: TransactionId): Future[Unit] =
    Future.successful(())
  //  runCmd(Seq("pause", id.asString), config.timeouts.pause).map(_ => ())

  def unpause(id: ContainerId)(implicit transid: TransactionId): Future[Unit] =
    Future.successful(())
  //  runCmd(Seq("unpause", id.asString), config.timeouts.unpause).map(_ => ())

  def rm(id: ContainerId)(implicit transid: TransactionId): Future[Unit] = {
    containerIdToPortMap -= id.asString
    runCmd(Seq("instance", "stop", "--force", id.asString), config.timeouts.rm).map(_ => ())
  }

  def ps()(
    implicit transid: TransactionId): Future[Seq[ContainerId]] = {
    val cmd = Seq("instance", "list", "-j")
    runCmd(cmd, config.timeouts.ps)
      .map(_.parseJson.convertTo[InstanceDataList])
      .map(_.instances.map(i => ContainerId(i.instance)))
  }

  /**
   * Stores pulls that are currently being executed and collapses multiple
   * pulls into just one. After a pull is finished, the cached future is removed
   * to enable constant updates of an image without changing its tag.
   */
  private val pullsInFlight = TrieMap[String, Future[Unit]]()
  def pull(image: String)(implicit transid: TransactionId): Future[Unit] =
    pullsInFlight.getOrElseUpdate(image, {
      runCmd(Seq("pull", "--name", "docker://" ++ image), config.timeouts.pull).map(
        _ => ()).andThen { case _ => pullsInFlight.remove(image) }
    })

  def isOomKilled(id: ContainerId)(implicit transid: TransactionId): Future[Boolean] =
    Future.successful(true)
//    runCmd(Seq("inspect", id.asString, "--format", "{{.State.OOMKilled}}"), config.timeouts.inspect).map(_.toBoolean)

  protected def runCmd(args: Seq[String], timeout: Duration)(implicit transid: TransactionId): Future[String] = {
    val cmd = singularityCmd ++ args
    val start = transid.started(
      this,
      LoggingMarkers.INVOKER_SINGULARITY_CMD(args.head),
      s"running ${cmd.mkString(" ")} (timeout: $timeout)",
      logLevel = InfoLevel)
    executeProcess(cmd, timeout).andThen {
      case Success(_) =>
        transid.finished(this, start)
      case Failure(pte: ProcessTimeoutException) =>
        transid.failed(this, start, pte.getMessage, ErrorLevel)
        MetricEmitter.emitCounterMetric(LoggingMarkers.INVOKER_SINGULARITY_CMD_TIMEOUT(args.head))
      case Failure(t) =>
        transid.failed(this, start, t.getMessage, ErrorLevel)
    }
  }

  protected def runCmdWithoutSingularity(cmd: Seq[String], timeout: Duration)(implicit transid: TransactionId): Future[String] = {
    val start = transid.started(
      this,
      LoggingMarkers.INVOKER_SINGULARITY_CMD(args.head),
      s"running ${cmd.mkString(" ")} (timeout: $timeout)",
      logLevel = InfoLevel)
    executeProcess(cmd, timeout).andThen {
      case Success(_) =>
        transid.finished(this, start)
      case Failure(pte: ProcessTimeoutException) =>
        transid.failed(this, start, pte.getMessage, ErrorLevel)
        MetricEmitter.emitCounterMetric(LoggingMarkers.INVOKER_SINGULARITY_CMD_TIMEOUT(args.head))
      case Failure(t) =>
        transid.failed(this, start, t.getMessage, ErrorLevel)
    }
  }

}

trait SingularityApi {

  /**
   * The version number of the singularity client cli
   *
   * @return The version of the singularity client cli being used by the invoker
   */
  def clientVersion: String

  /**
   * Spawns a container in detached mode.
   *
   * @param image the image to start the container with
   * @param args arguments for the singularity run command
   * @param name desired container name
   * @return id of the started container
   */
  def run(image: String, args: Seq[String] = Seq.empty[String], name: Option[String] = None)(implicit transid: TransactionId): Future[ContainerId]

  /**
   * Gets the IP address of a given container.
   *
   * A container may have more than one network. The container has an
   * IP address in each of these networks such that the network name
   * is needed.
   *
   * @param id the id of the container to get the IP address from
   * @param network name of the network to get the IP address from
   * @return ip of the container
   */
  def inspectIPAddress(id: ContainerId)(implicit transid: TransactionId): Future[ContainerAddress]

  /**
   * Pauses the container with the given id.
   *
   * @param id the id of the container to pause
   * @return a Future completing according to the command's exit-code
   */
  def pause(id: ContainerId)(implicit transid: TransactionId): Future[Unit]

  /**
   * Unpauses the container with the given id.
   *
   * @param id the id of the container to unpause
   * @return a Future completing according to the command's exit-code
   */
  def unpause(id: ContainerId)(implicit transid: TransactionId): Future[Unit]

  /**
   * Removes the container with the given id.
   *
   * @param id the id of the container to remove
   * @return a Future completing according to the command's exit-code
   */
  def rm(id: ContainerId)(implicit transid: TransactionId): Future[Unit]

  /**
   * Returns a list of ContainerIds in the system.
   *
   * @return A list of ContainerIds
   */
  def ps()(
    implicit transid: TransactionId): Future[Seq[ContainerId]]

  /**
   * Pulls the given image.
   *
   * @param image the image to pull
   * @return a Future completing once the pull is complete
   */
  def pull(image: String)(implicit transid: TransactionId): Future[Unit]

  /**
   * Determines whether the given container was killed due to
   * memory constraints.
   *
   * @param id the id of the container to check
   * @return a Future containing whether the container was killed or not
   */
  def isOomKilled(id: ContainerId)(implicit transid: TransactionId): Future[Boolean]
}

/** Indicates any error while starting a container that leaves a broken container behind that needs to be removed */
case class BrokenSingularityContainer(id: ContainerId, msg: String) extends Exception(msg)
