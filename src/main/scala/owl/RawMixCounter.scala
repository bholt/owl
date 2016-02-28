package owl

import com.websudos.phantom.connectors.KeySpace
import com.websudos.phantom.dsl._
import org.apache.commons.math3.distribution.ZipfDistribution

import scala.concurrent._
import scala.concurrent.duration._
import Util._
import java.util.concurrent.Semaphore

import ipa.Counter

import scala.util.Random

class RawMixCounter(val duration: FiniteDuration) extends {
  override implicit val space = RawMix.space
} with OwlService {
  import Consistency._

  val nsets = config.rawmix.nsets
  val mix = config.rawmix.counter.mix

  val zipfDist = new ZipfDistribution(nsets, config.zipf)

  def zipfID() = id(zipfDist.sample())
  def urandID() = id(Random.nextInt(nsets))

  val counter = config.bound match {
    case Latency(l) =>
      new Counter("raw") with Counter.LatencyBound { override val bound = l }

    case Consistency(Weak, Weak) =>
      new Counter("raw") with Counter.WeakOps

    case Consistency(Strong, Weak) =>
      new Counter("raw") with Counter.StrongRead

    case Consistency(Weak, Strong) =>
      new Counter("raw") with Counter.StrongWrite

    case t @ Tolerance(_) =>
      new Counter("raw") with Counter.ErrorTolerance { override val tolerance = t }

    case e =>
      println("error parsing bound")
      sys.error(s"impossible case: $e")
  }

  val timerIncr      = metrics.create.timer("incr_latency")
  val timerRead = metrics.create.timer("read_latency")

  val countReadStrong = metrics.create.counter("read_strong")
  val countReadWeak   = metrics.create.counter("read_weak")

  val countConsistent = metrics.create.counter("consistent")
  val countInconsistent = metrics.create.counter("inconsistent")

  val histIntervalWidth = metrics.create.histogram("interval_width")

  def recordResult(r: Any): Inconsistent[Long] = {
    val cons = counter match {
      case _: Counter.ErrorTolerance =>
        val iv = r.asInstanceOf[Interval[Long]]
        val width = iv.max - iv.min
        histIntervalWidth << width
        Weak // reads always weak
      case _: Counter.LatencyBound =>
        r.asInstanceOf[Rushed[Long]].consistency
      case _: Counter.WeakOps =>
        Consistency.Weak
      case _: Counter.StrongWrite =>
        Consistency.Weak
      case _: Counter.StrongRead =>
        Consistency.Strong
      case _ =>
        sys.error("datatype didn't match any of the options")
    }
    cons match {
      case Strong => countReadStrong += 1
      case Weak   => countReadWeak += 1
      case _ => // do nothing
    }
    r.asInstanceOf[Inconsistent[Long]]
  }

  def run(truncate: Boolean = false) {

    counter.create().await()
    if (truncate) counter.truncate().await()

    val actualDurationStart = Deadline.now
    val deadline = duration.fromNow
    val sem = new Semaphore(config.concurrent_reqs)

    while (deadline.hasTimeLeft) {
      sem.acquire()
      val handle = counter(zipfID())
      val op = weightedSample(mix)
      val f = op match {
        case 'incr =>
          handle.incr().instrument(timerIncr)
        case 'read =>
          handle.read().instrument(timerRead).map(recordResult(_)).unit
      }
      f onSuccess { case _ => sem.release() }
      f onFailure { case e: Throwable =>
        Console.err.println(e.getMessage)
        sys.exit(1)
      }
    }

    val actualTime = actualDurationStart.elapsed
    output += ("actual_time" -> actualTime)
    println(s"# Done in ${actualTime.toSeconds}.${actualTime.toMillis%1000}s")
  }

}

object RawMixCounter extends {
  override implicit val space = KeySpace("rawmix")
} with Connector {

  def main(args: Array[String]): Unit = {
    if (config.do_reset) dropKeyspace()
    createKeyspace()

    val warmup = new RawMixCounter(5 seconds)
    println(s">>> warmup (${warmup.duration})")
    warmup.run(truncate = true)

    reservations.all map { _.metricsReset() } bundle() await()

    val workload = new RawMixCounter(config.duration)
    println(s">>> workload (${workload.duration})")
    workload.run()
    workload.metrics.dump()

    sys.exit()
  }

}
