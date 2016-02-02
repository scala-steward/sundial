package util

import java.io.{FileWriter, BufferedWriter, File}
import java.text.SimpleDateFormat
import java.util.UUID
import java.util.concurrent.TimeUnit
import model.TaskStatus

import scala.sys.process._

import dao.SundialDao

class Graphify {

  def toGv(uuid: UUID) = "_" + uuid.toString.replace("-", "_")

  def toHumanTime(millis: Long): String = {
    if(millis < 10000) {
      s"${millis}ms"
    } else if(millis < TimeUnit.MINUTES.toMillis(3)) {
      s"${TimeUnit.MILLISECONDS.toSeconds(millis)}sec"
    } else if(millis < TimeUnit.HOURS.toMillis(3)) {
      s"${TimeUnit.MILLISECONDS.toMinutes(millis)}min"
    } else {
      val hours = TimeUnit.MILLISECONDS.toHours(millis)
      val minutes = TimeUnit.MILLISECONDS.toMinutes(millis - TimeUnit.HOURS.toMillis(hours))
      s"${hours}hrs ${minutes.formatted("%02d")}mins"
    }
  }

  def safeName(name: String) = name.replace("-", "_")

  def toGraphVizFormat(processId: UUID)(implicit dao: SundialDao): String = {
    (for {
      process <- dao.processDao.loadProcess(processId)
      tasks = dao.processDao.loadTasksForProcess(processId)
      processDefinition <- dao.processDefinitionDao.loadProcessDefinition(process.processDefinitionName)
      taskDefinitions = dao.processDefinitionDao.loadTaskDefinitions(process.id)
    } yield {
      // build a graph out of the task definitions
      val sb = new StringBuilder()

      val font = "helvetica";

      val title = s"<b>${processDefinition.name}</b> started at ${new SimpleDateFormat("M/d/y hh:mm a").format(process.startedAt)}"

      sb.append(s"digraph ${safeName(process.processDefinitionName)} {\n")
      sb.append("  rankdir=BT;\n")
      sb.append(s"""  graph [fontname = "$font"];""").append("\n")
      sb.append(s"""  node [fontname = "$font"];""").append("\n")
      sb.append(s"""  edge [fontname = "$font"];""").append("\n")
      sb.append("  labelloc=\"t\";\n")
      //sb.append(s"""  label=<$title>;""").append("\n")
      sb.append("  node [fontsize = 10, shape = box, style = filled, color = black];\n")
      taskDefinitions.foreach { taskDef =>
        val theseTasks = tasks.filter(_.taskDefinitionName == taskDef.name).sortBy(_.startedAt).reverse
        val lastTask = theseTasks.headOption
        val failures = theseTasks.map(_.status).collect { case TaskStatus.Failure(_, _) => 1 }.sum
        val color = lastTask.map(_.status) match {
          case Some(TaskStatus.Running()) => "cyan3"
          case Some(TaskStatus.Success(_)) => "darkolivegreen1"
          case Some(TaskStatus.Failure(_, _)) => "salmon"
          case _ => "wheat"
        }
        val timeRange = (for {
          first <- theseTasks.reverse.headOption
          last <- theseTasks.headOption
        } yield {
          val fmt = new SimpleDateFormat("HH:mm:ss")
          val firstStr = fmt.format(first.startedAt)
          last.endedAt match {
            case Some(endedAt) =>
              val duration = endedAt.getTime() - first.startedAt.getTime()
              Seq(s"$firstStr - ${fmt.format(endedAt)}", s"${toHumanTime(duration)}")
            case _ =>
              Seq(s"$firstStr - now")
          }
        }).getOrElse(Seq.empty)
        val labelParts =
          Seq(s"<b>${taskDef.name}</b>") ++
          timeRange ++
          (if(failures > 0) Seq(s"$failures failures") else Seq.empty)
        val label = labelParts.mkString("<br/>")
        sb.append(s"""  ${safeName(taskDef.name)} [label=<$label>, fillcolor=$color];\n""")
      }
      taskDefinitions.foreach { taskDef =>
        taskDef.dependencies.required.foreach { dep =>
          sb.append(s"  ${safeName(taskDef.name)} -> ${safeName(dep)};\n")
        }
        taskDef.dependencies.optional.foreach { dep =>
          sb.append(s"  ${safeName(taskDef.name)} -> ${safeName(dep)} [style=dashed];\n")
        }
      }
      sb.append("}\n")

      sb.toString()
    }).getOrElse("")
  }

  def toGraphViz(processId: UUID)(implicit dao: SundialDao): File = {
    val gv = toGraphVizFormat(processId)
    val infile = File.createTempFile("sundial_gv", ".gv")
    val outfile = File.createTempFile("sundial_gv", ".png")
    val writer = new BufferedWriter(new FileWriter(infile))
    writer.write(gv)
    writer.close()

    (Seq("dot", "-Tpng", infile.getAbsolutePath) #> outfile).!

    infile.delete()
    outfile
  }

}
