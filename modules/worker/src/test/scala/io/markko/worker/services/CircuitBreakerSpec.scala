package io.markko.worker.services

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.BeforeAndAfterEach
import io.prometheus.client.{CollectorRegistry, Counter}

class CircuitBreakerSpec extends AnyFlatSpec with Matchers with BeforeAndAfterEach {

  override def beforeEach(): Unit = {
    CollectorRegistry.defaultRegistry.clear()
  }

  private def createMetrics(): (Counter, Counter) = {
    val tripsTotal = Counter.build()
      .name("test_cb_trips_total")
      .help("Test circuit breaker trips")
      .register()
    val callsRejected = Counter.build()
      .name("test_cb_rejected_total")
      .help("Test circuit breaker rejections")
      .register()
    (tripsTotal, callsRejected)
  }

  private def makeCb(maxFailures: Int = 5, resetTimeoutMs: Long = 30000): CircuitBreaker = {
    val (trips, rejected) = createMetrics()
    new CircuitBreaker(maxFailures = maxFailures, resetTimeoutMs = resetTimeoutMs, tripsTotal = trips, callsRejected = rejected)
  }

  "CircuitBreaker" should "start in Closed state" in {
    val cb = makeCb(maxFailures = 3, resetTimeoutMs = 100)
    cb.currentState shouldBe cb.Closed
    cb.failures shouldBe 0
  }

  it should "allow calls in Closed state" in {
    val cb = makeCb(maxFailures = 3)
    val result = cb.protect { 42 }
    result.isSuccess shouldBe true
    result.get shouldBe 42
  }

  it should "track failures and trip to Open after maxFailures" in {
    val cb = makeCb(maxFailures = 3, resetTimeoutMs = 5000)

    cb.protect { throw new RuntimeException("fail 1") }
    cb.currentState shouldBe cb.Closed
    cb.protect { throw new RuntimeException("fail 2") }
    cb.currentState shouldBe cb.Closed

    cb.protect { throw new RuntimeException("fail 3") }
    cb.currentState shouldBe cb.Open
    cb.failures shouldBe 3
  }

  it should "reject calls when Open" in {
    val cb = makeCb(maxFailures = 1, resetTimeoutMs = 5000)

    cb.protect { throw new RuntimeException("fail") }
    cb.currentState shouldBe cb.Open

    val result = cb.protect { 42 }
    result.isFailure shouldBe true
    result.failed.get shouldBe a[CircuitBreakerOpenException]
  }

  it should "transition to HalfOpen after reset timeout" in {
    val cb = makeCb(maxFailures = 1, resetTimeoutMs = 50)

    cb.protect { throw new RuntimeException("fail") }
    cb.currentState shouldBe cb.Open

    Thread.sleep(100)

    val result = cb.protect { 42 }
    result.isSuccess shouldBe true
    cb.currentState shouldBe cb.Closed
  }

  it should "go back to Open if HalfOpen probe fails" in {
    val cb = makeCb(maxFailures = 1, resetTimeoutMs = 50)

    cb.protect { throw new RuntimeException("fail") }

    Thread.sleep(100)

    cb.protect { throw new RuntimeException("probe fail") }
    cb.currentState shouldBe cb.Open
  }

  it should "reset failure count on success" in {
    val cb = makeCb(maxFailures = 3)

    cb.protect { throw new RuntimeException("fail 1") }
    cb.protect { throw new RuntimeException("fail 2") }
    cb.failures shouldBe 2

    cb.protect { 42 }
    cb.failures shouldBe 0
  }
}
