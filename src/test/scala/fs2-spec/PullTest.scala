package fs2spec

import scala.concurrent.duration._
import cats.implicits._
import cats.effect.implicits._
import cats._
import cats.effect._
import fs2.Chunk
import cats.arrow.FunctionK
import cats.effect.kernel.CancelScope
import munit.CatsEffectSuite

class PullTest extends CatsEffectSuite {
  import Pull._

  def infSeq(n: Int): Stream2[IO, Int] =
    Stream2[IO, Int](n) ++ infSeq(n + 1)

  test("yoou") {
    val p =
      Stream2[IO, Int](1).repeatN(100000).chunkMin(100).evalMap(x => IO(x.size))

    IO.println("CPS") >>
      fs2
        .Stream(1)
        .covary[IO]
        .repeatN(100000)
        .chunkMin(100)
        .evalMapChunk(x => IO(x.size))
        .compile
        .drain
        .timed
        .flatMap { case (duration, _) =>
          IO(println(duration))
        } >> IO.println("closure") >> p.drain.timed.flatMap {
        case (duration, _) =>
          IO(println(duration))
      }
  }

  test("err") {
    val p = Stream2[IO, Int](1)
      .repeatN(3)
      .evalMap(i => IO.raiseError[Unit](new Exception("boom")))
      .map(_ => Option.empty[Throwable])
      .handleErrorWith(e => Stream2[IO, Option[Throwable]](Some(e)))

    p.foldMap(Chunk.empty[Option[Throwable]])((z, c) => z ++ c).map(println)
  }

  test("resource") {
    val p = Stream2.eval(IO.ref(0)).flatMap { ref =>
      Stream2
        .resource {
          Resource
            .make(
              ref.getAndUpdate(_ + 1).flatTap(n => IO.println(s"opening $n"))
            )(n => IO.println(s"closing $n"))
        }
        .subArc
        .repeatN(3)
        .flatMap { x =>
          Stream2
            .resource[IO, Int](
              Resource
                .make(IO.println(s"sub-resource for $x"))(_ =>
                  IO.println(s"sub-closing for $x")
                )
                .as(x)
            )
            .subArc
        }
        .evalMap(x => IO.println(s"using $x"))
    }

    p.drain.void
  }

  test("interruption") {
    IO.deferred[Unit].flatMap { d =>
      val p = infSeq(0)
        .evalMap(x => IO.sleep(100.millis) >> IO.println(x))
        .interruptWhen(d.get)

      (IO.sleep(500.millis) >> d.complete(()).void).background.surround {
        p.drain
      }
    }
  }

  test("resource with error") {
    IO.ref(0).flatMap { ref =>
      Stream2
        .resource(Resource.make(ref.update(_ + 1))(_ => ref.update(_ - 1)))
        .evalMap { _ =>
          IO.raiseError[Unit](new Exception("boom"))
        }
        .handleErrorWith(e => Stream2.eval(IO.println(e)))
        .drain
        .flatMap { _ =>
          ref.get.assertEquals(0)
        }
    }
  }

  test("transfer resource") {
    IO.deferred[Resource[IO, Boolean]]
  }

  test("ttest") {
    var n = 0
    fs2.Stream.unit
      .covary[IO]
      .pull
      .uncons1
      .flatMap {
        case None => fs2.Pull.done
        case Some((hd, tl)) =>
          fs2.Pull
            .extendScopeTo(fs2.Stream.unit.covary[IO])
            .evalMap(_.compile.drain)
      }
      .stream
      .compile
      .drain
  }
}
