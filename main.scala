//> using scala 3
//> using javaOpt --add-exports java.base/jdk.internal.vm=ALL-UNNAMED
//> using dep org.typelevel::cats-effect:3.5.4

import cats.effect.{Deferred, FiberIO, IO, IOApp, Ref}
import cats.effect.std.{Dispatcher, Mutex, Semaphore}
import jdk.internal.vm.{Continuation, ContinuationScope}
import cats.effect.std.Console
import scala.util.Try

extension [A](effect: IO[A])
  def await(implicit scope: Scope): A = {
    var resumed: A = null.asInstanceOf
    scope.dispatcher.unsafeRunSync(scope.sem.acquire)
    scope.dispatcher.unsafeRunAndForget(
      effect
        .map(result => {
          resumed = result
        })
        .onError(throwable =>
          scope.deferred.complete(Left(throwable)) *> scope.done.set(true)
        )
        .guarantee(scope.sem.release)
    )
    Continuation.`yield`(scope.cscope)
    resumed
  }

abstract class Scope {
  val cscope: ContinuationScope
  val dispatcher: Dispatcher[IO]
  val sem: Semaphore[IO]
  val deferred: Deferred[IO, Either[Throwable, Any]]
  val done: Ref[IO, Boolean]
}

def async[A](f: Scope ?=> IO[A]): IO[A] = for {
  dispatch <- Dispatcher.parallel[IO].allocated
  m <- Semaphore[IO](1)
  returnValue <- Deferred[IO, Either[Throwable, A]]
  doneV <- Ref.of[IO, Boolean](false)
  scope: ContinuationScope <- IO.delay(ContinuationScope(s"async-io"))
  scopeInstance: Scope <- IO.delay(new Scope {
    override val cscope: ContinuationScope = scope
    override val dispatcher: Dispatcher[IO] = dispatch._1
    override val sem: Semaphore[IO] = m
    override val deferred: Deferred[IO, Either[Throwable, Any]] =
      returnValue.asInstanceOf
    override val done: Ref[IO, Boolean] = doneV
  })
  continuation <- IO.delay(
    new Continuation(
      scope,
      () => {
        val result = Try { f(using scopeInstance) }
        dispatch._1.unsafeRunSync(
          IO.fromTry(result)
            .flatten
            .attempt
            .flatMap(v => returnValue.complete(v)) *> doneV.set(true)
        )
        Continuation.`yield`(scope)
        ()
      }
    )
  )
  _ <- m.acquire
  _ <- (m.release *> IO.delay(continuation.run()) *> m.acquire)
    .whileM_(doneV.get.map(!_))
  result <- returnValue.get.flatMap(IO.fromEither).guarantee(dispatch._2)
} yield result

def funtionAsyncTest(someNumber: IO[Int]): IO[Int] = async {
  val next = someNumber.map(_ + 1).await
  val ref = Ref.of[IO, Int](next).await
  val counter = Ref.of[IO, Int](100).await
  Console[IO].println(s"Current value is ${next}. Starting loop.").await
  async {
    val thisValue = ref.get.await
    Console[IO].println(s"Current value is ${next}. Ref ${thisValue}.").await
    ref.set(thisValue + 1).await
    counter.update(_ - 1).await
    Console[IO].println(s"Current value is ${next}. Ref has been set.")
    IO.unit
  }.whileM_(counter.get.map(_ > 0)).await
  Console[IO].println(s"Current value is ${next}. End loop.").await
  ref.get
}

object Main extends IOApp.Simple {
  override def run: IO[Unit] = async {
    var fibers = Seq.empty[FiberIO[Unit]]
    for (x <- 0 to 999) {
      val fiber = async {
        val returnValue = funtionAsyncTest(IO.pure(x)).await
        IO { assert(returnValue == x + 101) }
      }.start.await
      fibers = fibers.appended(fiber)
    }
    fibers.map(_.join.await)
    IO.unit
  }
}
