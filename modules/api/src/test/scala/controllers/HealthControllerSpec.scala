package controllers

import org.scalatestplus.play._
import org.scalatestplus.play.guice._
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.stream.{Materializer, SystemMaterializer}
import org.scalatest.BeforeAndAfterAll
import play.api.test._
import play.api.test.Helpers._
import play.api.libs.json._

class HealthControllerSpec extends PlaySpec with BeforeAndAfterAll {

  implicit lazy val actorSystem: ActorSystem = ActorSystem("health-controller-spec")
  implicit lazy val mat: Materializer = SystemMaterializer(actorSystem).materializer

  "HealthController GET /health" must {
    "return 200 with status ok" in {
      val controller = new HealthController(stubControllerComponents())
      val result = controller.health().apply(FakeRequest(GET, "/health"))

      status(result) mustBe OK
      val json = contentAsJson(result)
      (json \ "status").as[String] mustBe "ok"
      (json \ "service").as[String] mustBe "markko-api"
    }
  }

  override protected def afterAll(): Unit = {
    actorSystem.terminate()
    super.afterAll()
  }
}
