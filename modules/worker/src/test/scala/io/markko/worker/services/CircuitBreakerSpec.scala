package io.markko.worker.services

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.BeforeAndAfterEach
import io.prometheus.client.CollectorRegistry

class CircuitBreakerSpec extends AnyFlatSpec with Matchers with BeforeAndAfterEach {

  override def beforeEach(): Unit = {
    // Clear Prometheus registry before each test to avoid conflicts
    CollectorRegistry.defaultRegistry.clear()
  }

  "CircuitBreaker" should "start in Closed state" in {
    val cb = new CircuitBreaker(maxFailures = 3, resetTimeoutMs = 100)
    cb.currentState shouldBe cb.Closed
    cb.failures shouldBe 0
  }

  it should "allow calls in Closed state" in {
    val cb = new CircuitBreaker(maxFailures = 3)
    val result = cb.protect { 42 }
    result.isSuccess shouldBe true
    result.get shouldBe 42
  }

  it should "track failures and trip to Open after maxFailures" in {
    val cb = new CircuitBreaker(maxFailures = 3, resetTimeoutMs = 5000)

    // First 2 failures: still Closed
    cb.protect { throw new RuntimeException("fail 1") }
    cb.currentState shouldBe cb.Closed
    cb.protect { throw new RuntimeException("fail 2") }
    cb.currentState shouldBe cb.Closed

    // 3rd failure: trips to Open
    cb.protect { throw new RuntimeException("fail 3") }
    cb.currentState shouldBe cb.Open
    cb.failures shouldBe 3
  }

  it should "reject calls when Open" in {
    val cb = new CircuitBreaker(maxFailures = 1, resetTimeoutMs = 5000)

    // Trip the breaker
    cb.protect { throw new RuntimeException("fail") }
    cb.currentState shouldBe cb.Open

    // Next call should be rejected
    val result = cb.protect { 42 }
    result.isFailure shouldBe true
    result.failed.get shouldBe a[CircuitBreakerOpenException]
  }

  it should "transition to HalfOpen after reset timeout" in {
    val cb = new CircuitBreaker(maxFailures = 1, resetTimeoutMs = 50)

    // Trip the breaker
    cb.protect { throw new RuntimeException("fail") }
    cb.currentState shouldBe cb.Open

    // Wait for reset timeout
    Thread.sleep(100)

    // Next call should succeed and close the breaker
    val result = cb.protect { 42 }
    result.isSuccess shouldBe true
    cb.currentState shouldBe cb.Closed
  }

  it should "go back to Open if HalfOpen probe fails" in {
    val cb = new CircuitBreaker(maxFailures = 1, resetTimeoutMs = 50)

    // Trip
    cb.protect { throw new RuntimeException("fail") }

    // Wait for half-open
    Thread.sleep(100)

    // Probe fails
    cb.protect { throw new RuntimeException("probe fail") }
    cb.currentState shouldBe cb.Open
  }

  it should "reset failure count on success" in {
    val cb = new CircuitBreaker(maxFailures = 3)

    cb.protect { throw new RuntimeException("fail 1") }
    cb.protect { throw new RuntimeException("fail 2") }
    cb.failures shouldBe 2

    // Success resets counter
    cb.protect { 42 }
    cb.failures shouldBe 0
  }
}
