package controllers

import actions.AuthAction
import javax.inject._
import org.apache.pekko.actor._
import org.apache.pekko.stream.Materializer
import play.api.libs.json._
import play.api.libs.streams.ActorFlow
import play.api.mvc._
import services.RedisService

@Singleton
class WebSocketController @Inject()(
  cc:           ControllerComponents,
  redisService: RedisService,
  authAction: AuthAction
)(implicit system: ActorSystem, mat: Materializer) extends AbstractController(cc) {

  /** WS /ws/feed — Real-time feed of parsed link events */
  def feed(): WebSocket = WebSocket.acceptOrResult[String, String] { request =>
    authAction.authenticate(request).map {
      case Right(_) =>
        Right(ActorFlow.actorRef { out =>
          FeedActor.props(out, redisService)
        })
      case Left(result) =>
        Left(result)
    }(system.dispatcher)
  }
}

/** Actor that subscribes to Redis pub/sub and forwards events to WebSocket */
object FeedActor {
  def props(out: ActorRef, redisService: RedisService): Props =
    Props(new FeedActor(out, redisService))
}

class FeedActor(out: ActorRef, redisService: RedisService) extends Actor {
  private var subscription: Option[RedisService.Subscription] = None

  override def preStart(): Unit = {
    subscription = Some(redisService.subscribeParsedEvents { linkId =>
      val event = Json.obj(
        "type"   -> "link_parsed",
        "linkId" -> linkId
      ).toString()
      out ! event
    })
  }

  def receive: Receive = {
    case msg: String =>
      // Client messages — could handle commands here
      ()
  }

  override def postStop(): Unit = {
    subscription.foreach(_.unsubscribe())
    subscription = None
  }
}
