package udemy.cluster.onokube1

import akka.actor.{Actor, ActorLogging, ActorRef, ActorSystem, Address, ExtendedActorSystem, Extension, ExtensionId, ExtensionIdProvider, Props, ReceiveTimeout}
import akka.cluster.ClusterEvent._
import akka.cluster.{Cluster, Member}
import akka.dispatch.{PriorityGenerator, UnboundedPriorityMailbox}
import akka.util.Timeout
import com.typesafe.config.{Config, ConfigFactory}
import udemy.cluster.ono.OnoUtil
import udemy.cluster.onokube2
import udemy.cluster.onokube2.{AdditionalWorker, Aggregator, OnoClusterClient, OnoClusterMaster, OnoClusterSeedNodes, RunOnoClusterClient}

import scala.concurrent.duration._
import scala.language.postfixOps
import scala.util.{Failure, Random, Success}

trait OnoSerializable

object OnoClusteringDomain {
  val cluserConfigPath:String = "udemy/ono/OnoKubeClustering.conf"
  val clusterName:String = "OnoKubeCluster1"
  val masterRoleName:String = "master"
  val workerRoleName:String = "worker"
  val masterName:String = "master"
  val workerName:String = "worker"
  val inputFilePath = "ono_cluster_master/src/main/resources/lipsum.txt"

  case class ProcessFile(filePath: String) extends onokube2.OnoSerializable
  case class AssignChunkToWorker(lastIdx:Int, line:String, aggregator:ActorRef) extends onokube2.OnoSerializable
  case class ProcessLine(lastIdx:Int, line: String, aggregator:ActorRef) extends onokube2.OnoSerializable
  case class ProcessLineResult(lastIdx:Int, count: Int) extends onokube2.OnoSerializable
  case class RegisterWorker(address:Address, ref:ActorRef) extends onokube2.OnoSerializable
  case class OnoMemberUp(member:Member) extends onokube2.OnoSerializable
  case class SendStartCommandToClusterMaster() extends onokube2.OnoSerializable
}

class OnoClusteringMailBox (settings: ActorSystem.Settings, config:Config) extends UnboundedPriorityMailbox (
  PriorityGenerator {
    case _: MemberEvent => 0
    case _ => 4
  }
)

class RemoteAddressExtensionImpl(system: ExtendedActorSystem) extends Extension {
  def address = system.provider.getDefaultAddress
}

object RemoteAddressExtension extends ExtensionId[onokube2.RemoteAddressExtensionImpl]  with ExtensionIdProvider {
  override def lookup = onokube2.RemoteAddressExtension
  override def createExtension(system: ExtendedActorSystem) = new onokube2.RemoteAddressExtensionImpl(system)
  override def get(system: ActorSystem): onokube2.RemoteAddressExtensionImpl = super.get(system)
}


class OnoClusterMaster extends Actor with ActorLogging {
  import udemy.cluster.onokube2.OnoClusteringDomain._
  import context.dispatcher
  implicit val timeout =  Timeout(3 seconds)

  val cluster = Cluster(context.system)

  var workers: Map[Address, ActorRef] = Map()
  var pendingMembers: Map[Address, ActorRef] = Map()

  override def preStart(): Unit = {
    cluster.subscribe(
      self,
      initialStateMode = InitialStateAsEvents,
      classOf[MemberEvent],
      classOf[UnreachableMember]
    )
  }

  override def postStop(): Unit = {
    cluster.unsubscribe(self)
  }

  override def receive:Receive = handleMemberEvents.orElse(handleRegisterWorker).orElse(handleJob)

  def handleMemberEvents:Receive = {
    case MemberUp(member) =>
      println("MemberUp", member.address)
      if (member.hasRole("worker"))
        self ! OnoMemberUp(member)
    case OnoMemberUp(member)  =>
      println("OnoMemberUp", member.address)
      if (pendingMembers.contains(member.address))
        pendingMembers = pendingMembers - member.address
      else {
        val address = s"${member.address}/user/worker"
        context.actorSelection(address).resolveOne.onComplete {
          case Success(ref) =>
            self ! RegisterWorker(member.address,  ref)
          case Failure(ex) =>
            println(s"master:resolve:failure:$ex")
        }
      }
    case MemberRemoved(member, previousStatus) =>
      pendingMembers = pendingMembers - member.address
      workers = workers - member.address
      println("MemberRemoved",member.address)

    case UnreachableMember(member) if member.hasRole("worker") =>
      val workerOption = workers.get(member.address)
      workerOption.foreach { ref =>
        pendingMembers = pendingMembers + (member.address -> ref)
      }

    case m: MemberEvent =>
      println(s"new member: $m")

  }

  def handleRegisterWorker: Receive = {
    case RegisterWorker(address, actorRef) =>
      println("RegisterWorker",address)
      workers = workers ++ Map(address -> actorRef)
  }

  def handleJob:Receive = {

    case ProcessFile(filePath) =>
      val strings = scala.io.Source.fromFile(filePath).getLines().toList
      //println("filePath.strings.length", strings.length)
      val aggregator = context.actorOf(Props[Aggregator], "aggregator")
      strings.foreach { line  =>
        self ! AssignChunkToWorker(strings.length-1, line, aggregator)
      }//foreach
    case AssignChunkToWorker(lastIdx, line, aggregator) =>
      val workerList = workers.keys.toList
      val workersExist = workerList.size > 0
      if (workersExist) {
        val workerAddress = workerList(Random.nextInt(workerList.size))
        val worker = workers(workerAddress)
        println("sending to worker:", workerAddress)
        //we pass to self to get out of the thread so that master can receive some events
        worker !  ProcessLine(lastIdx, line, aggregator)
        Thread.sleep(20)
      }

    case m: Any => println("unmanaged event:", m)
  }
}


