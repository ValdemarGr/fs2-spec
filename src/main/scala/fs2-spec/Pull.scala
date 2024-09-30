package fs2spec

import scala.concurrent.duration._
import cats.implicits._
import cats.effect.implicits._
import cats._
import cats.effect._
import fs2.Chunk
import cats.arrow.FunctionK
import cats.effect.kernel.CancelScope

trait Leasable[F[_]] { self =>
  def lease: Resource[F, Boolean]

  def mapK[G[_]](
      fk: F ~> G
  )(implicit F: MonadCancel[F, ?], G: MonadCancel[G, ?]): Leasable[G] =
    new Leasable[G] {
      override def lease: Resource[G, Boolean] = self.lease.mapK(fk)
    }
}

trait Arc[F[_]] extends Leasable[F] {
  def lease: Resource[F, Boolean]

  // n.b this resource is safe to not release manually since Arc will release it when the Arc reaches 0 leases
  def attachResource[A](r: Resource[F, A]): Resource[F, Option[A]]
}

object Arc {
  final case class State[F[_]](
      activeLeases: Set[Int],
      finalizers: Map[Int, F[Unit]],
      nextId: Int
  ) {
    def acquireLease: (State[F], Int) =
      copy(activeLeases = activeLeases + nextId, nextId = nextId + 1) -> nextId

    def releaseLease(id: Int): (Option[State[F]], List[F[Unit]]) = {
      val without = activeLeases - id
      if (without.isEmpty) None -> finalizers.values.toList
      else Some(copy(activeLeases = without)) -> Nil
    }

    def attachFinalizer(fin: F[Unit]): (State[F], Int) =
      copy(
        finalizers = finalizers + (nextId -> fin),
        nextId = nextId + 1
      ) -> nextId

    def releaseFinalizer(id: Int): (State[F], Option[F[Unit]]) =
      copy(finalizers = finalizers - id) -> finalizers.get(id)
  }

  def make[F[_]](implicit F: Target[F]): Resource[F, Arc[F]] = {
    val init = 0
    val alloc =
      F.ref[Option[State[F]]](Some(State[F](Set(init), Map.empty, init + 1)))
    Resource.eval(alloc).flatMap { ref =>
      def releaseLease(id: Int) = F.uncancelable { _ =>
        ref
          .modify {
            case None    => (None, Nil)
            case Some(s) => s.releaseLease(id)
          }
          .flatMap(_.traverse(_.attempt).map(_.sequence_).rethrow)
      }

      def acquireLease = ref.modify {
        case None => (None, None)
        case Some(x) =>
          val (s, i) = x.acquireLease
          (Some(s), Some(i))
      }

      val useOne: Resource[F, Boolean] = Resource
        .make(acquireLease)(id => id.traverse_(releaseLease))
        .map(_.isDefined)

      Resource.onFinalize[F](releaseLease(init)).as {
        new Arc[F] {
          override def lease: Resource[F, Boolean] = useOne

          override def attachResource[A](
              r: Resource[F, A]
          ): Resource[F, Option[A]] =
            Resource.uncancelable { poll =>
              poll(Resource.eval(r.allocated)).flatMap { case (a, release) =>
                val allocate: F[Option[Int]] = ref
                  .modify[F[Option[Int]]] {
                    case None => (None, release.as(None))
                    case Some(s) =>
                      val (s2, id) = s.attachFinalizer(release)
                      (Some(s2), F.pure(Some(id)))
                  }
                  .flatten
                Resource.eval(allocate).flatMap {
                  case None => Resource.eval(F.pure(None))
                  case Some(id) =>
                    Resource
                      .onFinalize(ref.modify {
                        case None => (None, F.unit)
                        case Some(s) =>
                          val (s2, fin) = s.releaseFinalizer(id)
                          (Some(s2), fin.sequence_)
                      }.flatten)
                      .as(Some(a))
                }
              }
            }
        }
      }
    }
  }
}

