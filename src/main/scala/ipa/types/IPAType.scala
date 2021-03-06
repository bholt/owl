package ipa.types

import com.datastax.driver.core.{ConsistencyLevel => CLevel}
import com.twitter.{util => tw}
import org.joda.time.DateTime

import scala.concurrent.duration.{Duration, FiniteDuration}
import scala.concurrent.{ExecutionContext, Future}
import scala.language.implicitConversions
import scala.math.Ordering.Implicits._
import scala.util.Try

import ipa.Connector.config
import ipa.thrift

/** Define ordering over consistency levels */
trait ConsistencyOrder extends Ordering[CLevel] {
  def compare(a: CLevel, b: CLevel) = a.compareTo(b)
}

object Consistency {
  val Strong = CLevel.QUORUM
  val Weak = CLevel.ONE
}

case class Timestamped[T](value: T, time: Long = System.nanoTime) {
  def expired: Boolean = (System.nanoTime - time) > config.lease.periodNanos
  def get: Option[T] = if (!expired) Some(value) else None
}


sealed trait Bound
final case class Latency(d: FiniteDuration) extends Bound {
  override def toString = s"latency:${d.toMillis}ms"
}
final case class Consistency(read: CLevel, write: CLevel) extends Bound {
  import Consistency._
  override def toString = "consistency:" + {
    import CLevel._
    (read, write) match {
      case (ONE, ONE) | (LOCAL_ONE, LOCAL_ONE) => "weakwrite"
      case (ONE, QUORUM) | (LOCAL_ONE, QUORUM) => "weak"
      case (QUORUM, _) => "strong"
      case _ => super.toString
    }
  }
  def isStrong: Boolean = read == Strong && write == Strong
}
final case class Tolerance(error: Double) extends Bound {
  def delta(value: Long) = (value * error).toLong
  def delta(value: Int) = (value * error).toInt
  override def toString = s"tolerance:$error"
}

object Bound {
  import Consistency._
  def fromString(str: String): Bound = {
    Try {
      val split = str.split(":")
      split(0) match {
        case "latency" => Latency(Duration(split(1)).asInstanceOf[FiniteDuration])
        case "consistency" =>
          split(1) match {
            case "weak"   => Consistency(Weak, Strong)
            case "weakwrite" => Consistency(Weak, Weak)
            case "weakest" => Consistency(Weak, Weak)
            case "strong" => Consistency(Strong, Strong)
          }
        case "tolerance" => Tolerance(split(1).toDouble)
        case _ => throw new RuntimeException(s"invalid bound: ${split(0)}:${split(1)}")
      }
    } recover {
      case e: Throwable =>
        Console.err.println(s"error parsing bound '$str': ${e.getMessage}")
        e.printStackTrace()
        sys.exit(1)
    } get
  }
}

class IPAType {}

class Inconsistent[T](value: T) extends IPAType {
  def consistency = Consistency.Weak
  /** get the value anyway (should we call it 'endorse'?)*/
  def get: T = value
  override def toString = s"Inconsistent($get)"
  def map[B](f: T => B): Inconsistent[B] = new Inconsistent(f(value))
}
object Inconsistent { def apply[T](value: T) = new Inconsistent(value) }


case class Consistent[T](value: T) extends Inconsistent[T](value) {
  override def consistency = Consistency.Strong
  override def toString = s"Consistent($value)"
}


class Transient[T](value: T) extends Inconsistent[T](value) {
  /** wait for it to become consistent */
  def waitUntilConsistent(): T = {
    // TODO: implement me
    value
  }
}


class Rushed[T](value: T, cons: CLevel)
    extends Inconsistent[T](value) with Ordered[Rushed[T]]
{
  override def consistency = cons
  def compare(o: Rushed[T]) = { this.consistency compareTo o.consistency }
  override def toString = s"Rushed($value, $consistency)"
  override def map[B](f: T => B) = new Rushed(f(value), cons)
}

object Rushed {
  def apply[T](value: T, c: CLevel) = new Rushed(value, c)
}


class Stale[T](
    value: T,
    override val consistency: CLevel,
    val time: DateTime
) extends Rushed[T](value, consistency)



case class Interval[T](min: T, max: T)(implicit ev: Numeric[T]) extends Inconsistent[T](min) {
  override def get = ev.fromInt(median.toInt)
  def median: Double = { (ev.toDouble(min) + ev.toDouble(max)) / 2 }
  def contains(o: T): Boolean = { o >= min && o <= max }
  override def toString = s"Interval($min..$max)"
}

object Conversions {
  implicit def thriftIntervalLongToNative(v: thrift.IntervalLong): Interval[Long] =
    Interval[Long](v.min, v.max)

  implicit def nativeIntervalToThriftLong(v: Interval[Long]): thrift.IntervalLong =
    thrift.IntervalLong(v.min, v.max)

  implicit def nativeIntervalIntToThriftLong(v: Interval[Int]): thrift.IntervalLong =
    thrift.IntervalLong(v.min, v.max)

  implicit def thriftTwFutureToNative[A, B](f: tw.Future[A])(implicit ev: A => B): tw.Future[B] = f map { v => v: B }

  implicit def thriftFutureToNative[A, B](f: Future[A])(implicit ev: A => B, ec: ExecutionContext): Future[B] = f map { v => v: B }

  implicit def consistentValueToValue[T](c: Consistent[T]): T = c.get
  implicit def valueToConsistentValue[T](v: T): Consistent[T] = Consistent(v)
}
