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
import cats.effect.std.Supervisor

class PullTest extends CatsEffectSuite {
  import Pull._

  def infSeq(n: Int): Stream[IO, Int] =
    Stream[IO, Int](n) ++ infSeq(n + 1)

  test("yoou") {
    val p =
      Stream[IO, Int](1).repeatN(100000).chunkMin(100).evalMap(x => IO(x.size))

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
    val p = Stream[IO, Int](1)
      .repeatN(3)
      .evalMap(i => IO.raiseError[Unit](new Exception("boom")))
      .map(_ => Option.empty[Throwable])
      .handleErrorWith(e => Stream[IO, Option[Throwable]](Some(e)))

    p.foldMap(Chunk.empty[Option[Throwable]])((z, c) => z ++ c).map(println)
  }

  test("resource") {
    val p = Stream.eval(IO.ref(0)).flatMap { ref =>
      Stream
        .resource {
          Resource
            .make(
              ref.getAndUpdate(_ + 1).flatTap(n => IO.println(s"opening $n"))
            )(n => IO.println(s"closing $n"))
        }
        .subArc
        .repeatN(3)
        .flatMap { x =>
          Stream
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
      Stream
        .resource(Resource.make(ref.update(_ + 1))(_ => ref.update(_ - 1)))
        .evalMap { _ =>
          IO.raiseError[Unit](new Exception("boom"))
        }
        .handleErrorWith(e => Stream.eval(IO.println(e)))
        .drain
        .flatMap { _ =>
          ref.get.assertEquals(0)
        }
    }
  }

  def refRes = IO
    .ref(0)
    .map(ref => ref -> Resource.make(ref.update(_ + 1))(_ => ref.update(_ - 1)))

  test("transfer resource") {
    Supervisor[IO].use { sup =>
      IO.deferred[
        (
            Resource[IO, Boolean],
            IO[Unit]
        )
      ].flatMap { d =>
        refRes.flatMap { case (ref, res) =>
          val bg =
            Stream
              .resource(res)
              .pull
              .uncons
              .flatMap {
                case None => Pull.done
                case Some((_, tl)) =>
                  for {
                    canProceed <- Pull.eval(IO.deferred[Unit])
                    lease <- tl.leaseAll
                    _ <- Pull
                      .eval(d.complete((lease, canProceed.complete(()).void)))
                    _ <- Pull.eval(canProceed.get)
                    o <- tl
                  } yield o
              }
              .stream
              .drain

          sup.supervise(bg).flatMap { fib =>
            d.get.flatMap { case (res, canStop) =>
              res.use { b =>
                IO(assert(b)) >>
                  ref.get.assertEquals(1) >>
                  canStop >>
                  fib.joinWithNever >>
                  // we still hold the resource
                  ref.get.assertEquals(1)
              } >> ref.get.assertEquals(0) >> res.use { b =>
                IO(assert(!b)) >> ref.get.assertEquals(0)
              }
            }
          }
        }
      }
    }
  }

  test("pull is stateless") {
    refRes.flatMap { case (ref, res) =>
      Stream
        .resource(res)
        .subArc
        .repeatN(3)
        .pull
        .uncons
        .flatMap {
          case None          => Pull.done
          case Some((_, tl)) =>
            // one for this pull and the tail pull
            eval(tl.stream.evalMap(_ => ref.get.assertEquals(1)).drain)
        }
        .stream
        .drain
    }
  }

  test("covariant higher kinds are unsound in scala 2".ignore) {
    sealed trait Lang[+F[_]]
    trait LangImpl[F[_]] extends Lang[F] {
      def extract[A](a: F[A]): Int
    }

    trait Pure[A] {
      def value: A
    }
    case class PureImpl[A](value: A, constant: Int = 5) extends Pure[A]

    val l: Lang[PureImpl] = new LangImpl[PureImpl] {
      def extract[A](a: PureImpl[A]): Int = a.constant
    }

    def getImpl[F[_]](l: Lang[F]): LangImpl[F] = l match {
      case l: LangImpl[F] => l
    }

    val refine = l: Lang[Pure]
    getImpl(refine).extract(new Pure[String] { val value = "hello" })
  }
}