trait Target[F[_]] extends MonadCancelThrow[F] {
  val F: MonadCancelThrow[F]
  def ref[A](a: A): F[Ref[F, A]]

  def pure[A](a: A): F[A] = F.pure(a)
  def handleErrorWith[A](fa: F[A])(f: Throwable => F[A]): F[A] =
    F.handleErrorWith(fa)(f)
  def raiseError[A](e: Throwable): F[A] = F.raiseError(e)
  def flatMap[A, B](fa: F[A])(f: A => F[B]): F[B] = F.flatMap(fa)(f)
  def tailRecM[A, B](a: A)(f: A => F[Either[A, B]]): F[B] = F.tailRecM(a)(f)
  def uncancelable[A](f: Poll[F] => F[A]): F[A] = F.uncancelable(f)
  def canceled: F[Unit] = F.canceled
  def forceR[A, B](fa: F[A])(fb: F[B]): F[B] = F.forceR(fa)(fb)
  def onCancel[A](fa: F[A], fin: F[Unit]): F[A] = F.onCancel(fa, fin)
  def rootCancelScope: CancelScope = F.rootCancelScope
}

object Target {
  implicit def concurrent[F[_]](implicit F0: Concurrent[F]): Target[F] =
    new Target[F] {
      val F: MonadCancelThrow[F] = F0
      def ref[A](a: A): F[Ref[F, A]] = F0.ref(a)
    }
}

final case class Context[F[_]](
    arc: Arc[F],
    parents: List[Leasable[F]]
)

final case class Unconsed[F[_], O, A](
    ctx: Context[F],
    cont: Either[A, (fs2.Chunk[O], Pull[F, O, A])]
)

sealed trait Pull[+F[_], +O, +A]
object Pull {
  sealed trait Core[F[_], O, A] extends Pull[F, O, A]
  final case class Read[F[_]]()
      extends Core[F, Nothing, (Target[F], Context[F])]
  final case class Write[F[_], O, A](fa: F[Unconsed[F, O, A]])
      extends Core[F, O, A]
  final case class FlatMap[F[_], O, A, B](
      fa: Pull[F, O, A],
      f: A => Pull[F, O, B]
  ) extends Core[F, O, B]

  sealed trait Optimization[F[_], O, A] extends Pull[F, O, A]
  final case class Uncons[F[_], O, A](
      p: Pull[F, O, A]
  ) extends Optimization[F, Nothing, Option[(fs2.Chunk[O], Pull[F, O, A])]]

  def read[F[_]]: Pull[F, Nothing, (Target[F], Context[F])] = Read()

  def write[F[_], O, A](fa: F[Unconsed[F, O, A]]): Pull[F, O, A] =
    Write(fa)

  def flatMap[F[_], O, A, B](fa: Pull[F, O, A])(
      f: A => Pull[F, O, B]
  ): Pull[F, O, B] =
    FlatMap(fa, f)

  def run[F[_], O, A](
      p: Pull[F, O, A],
      ctx: Context[F]
  )(implicit F: Target[F]): F[Unconsed[F, O, A]] =
    p match {
      case _: Read[F]        => F.pure(Unconsed(ctx, Left(F -> ctx)))
      case w: Write[F, O, A] => w.fa
      case fm: FlatMap[F, O, a, A] =>
        F.unit >> run(fm.fa, ctx).flatMap {
          case Unconsed(ctx, Left(a)) => run(fm.f(a), ctx)
          case Unconsed(ctx, Right((hd, tl))) =>
            F.pure(Unconsed(ctx, Right(hd -> flatMap(tl)(fm.f))))
        }
      case uc: Uncons[F, o, a] =>
        run(uc.p, ctx).map(uc => Unconsed(ctx, Left(uc.cont.toOption)))
    }

  def stateful[F[_], O, A](
      f: (Target[F], Context[F]) => F[Unconsed[F, O, A]]
  ): Pull[F, O, A] =
    read[F].flatMap { case (tc, ctx) => write(f(tc, ctx)) }

