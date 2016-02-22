package ipa

import java.util.UUID

import com.datastax.driver.core.{Row, ConsistencyLevel => CLevel}
import com.websudos.phantom.dsl._
import com.websudos.phantom.keys.PartitionKey
import nl.grons.metrics.scala.Timer
import owl._

import scala.concurrent.Future
import owl.Util._

import scala.concurrent.duration.FiniteDuration

object Counter {

  object Ops {

    trait Incr {
      def incr(key: UUID, by: Long): Future[Unit]
    }

    trait Read {
      type ReadType

      def read(key: UUID): Future[ReadType]
    }

  }

  trait WeakOps extends Ops.Incr with Ops.Read { base: Counter =>
    override def incr(key: UUID, by: Long) = base.incr(CLevel.ONE)(key, by)

    type ReadType = Inconsistent[Long]
    override def read(key: UUID) = base.read(CLevel.ONE)(key)
  }

  trait StrongOps extends Ops.Incr with Ops.Read { base: Counter =>
    override def incr(key: UUID, by: Long) = base.incr(CLevel.ALL)(key, by)

    type ReadType = Long
    override def read(key: UUID) = base.read(CLevel.ALL)(key).map(_.get)
  }

  trait LatencyBound extends Ops.Incr with Ops.Read with RushImpl {
    base: Counter =>

    def bound: FiniteDuration

    type ReadType = Rushed[Long]

    override def read(key: UUID) =
      rush(bound){ c: CLevel => base.read(c)(key) }

    override def incr(key: UUID, by: Long) =
      base.incr(CLevel.ONE)(key, by)
  }

  trait ErrorTolerance extends Ops.Incr with Ops.Read {
    def tolerance: Tolerance

    type ReadType = Interval[Long]
    override def incr(key: UUID, by: Long) = ???
    override def read(key: UUID) = ???
  }

}

class Counter(val name: String)(implicit imps: CommonImplicits) extends DataType(imps) {
  self: Counter.Ops.Incr with Counter.Ops.Read =>

  case class Count(key: UUID, count: Long)
  class CountTable extends CassandraTable[CountTable, Count] {
    object ekey extends UUIDColumn(this) with PartitionKey[UUID]
    object ecount extends CounterColumn(this)
    override val tableName = name
    override def fromRow(r: Row) = Count(ekey(r), ecount(r))
  }

  val tbl = new CountTable

  override def create(): Future[Unit] =
    tbl.create.ifNotExists.future().unit

  override def truncate(): Future[Unit] =
    tbl.truncate.future().unit

  class Handle(key: UUID) {
    def incr(by: Long = 1L): Future[Unit] = self.incr(key, by)
    def read(): Future[ReadType] = self.read(key)
  }
  def apply(key: UUID) = new Handle(key)


  def incr(c: CLevel)(key: UUID, by: Long): Future[Unit] = {
    tbl.update()
        .consistencyLevel_=(c)
        .where(_.ekey eqs key)
        .modify(_.ecount += by)
        .future()
        .instrument()
        .unit
  }

  def read(c: CLevel)(key: UUID): Future[Inconsistent[Long]] = {
    tbl.select(_.ecount)
        .consistencyLevel_=(c)
        .where(_.ekey eqs key)
        .one()
        .map(o => Inconsistent(o.getOrElse(0L)))
        .instrument()
  }

}
