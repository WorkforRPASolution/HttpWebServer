package com.sec.eeg.ars.actor

import java.io.File
import java.net.InetAddress
import java.util.concurrent.TimeUnit
import akka.actor.SupervisorStrategy.Restart
import akka.actor.{Actor, ActorRef, ActorSystem, OneForOneStrategy, PoisonPill, Props, Terminated}
import akka.pattern.{Backoff, BackoffSupervisor, gracefulStop}
import akka.routing.SmallestMailboxPool
import com.datastax.driver.core.policies.{ExponentialReconnectionPolicy, RoundRobinPolicy, TokenAwarePolicy}
import com.datastax.driver.core.{Cluster, ConsistencyLevel, QueryOptions}
import com.mongodb.client.MongoClients
import com.sec.eeg.ars.data.ServiceConfig
import com.sec.eeg.ars.data.ServiceConfig.{MongoDBUrl, conf, database, serviceName}
import com.sec.eeg.ars.endpoint.ShutdownServer
import com.typesafe.config.ConfigFactory
import org.apache.curator.framework.imps.CuratorFrameworkState
import org.apache.curator.framework.{CuratorFramework, CuratorFrameworkFactory}
import org.apache.curator.framework.recipes.cache.NodeCache
import org.apache.curator.framework.recipes.leader.LeaderSelector
import org.apache.curator.retry.ExponentialBackoffRetry
import org.apache.curator.utils.CloseableUtils
import org.apache.zookeeper.CreateMode
import org.apache.zookeeper.KeeperException.NodeExistsException
import org.eclipse.jetty.server.Server
import org.eclipse.jetty.webapp.WebAppContext
import org.scalatra.servlet.ScalatraListener
import org.slf4j.LoggerFactory

import scala.concurrent.duration._
import scala.language.postfixOps
import scala.concurrent.{Await, Future}

object Master {
  private val logger = LoggerFactory.getLogger(Master.toString)
  case class ShutDown()
  var system : ActorSystem = _
  var master : ActorRef = _
  val stoplock = new Object()
  var server : Server = null

  init

  def init = {
    try {
      val root_dir = System.getProperty("EEG_BASE")
      val akkaApplicationConf = new File(root_dir + s"/conf/${ServiceConfig.serviceName}/application.conf")
      ServiceConfig.conf = ConfigFactory.parseFile(akkaApplicationConf)
    }
    catch {
      case ex : Exception =>
    }
  }

  def MakeActor(): Unit = {
    if(master == null){
      if(ServiceConfig.load == false)
        throw new Exception("Service Config loading is failed")

      system = ActorSystem.create(ServiceConfig.AkkaSystem, ConfigFactory.load(ServiceConfig.conf))
      master = system.actorOf(Props[Master], "Master")
    }
  }

  def StopForTermination(): Unit = {
    if(master != null){
      try{
        val stopped : Future[Boolean] = gracefulStop(master, 20 seconds, ShutDown())
        Await.result(stopped, 30 seconds)
      }catch {
        case e: akka.pattern.AskTimeoutException =>
      }
    }
  }

  def WebServiceStart = {
    server = new Server(ServiceConfig.HttpPort)
    val context = new WebAppContext()
    context setContextPath "/"
    context.setResourceBase(s"${ServiceConfig.root_dir}/parcels/${ServiceConfig.serviceName}/public")
    context.setInitParameter(ScalatraListener.LifeCycleKey, "ScalatraBootstrap")
    context.addEventListener(new ScalatraListener)
    server.setHandler(context)

    server.start()
  }

  def WebServiceStop = {
    if(server != null){
      server.stop()
      server.join()
    }
  }

  def main(args: Array[String]): Unit = {
    if(args.length != 1) {
      return
    }
    try{
      if(args(0) == "start") {
        MakeActor()
        logger.info("HttpWebServer started")
      }
      else if(args(0) == "stop") {
        stoplock.synchronized{
          StopForTermination()
          logger.info("HttpWebServer stopped")
        }
      }
    }
    catch {
      case ex: Exception => logger.error(s"program execution failed: ${ex}")
    }
  }
}

