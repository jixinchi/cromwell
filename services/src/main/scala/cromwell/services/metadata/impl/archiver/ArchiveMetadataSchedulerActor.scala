package cromwell.services.metadata.impl.archiver

import akka.actor.{Actor, ActorLogging, ActorRef, Props}
import akka.pattern.ask
import akka.util.Timeout
import com.google.common.io.BaseEncoding
import com.google.common.primitives.Longs
import common.util.StringUtil.EnhancedToStringable
import common.util.TimeUtil.EnhancedOffsetDateTime
import cromwell.core.io.{AsyncIo, DefaultIoCommandBuilder}
import cromwell.core.path.{Path, PathFactory}
import cromwell.core.{WorkflowAborted, WorkflowFailed, WorkflowId, WorkflowSucceeded}
import cromwell.database.sql.SqlConverters.{ClobOptionToRawString, TimestampToSystemOffsetDateTime}
import cromwell.database.sql.tables.MetadataEntry
import cromwell.services.metadata.MetadataArchiveStatus.{Archived, Unarchived}
import cromwell.services.metadata.MetadataService.{GetMetadataStreamAction, MetadataLookupStreamFailed, MetadataLookupStreamSuccess, QueryForWorkflowsMatchingParameters, WorkflowQueryFailure, WorkflowQueryResult, WorkflowQuerySuccess}
import cromwell.services.metadata.WorkflowQueryKey._
import cromwell.services.metadata.impl.MetadataDatabaseAccess
import cromwell.services.metadata.impl.archiver.ArchiveMetadataSchedulerActor._
import cromwell.services.{IoActorRequester, MetadataServicesStore}
import cromwell.util.GracefulShutdownHelper
import cromwell.util.GracefulShutdownHelper.ShutdownCommand
import org.apache.commons.codec.digest.PureJavaCrc32C
import org.apache.commons.csv.{CSVFormat, CSVPrinter}
import slick.basic.DatabasePublisher

import java.io.{OutputStream, OutputStreamWriter}
import java.nio.file.{Files, StandardOpenOption}
import java.time.OffsetDateTime
import java.util.UUID

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success}


class ArchiveMetadataSchedulerActor(archiveMetadataConfig: ArchiveMetadataConfig,
                                    override val serviceRegistryActor: ActorRef)
  extends Actor
    with ActorLogging
    with GracefulShutdownHelper
    with MetadataDatabaseAccess
    with MetadataServicesStore
    with IoActorRequester {

  implicit val ec: ExecutionContext = context.dispatcher
  implicit val askTimeout: Timeout = new Timeout(60.seconds)
  lazy val futureAsyncIo: Future[AsyncIo] = requestIoActor() map { ioActor => {
    log.info(s"IoActor reference received by ${self.path.name}")
    new AsyncIo(ioActor, DefaultIoCommandBuilder)
  } }

  // kick off archiving immediately
  self ! ArchiveNextWorkflowMessage

  override def receive: Receive = {
    case ArchiveNextWorkflowMessage => archiveNextWorkflow().onComplete({
      case Success(true) => self ! ArchiveNextWorkflowMessage
      case Success(false) => scheduleNextWorkflowToArchive()
      case Failure(error) =>
        log.error(error, s"Error while archiving, will retry.")
        scheduleNextWorkflowToArchive()
    })
    case ShutdownCommand => context.stop(self)  // TODO: cancel any streaming that might be happening?
    case other => log.info(s"Programmer Error! The ArchiveMetadataSchedulerActor received unexpected message! ($sender sent ${other.toPrettyElidedString(1000)}})")
  }

  def archiveNextWorkflow(): Future[Boolean] = {
    for {
      maybeWorkflowQueryResult <- lookupNextWorkflowToArchive()
      result <- maybeWorkflowQueryResult match {
        case Some(workflow) => for {
          path <- getGcsPathForMetadata(workflow)
          dbStream <- fetchStreamFromDatabase(WorkflowId(UUID.fromString(workflow.id)))
          _ = log.info(s"Archiving metadata for ${workflow.id} to ${path.pathAsString}")
          _ <- streamMetadataToGcs(path, dbStream)
          _ <- updateMetadataArchiveStatus(WorkflowId(UUID.fromString(workflow.id)), Archived)
          _ = log.info(s"Archiving succeeded for ${workflow.id}")
        } yield true
        case None => Future.successful(false)
      }
    } yield result
  }

  def lookupNextWorkflowToArchive(): Future[Option[WorkflowQueryResult]] = {
    (serviceRegistryActor ? QueryForWorkflowsMatchingParameters(queryParametersForWorkflowsToArchive(OffsetDateTime.now(), archiveMetadataConfig.archiveDelay))) flatMap {
      case WorkflowQuerySuccess(response, _) =>
        if (response.results.nonEmpty)
          Future.successful(Option(response.results.head))
        else
          Future.successful(None)
      case WorkflowQueryFailure(reason) => Future.failed(new Exception("Failed to fetch new workflow to archive", reason))
      case other => Future.failed(new Exception(s"Programmer Error: Got unexpected message fetching new workflows to archive: ${other.toPrettyElidedString(1000)}"))
    }
  }

  private def getGcsPathForMetadata(workflow: WorkflowQueryResult): Future[Path] =  {
    val bucket = archiveMetadataConfig.bucket
    val workflowId = workflow.id
    val rootWorkflowId = workflow.rootWorkflowId.getOrElse(workflowId)
    Future(PathFactory.buildPath(s"gs://$bucket/$rootWorkflowId/$workflowId.csv", archiveMetadataConfig.pathBuilders))
  }

  def fetchStreamFromDatabase(workflowId: WorkflowId): Future[DatabasePublisher[MetadataEntry]] = {
    (serviceRegistryActor ? GetMetadataStreamAction(workflowId, archiveMetadataConfig.databaseStreamFetchSize)) flatMap {
      case MetadataLookupStreamSuccess(_, responseStream) => Future.successful(responseStream)
      case MetadataLookupStreamFailed(_, reason) => Future.failed(new Exception(s"Failed to get metadata stream", reason))
      case other => Future.failed(new Exception(s"Failed to get metadata stream: ${other.toPrettyElidedString(1000)}"))
    }
  }

  def streamMetadataToGcs(path: Path, stream: DatabasePublisher[MetadataEntry]): Future[Unit] = {
    for {
      asyncIo <- futureAsyncIo
      gcsStream = Files.newOutputStream(path.nioPath, StandardOpenOption.CREATE)
      crc32cStream = new Crc32cStream()
      teeStream = new TeeingOutputStream(gcsStream, crc32cStream)
      csvPrinter = new CSVPrinter(new OutputStreamWriter(teeStream), CSVFormat.DEFAULT.withHeader(CsvFileHeaders : _*))
      _ <- stream.foreach(me => {
        csvPrinter.printRecord(
          me.metadataEntryId.map(_.toString).getOrElse(""),
          me.workflowExecutionUuid,
          me.metadataKey,
          me.callFullyQualifiedName.getOrElse(""),
          me.jobIndex.map(_.toString).getOrElse(""),
          me.jobAttempt.map(_.toString).getOrElse(""),
          me.metadataValue.toRawString,
          me.metadataTimestamp.toSystemOffsetDateTime.toUtcMilliString,
          me.metadataValueType.getOrElse("")
        )
      })
      _ = csvPrinter.close()
      expectedChecksum = crc32cStream.checksumString
      uploadedChecksum <- asyncIo.hashAsync(path)
      _ <- if (uploadedChecksum == expectedChecksum) Future.successful(()) else Future.failed(new Exception(s"Uploaded checksum '$uploadedChecksum' did not match local calculation ('$expectedChecksum')"))
    } yield ()
  }

  def scheduleNextWorkflowToArchive(): Unit = {
    context.system.scheduler.scheduleOnce(archiveMetadataConfig.backoffInterval)(self ! ArchiveNextWorkflowMessage)
    ()
  }
}

