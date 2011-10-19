/**
 * Copyright (C) 2009-2011 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.actor

import akka.event.EventHandler
import akka.config.ConfigurationException
import akka.util.ReflectiveAccess
import akka.routing._
import akka.AkkaApplication
import akka.dispatch.MessageDispatcher
import java.util.concurrent.ConcurrentHashMap
import akka.dispatch.Promise
import com.eaio.uuid.UUID
import akka.AkkaException

/**
 * Interface for all ActorRef providers to implement.
 */
trait ActorRefProvider {

  def actorOf(props: Props, address: String): ActorRef

  def actorOf(props: RoutedProps, address: String): ActorRef

  def actorFor(address: String): Option[ActorRef]

  private[akka] def actorOf(props: Props, address: String, systemService: Boolean): ActorRef

  private[akka] def evict(address: String): Boolean

  private[akka] def deserialize(actor: SerializedActorRef): Option[ActorRef]
}

/**
 * Interface implemented by AkkaApplication and AkkaContext, the only two places from which you can get fresh actors
 */
trait ActorRefFactory {

  def provider: ActorRefProvider

  def dispatcher: MessageDispatcher

  def createActor(props: Props): ActorRef = createActor(props, new UUID().toString)

  /*
   * TODO this will have to go at some point, because creating two actors with
   * the same address can race on the cluster, and then you never know which
   * implementation wins
   */
  def createActor(props: Props, address: String): ActorRef = provider.actorOf(props, address)

  def createActor[T <: Actor](implicit m: Manifest[T]): ActorRef = createActor(Props(m.erasure.asInstanceOf[Class[_ <: Actor]]))

  def createActor[T <: Actor](address: String)(implicit m: Manifest[T]): ActorRef =
    createActor(Props(m.erasure.asInstanceOf[Class[_ <: Actor]]), address)

  def createActor[T <: Actor](clazz: Class[T]): ActorRef = createActor(Props(clazz))

  def createActor(factory: ⇒ Actor): ActorRef = createActor(Props(() ⇒ factory))

  def createActor(creator: UntypedActorFactory): ActorRef = createActor(Props(() ⇒ creator.create()))

  def createActor(props: RoutedProps): ActorRef = createActor(props, new UUID().toString)

  def createActor(props: RoutedProps, address: String): ActorRef = provider.actorOf(props, address)

  def findActor(address: String): Option[ActorRef] = provider.actorFor(address)

}

class ActorRefProviderException(message: String) extends AkkaException(message)

/**
 * Local ActorRef provider.
 */
class LocalActorRefProvider(val app: AkkaApplication) extends ActorRefProvider {

  private val actors = new ConcurrentHashMap[String, Promise[ActorRef]]

  def actorOf(props: Props, address: String): ActorRef = actorOf(props, address, false)

  def actorFor(address: String): Option[ActorRef] = actors.get(address) match {
    case null   ⇒ None
    case future ⇒ Some(future.get)
  }

  /**
   * Returns true if the actor was in the provider's cache and evicted successfully, else false.
   */
  private[akka] def evict(address: String): Boolean = actors.remove(address) ne null

  private[akka] def actorOf(props: Props, address: String, systemService: Boolean): ActorRef = {
    Address.validate(address)

    val localProps =
      if (props.dispatcher == Props.defaultDispatcher)
        props.copy(dispatcher = app.dispatcher)
      else
        props

    val defaultTimeout = app.AkkaConfig.ActorTimeout

    val newFuture = Promise[ActorRef](5000)(app.dispatcher) // FIXME is this proper timeout?
    val oldFuture = actors.putIfAbsent(address, newFuture)

    if (oldFuture eq null) { // we won the race -- create the actor and resolve the future

      val actor: ActorRef = try {
        app.deployer.lookupDeploymentFor(address) match { // see if the deployment already exists, if so use it, if not create actor

          // create a local actor
          case None | Some(DeploymentConfig.Deploy(_, _, DeploymentConfig.Direct, _, _, DeploymentConfig.LocalScope)) ⇒
            new LocalActorRef(app, localProps, address, systemService) // create a local actor

          // create a routed actor ref
          case deploy @ Some(DeploymentConfig.Deploy(_, _, routerType, nrOfInstances, _, DeploymentConfig.LocalScope)) ⇒
            val routerFactory: () ⇒ Router = DeploymentConfig.routerTypeFor(routerType) match {
              case RouterType.Direct            ⇒ () ⇒ new DirectRouter
              case RouterType.Random            ⇒ () ⇒ new RandomRouter
              case RouterType.RoundRobin        ⇒ () ⇒ new RoundRobinRouter
              case RouterType.ScatterGather     ⇒ () ⇒ new ScatterGatherFirstCompletedRouter()(props.dispatcher, defaultTimeout)
              case RouterType.LeastCPU          ⇒ sys.error("Router LeastCPU not supported yet")
              case RouterType.LeastRAM          ⇒ sys.error("Router LeastRAM not supported yet")
              case RouterType.LeastMessages     ⇒ sys.error("Router LeastMessages not supported yet")
              case RouterType.Custom(implClass) ⇒ () ⇒ Routing.createCustomRouter(implClass)
            }

            val connections: Iterable[ActorRef] =
              if (nrOfInstances.factor > 0)
                Vector.fill(nrOfInstances.factor)(new LocalActorRef(app, localProps, new UUID().toString, systemService))
              else Nil

            actorOf(RoutedProps(routerFactory = routerFactory, connectionManager = new LocalConnectionManager(connections)), address)

          case _ ⇒ throw new Exception("Don't know how to create this actor ref! Why?")
        }
      } catch {
        case e: Exception ⇒
          newFuture completeWithException e // so the other threads gets notified of error
          throw e
      }

      newFuture completeWithResult actor
      actor

    } else { // we lost the race -- wait for future to complete
      oldFuture.await.resultOrException.get
    }
  }

  /**
   * Creates (or fetches) a routed actor reference, configured by the 'props: RoutedProps' configuration.
   */
  def actorOf(props: RoutedProps, address: String): ActorRef = {
    //FIXME clustering should be implemented by cluster actor ref provider
    //TODO Implement support for configuring by deployment ID etc
    //TODO If address matches an already created actor (Ahead-of-time deployed) return that actor
    //TODO If address exists in config, it will override the specified Props (should we attempt to merge?)
    //TODO If the actor deployed uses a different config, then ignore or throw exception?
    if (props.connectionManager.size == 0) throw new ConfigurationException("RoutedProps used for creating actor [" + address + "] has zero connections configured; can't create a router")
    // val clusteringEnabled = ReflectiveAccess.ClusterModule.isEnabled
    // val localOnly = props.localOnly
    // if (clusteringEnabled && !props.localOnly) ReflectiveAccess.ClusterModule.newClusteredActorRef(props)
    // else new RoutedActorRef(props, address)
    new RoutedActorRef(props, address)
  }

  private[akka] def deserialize(actor: SerializedActorRef): Option[ActorRef] = actorFor(actor.address)
}