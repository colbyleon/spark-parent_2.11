/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.spark.scheduler

import java.lang.management.ManagementFactory
import java.nio.ByteBuffer
import java.util.Properties

import scala.language.existentials

import org.apache.spark._
import org.apache.spark.broadcast.Broadcast
import org.apache.spark.executor.TaskMetrics
import org.apache.spark.internal.Logging
import org.apache.spark.rdd.RDD
import org.apache.spark.shuffle.ShuffleWriter

/**
  * ShuffleMapTask将RDD的元素划分为多个buckets(基于ShuffleDependency中指定的partitioner)。
  * 个人理解： buckets对应每一个输出的分区
  *
 * A ShuffleMapTask divides the elements of an RDD into multiple buckets (based on a partitioner
 * specified in the ShuffleDependency).
 *
 * See [[org.apache.spark.scheduler.Task]] for more information.
 *
 * @param stageId id of the stage this task belongs to
 * @param stageAttemptId attempt id of the stage this task belongs to
 * @param taskBinary broadcast version of the RDD and the ShuffleDependency. Once deserialized,
 *                   the type should be (RDD[_], ShuffleDependency[_, _, _]).
 * @param partition partition of the RDD this task is associated with
 * @param locs preferred task execution locations for locality scheduling
 * @param metrics a `TaskMetrics` that is created at driver side and sent to executor side.
 * @param localProperties copy of thread-local properties set by the user on the driver side.
 *
 * The parameters below are optional:
 * @param jobId id of the job this task belongs to
 * @param appId id of the app this task belongs to
 * @param appAttemptId attempt id of the app this task belongs to
 */
private[spark] class ShuffleMapTask(
    stageId: Int,
    stageAttemptId: Int,
    taskBinary: Broadcast[Array[Byte]],
    partition: Partition,
    @transient private var locs: Seq[TaskLocation],
    metrics: TaskMetrics,
    localProperties: Properties,
    jobId: Option[Int] = None,
    appId: Option[String] = None,
    appAttemptId: Option[String] = None)
  extends Task[MapStatus](stageId, stageAttemptId, partition.index, metrics, localProperties, jobId,
    appId, appAttemptId)
  with Logging {

  /** A constructor used only in test suites. This does not require passing in an RDD. */
  def this(partitionId: Int) {
    this(0, 0, null, new Partition { override def index: Int = 0 }, null, null, new Properties)
  }

  @transient private val preferredLocs: Seq[TaskLocation] = {
    if (locs == null) Nil else locs.toSet.toSeq
  }

  /**
    * 非常重要的一点就是shuffleMapTask的runTask方法有MapStatus返回值
    */
  override def runTask(context: TaskContext): MapStatus = {
    // Deserialize the RDD using the broadcast variable.
    val threadMXBean = ManagementFactory.getThreadMXBean
    val deserializeStartTime = System.currentTimeMillis()
    val deserializeStartCpuTime = if (threadMXBean.isCurrentThreadCpuTimeSupported) {
      threadMXBean.getCurrentThreadCpuTime
    } else 0L
    // 对相关数据做反序列化
    val ser = SparkEnv.get.closureSerializer.newInstance()
    // 取得了task的rdd和依赖，应该是stage的最后一个rdd
    val (rdd, dep) = ser.deserialize[(RDD[_], ShuffleDependency[_, _, _])](
      ByteBuffer.wrap(taskBinary.value), Thread.currentThread.getContextClassLoader)
    _executorDeserializeTime = System.currentTimeMillis() - deserializeStartTime
    _executorDeserializeCpuTime = if (threadMXBean.isCurrentThreadCpuTimeSupported) {
      threadMXBean.getCurrentThreadCpuTime - deserializeStartCpuTime
    } else 0L

    var writer: ShuffleWriter[Any, Any] = null
    try {
      // 从shuffleManager中获取ShuffleWriter
      val manager = SparkEnv.get.shuffleManager
      writer = manager.getWriter[Any, Any](dep.shuffleHandle, partitionId, context)

      // 最最重要的一行代码
      // 核心逻辑在iterator()方法中，执行了我们自己定义的逻辑
      // 返回的数据都是通过ShuffleWriter经过Partitioner进行分区之后写入不同的bucket中
      /**
        * iterator()的数据源从哪里来？
        * 首先是从本地缓存或磁盘中获取
        * 其次是从checkPoint中获取
        * 最后的办法是根据Lineage通过迭代计算得到，并按storageLevel存入内存或磁盘
        * 那计算的数据从哪里来？
        * see: [[org.apache.spark.rdd.ShuffledRDD#compute]]
        *      [[org.apache.spark.rdd.HadoopRDD#compute]]
        * 边迭代边写按partitioner写入bucket中，一次只有一个元素在流动
        * see: [[org.apache.spark.util.collection.ExternalSorter#insertAll]]
        */
      writer.write(rdd.iterator(partition, context).asInstanceOf[Iterator[_ <: Product2[Any, Any]]])
      // 最后返回结果，MapperStatus
      // 里面封装了shuffleMapTask计算后的数据，其实就是BlockManager相关的信息
      // BlockManager是Spark底层的内存、数据、磁盘数据管理的组件
      writer.stop(success = true).get
    } catch {
      case e: Exception =>
        try {
          if (writer != null) {
            writer.stop(success = false)
          }
        } catch {
          case e: Exception =>
            log.debug("Could not stop writer", e)
        }
        throw e
    }
  }

  override def preferredLocations: Seq[TaskLocation] = preferredLocs

  override def toString: String = "ShuffleMapTask(%d, %d)".format(stageId, partitionId)
}
