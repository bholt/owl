# Scratch notes

### Manual commands to paste into Scala console
~~~scala
import owl._
import Util._
import com.websudos.phantom.dsl._
object ReplService extends OwlService
import ReplService._

val arthur = User(username = "tealuver", name = "Arthur Dent")
val ford = User(username = "hastowel", name = "Ford Prefect")
val zaphod = User(username = "froodyprez", name = "Zaphod Beeblebrox")

val t1 = Tweet(user = arthur.id, body = "Nutri-matic drinks are the worst. #fml")
val t2 = Tweet(user = zaphod.id, body = "Things more important than my ego: none")

service.createTables

implicit val consistency = ConsistencyLevel.ALL

await(Future.sequence(List(arthur, ford, zaphod) map (service.store(_))))

await(service.follow(arthur.id, zaphod.id))
await(service.follow(ford.id, zaphod.id))
await(service.follow(ford.id, arthur.id))

await(service.post(t1))
await(service.post(t2))
~~~

### SQL commands
~~~sql
CREATE KEYSPACE IF NOT EXISTS owl WITH replication = {'class': 'SimpleStrategy', 'replication_factor': 3};
CREATE TABLE users (id int PRIMARY KEY, username text, name text);
INSERT INTO users(id,username,name) VALUES (42, 'tealover42', 'Arthur Dent');
~~~