  def pure[F[_], A](a: A): Pull[F, Nothing, A] =
    stateful[F, Nothing, A]((F, ctx) => F.pure(Unconsed(ctx, Left(a))))

  def getTarget[F[_]]: Pull[F, Nothing, Target[F]] =
    stateful[F, Nothing, Target[F]]((F, ctx) => F.pure(Unconsed(ctx, Left(F))))

  def output[F[_], O](value: fs2.Chunk[O]): Pull[F, O, Unit] =
    stateful[F, O, Unit]((F, ctx) =>
      F.pure(Unconsed(ctx, Right(value -> done)))
    )

  def output1[F[_], O](value: O): Pull[F, O, Unit] =
    output(fs2.Chunk.singleton(value))

  def eval[F[_], A](fa: F[A]): Pull[F, Nothing, A] =
    stateful[F, Nothing, A]((F, ctx) => F.map(fa)(a => Unconsed(ctx, Left(a))))

  def acquire[F[_], A](r: Resource[F, A]): Pull[F, Nothing, Option[A]] =
    stateful[F, Nothing, Option[A]] { (F, ctx) =>
      F.map(ctx.arc.attachResource(r).allocated(F)) { case (a, _) =>
        Unconsed(ctx, Left(a))
      }
    }

  def replaceContext[F[_]](ctx: Context[F]): Pull[F, Nothing, Unit] =
    stateful[F, Nothing, Unit]((F, _) => F.pure(Unconsed(ctx, leftUnit)))

  def getContext[F[_]]: Pull[F, Nothing, Context[F]] =
    stateful[F, Nothing, Context[F]]((F, ctx) =>
      F.pure(Unconsed(ctx, Left(ctx)))
    )

  def unStep[F[_], O, A](
      p: Pull[F, O, A]
  ): Pull[F, Nothing, F[Either[A, (Chunk[O], Pull[F, O, A])]]] =
    stateful { (F: Target[F], ctx: Context[F]) =>
      F.pure {
        val fa: F[Either[A, (Chunk[O], Pull[F, O, A])]] =
          F.map(run(p, ctx)(F)) { case Unconsed(ctx, e) =>
            e.map { case (hd, tl) =>
              hd -> stateful[F, O, A]((F, _) => run(tl, ctx)(F))
            }
          }
        Unconsed(ctx, Left(fa))
      }
    }

  def translate[F[_], G[_], O, A](
      pull: Pull[G, O, A],
      fk: G ~> F,
      gk: F ~> G
  )(implicit G: Target[G]): Pull[F, O, A] =
    read[F].flatMap { case (f0, ctx) =>
      implicit val F = f0
      write {
        shift(ctx.arc, fk).allocated.flatMap { case (child, release) =>
          val newParents: List[Leasable[G]] =
            (ctx.arc :: ctx.parents).map(_.mapK(gk))
          fk(run(pull, Context(child, newParents))).flatMap { out =>
            out.cont match {
              case Left(a) => release.as(Unconsed[F, O, A](ctx, Left(a)))
              case Right((hd, tl)) =>
                val tl2 =
                  translate(tl, fk, gk) <* eval(release) <* replaceContext(ctx)
                F.pure(Unconsed[F, O, A](ctx, Right(hd -> tl2)))
            }
          }
        }
      }
    }

  def leaseAll[F[_]]: Pull[F, Nothing, Resource[F, Boolean]] =
    getContext[F].map { ctx =>
      (ctx.arc :: ctx.parents).traverse(_.lease).map(_.foldLeft(true)(_ && _))
    }

  val done: Pull[Nothing, Nothing, Unit] = pure(())

  def shift[F[_], G[_]: Target](
      arc: Arc[F],
      fk: G ~> F
  )(implicit F: MonadCancel[F, ?]): Resource[F, Arc[G]] =
    arc.attachResource(Arc.make[G].mapK(fk)).map(_.get)

  val leftUnit = ().asLeft[Nothing]

