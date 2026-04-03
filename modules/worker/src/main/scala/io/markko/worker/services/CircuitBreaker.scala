package io.markko.worker.services

import com.typesafe.scalalogging.LazyLogging

import java.util.concurrent.atomic.{AtomicInteger, AtomicLong, AtomicReference}
import scala.util.{Try, Success, Failure}

class CircuitBreaker(
  maxFailures:    Int = 5,
  resetTimeoutMs: Long = 30000,
  halfOpenMax:    Int = 1,
  tripsTotal:     io.prometheus.client.Counter,
  callsRejected:  io.prometheus.client.Counter
) extends LazyLogging {

  sealed trait State
  case object Closed   extends State
  case object Open     extends State
  case object HalfOpen extends State

  private val stateRef      = new AtomicReference[State](Closed)
  private val failureCount  = new AtomicInteger(0)
  private val lastFailureAt = new AtomicLong(0)
  private val halfOpenProbes = new AtomicInteger(0)

  def protect[T](block: => T): Try[T] = {
    stateRef.get() match {
      case Open =>
        if (System.currentTimeMillis() - lastFailureAt.get() > resetTimeoutMs) {
          if (stateRef.compareAndSet(Open, HalfOpen)) {
            logger.info("Circuit breaker transitioning to Half-Open")
            halfOpenProbes.set(0)
            tryCall(block)
          } else {
            callsRejected.inc()
            Failure(new CircuitBreakerOpenException("Circuit breaker state changed during transition"))
          }
        } else {
          callsRejected.inc()
          Failure(new CircuitBreakerOpenException(
            s"Circuit breaker is Open (${failureCount.get()} failures, " +
            s"resets in ${(resetTimeoutMs - (System.currentTimeMillis() - lastFailureAt.get())) / 1000}s)"
          ))
        }

      case HalfOpen =>
        if (halfOpenProbes.getAndIncrement() < halfOpenMax) {
          tryCall(block)
        } else {
          callsRejected.inc()
          Failure(new CircuitBreakerOpenException("Circuit breaker is Half-Open, max probes reached"))
        }

      case Closed =>
        tryCall(block)
    }
  }

  private def tryCall[T](block: => T): Try[T] = {
    Try(block) match {
      case s @ Success(_) =>
        onSuccess()
        s
      case f @ Failure(ex) =>
        onFailure(ex)
        f
    }
  }

  private def onSuccess(): Unit = {
    val current = stateRef.get()
    if (current == HalfOpen) {
      logger.info("Circuit breaker closing (successful probe)")
    }
    failureCount.set(0)
    stateRef.set(Closed)
  }

  private def onFailure(ex: Throwable): Unit = {
    val count = failureCount.incrementAndGet()
    lastFailureAt.set(System.currentTimeMillis())

    val current = stateRef.get()
    if (count >= maxFailures && current == Closed) {
      if (stateRef.compareAndSet(Closed, Open)) {
        tripsTotal.inc()
        logger.warn(s"Circuit breaker tripped to Open after $count failures: ${ex.getMessage}")
      }
    } else if (current == HalfOpen) {
      if (stateRef.compareAndSet(HalfOpen, Open)) {
        tripsTotal.inc()
        logger.warn(s"Circuit breaker back to Open (probe failed): ${ex.getMessage}")
      }
    }
  }

  def currentState: State = stateRef.get()
  def failures: Int = failureCount.get()
}

class CircuitBreakerOpenException(msg: String) extends RuntimeException(msg)
