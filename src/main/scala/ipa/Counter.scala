package ipa

import java.util.UUID

import com.datastax.driver.core.{BoundStatement, Row, ConsistencyLevel => CLevel}
import com.twitter.{util => tw}
import com.websudos.phantom.dsl._
import com.websudos.phantom.keys.PartitionKey
import ipa.thrift.{ReservationException, Table}
import owl.Conversions._
import owl.Util._
import owl._

import scala.concurrent.Future
import scala.concurrent.duration.FiniteDuration
import scala.util.{Failure, Success, Try}
import scala.language.higherKinds

object Counter {
  import Consistency._

  trait Ops {
    type IPAType[T] <: Inconsistent[T]

    def incr(key: UUID, by: Long): Future[Unit]

    def read(key: UUID): Future[IPAType[Long]]

  }

  trait WeakOps extends Ops { base: Counter =>
    type IPAType[T] = Inconsistent[T]
    override def read(key: UUID) =
      base.read(Weak)(key).map(Inconsistent(_))
    override def incr(key: UUID, by: Long) =
      base.incr(Strong)(key, by)
  }

  trait WeakWeakOps extends Ops { base: Counter =>
    type IPAType[T] = Inconsistent[T]
    override def read(key: UUID) =
      base.read(Weak)(key).map(Inconsistent(_))
    override def incr(key: UUID, by: Long) =
      base.incr(Weak)(key, by)
  }

  trait StrongOps extends Ops { base: Counter =>
    type IPAType[T] = Consistent[T]
    override def read(key: UUID): Future[Consistent[Long]] =
      base.read(Strong)(key) map { Consistent(_) }
    override def incr(key: UUID, by: Long): Future[Unit] =
      base.incr(Strong)(key, by)
  }

  trait LatencyBound extends Ops with RushImpl {
    base: Counter =>

    def bound: FiniteDuration

    type IPAType[T] = Rushed[T]

    override def read(key: UUID) =
      rush(bound){ c: CLevel => base.read(c)(key) }

    override def incr(key: UUID, by: Long) =
      base.incr(Strong)(key, by)
  }

  trait ErrorTolerance extends Ops {
    base: Counter =>

    def tolerance: Tolerance

    override def meta = Metadata(Some(tolerance))

    override def create(): Future[Unit] = {
      createTwitter() flatMap { _ =>
        reservations.client.createCounter(table, tolerance.error)
      } asScala
    }

    type IPAType[T] = Interval[T]
    
    override def incr(key: UUID, by: Long): Future[Unit] = {
      reservations.client.incr(table, key.toString, by).asScala
    }

    override def read(key: UUID): Future[Interval[Long]] = {
      reservations.client.readInterval(table, key.toString)
          .map(v => v: Interval[Long])
          .asScala
    }
  }

  def fromBound(bound: Bound)(implicit imps: CommonImplicits) = bound match {
    case Latency(l) =>
      new Counter("raw") with Counter.LatencyBound { override val bound = l }

    case Consistency(Weak, Weak) =>
      new Counter("raw") with Counter.WeakWeakOps

    case Consistency(Weak, Strong) =>
      new Counter("raw") with Counter.WeakOps

    case Consistency(Strong, _) =>
      new Counter("raw") with Counter.StrongOps

    case t @ Tolerance(_) =>
      new Counter("raw") with Counter.ErrorTolerance { override val tolerance = t }

    case e =>
      println("error parsing bound")
      sys.error(s"impossible case: $e")
  }

  def fromName(name: String)(implicit imps: CommonImplicits): Try[Counter] = {
    DataType.lookupMetadata(name) flatMap { metaStr =>
      val meta = Metadata.fromString(metaStr)
      meta.bound match {
        case Some(bound) =>
          Success(Counter.fromBound(bound))
        case _ =>
          Failure(ReservationException(s"Unable to find metadata for $name"))
      }
    } recoverWith {
      case e: Throwable =>
        Failure(ReservationException(s"metadata not found for $name"))
    }
  }
}

class Counter(val name: String)(implicit imps: CommonImplicits) extends DataType(imps) {
  self: Counter.Ops =>

  case class Count(key: UUID, count: Long)

  class CountTable extends CassandraTable[CountTable, Count] {
    object key extends UUIDColumn(this) with PartitionKey[UUID]
    object value extends CounterColumn(this)

    override val tableName = name
    override def fromRow(r: Row) = Count(key(r), value(r))
  }

  val tbl = new CountTable

  def createTwitter(): tw.Future[Unit] =
    DataType.createWithMetadata(name, tbl, meta.toString)

  override def create(): Future[Unit] =
    createTwitter().asScala

  override def truncate(): Future[Unit] =
    tbl.truncate.future().unit

  class Handle(key: UUID) {
    def incr(by: Long = 1L): Future[Unit] = self.incr(key, by)
    def read(): Future[IPAType[Long]] = self.read(key)
  }
  def apply(key: UUID) = new Handle(key)

  object prepared {
    private val (k, v, t) = (tbl.key.name, tbl.value.name, s"${space.name}.$name")

    lazy val read: (UUID) => (CLevel) => BoundStatement = {
      val ps = session.prepare(s"SELECT $v FROM $t WHERE $k = ?")
      key: UUID => ps.bindWith(key)
    }

    lazy val incr: (UUID, Long) => (CLevel) => BoundStatement = {
      val ps = session.prepare(s"UPDATE $t SET $v = $v + ? WHERE $k = ?")
      (key: UUID, by: Long) => ps.bindWith(by, key)
    }
  }

  private def result(rs: ResultSet): Long = {
    Option(rs.one()).map(tbl.value(_)).getOrElse(0L)
  }

  def read(c: CLevel)(key: UUID) =
    prepared.read(key)(c).execAsScala().instrument().map(result)

  def readTwitter(c: CLevel)(key: UUID): tw.Future[Long] = {
    prepared.read(key)(c).execAsTwitter().instrument().map(result)
  }

  def incr(c: CLevel)(key: UUID, by: Long) =
    prepared.incr(key,by)(c).execAsScala().instrument().unit

  def incrTwitter(c: CLevel)(key: UUID, by: Long): tw.Future[Unit] =
    prepared.incr(key, by)(c).execAsTwitter().instrument().unit

}