object ArchiveMetadataSchedulerActor {
  case object ArchiveNextWorkflowMessage

  val CsvFileHeaders = List(
    "METADATA_JOURNAL_ID",
    "WORKFLOW_EXECUTION_UUID",
    "METADATA_KEY",
    "CALL_FQN",
    "JOB_SCATTER_INDEX",
    "JOB_RETRY_ATTEMPT",
    "METADATA_VALUE",
    "METADATA_TIMESTAMP",
    "METADATA_VALUE_TYPE"
  )

  def queryParametersForWorkflowsToArchive(currentTime: OffsetDateTime, archiveDelay: FiniteDuration): Seq[(String, String)] = Seq(
    IncludeSubworkflows.name -> "true",
    Status.name -> WorkflowSucceeded.toString,
    Status.name -> WorkflowFailed.toString,
    Status.name -> WorkflowAborted.toString,
    MetadataArchiveStatus.name -> Unarchived.toString,
    Page.name -> "1",
    PageSize.name -> "1",
    NewestFirst.name -> "false", // oldest first for archiving
    EndDate.name -> currentTime.minusNanos(archiveDelay.toNanos).toUtcMilliString
  )

  def props(archiveMetadataConfig: ArchiveMetadataConfig, serviceRegistryActor: ActorRef): Props =
    Props(new ArchiveMetadataSchedulerActor(archiveMetadataConfig, serviceRegistryActor))

  final class TeeingOutputStream(streams: OutputStream*) extends OutputStream {
    override def write(b: Int): Unit = { streams.foreach(_.write(b)) }
    override def close(): Unit = { streams.foreach(_.close())}
    override def flush(): Unit = { streams.foreach(_.flush())}
  }

  final class Crc32cStream() extends OutputStream {
    private val checksumCalculator = new PureJavaCrc32C()
    override def write(b: Int): Unit = checksumCalculator.update(b)

    def checksumString: String = {
      val finalChecksumValue = checksumCalculator.getValue
      // Google checksums are actually only the lower four bytes of the crc32c checksum:
      val bArray = java.util.Arrays.copyOfRange(Longs.toByteArray(finalChecksumValue), 4, 8)
      BaseEncoding.base64.encode(bArray)
    }
  }
}
