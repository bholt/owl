package owl

import java.util.UUID

import com.codahale.metrics.MetricRegistry
import com.datastax.driver.core.{ConsistencyLevel, Row}
import com.datastax.driver.core.utils.UUIDs
import com.websudos.phantom.builder.primitives.Primitive
import com.websudos.phantom.{dsl, CassandraTable}
import com.websudos.phantom.column.DateTimeColumn
import com.websudos.phantom.dsl.{StringColumn, UUIDColumn}
import com.websudos.phantom.keys.PartitionKey
import nl.grons.metrics.scala.{Timer, FutureMetrics, InstrumentedBuilder}
import org.joda.time.DateTime

import com.websudos.phantom.dsl._

import scala.collection.immutable.IndexedSeq
import scala.concurrent.{Future, blocking}
import scala.concurrent.duration._
import scala.util.{Failure, Success, Try}
import scala.collection.JavaConversions._

import org.apache.commons.math3.distribution.ZipfDistribution

// for Vector.sample
import Util._

//////////////////
// User
case class User(
    id: UUID = UUID.randomUUID(),
    username: String,
    name: String,
    created: DateTime = DateTime.now()
)

object User {
  def id(i: Int) = UUID.nameUUIDFromBytes(BigInt(i).toByteArray)
}

class Users extends CassandraTable[Users, User] {
  object id extends UUIDColumn(this) with PartitionKey[UUID]
  object username extends StringColumn(this)
  object name extends StringColumn(this)
  object created extends DateTimeColumn(this)

  override val tableName = "users"
  override def fromRow(r: Row) = User(id(r), username(r), name(r), created(r))
}

//////////////////
// Followers
case class Follower(user: UUID, follower: UUID)
case class Following(user: UUID, followee: UUID)

class Followers extends CassandraTable[Followers, Follower] {
  object user extends UUIDColumn(this) with PartitionKey[UUID]
  object follower extends UUIDColumn(this) with PrimaryKey[UUID]
  override val tableName = "followers"
  override def fromRow(r: Row) = Follower(user(r), follower(r))
}

class Followees extends CassandraTable[Followees, Following] {
  object user extends UUIDColumn(this) with PartitionKey[UUID]
  object following extends UUIDColumn(this) with PrimaryKey[UUID]
  override val tableName = "followees"
  override def fromRow(r: Row) = Following(user(r), following(r))
}


//////////////////
// Tweets
case class Tweet(
  id: UUID = UUID.randomUUID(),
  user: UUID,
  body: String,
  created: DateTime = DateTime.now(),
  retweets: Long = 0,
  name: Option[String] = None
) {
  override def toString = {
    s"${name.getOrElse(user)}: $body [$created]"
  }
}

/** For displaying tweets (includes user's name, etc). */
case class DisplayTweet(tweet: Tweet, user: User) {
  override def toString = {
    s"${user.name}: ${tweet.body} [${tweet.created}]"
  }
}

class Tweets extends CassandraTable[Tweets, Tweet] {
  object id extends UUIDColumn(this) with PartitionKey[UUID]
  object user extends UUIDColumn(this)
  object body extends StringColumn(this)
  object created extends DateTimeColumn(this)
  override val tableName = "tweets"
  override def fromRow(r: Row) = Tweet(id(r), user(r), body(r), created(r))
}

case class TimelineEntry(user: UUID, tweet: UUID)
class Timelines extends CassandraTable[Timelines, TimelineEntry] {
  object user extends UUIDColumn(this) with PartitionKey[UUID]
  object tweet extends UUIDColumn(this) with PrimaryKey[UUID]
  override val tableName = "timelines"
  override def fromRow(r: Row) = TimelineEntry(user(r), tweet(r))
}

case class Retweet(tweet: UUID, retweeter: UUID)
class Retweets extends CassandraTable[Retweets, Retweet] {
  object tweet extends UUIDColumn(this) with PartitionKey[UUID]
  object retweeter extends UUIDColumn(this) with PrimaryKey[UUID]
  override val tableName = "retweets"
  override def fromRow(r: Row) = Retweet(tweet(r), retweeter(r))
}

case class RetweetCount(tweet: UUID, count: Long)
class RetweetCounts extends CassandraTable[RetweetCounts, RetweetCount] {
  object tweet extends UUIDColumn(this) with PartitionKey[UUID]
  object count extends CounterColumn(this)
  override val tableName = "retweetCounts"
  override def fromRow(r: Row) = RetweetCount(tweet(r), count(r))
}

trait OwlService extends Connector with InstrumentedBuilder with FutureMetrics {

  override val metricRegistry = new MetricRegistry

  object metric {
    val cassandraOpLatency = metrics.timer("cassandraOpLatency")
    val retwisOps = metrics.meter("retwisOps")
  }
  implicit class InstrumentedFuture[T](f: Future[T]) {
    def instrument(): Future[T] = {
      val ctx = metric.cassandraOpLatency.timerContext()
      f onComplete { _ => ctx.stop() }
      f
    }
  }

