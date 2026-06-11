package com.sec.eeg.ars.actor

import java.util.concurrent.TimeUnit

import akka.actor.Actor
import com.lambdaworks.redis.RedisClient
import com.lambdaworks.redis.api.StatefulRedisConnection
import com.lambdaworks.redis.pubsub.StatefulRedisPubSubConnection
import com.sec.eeg.ars.data.ServiceConfig
import org.slf4j.LoggerFactory

case class EmailFormat(project: String, category: String, body: String)
case class RedisSetData(key: String, value: String)
case class RedisPubMessage(cha: String, value: String)

class RedisActor extends Actor {
  import scala.concurrent.ExecutionContext.Implicits.global
  private val log = LoggerFactory.getLogger(classOf[RedisActor])
  var redisClient : com.lambdaworks.redis.RedisClient = null
  var redisConnection : StatefulRedisConnection[String,String] = null
  var redisPubSub : StatefulRedisPubSubConnection[String,String] = null

  case class Ping()

  private var pingRedis : akka.actor.Cancellable = _

  private def shutdown = {
    try{
      if(redisClient != null){
        redisClient.shutdown()
      }
      log.info("LettuceRedisClient shutdown success")
    }
    catch {
      case ex : Exception => log.warn(s"LettuceRedisClient shutdown failed: ${ex}")
    }
  }

  override def preStart() : Unit = {
    if(ServiceConfig.RedisSentinelConnection.exists(ch => ch == '#'))
      redisClient = RedisClient.create(s"redis-sentinel://visuallove@${ServiceConfig.RedisSentinelConnection}")
    else
      redisClient = RedisClient.create(s"redis://visuallove@${ServiceConfig.RedisSentinelConnection}")

    redisConnection = redisClient.connect()
    redisPubSub = redisClient.connectPubSub()

    if(ServiceConfig.RedisPingInterval != null)
      pingRedis = context.system.scheduler.scheduleOnce(ServiceConfig.RedisPingInterval, self, Ping())

    log.info(s"RedisActor preStart")
  }

  override def postStop() : Unit = {
    shutdown
    log.info("RedisActor postStop")
  }

  override def receive : Receive = {
    case Ping() =>
      try{ redisConnection.sync().ping()    }catch { case _ : Throwable => }
      try{ redisPubSub.sync().ping()    }catch { case _ : Throwable => }
      pingRedis = context.system.scheduler.scheduleOnce(ServiceConfig.RedisPingInterval, self, Ping())
    case EmailFormat(p,c,body) =>
      log.info(s"publish email ${p}:${c}")
      redisPubSub.sync().publish(s"SendEmails-${p}:${c}",body)
    case RedisSetData(k,v) =>
      log.info(s"redis set data: ${k} - ${v}")
      redisConnection.sync().set(k,v)
    case RedisPubMessage(c,v) =>
      log.info(s"redis publish: ${c} - ${v}")
      redisPubSub.sync().publish(c,v)
    case _ =>
  }
}
