/*
 * Copyright (c) 2019 The StreamX Project
 * <p>
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package com.streamxhub.streamx.repl.flink.interpreter

import com.mashape.unirest.http.{JsonNode, Unirest}
import com.streamxhub.streamx.common.util.{DateUtils, Logger}
import org.apache.commons.lang3.StringUtils
import org.apache.flink.api.common.JobID
import org.apache.flink.core.execution.JobClient
import org.apache.zeppelin.interpreter.InterpreterContext
import org.json.JSONObject

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import scala.collection.JavaConversions._
import scala.util.control.Breaks._

object JobManager extends Logger {
  val LATEST_CHECKPOINT_PATH = "latest_checkpoint_path"
  val SAVEPOINT_PATH = "savepoint_path"
  val RESUME_FROM_SAVEPOINT = "resumeFromSavepoint"
  val RESUME_FROM_CHECKPOINT = "resumeFromLatestCheckpoint"
  val SAVEPOINT_DIR = "savepointDir"

}

class JobManager(var flinkWebUrl: String, var replacedFlinkWebUrl: String)  extends Logger {
  private val jobs = new java.util.HashMap[String, JobClient]()
  private val jobProgressPollerMap = new ConcurrentHashMap[JobID, FlinkJobProgressPoller]


  def addJob(context: InterpreterContext, jobClient: JobClient): Unit = {
    val paragraphId = context.getParagraphId
    val previousJobClient = this.jobs.put(paragraphId, jobClient)
    val thread = new FlinkJobProgressPoller(flinkWebUrl, jobClient.getJobID, context)
    thread.setName("JobProgressPoller-Thread-" + paragraphId)
    thread.start()
    this.jobProgressPollerMap.put(jobClient.getJobID, thread)
    if (previousJobClient != null) {
      logWarn(s"There's another Job ${jobClient.getJobID} that is associated with paragraph ${paragraphId}")
    }
  }

  def removeJob(paragraphId: String): Unit = {
    logInfo("Remove job in paragraph: " + paragraphId)
    val jobClient = this.jobs.remove(paragraphId)
    if (jobClient == null) {
      logWarn("Unable to remove job, because no job is associated with paragraph: " + paragraphId)
      return
    }
    val jobProgressPoller = this.jobProgressPollerMap.remove(jobClient.getJobID)
    jobProgressPoller.cancel()
    jobProgressPoller.interrupt()
  }

  def sendFlinkJobUrl(context: InterpreterContext): Unit = {
    val jobClient = jobs.get(context.getParagraphId)
    if (jobClient == null) {
      logWarn("No job is associated with paragraph: " + context.getParagraphId)
    } else {
      val jobUrl: String = if (replacedFlinkWebUrl != null) {
        replacedFlinkWebUrl + "#/job/" + jobClient.getJobID
      } else {
        flinkWebUrl + "#/job/" + jobClient.getJobID
      }
      val infos = new java.util.HashMap[String, String]
      infos.put("jobUrl", jobUrl)
      infos.put("label", "FLINK JOB")
      infos.put("tooltip", "View in Flink web UI")
      infos.put("noteId", context.getNoteId)
      infos.put("paraId", context.getParagraphId)
      context.getIntpEventClient.onParaInfosReceived(infos)
    }
  }

  def getJobProgress(paragraphId: String): Int = {
    val jobClient = this.jobs.get(paragraphId)
    if (jobClient == null) {
      logWarn("Unable to get job progress for paragraph: " + paragraphId + ", because no job is associated with this paragraph")
      return 0
    }
    val jobProgressPoller = this.jobProgressPollerMap.get(jobClient.getJobID)
    if (jobProgressPoller == null) {
      logWarn("Unable to get job progress for paragraph: " + paragraphId + ", because no job progress is associated with this jobId: " + jobClient.getJobID)
      return 0
    }
    jobProgressPoller.getProgress
  }

  @throws[RuntimeException] def cancelJob(context: InterpreterContext): Unit = {
    logInfo(s"Canceling job associated of paragraph: ${context.getParagraphId}")
    val jobClient = this.jobs.get(context.getParagraphId)
    if (jobClient == null) {
      logWarn(s"Unable to remove Job from paragraph {} as no job associated to this paragraph ${context.getParagraphId}")
      return
    }
    var cancelled = false
    try {
      val savePointDir = context.getLocalProperties.get(JobManager.SAVEPOINT_DIR)
      if (StringUtils.isBlank(savePointDir)) {
        logInfo(s"Trying to cancel job of paragraph ${context.getParagraphId}")
        jobClient.cancel
      }
      else {
        logInfo(s"Trying to stop job of paragraph ${context.getParagraphId} with save point dir: ${savePointDir}")
        val savePointPath = jobClient.stopWithSavepoint(true, savePointDir).get
        val config = new java.util.HashMap[String, String]
        config.put(JobManager.SAVEPOINT_PATH, savePointPath)
        //        context.getIntpEventClient.updateParagraphConfig(context.getNoteId, context.getParagraphId, config)
        logInfo(s"Job ${jobClient.getJobID} of paragraph ${context.getParagraphId} is stopped with save point path: ${savePointPath}")
      }
      cancelled = true
    } catch {
      case e: Exception =>
        val errorMessage = String.format("Fail to cancel job %s that is associated " + "with paragraph %s", jobClient.getJobID, context.getParagraphId)
        logWarn(errorMessage, e)
        throw new RuntimeException(errorMessage, e)
    } finally if (cancelled) {
      logInfo("Cancelling is successful, remove the associated FlinkJobProgressPoller of paragraph: " + context.getParagraphId)
      val jobProgressPoller = jobProgressPollerMap.remove(jobClient.getJobID)
      if (jobProgressPoller != null) {
        jobProgressPoller.cancel()
        jobProgressPoller.interrupt()
      }
      this.jobs.remove(context.getParagraphId)
    }
  }

  def shutdown(): Unit = {
    for (jobProgressPoller <- jobProgressPollerMap.values) {
      jobProgressPoller.cancel()
    }
  }


}


class FlinkJobProgressPoller(var flinkWebUrl: String, var jobId: JobID, var context: InterpreterContext) extends Thread with Logger {

  this.isStreamingInsertInto = context.getLocalProperties.containsKey("flink.streaming.insert_into")
  private var isStreamingInsertInto = false
  private var progress = 0
  private val running = new AtomicBoolean(true)
  private var isFirstPoll = true

  override def run(): Unit = {
    breakable {
      while (!Thread.currentThread.isInterrupted && running.get) {
        var rootNode: JsonNode = null
        try {
          running synchronized running.wait(1000)
          rootNode = Unirest.get(flinkWebUrl + "/jobs/" + jobId.toString).asJson.getBody
          val vertices = rootNode.getObject.getJSONArray("vertices")
          var totalTasks = 0
          var finishedTasks = 0
          for (i <- 0 until vertices.length) {
            val vertex = vertices.getJSONObject(i)
            totalTasks += vertex.getInt("parallelism")
            finishedTasks += vertex.getJSONObject("tasks").getInt("FINISHED")
          }
          logDebug(s"Total tasks: $totalTasks")
          logDebug(s"Finished tasks: $finishedTasks")
          if (finishedTasks != 0) {
            this.progress = finishedTasks * 100 / totalTasks
            logDebug(s"Progress: $this.progress" )
          }
          val jobState = rootNode.getObject.getString("state")
          if (jobState.equalsIgnoreCase("finished")) {
            break
          }
          val duration = rootNode.getObject.getLong("duration") / 1000
          if (isStreamingInsertInto) {
            if (isFirstPoll) {
              val builder = new StringBuilder("%angular ")
              builder.append("<h1>Duration: {{duration}} </h1>")
              builder.append("\n%text ")
              context.out.clear(false)
              context.out.write(builder.toString)
              context.out.flush()
              isFirstPoll = false
            }
            context.getAngularObjectRegistry.add("duration", DateUtils.toRichTimeDuration(duration), context.getNoteId, context.getParagraphId)
          }
          // fetch checkpoints info and save the latest checkpoint into paragraph's config.
          rootNode = Unirest.get(flinkWebUrl + "/jobs/" + jobId.toString + "/checkpoints").asJson.getBody
          if (rootNode.getObject.has("latest")) {
            val latestObject = rootNode.getObject.getJSONObject("latest")
            if (latestObject.has("completed") && latestObject.get("completed").isInstanceOf[JSONObject]) {
              val completedObject = latestObject.getJSONObject("completed")
              if (completedObject.has("external_path")) {
                val checkpointPath = completedObject.getString("external_path")
                logDebug(s"Latest checkpoint path: ${checkpointPath}")
                if (!StringUtils.isBlank(checkpointPath)) {
                  val config = new java.util.HashMap[String, String]
                  config.put(JobManager.LATEST_CHECKPOINT_PATH, checkpointPath)
                  //                  context.getIntpEventClient.updateParagraphConfig(context.getNoteId, context.getParagraphId, config)
                }
              }
            }
          }
        } catch {
          case e: Exception =>
            error(s"Fail to poll flink job progress via rest api:$e")
        }
      }
    }
  }

  def cancel(): Unit = {
    this.running.set(false)
    running synchronized running.notify()

  }

  def getProgress: Int = progress
}