  val users = new Users
  val followers = new Followers
  val followees = new Followees
  val tweets = new Tweets
  val timelines = new Timelines
  val retweets = new Retweets
  val retweetCounts = new RetweetCounts

  class IPASet[K, V](name: String)(implicit evK: Primitive[K], evV: Primitive[V]) {

    case class Entry(key: K, value: V)
    class EntryTable extends CassandraTable[EntryTable, Entry] {
      object ekey extends PrimitiveColumn[EntryTable, Entry, K](this) with PartitionKey[K]
      object evalue extends PrimitiveColumn[EntryTable, Entry, V](this) with PrimaryKey[V]
      override val tableName = name
      override def fromRow(r: Row) = Entry(ekey(r), evalue(r))
    }

    case class Count(key: K, count: Long)
    class CountTable extends CassandraTable[CountTable, Count] {
      object ekey extends PrimitiveColumn[CountTable, Count, K](this) with PartitionKey[K]
      object ecount extends CounterColumn(this)
      override val tableName = name + "Count"
      override def fromRow(r: Row) = Count(ekey(r), ecount(r))
    }

    val entryTable = new EntryTable
    val countTable = new CountTable

    class Handle(key: K)(implicit consistency: ConsistencyLevel) {

      def contains(value: V): Future[Boolean] = {
        entryTable.select(_.evalue)
            .consistencyLevel_=(consistency)
            .where(_.ekey eqs key)
            .and(_.evalue eqs value)
            .one()
            .instrument()
            .map(_.isDefined)
      }

      def add(value: V): Future[Boolean] = {
        this.contains(value) flatMap { dup =>
          if (dup) Future { false }
          else {
            for {
              _ <- countTable.update()
                  .consistencyLevel_=(consistency)
                  .where(_.ekey eqs key)
                  .modify(_.ecount += 1)
                  .future()
                  .instrument()
              _ <- entryTable.insert()
                  .consistencyLevel_=(consistency)
                  .value(_.ekey, key)
                  .value(_.evalue, value)
                  .future()
                  .instrument()
            } yield true
          }
        }
      }

      def size(): Future[Long] = {
        countTable.select(_.ecount)
            .consistencyLevel_=(consistency)
            .where(_.ekey eqs key)
            .one()
            .map(opt => opt.getOrElse(0l))
            .instrument()
      }
    }

//    def apply()
  }

  object IPASet {
    def apply[K, V](name: String)(implicit evK: Primitive[K], evV: Primitive[V]) = {
      new IPASet[K, V](name)
    }
  }

  class RetweetSet(tweet: UUID)(implicit consistency: ConsistencyLevel) {

    def contains(user: UUID): Future[Boolean] = {
      retweets.select(_.retweeter)
          .consistencyLevel_=(consistency)
          .where(_.tweet eqs tweet)
          .and(_.retweeter eqs user)
          .one()
          .instrument()
          .map(_.isDefined)
    }

    def add(retweeter: UUID): Future[Boolean] = {
      this.contains(retweeter) flatMap { dup =>
        if (dup) Future { false }
        else {
          for {
            _ <- retweetCounts.update()
                .consistencyLevel_=(consistency)
                .where(_.tweet eqs tweet)
                .modify(_.count += 1)
                .future()
                .instrument()
            _ <- retweets.insert()
                .consistencyLevel_=(consistency)
                .value(_.tweet, tweet)
                .value(_.retweeter, retweeter)
                .future()
                .instrument()
          } yield true
        }
      }
    }

    def size(): Future[Long] = {
      retweetCounts.select(_.count)
          .consistencyLevel_=(consistency)
          .where(_.tweet eqs tweet)
          .one()
          .map(opt => opt.getOrElse(0l))
          .instrument()
    }
  }

  object RetweetSet {
    implicit val consistency = configConsistency()

    def apply(tweet: UUID) = new RetweetSet(tweet)
  }


  object service {

    val tables = List(users, followers, followees, tweets, timelines,
      retweets, retweetCounts)

    val FIRST_NAMES = Vector("Arthur", "Ford", "Tricia", "Zaphod")
    val LAST_NAMES = Vector("Dent", "Prefect", "McMillan", "Beeblebrox")

    def createTables(): Unit = {
      Future.sequence(
        tables.map(_.create.ifNotExists().future())
      ).await()
    }

    def cleanupTables(): Unit = {
       tables.map(_.tableName)
             .foreach(t => session.execute(s"DROP TABLE IF EXISTS $t"))
    }

    def resetKeyspace(): Unit = {
      val tmpSession = blocking { cluster.connect() }
      blocking { tmpSession.execute(s"DROP KEYSPACE IF EXISTS ${space.name}") }
      createKeyspace(tmpSession)
    }