class Master extends Actor {
  private val log = LoggerFactory.getLogger(classOf[Master])
  import Master._
  var childsCnt : Int = 0
  var selectorService: LeaderSelector = null
  var zkCli : CuratorFramework = null

  var cassandraConnection: Cluster = null
  var HttpWebServerLeaderNodeCache: NodeCache = null

  private def createChildActor(childActor: ActorRef): ActorRef = {
    childsCnt += 1
    context.watch(childActor)
  }

  private def initializeCassandra = {
    import scala.collection.JavaConversions._
    val ctpLists = scala.collection.mutable.ListBuffer.empty[InetAddress]

    val address_port = ServiceConfig.CassandraAddress.split("#")
    if(address_port(0) != "" && address_port(1) != ""){
      val contactPoints = address_port(0).split(",")
      contactPoints.foreach(ctp => {
        if(ctp != "")
          ctpLists.+=(InetAddress.getByName(ctp))
      })
      if(contactPoints.length == 0)
        throw new Exception("Invalid Cassandra connection info")

      if(ServiceConfig.CassandraAuthenticationEnable == true){
        cassandraConnection = Cluster.builder.addContactPoints(ctpLists).withCredentials("ars","visuallove").withPort(address_port(1).toInt)
          .withLoadBalancingPolicy(new TokenAwarePolicy(new RoundRobinPolicy()))
          .withQueryOptions(new QueryOptions().setConsistencyLevel(ConsistencyLevel.ONE))
          .withReconnectionPolicy(new ExponentialReconnectionPolicy(10, 10000)).build
      }
      else{
        cassandraConnection = Cluster.builder.addContactPoints(ctpLists).withPort(address_port(1).toInt)
          .withLoadBalancingPolicy(new TokenAwarePolicy(new RoundRobinPolicy()))
          .withQueryOptions(new QueryOptions().setConsistencyLevel(ConsistencyLevel.ONE))
          .withReconnectionPolicy(new ExponentialReconnectionPolicy(10, 10000)).build
      }
    }
    else{
      throw new Exception("Invalid Cassandra connection info")
    }
  }

  private def makeChild() = {

    initializeCassandra

    val httpworkerProp = Props(classOf[HttpWorker], cassandraConnection)
    val emailworkerProp = Props(classOf[EmailWorker], ServiceConfig.conf, cassandraConnection)
    val redisworkerProp = Props(classOf[RedisActor])

    val httpWorkerSupervisor = BackoffSupervisor.props(
      Backoff.onFailure(httpworkerProp, "HttpWorker", Duration.create(3, TimeUnit.SECONDS), Duration.create(30, TimeUnit.SECONDS), 0.2
      ).withSupervisorStrategy(
        OneForOneStrategy() {
          case ex: Throwable =>
            log.info(s"Master supervisorStrategy: ${ex}")
            Restart
        }
      )
    )

    val emailWorkerSupervisor = BackoffSupervisor.props(
      Backoff.onFailure(emailworkerProp, "EmailWorker", Duration.create(3, TimeUnit.SECONDS), Duration.create(30, TimeUnit.SECONDS), 0.2
      ).withSupervisorStrategy(
        OneForOneStrategy() {
          case ex: Throwable =>
            log.info(s"Master supervisorStrategy: ${ex}")
            Restart
        }
      )
    )

    val redisWorkerSupervisor = BackoffSupervisor.props(
      Backoff.onFailure(redisworkerProp, "RedisWorker", Duration.create(3, TimeUnit.SECONDS), Duration.create(100, TimeUnit.SECONDS), 0.2
      ).withSupervisorStrategy(
        OneForOneStrategy() {
          case ex: Throwable =>
            log.info(s"Master supervisorStrategy: ${ex}")
            Restart
        }
      )
    )

    database = MongoClients.create(MongoDBUrl).getDatabase("EARS") //prestart 에 중복?

    createChildActor(context.actorOf(redisWorkerSupervisor, "RedisActor"))
    createChildActor(context.actorOf(SmallestMailboxPool(ServiceConfig.HttpWorkerCount).props(httpWorkerSupervisor),"HttpWorker"))
    createChildActor(context.actorOf(SmallestMailboxPool(ServiceConfig.EmailWorkerCount).props(emailWorkerSupervisor),"EmailWorker"))
    Master.WebServiceStart

    log.info("Master makeChild")
  }

