package com.kubukoz

import java.util.concurrent.CountDownLatch
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import scala.concurrent.ExecutionContext
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import scala.util.control.NonFatal
import scala.concurrent.Promise
import scala.concurrent.Future
import cats.MonadError
import cats.StackSafeMonad
import cats.implicits._
import scala.util.Random

sealed trait Exit[+A] extends Product with Serializable

object Exit {
  final case class Succeeded[A](a: A) extends Exit[A]
  final case class Failed(e: Throwable) extends Exit[Nothing]
  final case object Canceled extends Exit[Nothing]
}

trait Fiber[+A] extends Serializable {
  def join: IO[Exit[A]]
  def cancel: IO[Unit]
  def id: FiberId
}

final case class FiberId(id: String)

sealed trait IO[+A] extends Serializable {
  def flatMap[B](f: A => IO[B]): IO[B] = IO.FlatMap(this, f, cancelable = true)
  def flatten[B](implicit ev: A <:< IO[B]): IO[B] = flatMap(ev)

  def continual[B](f: Either[Throwable, A] => IO[B]): IO[B] = IO.FlatMap(this.attempt, f, cancelable = false)
  def map[B](f: A => B): IO[B] = flatMap(f.andThen(IO.pure))
  def as[B](b: => B): IO[B] = map(_ => b)
  def void: IO[Unit] = this *> IO.unit

  def attempt: IO[Either[Throwable, A]] = IO.Attempt(this)

  def *>[B](iob: IO[B]): IO[B] = flatMap(_ => iob)
  def <*[B](iob: IO[B]): IO[A] = flatMap(a => iob *> IO.pure(a))

  def evalOn(ec: ExecutionContext): IO[A] = IO.On(ec, this)

  def fork: IO[Fiber[A]] = IO.Fork(this)
}

object IO {
  final case class Pure[A](a: A) extends IO[A]
  final case class RaiseError(e: Throwable) extends IO[Nothing]
  final case class Attempt[A](self: IO[A]) extends IO[Either[Throwable, A]]
  final case class Delay[A](f: () => A) extends IO[A]
  final case class Fork[A](self: IO[A]) extends IO[Fiber[A]]
  final case class Async[A](cb: (Either[Throwable, A] => Unit) => Unit) extends IO[A]
  final case class On[A](ec: ExecutionContext, underlying: IO[A]) extends IO[A]
  final case class FlatMap[A, B](ioa: IO[A], f: A => IO[B], cancelable: Boolean) extends IO[B]
  final case object Blocker extends IO[ExecutionContext]
  final case object Executor extends IO[ExecutionContext]
  final case object Scheduler extends IO[ScheduledExecutorService]
  final case object Identifier extends IO[FiberId]

  def apply[A](a: => A): IO[A] = delay(a)

  val unit: IO[Unit] = IO.pure(())
  def pure[A](a: A): IO[A] = Pure(a)
  def delay[A](a: => A): IO[A] = Delay(() => a)
  def suspend[A](a: => IO[A]): IO[A] = delay(a).flatten
  def raiseError(e: Throwable): IO[Nothing] = RaiseError(e)
  def fromEither[A](ea: Either[Throwable, A]): IO[A] = ea.fold(raiseError, pure)

  def async[A](cb: (Either[Throwable, A] => Unit) => Unit): IO[A] = Async(cb)
  val never: IO[Nothing] = async(_ => ())

  def fromFuture[A](futurea: IO[Future[A]]): IO[A] = futurea.flatMap { future =>
    future.value match {
      case None    => IO.async(cb => future.onComplete(cb.compose(_.toEither))(ExecutionContext.parasitic))
      case Some(t) => fromEither(t.toEither)
    }
  }

  def blocking[A](ioa: IO[A]): IO[A] = IO.Blocker.flatMap(ioa.evalOn(_))

  val scheduler: IO[ScheduledExecutorService] = IO.Scheduler
  val executor: IO[ExecutionContext] = IO.Executor

  val fiberId: IO[FiberId] = IO.Identifier

  val flipCoin: IO[Boolean] = IO(Random.nextBoolean())

  def sleep(units: Long, unit: TimeUnit): IO[Unit] =
    IO.scheduler.flatMap { ses =>
      IO.async[Unit] { cb =>
        //comment for formatting
        //todo cancelable
        val _ = ses.schedule((() => cb(Right(()))): Runnable, units, unit)
      }
    }

  implicit val ioMonad: MonadError[IO, Throwable] = new StackSafeMonad[IO] with MonadError[IO, Throwable] {
    def flatMap[A, B](fa: IO[A])(f: A => IO[B]): IO[B] = fa.flatMap(f)
    def pure[A](x: A): IO[A] = IO.pure(x)
    def raiseError[A](e: Throwable): IO[A] = IO.raiseError(e)
    def handleErrorWith[A](fa: IO[A])(f: Throwable => IO[A]): IO[A] = fa.attempt.flatMap(_.fold(f, pure))
  }