  def compile[F[_], O, A, B](
      p: Pull[F, O, A],
      root: Arc[F],
      init: B
  )(fold: (B, fs2.Chunk[O]) => B)(implicit F: Target[F]): F[(B, A)] =
    (init, Unconsed(Context(root, Nil), Right(fs2.Chunk.empty -> p)))
      .tailRecM[F, (B, A)] {
        case (z, Unconsed(_, Left(a))) => F.pure(Right((z, a)))
        case (z, Unconsed(ctx, Right((hd, tl)))) =>
          val z2 = if (hd.nonEmpty) fold(z, hd) else z
          run(tl, ctx)(F).map(x => Left(z2 -> x))
      }

  implicit def monad[F[_], O]: Monad[Pull[F, O, *]] = new Monad[Pull[F, O, *]] {
    def pure[A](a: A): Pull[F, O, A] = Pull.pure(a)
    def flatMap[A, B](fa: Pull[F, O, A])(f: A => Pull[F, O, B]): Pull[F, O, B] =
      Pull.flatMap(fa)(f)
    def tailRecM[A, B](a: A)(f: A => Pull[F, O, Either[A, B]]): Pull[F, O, B] =
      f(a).flatMap {
        case Left(a)  => tailRecM(a)(f)
        case Right(b) => pure(b)
      }
  }

  implicit class PullOps[+F[_], +O, +A](private val self: Pull[F, O, A]) {
    def flatMap[F2[x] >: F[x], O2 >: O, B](
        f: A => Pull[F2, O2, B]
    ): Pull[F2, O2, B] =
      Pull.flatMap[F2, O2, A, B](self)(f)

    def >>[F2[x] >: F[x], O2 >: O, B](
        that: => Pull[F2, O2, B]
    ): Pull[F2, O2, B] = flatMap(_ => that)

    def map[B](f: A => B): Pull[F, O, B] = flatMap(a => pure(f(a)))

    def evalMap[F2[x] >: F[x], A2](f: A => F2[A2]): Pull[F2, O, A2] =
      flatMap(a => Pull.eval(f(a)))

    def leaseAll[F2[x] >: F[x]]: Pull[F2, Nothing, Resource[F2, Boolean]] =
      Pull.leaseAll[F2]

    def transferAll[F2[x] >: F[x], O2 >: O, A2 >: A]
        : Pull[F2, Nothing, Pull[F2, O, Option[A]]] =
      leaseAll[F2].map(acquire(_).flatMap {
        case None | Some(false) => pure(None)
        case Some(true)         => self.map(Some(_))
      })

    def transferLease[F2[x] >: F[x], O2 >: O, A2 >: A]
        : Pull[F2, O, Pull[F2, Nothing, Option[A]]] =
      self.flatMap { a =>
        leaseAll[F2].map(acquire(_).map {
          case None | Some(false) => None
          case Some(true)         => Some(a)
        })
      }

    def unStep[F2[x] >: F[x], O2 >: O, A2 >: A]
        : Pull[F2, Nothing, F2[Either[A2, (Chunk[O2], Pull[F2, O2, A2])]]] =
      Pull.unStep[F2, O2, A2](self)
  }

  implicit class PullUnitOps[F[_], O](private val self: Pull[F, O, Unit]) {
    def uncons: Pull[F, Nothing, Option[(fs2.Chunk[O], Pull[F, O, Unit])]] =
      Uncons(self)

    def stream: Stream[F, O] = new Stream(self)
  }

  class Stream[F[_], O](val pull: Pull[F, O, Unit]) {
    def map[O2](f: O => O2): Stream[F, O2] =
      new Stream(
        pull.uncons.flatMap {
          case None => done
          case Some((hd, tl)) =>
            output(hd.map(f)) >> new Stream(tl).map(f).pull
        }
      )

    def flatMap[O2](f: O => Stream[F, O2]): Stream[F, O2] =
      new Stream(
        pull.uncons.flatMap {
          case None => done
          case Some((hd, tl)) =>
            def go(xs: List[O]): Pull[F, O2, Unit] =
              xs match {
                case Nil     => new Stream(tl).flatMap(f).pull
                case x :: xs => f(x).pull >> go(xs)
              }
            go(hd.toList)
        }
      )

