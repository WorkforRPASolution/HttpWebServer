package com.sec.eeg.ars.testkit

import java.util.concurrent.atomic.AtomicReference

import akka.actor.{Actor, ActorIdentity, ActorRef, ActorSystem, Identify, Props}
import akka.pattern.ask
import akka.util.Timeout

import scala.concurrent.Await
import scala.concurrent.duration._

/** 받은 메시지를 그대로 target 에 넘긴다 (골든 계층의 RedisActor 자리) */
class Forwarder(target: ActorRef) extends Actor {
  def receive = { case m => target forward m }
}

object Forwarder {
  def props(target: ActorRef): Props = Props(new Forwarder(target))
}

/** 받은 메시지를 probe 에 알리고, reply 에 든 값으로 응답한다 (라우팅 계층의 HttpWorker·EmailWorker 자리) */
class Responder(probe: ActorRef, reply: AtomicReference[Any]) extends Actor {
  def receive = {
    case m =>
      probe ! m
      reply.get() match {
        case Responder.NoReply => ()
        case r => sender() ! r
      }
  }
}

object Responder {
  /** reply 에 이 값이 들어 있으면 응답하지 않는다 */
  case object NoReply

  def props(probe: ActorRef, reply: AtomicReference[Any]): Props = Props(new Responder(probe, reply))
}

/** /user/Master 자리. 자식을 이름대로 만들고, 자기에게 온 메시지는 probe 에 넘긴다 */
class StubMaster(probe: ActorRef, children: Map[String, Props]) extends Actor {
  override def preStart(): Unit = children.foreach { case (name, props) => context.actorOf(props, name) }

  def receive = { case m => probe ! m }
}

object StubMaster {
  def props(probe: ActorRef, children: Map[String, Props]): Props = Props(new StubMaster(probe, children))
}

object ActorKit {
  /** path 의 액터가 생기고 preStart 를 마칠 때까지 기다린다 (Identify 는 preStart 뒤에 처리된다) */
  def awaitActor(system: ActorSystem, path: String, within: FiniteDuration = 5.seconds): ActorRef = {
    implicit val timeout: Timeout = Timeout(1.second)
    val deadline = within.fromNow
    while (deadline.hasTimeLeft()) {
      Await.result(system.actorSelection(path) ? Identify(path), 2.seconds) match {
        case ActorIdentity(_, Some(ref)) => return ref
        case _ => Thread.sleep(50)
      }
    }
    throw new IllegalStateException(s"액터가 생기지 않았다: $path")
  }
}