  override def preStart() : Unit = {

    makeChild()

    zkCli = CuratorFrameworkFactory.newClient(ServiceConfig.ZookeeperQuorum, new ExponentialBackoffRetry(1000, 3))
    zkCli.start()
    zkCli.getZookeeperClient.blockUntilConnectedOrTimedOut()
    if(zkCli.getState != CuratorFrameworkState.STARTED){
      throw new Exception("Zk startup timed out")
    }

    //x.x.x.1-zookeeper parent node 확인/생성
    val zkBasePath = s"${ServiceConfig.ZookeeperNodePath}/daemons"
    try {
      zkCli.create().creatingParentsIfNeeded().withMode(CreateMode.PERSISTENT).forPath(zkBasePath) //parent
    } catch {
      case _: NodeExistsException =>  // 이미 있으면 무시
    }
    //x.x.x.1-zookeeper node 존재 확인 후 등록
    val zkNodePath = s"${zkBasePath}/${ServiceConfig.MyServiceAddress}"
    val zkNodeData = ServiceConfig.Version.getBytes
    try{
      zkCli.create().withMode(CreateMode.EPHEMERAL).forPath(zkNodePath,zkNodeData)
    } catch {
      case _ : NodeExistsException =>
        log.info("zookeeper node already exist")
        // Curator 내부의 ZooKeeper 인스턴스에서 현재 세션 ID를 꺼내기
        val stat = zkCli.checkExists().forPath(zkNodePath)
        val zk   = zkCli.getZookeeperClient.getZooKeeper
        val mySessionId = zk.getSessionId
        if (stat.getEphemeralOwner == mySessionId) {// 같은 세션이 만든 노드 → 데이터만 업데이트
          log.info(s"same sessionId:${mySessionId}. update data:${zkNodeData}")
          zkCli.setData().forPath(zkNodePath, zkNodeData)
        } else { //  다른 세션(이전 인스턴스)이 만든 노드 → 삭제 후 재생성
          try{
            zkCli.delete().guaranteed().forPath(zkNodePath)
            log.info(s"zooKeeper node deleted successfully")
          }catch {
            case _: NodeExistsException => //삭제 실패 = 이미 삭제되었음
              log.info(s"zooKeeper node is already deleted")
          }
          zkCli.create().withMode(CreateMode.EPHEMERAL).forPath(zkNodePath, zkNodeData)
        }
    }

    database = MongoClients.create(MongoDBUrl).getDatabase("EARS")

    ShutdownServer.start
    log.info("Master preStart")
  }

  override def postStop() : Unit = {
    try{
      if(cassandraConnection != null){
        cassandraConnection.close()
      }
    }catch {
      case ex : Throwable => log.error(s"Master postStop failed : ${ex.getMessage}")
    }
    ShutdownServer.stop
    log.info("Master postStop")
  }

  def receive = {
    case ShutDown() =>
      log.info("Shudown started")

      if(zkCli != null){
        zkCli.delete().forPath(s"${ServiceConfig.ZookeeperNodePath}/daemons/${ServiceConfig.MyServiceAddress}")
        CloseableUtils.closeQuietly(zkCli)
      }

      val cnt = context.children.size
      if(cnt <= 0){
        context stop self
        Master.WebServiceStop
        system.terminate()
      }
      else{
        context.children.foreach(ch => {
          ch ! PoisonPill
        })
        context.become(shuttingDown)
      }
    case _ =>
  }

  def shuttingDown: Receive = {
    case Terminated(actor) =>
      log.debug(s"Actor terminated: ${actor}")
      context.unwatch(actor)
      childsCnt -= 1
      log.debug(s"childCnt: ${childsCnt}")
      if(childsCnt <= 0){
        Master.WebServiceStop
        context stop self
        system.terminate()
      }
  }
}