class OnoClusterWorker extends Actor with ActorLogging {
  import udemy.cluster.onokube2.OnoClusteringDomain._

  def work(text:String): Int = text.split(" ").length

  override def receive: Receive = {

    case ProcessLine(lastIdx, line, aggregator) =>

      //val address = self.path.toStringWithAddress(self.path.address)
      val address = onokube2.RemoteAddressExtension(context.system).address
      val workResult:Int = work(line)
      //Thread.sleep(500)
      aggregator ! ProcessLineResult(lastIdx, workResult)
  }
}

class Aggregator extends Actor with ActorLogging {
  import udemy.cluster.onokube2.OnoClusteringDomain._

  override def receive: Receive = online(0,0)

  def online(idx:Int, total:Int): Receive = {
    case ProcessLineResult(lastIdx, count) =>
      val newTotal = count + total
      if (idx==lastIdx)
        println(s"$idx, TOTAL COUNT: ${newTotal}")
      else
        context.become(online(idx+1, newTotal))

    case ReceiveTimeout =>
      println(s"TOTAL COUNT: $total")
      context.setReceiveTimeout(Duration.Undefined)
  }
}

object OnoClusterSeedNodes extends App {
  import udemy.cluster.onokube2.OnoClusteringDomain._
  def createNode(name:String, role:String, port:Int, props:Props): Unit = {
    val config = ConfigFactory.parseString(
      s"""
         | akka.cluster.roles = ["$role"]
         | akka.remote.artery.canonical.port = $port
         | akka.remote.artery.canonical.hostname = "127.0.0.1"
         |""".stripMargin
    ).withFallback(ConfigFactory.load(onokube2.OnoClusteringDomain.cluserConfigPath))
    val system = ActorSystem(onokube2.OnoClusteringDomain.clusterName, config)
    import system.dispatcher
    val actor = system.actorOf(props, name)
  }

  def run: Unit = {
    createNode(onokube2.OnoClusteringDomain.masterName, onokube2.OnoClusteringDomain.masterRoleName, 14551, Props[OnoClusterMaster])
    createNode(onokube2.OnoClusteringDomain.workerName, onokube2.OnoClusteringDomain.workerRoleName, 14552, Props[onokube2.OnoClusterWorker])
    createNode(onokube2.OnoClusteringDomain.workerName, onokube2.OnoClusteringDomain.workerRoleName, 14553, Props[onokube2.OnoClusterWorker])
  }
}

class OnoClusterClient extends Actor with ActorLogging {
  import udemy.cluster.onokube2.OnoClusteringDomain._
  implicit val timeout =  Timeout(3 seconds)
  import context.dispatcher

  val cluster = Cluster(context.system)
  override def preStart(): Unit = {
    cluster.subscribe(
      self,
      initialStateMode = InitialStateAsEvents,
      classOf[MemberEvent],
      classOf[UnreachableMember]
    )
  }

  override def postStop(): Unit = {
    cluster.unsubscribe(self)
  }

  override def receive: Receive = {
    case MemberUp(member) if member.hasRole(onokube2.OnoClusteringDomain.masterRoleName) =>
      println("Master is up", member.address)
      //actor ! ProcessFile(OnoClusteringDomain.inputFilePath)
      val address = s"${member.address}/user/master"
      context.actorSelection(address).resolveOne.onComplete {
        case Success(masterRef) =>
          masterRef ! ProcessFile(onokube2.OnoClusteringDomain.inputFilePath)
        case Failure(ex) =>
          println(s"OnoClusterClient:resolve:master:failure:$ex")
      }
  }
}



object AdditionalWorker extends App {
  def up = {
    OnoUtil.siteLocalAddress() match {
      case Some(siteAddress) =>
        val config = ConfigFactory.parseString(
          s"""
             |akka.cluster.roles = ["worker"]
             |akka.remote.artery.canonical.port = 14601
             |akka.remote.artery.canonical.hostname = "$siteAddress"
         """.stripMargin)
          .withFallback(ConfigFactory.load(onokube2.OnoClusteringDomain.cluserConfigPath))

        val system = ActorSystem(onokube2.OnoClusteringDomain.clusterName, config)
        system.actorOf(Props[onokube2.OnoClusterWorker], onokube2.OnoClusteringDomain.workerName)
      case _ => println("there is no site address")
    }

  }
}



object RunOnoClusterClient extends App {
  def up = {
    OnoUtil.siteLocalAddress() match {
      case Some(siteAddress) =>
        val config = ConfigFactory.parseString(
          s"""
             |akka.cluster.roles = ["client"]
             |akka.remote.artery.canonical.port = 20001
             |akka.remote.artery.canonical.hostname = "$siteAddress"
             """.stripMargin)
          .withFallback(ConfigFactory.load(onokube2.OnoClusteringDomain.cluserConfigPath))

        val system = ActorSystem(onokube2.OnoClusteringDomain.clusterName, config)
        system.actorOf(Props[OnoClusterClient], "client")
      case _ => println("OnoClient: no site address found")
    }
  }
}

object OnoClusterSeedNodes1 extends App {
  OnoClusterSeedNodes.run
}
object AdditionalWorker1 extends App {
  AdditionalWorker.up
}
object RunOnoClusterClient1 extends App {
  RunOnoClusterClient.up
}
