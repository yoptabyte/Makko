package io.markko.worker.services

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.BeforeAndAfterEach
import io.prometheus.client.{CollectorRegistry, Counter}

import scala.util.Success

class RetryWithBackoffSpec extends AnyFlatSpec with Matchers with BeforeAndAfterEach {

  override def beforeEach(): Unit = {
    CollectorRegistry.defaultRegistry.clear()
  }

  private def makeCb(maxFailures: Int = 5, resetTimeoutMs: Long = 30000): CircuitBreaker = {
    val tripsTotal = Counter.build()
      .name("test_retry_cb_trips_total")
      .help("Test")
      .register()
    val callsRejected = Counter.build()
      .name("test_retry_cb_rejected_total")
      .help("Test")
      .register()
    new CircuitBreaker(maxFailures = maxFailures, resetTimeoutMs = resetTimeoutMs, tripsTotal = tripsTotal, callsRejected = callsRejected)
  }

  "RetryWithBackoff" should "succeed on first attempt without retrying" in {
    var attempts = 0
    val result = RetryWithBackoff.execute(maxRetries = 3, baseDelayMs = 10, label = "test") {
      attempts += 1
      42
    }
    result shouldBe Success(42)
    attempts shouldBe 1
  }

  it should "retry on failure and eventually succeed" in {
    var attempts = 0
    val result = RetryWithBackoff.execute(maxRetries = 3, baseDelayMs = 10, label = "test") {
      attempts += 1
      if (attempts < 3) throw new RuntimeException(s"fail $attempts")
      99
    }
    result shouldBe Success(99)
    attempts shouldBe 3
  }

  it should "fail after exhausting all retries" in {
    var attempts = 0
    val result = RetryWithBackoff.execute(maxRetries = 2, baseDelayMs = 10, label = "test") {
      attempts += 1
      throw new RuntimeException("always fails")
    }
    result.isFailure shouldBe true
    attempts shouldBe 3 // initial + 2 retries
  }

  it should "respect maxRetries = 0 (no retries)" in {
    var attempts = 0
    val result = RetryWithBackoff.execute(maxRetries = 0, baseDelayMs = 10, label = "test") {
      attempts += 1
      throw new RuntimeException("fail")
    }
    result.isFailure shouldBe true
    attempts shouldBe 1
  }

  it should "fail fast when circuit breaker is open" in {
    val cb = makeCb(maxFailures = 1, resetTimeoutMs = 60000)
    // Trip the breaker
    cb.protect { throw new RuntimeException("trip") }
    cb.currentState shouldBe cb.Open

    var attempts = 0
    val result = RetryWithBackoff.executeWithBreaker(
      cb, maxRetries = 3, baseDelayMs = 10, label = "test"
    ) {
      attempts += 1
      42
    }
    result.isFailure shouldBe true
    result.failed.get shouldBe a[CircuitBreakerOpenException]
    attempts shouldBe 0 // block never executed
  }
}