    def ++(that: => Stream[F, O]): Stream[F, O] =
      new Stream(pull.flatMap(_ => that.pull))

    def repeat: Stream[F, O] =
      this ++ repeat

    def repeatN(n: Int): Stream[F, O] =
      if (n <= 0) Stream.empty
      else this ++ repeatN(n - 1)

    def evalMap[A](f: O => F[A]): Stream[F, A] =
      new Stream(
        getTarget[F].flatMap { implicit F =>
          pull.uncons.flatMap {
            case None => done
            case Some((hd, tl)) =>
              eval(hd.traverse(f))
                .flatMap(output[F, A](_)) >> new Stream(tl).evalMap(f).pull
          }
        }
      )

    def chunkMin(n: Int): Stream[F, fs2.Chunk[O]] = {
      def go(
          accum: Chunk[O],
          p: Pull[F, O, Unit]
      ): Pull[F, Chunk[O], Unit] =
        p.uncons.flatMap {
          case None => Pull.output1(accum)
          case Some((hd, tl)) =>
            val newAccum = accum ++ hd
            if (newAccum.size >= n)
              Pull.output1(newAccum) >> go(Chunk.empty, tl)
            else go(newAccum, tl)
        }

      new Stream(go(Chunk.empty, pull))
    }

    def handleErrorWith(f: Throwable => Stream[F, O]): Stream[F, O] =
      new Stream(
        stateful[F, O, Unit] { (F, ctx) =>
          F.flatMap(F.attempt(run(pull, ctx)(F))) {
            case Left(e) => run(f(e).pull, ctx)(F)
            case Right(uc) =>
              uc.cont match {
                case Left(a) => F.pure(Unconsed(uc.ctx, Left(a)))
                case Right((hd, tl)) =>
                  F.pure(
                    Unconsed(
                      uc.ctx,
                      Right(hd -> new Stream(tl).handleErrorWith(f).pull)
                    )
                  )
              }
          }
        }
      )

    def subArc: Stream[F, O] =
      new Stream({
        getTarget[F].flatMap { implicit F =>
          translate(pull, FunctionK.id[F], FunctionK.id[F])
        }
      })

    def interruptWhen(p: F[Unit])(implicit F: Async[F]): Stream[F, O] =
      new Stream({
        unStep(pull).flatMap { fa =>
          eval(p.race(fa).map(_.flatten.toOption)).flatMap {
            case None => done
            case Some((hd, tl)) =>
              output(hd) >> new Stream(tl).interruptWhen(p).pull
          }
        }
      }).subArc

    def foldMap[B](
        init: B
    )(f: (B, Chunk[O]) => B)(implicit F: Target[F]): F[B] =
      Arc.make[F].use { arc =>
        compile(pull, arc, init)(f).map(_._1)
      }

    def drain(implicit F: Target[F]): F[Unit] =
      foldMap(())((_, _) => ())
  }

  object Stream {
    def apply[F[_], O](a: O): Stream[F, O] = new Stream(output1(a))

    def chunk[F[_], O](chunk: Chunk[O]): Stream[F, O] = new Stream(
      Pull.output(chunk)
    )

    def empty[F[_], O]: Stream[F, O] = new Stream(pure(()))

    def eval[F[_], A](fa: F[A]): Stream[F, A] = new Stream(
      Pull.eval(fa).flatMap(output1)
    )

    def resource[F[_], A](r: Resource[F, A]): Stream[F, A] =
      new Stream(
        acquire(r)
          .flatMap { o =>
            getTarget[F].flatMap { implicit F =>
              o match {
                case None =>
                  Pull.eval[F, Nothing](
                    F.raiseError(new Exception("arc closed"))
                  )
                case Some(x) => output1(x)
              }
            }
          }
      )
  }
}
