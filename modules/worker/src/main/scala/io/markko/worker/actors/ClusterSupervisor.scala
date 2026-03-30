package io.markko.worker.actors

import org.apache.pekko.actor.typed.{ActorRef, Behavior}
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.cluster.typed.{Cluster, ClusterSingleton, SingletonActor}
import com.typesafe.config.Config
import com.typesafe.scalalogging.LazyLogging

/**
 * Pekko Cluster setup — ensures a single ParserSupervisor runs across the cluster.
 * If the node running the supervisor dies, a new node transparently takes over.
 *
 * Usage: start multiple WorkerApp instances with different ports.
 * Only one will run the ParserSupervisor (ClusterSingleton guarantee).
 * All nodes run ResyncActor locally for independent ES/graph work.
 */
object ClusterSupervisor extends LazyLogging {

  def apply(config: Config): Behavior[Nothing] = {
    Behaviors.setup[Nothing] { context =>
      val cluster = Cluster(context.system)
      logger.info(s"Cluster node ${cluster.selfMember.address} starting...")

      // Parser/Exporter supervisor as ClusterSingleton — exactly one runs at a time
      val singletonManager = ClusterSingleton(context.system)
      singletonManager.init(
        SingletonActor(ParserSupervisor(config), "parser-supervisor")
          .withStopMessage(ParserSupervisor.GracefulShutdown)
      )
      logger.info("ClusterSingleton[ParserSupervisor] initialized")

      // ResyncActor runs on every node (local processing, no contention)
      context.spawn(ResyncActor(config), "resync-actor")
      logger.info("ResyncActor spawned locally")

      Behaviors.empty
    }
  }
}
