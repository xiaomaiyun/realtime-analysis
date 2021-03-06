package com.hb.analysis

import java.io.{File, FileInputStream}
import java.sql.SQLException
import java.util.{ArrayList, Calendar, Date, Properties}
import java.text.SimpleDateFormat

import org.apache.log4j.Logger
import org.apache.log4j.PropertyConfigurator
import org.apache.spark.SparkConf
import org.apache.spark.storage.StorageLevel
import org.apache.spark.streaming.Seconds
import org.apache.spark.streaming.StreamingContext
import consumer.kafka.ReceiverLauncher
import consumer.kafka.ProcessedOffsetManager
import com.clearspring.analytics.stream.cardinality.HyperLogLogPlus
import com.hb.falcon.{Pack, Sender}
import com.hb.model.{IPMapping, IpToLong, LocationInfo}
import com.hb.pool.ConnectionPool
import com.hb.utils.Num

/**
  * Created by Simon on 2017/2/23.
  */

object NginxFlowAnalysis {
  //接口参数
  private val endpoint = "ngxmaster"
  private val step = 60
  private val counterType = "GAUGE"
  private val tags = "_Minute"
  private val ip_file = "/resources/ipCity.properties"

  //计算指标
  private val metric1 = "pv_min"
  private val metric2 = "errcounts"
  private val metric3 = "errs"
  private val metric4 = "uv_Min"
  private val metric5 = "pen99th"
  private val metric6 = "pen95th"
  private val metric7 = "pen75th"
  private val metric8 = "pen50th"
  private val metric9 = "uvTotal"
  private val metric10= "abnormalIPCounts"


  val logger = Logger.getLogger(NginxFlowAnalysis.getClass.getName)

  /**
    * 更新HyperLogLogPlus对象
    */
  val updateCardinal = (values: Seq[String], state: Option[HyperLogLogPlus]) => {
    val calendar = Calendar.getInstance()
    val hh = calendar.get(Calendar.HOUR_OF_DAY)
    val mm = calendar.get(Calendar.MINUTE)
    if (hh == 0 && mm == 0 ) {
      val hll = new HyperLogLogPlus(14)
      for (value <- values) { hll.offer(value) }
      Option(hll)
    }
    else {
      if (state.nonEmpty) {
        val hll = state.get
        for (value <- values) { hll.offer(value) }
        Option(hll)
      } else {
        val hll = new HyperLogLogPlus(14)
        for (value <- values) { hll.offer(value) }
        Option(hll)
      }
    }
  }


