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
import fs2.Pure

class PullTest extends CatsEffectSuite {
  import Pull._

  def infSeq(n: Int): Stream[IO, Int] =
    Stream[IO, Int](n) ++ infSeq(n + 1)

  test("yoou") {
    val p =
      Stream[IO, Int](1).repeatN(100000).chunkMin(100).evalMap(x => IO(x.size))

    val p2 =
      Test.fold(
        Test.evalMap(
          Test.chunkMin(
            Test.repeatN(Test.output(fs2.Chunk.singleton(1)), 100000),
            100
          )
        )(x => IO(x.size)),
        ()
      )((_, _) => ())

    def bench[A](
        warmups: Int,
        ioas0: (String, IO[A])*
    ) = {
      val ioas = ioas0.toList
      val effects = ioas.map(_._2)
      (0 to warmups).toList.traverse { _ =>
        effects.sequence_
      }

      val maxLen = ioas.map(_._1.length).max
      ioas.traverse { case (name, ioa) =>
        val diff = maxLen - name.length
        (0 to warmups).toList
          .traverse(_ => ioa.timed.map { case (d, _) => d })
          .map(_.min)
          .flatMap { duration =>
            IO(println(s"$name: ${" " * diff}$duration"))
          }
      }
    }

    val cps =
      fs2
        .Stream(1)
        .covary[IO]
        .repeatN(100000)
        .chunkMin(100)
        .evalMapChunk(x => IO(x.size))
        .compile
        .drain

    bench(
      20,
      "new recursion based fs2" -> p2,
      "fs2" -> cps,
      "new recursion based more expressive model" -> p.drain
    )
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

  test("my pull algebra is not subject to covariance soundness issues") {
    sealed trait BaseEffect[A] {
      def fa: IO[A]
    }
    final case class EffectA[A](fa: IO[A]) extends BaseEffect[A]
    final case class EffectB[A](fa: IO[A]) extends BaseEffect[A]

    implicit val concurrentForA: Concurrent[BaseEffect] with Sync[BaseEffect] =
      new Concurrent[BaseEffect] with Sync[BaseEffect] {
        override def monotonic: BaseEffect[FiniteDuration] = ???
        override def realTime: BaseEffect[FiniteDuration] = ???
        override def suspend[A](hint: effect.kernel.Sync.Type)(
            thunk: => A
        ): BaseEffect[A] =
          EffectA(IO.suspend(hint)(thunk))
        override def pure[A](x: A): BaseEffect[A] = EffectA(IO.pure(x))
        override def raiseError[A](e: Throwable): BaseEffect[A] = ???
        override def handleErrorWith[A](fa: BaseEffect[A])(
            f: Throwable => BaseEffect[A]
        ): BaseEffect[A] = ???
        override def flatMap[A, B](fa: BaseEffect[A])(
            f: A => BaseEffect[B]
        ): BaseEffect[B] =
          EffectB(fa.fa.flatMap(f.andThen(_.fa)))
        override def tailRecM[A, B](a: A)(
            f: A => BaseEffect[Either[A, B]]
        ): BaseEffect[B] = ???
        override def forceR[A, B](fa: BaseEffect[A])(
            fb: BaseEffect[B]
        ): BaseEffect[B] =
          ???
        override def uncancelable[A](
            body: Poll[BaseEffect] => BaseEffect[A]
        ): BaseEffect[A] =
          EffectA {
            IO.uncancelable { poll =>
              body {
                new Poll[BaseEffect] {
                  def apply[A](fa: BaseEffect[A]): BaseEffect[A] =
                    EffectA(poll(fa.fa))
                }
              }.fa
            }
          }
        override def canceled: BaseEffect[Unit] = ???
        override def onCancel[A](
            fa: BaseEffect[A],
            fin: BaseEffect[Unit]
        ): BaseEffect[A] =
          ???
        override def unique: BaseEffect[effect.kernel.Unique.Token] = ???
        override def start[A](
            fa: BaseEffect[A]
        ): BaseEffect[Fiber[BaseEffect, Throwable, A]] = ???
        override def never[A]: BaseEffect[A] = ???
        override def cede: BaseEffect[Unit] = ???
        override def ref[A](a: A): BaseEffect[Ref[BaseEffect, A]] = EffectA(
          IO(Ref.unsafe[BaseEffect, A](a)(this))
        )
        override def deferred[A]: BaseEffect[Deferred[BaseEffect, A]] = ???
      }

    translate(
      (read[EffectA].flatMap { case (tar, _) =>
        eval(tar.pure(1))
      }: Pull[BaseEffect, Nothing, Int]).flatMap { x =>
        read[EffectB].flatMap { case (tar, _) =>
          eval(tar.pure(x))
        }
      },
      new FunctionK[BaseEffect, IO] {
        def apply[A](fa: BaseEffect[A]): IO[A] = fa.fa
      },
      new FunctionK[IO, BaseEffect] {
        def apply[A](fa: IO[A]): EffectA[A] = EffectA(fa)
      }
    )
      .flatMap(x => output1(x))
      .stream
      .drain
  }

  test("second covariance test") {
    final case class OtherEffect[A](fa: IO[A])

    def invoke(fa: OtherEffect[Int]) = fa.fa.map(_ + 2)

    getTarget[fs2.Pure]
      .evalMap { T =>
        invoke(T.pure(1))
      }
      .flatMap(output1(_))
      .stream
      .foldMap(0)((x, y) => y.foldLeft(x)(_ + _))
  }

  test("third covariance test") {
    val p = new Test.PullImpl[fs2.Pure, Nothing, Nothing] {
      def run[G[_]](
          generalize: Pure ~> G,
          unsafeSpecialize: G ~> Pure,
          ctx: Context[G]
      )(implicit G: Target[G]): G[Test.Ret[Pure, G, Nothing, Nothing]] =
        Test.perform(
          Test.pure(unsafeSpecialize(G.unit)),
          generalize,
          unsafeSpecialize,
          ctx
        )
    }

    val p2 = (p: Test.Pull[fs2.Pure, Nothing, Nothing]): Test.Pull[
      IO,
      Nothing,
      Nothing
    ]

    val p3 = p2.map{ (x: Nothing) =>
      println(s"I have a nothing! $x")
      x
    }

    Test.fold(p3, ())((_, data) => ())
  }
}