    def randomUser(
      id: UUID = UUIDs.timeBased(),
      username: String = "",
      name: String = s"${FIRST_NAMES.sample} ${LAST_NAMES.sample}",
      time: DateTime = DateTime.now()
    ): User = {
      val uname = if (username.isEmpty) s"${id.hashCode()}" else username
      User(id, uname, name, time)
    }

    def store(user: User)(implicit consistency: ConsistencyLevel): Future[UUID] = {
      for {
        rs <- users.insert()
                   .consistencyLevel_=(consistency)
                   .value(_.id, user.id)
                   .value(_.username, user.username)
                   .value(_.name, user.name)
                   .value(_.created, user.created)
                   .future()
                   .instrument()
      } yield user.id
    }

    def getUserById(id: UUID)(implicit consistency: ConsistencyLevel): Future[Option[User]] = {
      users.select
          .consistencyLevel_=(consistency)
          .where(_.id eqs id)
          .one()
          .instrument()
    }

    def delete(user: User)(implicit consistency: ConsistencyLevel): Future[ResultSet] = {
      users.delete
          .consistencyLevel_=(consistency)
          .where(_.id eqs user.id)
          .future()
          .instrument()
    }

    def follow(follower: UUID, followee: UUID)(implicit consistency: ConsistencyLevel): Future[Unit] = {
      for {
        r1 <- followers.insert()
            .consistencyLevel_=(consistency)
            .value(_.user, followee)
            .value(_.follower, follower)
            .future()
            .instrument()
        r2 <- followees.insert()
            .consistencyLevel_=(consistency)
            .value(_.user, follower)
            .value(_.following, followee)
            .future()
            .instrument()
      } yield ()
    }

    def unfollow(follower: UUID, followee: UUID)(implicit consistency: ConsistencyLevel): Future[Unit] = {
      for {
        _ <- followers.delete()
            .consistencyLevel_=(consistency)
            .where(_.user eqs followee)
            .and(_.follower eqs follower)
            .future()
            .instrument()
        _ <- followees.delete()
            .consistencyLevel_=(consistency)
            .where(_.user eqs follower)
            .and(_.following eqs followee)
            .future()
            .instrument()
      } yield ()
    }

    def followersOf(user: UUID, limit: Int = 0)(implicit consistency: ConsistencyLevel): Future[Iterator[UUID]] = {
      val q = followers
          .select
          .consistencyLevel_=(consistency)
          .where(_.user eqs user)

      val ql = if (limit > 0) q.limit(limit) else q

      ql.future().instrument().map { results =>
        results.iterator() map { row =>
          followers.fromRow(row).follower
        }
      }
    }

    private def add_to_followers_timelines(tweet: UUID, user: UUID)(implicit consistency: ConsistencyLevel): Future[Unit] = {
      followersOf(user) flatMap { followers =>
        Future.sequence(followers map { f =>
          timelines.insert()
              .consistencyLevel_=(consistency)
              .value(_.user, f)
              .value(_.tweet, tweet)
              .future()
              .instrument()
        }).map(_ => ())
      }
    }

    def post(t: Tweet)(implicit consistency: ConsistencyLevel): Future[UUID] = {
      for {
        _ <- tweets.insert()
                .consistencyLevel_=(consistency)
                .value(_.id, t.id)
                .value(_.user, t.user)
                .value(_.body, t.body)
                .value(_.created, t.created)
                .future()
                .instrument()
        _ <- add_to_followers_timelines(t.id, t.user)
      } yield t.id
    }

    def retweet(tweet: UUID, retweeter: UUID)(implicit consistency: ConsistencyLevel): Future[Option[UUID]] = {
      // equivalent to:
      // if (Retweets(tweet).add(retweeter)):
      //   for f in Followers(retweeter):
      //     Timeline(f).add(tweet)
      val f = for {
        added <- RetweetSet(tweet).add(retweeter)
        if added
        _ <- add_to_followers_timelines(tweet, retweeter)
      } yield {
        Some(tweet)
      }
      f recover { case _ => None }
    }

    def getTweet(id: UUID)(implicit consistency: ConsistencyLevel): Future[Option[Tweet]] = {
      tweets.select.where(_.id eqs id).one() flatMap {
        case Some(tweet) =>
          for {
            userOpt <- users.select
                .consistencyLevel_=(consistency)
                .where(_.id eqs tweet.user)
                .one()
                .instrument()
            ct <- RetweetSet(id).size()
          } yield for {
            u <- userOpt
          } yield {
            tweet.copy(retweets = ct, name = Some(u.name))
          }
        case None =>
          Future { None }
      }
    }

    def timeline(user: UUID, limit: Int)(implicit consistency: ConsistencyLevel) = {
      timelines.select
          .consistencyLevel_=(consistency)
          .where(_.user eqs user)
          .limit(limit)
          .future()
          .instrument()
          .flatMap { rs =>
            rs.iterator()
                .map(timelines.fromRow(_).tweet)
                .map(getTweet)
                .bundle
          }
          .map(_.flatten)
    }

  }
}
