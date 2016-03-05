package owl

import com.datastax.driver.core.ConsistencyLevel
import com.websudos.phantom.connectors.KeySpace
import com.websudos.phantom.dsl._
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.{Inspectors, Matchers, BeforeAndAfterAll, WordSpec}

import scala.concurrent.duration._

import Util._
import Conversions._
import ipa.Set

class IPASetTests extends {
  override implicit val space = KeySpace("ipa_set_tests")
} with WordSpec with OwlService with BeforeAndAfterAll
    with Matchers with Inspectors with ScalaFutures {

  def now() = Deadline.now

  val timeout = 2 seconds
  implicit override val patienceConfig: PatienceConfig =
    PatienceConfig(timeout = timeout, interval = 20 millis)

  println(s"create keyspace ${space.name} in beforeAll")
  createKeyspace(session)

  val u1 = id(1)
  val u2 = id(2)
  val u3 = id(3)

  def test_generic(s: Set[UUID]): Unit = {

    "be created" in {
      s.create().await()
    }

    "add items" in {
      s(u1).add(u2).await(timeout)
      s(u1).add(u3).await(timeout)
    }

    "contain added items" in {
      assert(s(u1).contains(u2).futureValue.get)
      assert(s(u1).contains(u3).futureValue.get)
    }
    
    "size reflect added items" in {
      s(u1).size().futureValue.get shouldBe 2
    }

    "remove items that exist" in {
      s(u1).remove(u2).await(timeout)
      s(u1).size().futureValue.get shouldBe 1
    }

    "remove items that don't exist" in {
      s(u1).remove(u1).await(timeout)
      s(u1).size().futureValue.get shouldBe 1
    }

    "be empty initially" in {
      s(u2).contains(u1).futureValue.get shouldBe false
      s(u2).size().futureValue.get shouldBe 0
    }

  }

  "IPA Set with Weak ops" should {
    val s = new Set[UUID]("s_weak") with Set.WeakOps[UUID]

    test_generic(s)

  }

  "IPA Set with Strong ops" should {
    val s = new Set[UUID]("s_strong") with Set.StrongOps[UUID]

    test_generic(s)

    "handle implicit conversion" in {
      // verify that implicit conversion works
      val r: Consistent[Boolean] = s(u1).contains(u3).await(timeout)
      r shouldBe Consistent(true)
      val v: Boolean = r
      v shouldBe true
    }
  }

  "IPASet with LatencyBound" should {

    val s = new Set[UUID]("s_rush")
        with Set.LatencyBound[UUID] { override val bound = 100 millis }

    test_generic(s)

    "return rushed type" in {
      val r: Rushed[Boolean] = s(u1).contains(u3).await(timeout)
      r.get shouldBe true
      println(s"Set.contains @ ${r.consistency}")
    }

  }

  "Quick set" should {

    val s = new Set[UUID]("quick")
        with Set.LatencyBound[UUID] { override val bound = 1 millis }

    test_generic(s)

  }


  "Sloth set" should {

    val s = new Set[UUID]("sloth")
        with Set.LatencyBound[UUID] { override val bound = 2 seconds }

    test_generic(s)

  }

  "Hasty set" should {

    val s = new Set[UUID]("hasty")
        with Set.LatencyBound[UUID] { override val bound = 10 microseconds }

    "be created" in {
      s.create().await()
    }

    "allow adding" in {
      Seq( s(u1).add(u2), s(u1).add(u3) )
          .bundle
          .await()
    }

    "miss its deadline and get weak consistency" ignore {
      val t = now()
      val r = s(u1).size().await(timeout)
      t.elapsed should be > s.bound
      println(s"hasty: u1.size => $r")
      r.consistency shouldBe ConsistencyLevel.ONE
    }

  }

}
