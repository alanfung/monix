/*
 * Copyright (c) 2014-2018 by The Monix Project Developers.
 * See the project homepage at: https://monix.io
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package monix.execution

import cats.effect.IO
import monix.execution.atomic.AtomicAny
import monix.execution.exceptions.CompositeException
import monix.execution.internal.AttemptCallback
import monix.execution.misc.NonFatal
import scala.concurrent.Promise

/** Represents a one-time idempotent action that can be used
  * to cancel async computations, or to release resources that
  * active data sources are holding.
  *
  * It is equivalent to `java.io.Closeable`, but without the I/O focus, or
  * to `IDisposable` in Microsoft .NET, or to `akka.actor.Cancellable`.
  */
trait Cancelable extends Serializable {
  /** Cancels the unit of work represented by this reference.
    *
    * Guaranteed idempotency - calling it multiple times should have the
    * same side-effect as calling it only once. Implementations
    * of this method should also be thread-safe.
    */
  def cancel(): Unit
}

object Cancelable {
  /** Builds a [[monix.execution.Cancelable Cancelable]]. */
  def apply(): Cancelable =
    empty

  /** Builds a [[monix.execution.Cancelable Cancelable]] that executes the given
    * `callback` when calling [[Cancelable.cancel cancel]].
    */
  def apply(callback: () => Unit): Cancelable =
    new CancelableTask(callback)

  /** Returns a dummy [[Cancelable]] that doesn't do anything. */
  val empty: Cancelable =
    new Cancelable with IsDummy {
      def cancel() = ()
      override def toString = "monix.execution.Cancelable.empty"
    }

  /** Builds a [[Cancelable]] reference from a sequence,
    * cancelling everything on `cancel`.
    */
  def collection(refs: Iterable[Cancelable]): Cancelable =
    apply { () => cancelAll(refs) }

  /** Builds a [[Cancelable]] out of a Scala `Promise`, completing the
    * promise with the given `Throwable` on cancel.
    */
  def fromPromise[A](p: Promise[A], e: Throwable): Cancelable =
    new Cancelable {
      def cancel(): Unit =
        p.tryFailure(e)
    }

  /** Builds a [[Cancelable]] reference from an `IO[Unit]`.
    *
    * Guarantees idempotency and reports any uncaught errors.
    *
    * @param io is the `IO` value to evaluate on `cancel`
    * @param r is an exception reporter that's used in case our `IO`
    *        value is throwing an error on evaluation
    */
  def fromIO(io: IO[Unit])(implicit r: UncaughtExceptionReporter): Cancelable =
    Cancelable { () =>
      io.unsafeRunAsync(AttemptCallback.empty)
    }

  /** Internal API — builds a `Cancelable` reference from an `IO[Unit]`,
    * but without any protections for idempotency.
    */
  private[monix] def fromIOUnsafe(io: IO[Unit])
    (implicit r: UncaughtExceptionReporter): Cancelable = {

    new Cancelable {
      def cancel(): Unit =
        io.unsafeRunAsync(AttemptCallback.empty)
    }
  }

  /** Given a collection of cancelables, cancel them all.
    *
    * This function collects non-fatal exceptions and throws them all at the end as a
    * [[monix.execution.exceptions.CompositeException CompositeException]],
    * thus making sure that all references get canceled.
    */
  def cancelAll(seq: Iterable[Cancelable]): Unit = {
    var errors = List.empty[Throwable]
    val cursor = seq.iterator
    while (cursor.hasNext) {
      try cursor.next().cancel()
      catch { case ex if NonFatal(ex) => errors = ex :: errors }
    }

    errors match {
      case one :: Nil =>
        throw one
      case _ :: _ =>
        throw new CompositeException(errors)
      case _ =>
        () // Nothing
    }
  }

  /** Marker for cancelables that are dummies that can be ignored. */
  trait IsDummy { self: Cancelable => }

  /** Extension methods for [[Cancelable]]. */
  implicit final class Extensions(val self: Cancelable) extends AnyVal {
    /** Given a [[Cancelable]] reference, turn it into an `IO[Unit]`
      * that will trigger [[Cancelable.cancel cancel]] on evaluation.
      *
      * Useful when working with the `IO.cancelable` builder.
      */
    def cancelIO: IO[Unit] =
      self match {
        case _: IsDummy => IO.unit
        case _ => IO(self.cancel())
      }
  }

  private final class CancelableTask(cb: () => Unit)
    extends Cancelable {

    private[this] val callbackRef = AtomicAny(cb)

    def cancel(): Unit = {
      // Setting the callback to null with a `getAndSet` is solving
      // two problems: `cancel` is idempotent, plus we allow the garbage
      // collector to collect the task.
      val callback = callbackRef.getAndSet(null)
      if (callback != null) callback()
    }
  }
}