  private val globalFiberId = new AtomicLong(0)
  private def newFiberId() = FiberId("Fiber-" + globalFiberId.getAndIncrement())

  def unsafeRun[A](ioa: IO[A])(runtime: Runtime): Fiber[A] = {
    val promise = Promise[Exit[A]]()

    val fiberId = unsafeRunAsync(ioa) {
      case Left(e)  => promise.success(Exit.Failed(e))
      case Right(a) => promise.success(Exit.Succeeded(a))
    }(runtime)

    new Fiber[A] {
      def join: IO[Exit[A]] = IO.fromFuture(IO.pure(promise.future))
      def cancel: IO[Unit] = IO.raiseError(new Throwable("cancel isn't implemented yet"))
      def id: FiberId = fiberId
    }
  }

  def unsafeRunAsync[A](ioa: IO[A])(cb: Either[Throwable, A] => Unit)(runtime: Runtime): FiberId = {
    def doRun[B](iob: IO[B])(cb: Either[Throwable, B] => Unit)(ctx: Context): Unit = {
      def continue(value: B) = cb(Right(value))

      iob match {
        //sync programs
        case Pure(a) => continue(a)
        case Delay(f) =>
          try continue(f())
          catch { case NonFatal(e) => cb(Left(e)) }

        case RaiseError(e) => cb(Left(e))

        //context/runtime values
        case Executor    => continue(ctx.ec)
        case Identifier  => continue(ctx.id)
        case Blocker     => continue(runtime.blocker)
        case Scheduler   => continue(runtime.scheduler)
        case Attempt(io) => doRun(io)(continue)(ctx)

        case Fork(self) => cb(Right(unsafeRun(self)(runtime)))
        case Async(f) =>
          f { asyncResult =>
            //todo this must check for an idempotency flag
            ctx.ec.execute(() => cb(asyncResult))
          }
        case On(ec, io) =>
          ec.execute(() => doRun(io)(result => ctx.ec.execute(() => cb(result)))(ctx.withExecutor(ec)))
        case next: FlatMap[a, b] =>
          doRun(next.ioa) {
            case Left(e)  => cb(Left(e))
            case Right(v) => doRun(next.f(v))(cb)(ctx)
          }(ctx)

      }
    }

    val rootContext = Context(runtime.ec, newFiberId())

    runtime.ec.execute(() => doRun(ioa)(cb)(rootContext))
    rootContext.id
  }

  def unsafeRunSync[A](prog: IO[A])(runtime: Runtime): Either[Throwable, A] = {
    val latch = new CountDownLatch(1)
    var value: Option[Either[Throwable, A]] = None

    IO.unsafeRunAsync(prog) { v =>
      value = Some(v)
      latch.countDown()
    }(runtime)

    latch.await()
    value.get
  }

  final case class Runtime(ec: ExecutionContext, scheduler: ScheduledExecutorService, blocker: ExecutionContext)

  final case class Context(ec: ExecutionContext, id: FiberId) {
    def withExecutor(ec: ExecutionContext) = copy(ec = ec)
  }
}

object IODemo extends App {
  def putStrLn(s: Any): IO[Unit] = IO(println(s))

  def printThread(tag: String) = IO.suspend(putStrLn(tag + ": " + Thread.currentThread().getName()))

  val blocker = ExecutionContext.fromExecutorService(Executors.newCachedThreadPool(prefixFactory("blocker")))

  def prefixFactory(prefix: String) =
    new ThreadFactory {
      val a = new AtomicInteger(1)
      def newThread(r: Runnable): Thread = new Thread(r, s"$prefix-thread-${a.getAndIncrement()}")
    }

  val newEc = ExecutionContext.fromExecutorService(Executors.newSingleThreadExecutor(prefixFactory("newEc")))

  val ses = new ScheduledThreadPoolExecutor(1)

  val runtime = IO.Runtime(ExecutionContext.global, ses, blocker)

  val prog =
    for {
      _ <- printThread("foo")
      _ <- IO.blocking(
            printThread("blocking") *> IO.sleep(10L, TimeUnit.MILLISECONDS) *> printThread(
              "after sleep but in blocking"
            )
          )
      _ <- printThread("bar")
      _ <- IO.fiberId.flatMap(putStrLn)
      _ <- printThread("before sleep")

      prog = IO.sleep(500L, TimeUnit.MILLISECONDS) *> IO.fiberId.flatMap(putStrLn) *> IO
        .flipCoin
        .ifM(IO.pure(42), IO.raiseError(new Throwable("failed coin flip :/")))
      _ <- List.fill(10)(prog).traverse(_.fork).flatMap(_.traverse(_.join)).flatMap(putStrLn(_))
      _ <- printThread("after sleeps")
    } yield 42

  println {
    IO.unsafeRunSync(printThread("before evalOn") *> prog.evalOn(newEc) <* printThread("after evalOn"))(runtime)
  }

  ses.shutdown()
  newEc.shutdown()
  blocker.shutdown()
}
