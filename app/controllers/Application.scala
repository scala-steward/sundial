package controllers

import java.io.BufferedInputStream
import java.util.UUID
import javax.inject.{Inject, Named}

import com.amazonaws.services.s3.AmazonS3
import com.amazonaws.services.s3.model.AmazonS3Exception
import dao.SundialDaoFactory
import dto.DisplayModels
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.io.IOUtils
import play.api.http.FileMimeTypes
import play.api.{Configuration, Logger}
import play.api.mvc._
import util.Graphify

import scala.concurrent.ExecutionContext.Implicits.global

class Application @Inject() (config: Configuration,
                             graphify: Graphify,
                             daoFactory: SundialDaoFactory,
                             s3Client: AmazonS3,
                             displayModels: DisplayModels,
                             @Named("s3Bucket") s3Bucket:String,
                             fileMimeTypes: FileMimeTypes) extends Controller {

  private implicit val fmt = fileMimeTypes

  def index = Action { implicit request =>
    daoFactory.withSundialDao { implicit dao =>
      // TODO Allow count to be passed in as a parameter
      val processes = dao.processDao.findProcesses(limit = Some(20)).map(_.id)
      val processDtos = processes.flatMap(displayModels.fetchProcessDto(_, false))
      val triggers = dao.triggerDao.loadOpenProcessTriggerRequests()
      Ok(views.html.index(processDtos, triggers))
    }
  }

  def processDefinitions = Action { implicit request =>
    daoFactory.withSundialDao { implicit dao =>
      val processDefinitions = dao.processDefinitionDao.loadProcessDefinitions().sortBy(_.name)
      val dtos = processDefinitions.map(displayModels.toProcessDefinitionDTO)
      Ok(views.html.processDefinitions(dtos))
    }
  }

  def processDetail(processId: String) = Action { implicit request =>
    daoFactory.withSundialDao { implicit dao =>
      displayModels.fetchProcessDto(UUID.fromString(processId), false) match {
        case Some(processDto) =>
          val taskMetadata = processDto.tasks.flatMap(_.tasks).map { task =>
            val metadata = dao.taskMetadataDao.loadMetadataForTask(task.id)
            // we want the most recent per-key
            task.id -> metadata.groupBy(_.key).mapValues(_.sortBy(_.when.getTime).last.value)
          }.toMap
          Ok(views.html.processDetail(processDto, taskMetadata))
        case _ => NotFound
      }
    }
  }

  def processDefinition(processDefinitionName: String) = Action { implicit request =>
    daoFactory.withSundialDao { implicit dao =>
      dao.processDefinitionDao.loadProcessDefinition(processDefinitionName) match {
        case Some(processDefinition) =>
          // TODO Allow count to be passed in as a parameter
          val processes = dao.processDao.findProcesses(processDefinitionName = Some(processDefinitionName),
                                                       limit = Some(100))
            .flatMap(proc => displayModels.fetchProcessDto(proc.id, false))
          val taskDefs = dao.processDefinitionDao.loadTaskDefinitionTemplates(processDefinitionName)
          val triggers = dao.triggerDao.loadOpenProcessTriggerRequests().filter(_.processDefinitionName == processDefinitionName)
          Ok(views.html.processDefinition(processDefinition, taskDefs, processes, triggers))
        case _ =>
          NotFound
      }
    }
  }

  private def readEntireEntry(tarArchiveInputStream: TarArchiveInputStream): String = {
    val bytes = IOUtils.toByteArray(tarArchiveInputStream)
    new String(bytes)
  }

  private def readTruncatedEntry(tarArchiveInputStream: TarArchiveInputStream, totalSize: Int,  maxLogSizeBytes: Int): String = {
    val halfOfMaxSize = maxLogSizeBytes / 2
    val headBytes = new Array[Byte](halfOfMaxSize)
    tarArchiveInputStream.read(headBytes, 0 , halfOfMaxSize - 1)
    val tailBytes = new Array[Byte](halfOfMaxSize)
    tarArchiveInputStream.skip(totalSize - maxLogSizeBytes)
    tarArchiveInputStream.read(tailBytes, 0, halfOfMaxSize)
    new String(headBytes) + "...\n\n(TRUNCATED DUE TO LENGTH)\n\n" + new String(tailBytes)
  }

  def taskLogs(taskIdStr: String) = Action { implicit request =>
    val taskOpt = daoFactory.withSundialDao { implicit dao =>
      dao.processDao.loadTask(UUID.fromString(taskIdStr))
    }

    taskOpt match {
      case Some(task) =>
        val maxLogSizeBytes = 1 * 1024 * 1024 // 1MB
        val taskId = UUID.fromString(taskIdStr)

        try {
          val s3Object = s3Client.getObject(s3Bucket, s"logs/$taskId")
          val tar = new TarArchiveInputStream(new BufferedInputStream(s3Object.getObjectContent))

          try {
            val logs = collection.mutable.MutableList[(String, String)]()
            var entry = tar.getNextTarEntry
            while (entry != null) {
              val name = entry.getName.replaceAll("^\\./", "")
              val data = if (entry.getSize < maxLogSizeBytes) {
                readEntireEntry(tar)
              } else {
                readTruncatedEntry(tar, entry.getSize.toInt, maxLogSizeBytes)
              }
              logs += name -> data
              entry = tar.getNextTarEntry
            }

            Ok(views.html.taskLogs(task, logs))
          } finally {
            tar.close()
          }

        } catch {
          case e: AmazonS3Exception => {
            Logger.error("Error retrieving logs from S3", e)
            NotFound(e.getMessage)
          }
        }
      case _ =>
        NotFound
    }
  }


  def processes = Action { implicit request =>
    daoFactory.withSundialDao { dao =>
      Ok(views.html.processes(dao))
    }
  }

  def processGraph(processId: String) = Action { implicit request =>
    val file = daoFactory.withSundialDao { implicit dao =>
      graphify.toGraphViz(UUID.fromString(processId))
    }
    Ok.sendFile(content = file, inline = true, onClose = () => file.delete())
  }

}