  def main(args: Array[String]): Unit = {
    if (args.length != 3) {
      println("Usage: spark-2.0.0/bin/spark-submit --class com.hb.analysis.NginxFlowAnalysis --master yarn --num-executors 4 --executor-memory 8G --executor-cores 4 --driver-memory 1000M  log-analysis.jar  conf/log4j.properties  conf/conf.properties  conf/c3p0.properties" )
      System.exit(1)
    }

    val Array(logProperties,configProperties,dbProperties) = args
    val checkpointDirectory = "analysisCheckpoint"

    def createContext(logConfig : String,applicationConfig : String, dbConfig : String) = {
      PropertyConfigurator.configure(logConfig)

      //获取应用相关配置
      val in= new FileInputStream(new File(applicationConfig))
      val properties = new Properties
      properties.load(in)

      val master = properties.getProperty("master")
      logger.info("master address is : " + master)
      val zkHosts = properties.getProperty("zkAddress").split(",").map(line => line.split(":")(0)).mkString(",")
      logger.info("zkHosts is : " + zkHosts)
      val zkPort = properties.getProperty("zkAddress").split(",")(0).split(":")(1)
      logger.info("zkPort is : " + zkPort)
      val zkAddress = properties.getProperty("zkAddress")
      logger.info("zkAddress is : " + zkAddress)
      val group = properties.getProperty("group")
      logger.info("consumer group id is : " + group)
      val url = properties.getProperty("falconUrl")
      logger.info("falcon http interface  is : " + url)
      val topic = properties.getProperty("topics")
      logger.info("consumer topic  is : " + topic)

      val numberOfReceivers = properties.getProperty("numberOfReceivers").toInt
      val abnormalVisitThreshold = properties.getProperty("abnormalVisitThreshold").toInt
      val aggregateProvinceFlag = properties.getProperty("aggregateProvinceFlag").toBoolean
      val splitColumns = properties.getProperty("splitColumns")
      val percentileNums = properties.getProperty("percentileNums")

      //split提取ip,请求api,状态码,设备id,时延五个维度的数据
      val column1 = splitColumns.split(",")(0).toInt
      val column2 = splitColumns.split(",")(1).toInt
      val column3 = splitColumns.split(",")(2).toInt
      val column4 = splitColumns.split(",")(3).toInt
      val column5 = splitColumns.split(",")(4).toInt

      //各百分位指标
      val percentile1 = percentileNums.split(",")(0).toFloat
      val percentile2 = percentileNums.split(",")(1).toFloat
      val percentile3 = percentileNums.split(",")(2).toFloat
      val percentile4 = percentileNums.split(",")(3).toFloat

      val kafkaProperties: Map[String, String] =
        Map("zookeeper.hosts" -> zkHosts,
          "zookeeper.port" -> zkPort,
          "kafka.topic" -> topic,
          "zookeeper.consumer.connection" -> zkAddress,
          "kafka.consumer.id" -> group,
          "consumer.forcefromstart" -> "true",
          "consumer.backpressure.enabled" -> "true"
        )

      val props = new java.util.Properties()
      kafkaProperties foreach { case (key, value) => props.put(key, value) }

      val conf = new SparkConf().setAppName("NginxPerformanceMonitor")
        .set("spark.serializer", "org.apache.spark.serializer.KryoSerializer")
      logger.info("initializing spark config")

      val ssc = new StreamingContext(conf, Seconds(60))
      logger.info("starting spark streaming job")

      val inC3p0 = new FileInputStream(new File(dbConfig))
      val propC3p0 = new Properties()
      propC3p0.load(inC3p0)
      val propC3p0BroadCast = ssc.sparkContext.broadcast(propC3p0)

      //广播IP段和city的对应关系
      val ipMap = IPMapping.getIpMapping(ip_file)
      val ipMapBroadCast = ssc.sparkContext.broadcast(ipMap)

      ssc.checkpoint(checkpointDirectory)

      val messages = ReceiverLauncher.launch(ssc, props, numberOfReceivers, StorageLevel.MEMORY_ONLY)
      val partitonOffset_stream = ProcessedOffsetManager.getPartitionOffset(messages, props)
      logger.info("fetching current offset from zookeeper cluster")

      /**
        * 分多个JOB计算指标。
        */
      val filterMessages = messages.map { x => new String(x.getPayload) }
        .filter(s => s.contains("GET") || s.contains("POST"))
        .map(line => line.split("\\^\\^A"))
        .map(line => Array(line(column1), line(column2).split(" ")(1), line(column3), line(column4), line(column5)))

      import org.apache.spark.storage.StorageLevel.MEMORY_AND_DISK
      filterMessages.persist(MEMORY_AND_DISK)

      filterMessages.foreachRDD(rdd => {
        val ls = new ArrayList[Any]
        /**
          * 计算每分钟请求数
          */
        val counts = rdd.count()
        val countsJson = Pack.pack(endpoint, metric1, step, counts, counterType,tags)
        ls.add(countsJson)

        /**
          * 计算每分钟错误请求数
          */
        val errRecords = rdd.filter(_(2).trim().toInt >= 400).cache()
        val errCounts = errRecords.count()
        val errCountsJson = Pack.pack(endpoint, metric2, step, errCounts, counterType,tags)
        ls.add(errCountsJson)

        /**
          * 计算每分钟不同错误请求数
          */
        val diffErrors = errRecords.map(x => (x(2).trim.toInt, 1)).reduceByKey(_+_).collect()
        diffErrors.foreach{ x =>
          ls.add(Pack.pack(endpoint, metric3 + x._1.toString, step, x._2.toDouble, counterType,tags))
        }

        /**
          * 每分钟用户数
          */
        val uniqueVisitor =  rdd.map(x => (x(3),1)).reduceByKey(_ + _).count()
        val uniqueVisitorJson = Pack.pack(endpoint, metric4, step, uniqueVisitor, counterType,tags)
        ls.add(uniqueVisitorJson)

        //输出给open-falcon agent
        Sender.sender(ls,url)

        //保存到数据库
        val conn = ConnectionPool.getConnectionPool(propC3p0BroadCast.value).getConnection
        conn.setAutoCommit(false)

        val sdf = new SimpleDateFormat("yyyy/MM/dd HH:mm")
        val currentTimestamp = sdf.format(new Date())

        try {
          val sql = "insert into requests_minute(time,pv_minute,errs_minute,errs_400,errs_404,errs_405,errs_408,errs_499,errs_502,errs_503,uv_minute) values (?,?,?,?,?,?,?,?,?,?,?)"
          val preparedStatement = conn.prepareStatement(sql)
          preparedStatement.setString(1, currentTimestamp)
          preparedStatement.setLong(2, counts)
          preparedStatement.setLong(3, errCounts)

          diffErrors.foreach{ errs => {
            errs._1.toInt match {
              case 400  => preparedStatement.setLong(4, errs._2)
              case 404  => preparedStatement.setLong(5, errs._2)
              case 405  => preparedStatement.setLong(6, errs._2)
              case 408  => preparedStatement.setLong(7, errs._2)
              case 499  => preparedStatement.setLong(8, errs._2)
              case 502  => preparedStatement.setLong(9, errs._2)
              case 503  => preparedStatement.setLong(10, errs._2)
              case _    =>
            }
          }
          }

          val errColumnMap : Map[Int,Int] = Map (400 -> 4, 404 -> 5, 405 -> 6, 408 -> 7, 499 -> 8, 502 -> 9, 503 -> 10)
          val errAllSet : Set[Int]= Set(400,404,405,408,499,502,503)
          val errGotSet = diffErrors.map(x => x._1.toInt).toSet
          val errLostSet = errAllSet -- errGotSet
          //如果记录里面没有相关错误码,error次数置0
          for (key <- errLostSet) {preparedStatement.setLong(errColumnMap.get(key).get,0)}

          preparedStatement.setLong(11, uniqueVisitor)

          preparedStatement.addBatch()
          preparedStatement.executeBatch()
          conn.commit()
          preparedStatement.clearBatch()
        } catch {
          case e:SQLException  => conn.rollback()
          case e:Exception => e.printStackTrace()
        } finally {
          conn.close()
        }
      }
      )

      filterMessages.foreachRDD(rdd => {
        /**
          * 各百分位时延迟,99,95,75,50百分位
          * 每分钟的数据量不大的时候,为简化逻辑,用repartition函数进行partition合并,在一个worker进行计算,数据量大了应分布式计算再合并
          */

        rdd.map(x => x(4).toDouble).repartition(1).foreachPartition { partitionRecords =>

          val arrRecords = partitionRecords.toArray
          if (arrRecords.length > 0) {
            val ls = new ArrayList[Any]()
            val pen99th = Num.percentile(arrRecords, percentile1)
            val pen95th = Num.percentile(arrRecords, percentile2)
            val pen75th = Num.percentile(arrRecords, percentile3)
            val pen50th = Num.percentile(arrRecords, percentile4)

            val pen99thJson = Pack.pack(endpoint, metric5, step, pen99th, counterType,tags)
            val pen95thJson = Pack.pack(endpoint, metric6, step, pen95th, counterType,tags)
            val pen75thJson = Pack.pack(endpoint, metric7, step, pen75th, counterType,tags)
            val pen50thJson = Pack.pack(endpoint, metric8, step, pen50th, counterType,tags)

            ls.add(pen99thJson)
            ls.add(pen95thJson)
            ls.add(pen75thJson)
            ls.add(pen50thJson)

            //发送给open-falcon agent
            Sender.sender(ls,url)
            //保存到数据库
            val conn = ConnectionPool.getConnectionPool(propC3p0BroadCast.value).getConnection
            conn.setAutoCommit(false)

            val sdf = new SimpleDateFormat("yyyy/MM/dd HH:mm")
            val currentTimestamp = sdf.format(new Date())
            try {
              val sql = "insert into latency(time,pen99th,pen95th,pen75th,pen50th) values (?,?,?,?,?)"
              val preparedStatement = conn.prepareStatement(sql)
              preparedStatement.setString(1, currentTimestamp)
              preparedStatement.setDouble(2, pen99th)
              preparedStatement.setDouble(3, pen95th)
              preparedStatement.setDouble(4, pen75th)
              preparedStatement.setDouble(5, pen50th)

              preparedStatement.addBatch()
              preparedStatement.executeBatch()
              conn.commit()
              preparedStatement.clearBatch()
            } catch {
              case e:SQLException  => conn.rollback()
              case e:Exception => e.printStackTrace()
            } finally {
              conn.close()
            }
          }
        }
      }
      )

      /**
        * 总用户数UV，采用基数估计
        */
      filterMessages.map(x => (null, x(3))).updateStateByKey(updateCardinal)
        .map(x => x._2.cardinality).foreachRDD(rdd => {

        rdd.foreach { x =>
          val ls = new ArrayList[Any]
          val uvTotalJson = Pack.pack(endpoint, metric9, step, x, counterType, tags)
          ls.add(uvTotalJson)
          //发送给open-falcon agent
          Sender.sender(ls, url)

          // 保存数据库
          val conn = ConnectionPool.getConnectionPool(propC3p0BroadCast.value).getConnection
          conn.setAutoCommit(false)
          val sdf = new SimpleDateFormat("yyyy/MM/dd HH:mm");
          val currentTimestamp = sdf.format(new Date())

          try {
            val sql = "insert into uv_day(time,uv) values (?,?)"
            val preparedStatement = conn.prepareStatement(sql)
            preparedStatement.setString(1, currentTimestamp)
            preparedStatement.setLong(2, x.toLong)
            preparedStatement.addBatch()
            preparedStatement.executeBatch()
            conn.commit()
            preparedStatement.clearBatch()
          } catch {
            case e:SQLException  => conn.rollback()
            case e:Exception => e.printStackTrace()
          } finally {
            conn.close()
          }
        }
      }
      )


      val ipRecords = filterMessages.map(x => (x(0),1)).reduceByKey(_+_)
      ipRecords.persist(MEMORY_AND_DISK)


      /**
        * 异常访问IP次数和记录  记录详情保存数据库，异常次数输出给open-falcon做前端展示
        */
      ipRecords.filter(_._2 > abnormalVisitThreshold).foreachRDD( rdd => {
        //异常次数输出给open-falcon做前端展示
        val abnormalIPCounts = rdd.count()
        val ls = new ArrayList[Any]()
        val abnormalIPCountsJson = Pack.pack(endpoint, metric10, step, abnormalIPCounts, counterType,tags)
        ls.add(abnormalIPCountsJson)
        Sender.sender(ls, url)

        //ip详单保存到数据库
        rdd.foreachPartition{
          data => {
            val conn = ConnectionPool.getConnectionPool(propC3p0BroadCast.value).getConnection
            conn.setAutoCommit(false)

            val sdf = new SimpleDateFormat("yyyy/MM/dd HH:mm")
            val currentTimestamp = sdf.format(new Date())

            try {
              val sql = "insert into abnormal_ip(time,ip,frequency) values (?,?,?)"
              val preparedStatement = conn.prepareStatement(sql)
              data.foreach(r => {
                preparedStatement.setString(1, currentTimestamp)
                preparedStatement.setString(2, r._1.toString)
                preparedStatement.setInt(3, r._2)
                preparedStatement.addBatch()
              })

              preparedStatement.executeBatch()
              conn.commit()
              preparedStatement.clearBatch()
            } catch {
              case e:Exception => {
                case e:SQLException  => conn.rollback()
                case e:Exception => e.printStackTrace()
              }
            } finally {
              conn.close()
            }
          }
        }
      })

      /**
        * 各省用户数（笛卡尔乘积数据库大的时候比较耗cpu，暂时没想到好方法）
        * 记录保存到数据库
        */
      if (aggregateProvinceFlag) {
        ipRecords.map(x => (IpToLong.IPv4ToLong(x._1.trim),x._2))
          .map(x => (LocationInfo.findLocation(ipMapBroadCast.value,x._1),x._2))
          .reduceByKey(_+_) foreachRDD( rdd => {
          rdd.foreachPartition { data =>
            if (data != null) {
              val conn = ConnectionPool.getConnectionPool(propC3p0BroadCast.value).getConnection
              conn.setAutoCommit(false)

              val sdf = new SimpleDateFormat("yyyy/MM/dd HH:mm")
              val currentTimestamp = sdf.format(new Date())

              try {
                val sql = "insert into uv_province(time,province,uv) values (?,?,?)"
                val preparedStatement = conn.prepareStatement(sql)
                data.foreach(r => {
                  preparedStatement.setString(1, currentTimestamp)
                  preparedStatement.setString(2, r._1.toString)
                  preparedStatement.setInt(3, r._2)
                  preparedStatement.addBatch()
                })

                preparedStatement.executeBatch()
                conn.commit()
                preparedStatement.clearBatch()
              } catch {
                case e:SQLException  => conn.rollback()
                case e:Exception => e.printStackTrace()
              } finally {
                conn.close()
              }
            }
          }
        }
          )
      }
      ProcessedOffsetManager.persists(partitonOffset_stream, props)
      logger.info("persist current offset in zookeeper cluster")

      ssc
    }
    //broadcast value无法从checkpoint恢复，只能自己序列化保存updateStateByKey的状态，用于重启恢复
    //val ssc = StreamingContext.getOrCreate(checkpointDirectory,() => createContext(logProperties,configProperties,dbProperties))
    val ssc = createContext(logProperties,configProperties,dbProperties)

    ssc.start()
    ssc.awaitTermination()
  }
}





