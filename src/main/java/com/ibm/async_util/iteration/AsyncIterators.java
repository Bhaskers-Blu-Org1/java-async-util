//
// (C) Copyright IBM Corp. 2005 All Rights Reserved.
//
// Contact Information:
//
// IBM Corporation
// Legal Department
// 222 South Riverside Plaza
// Suite 1700
// Chicago, IL 60606, USA
//
// END-OF-HEADER
//
// -----------------------
// @author: rkhadiwala
//
// Date: Jun 18, 2015
// ---------------------

package com.ibm.async_util.iteration;

import com.ibm.async_util.iteration.AsyncIterator.End;
import com.ibm.async_util.locks.AsyncLock;
import com.ibm.async_util.locks.FairAsyncLock;
import com.ibm.async_util.locks.ObservableEpoch;
import com.ibm.async_util.util.Either;
import com.ibm.async_util.util.FutureSupport;

import java.util.ArrayDeque;
import java.util.Objects;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.stream.Collector;

/** Package private methods to use in {@link AsyncIterator} */
class AsyncIterators {

  private AsyncIterators() {}

  private static final Either<End, ?> ITERATION_END =
      Either.left(
          new AsyncIterator.End() {
            @Override
            public String toString() {
              return "End of iteration";
            }
          });

  private static final CompletionStage<? extends Either<AsyncIterator.End, ?>> END_FUTURE =
      CompletableFuture.completedFuture(ITERATION_END);

  static final EmptyAsyncIterator<?> EMPTY_ITERATOR = new EmptyAsyncIterator<>();

  @SuppressWarnings("unchecked")
  static <T> Either<AsyncIterator.End, T> end() {
    return (Either<AsyncIterator.End, T>) ITERATION_END;
  }

  @SuppressWarnings("unchecked")
  static <T> CompletionStage<Either<AsyncIterator.End, T>> endFuture() {
    return (CompletionStage<Either<AsyncIterator.End, T>>) END_FUTURE;
  }

  private static class EmptyAsyncIterator<T> implements AsyncIterator<T> {
    @Override
    public CompletionStage<Either<End, T>> nextFuture() {
      return AsyncIterators.endFuture();
    }

    @Override
    public String toString() {
      return "EmptyAsyncIterator";
    }
  }

  @SuppressWarnings("unchecked")
  static <A, R> R finishContainer(final A accumulator, final Collector<?, A, R> collector) {
    // cast instead of applying the finishing function if the collector indicates the
    // finishing function is just identity
    return collector.characteristics().contains(Collector.Characteristics.IDENTITY_FINISH)
        ? ((R) accumulator)
        : collector.finisher().apply(accumulator);
  }

  /** Complete dest with whatever result (T or a Throwable) comes out of source */
  static <T> void listen(final CompletionStage<T> source, final CompletableFuture<T> dest) {
    source.whenComplete(
        (t, ex) -> {
          if (t != null) {
            dest.complete(t);
          } else {
            dest.completeExceptionally(ex);
          }
        });
  }

  static <T> CompletionStage<T> convertSynchronousException(
      final Supplier<CompletionStage<T>> supplier) {
    try {
      return supplier.get();
    } catch (Throwable e) {
      return FutureSupport.errorStage(e);
    }
  }

  /** If both et and eu are right, then compute a new right either, otherwise just return left */
  static <T, U, V> Either<AsyncIterator.End, V> zipWith(
      Either<AsyncIterator.End, T> et,
      Either<AsyncIterator.End, U> eu,
      final BiFunction<T, U, V> f) {
    return et.fold(end -> end(), t -> eu.fold(end -> end(), u -> Either.right(f.apply(t, u))));
  }

  static <T, U> AsyncIterator<U> thenApplyImpl(
      AsyncIterator<T> it, Function<? super T, ? extends U> f, final Executor e) {
    return new AsyncIterator<U>() {
      @Override
      public CompletionStage<Either<End, U>> nextFuture() {
        return e == null
            ? it.nextFuture().thenApply(either -> either.map(f))
            : it.nextFuture().thenApplyAsync(either -> either.map(f), e);
      }

      @Override
      public CompletionStage<Void> close() {
        return it.close();
      }
    };
  }

  static <T, U> AsyncIterator<U> thenComposeImpl(
      final AsyncIterator<T> it, Function<T, CompletionStage<U>> f, final Executor e) {
    return new AsyncIterator<U>() {
      @Override
      public CompletionStage<Either<End, U>> nextFuture() {
        CompletionStage<Either<End, T>> nxt = it.nextFuture();
        // if there's a value, apply f and wrap the result in an Either,
        // otherwise just return end marker
        return e == null
            ? nxt.thenCompose(
                nt -> nt.fold(end -> endFuture(), t -> f.apply(t).thenApply(Either::right)))
            : nxt.thenComposeAsync(
                nt -> nt.fold(end -> endFuture(), t -> f.apply(t).thenApply(Either::right)), e);
      }

      @Override
      public CompletionStage<Void> close() {
        return it.close();
      }
    };
  }

  static class PartiallyEagerAsyncIterator<T, U> implements AsyncIterator<U> {
    private final AsyncIterator<T> backingIterator;
    private final int executeAhead;
    private final Function<U, CompletionStage<Void>> closeFn;
    final Function<Either<End, T>, CompletionStage<Either<End, U>>> mappingFn;
    final Queue<CompletionStage<Either<End, U>>> pendingResults;
    final FairAsyncLock lock;
    final ObservableEpoch epoch;

    PartiallyEagerAsyncIterator(
        AsyncIterator<T> backingIterator,
        final int executeAhead,
        final Function<Either<End, T>, CompletionStage<Either<End, U>>> mappingFn,
        final Function<U, CompletionStage<Void>> closeFn) {
      this.backingIterator = backingIterator;
      this.executeAhead = executeAhead;
      this.closeFn = u -> convertSynchronousException(() -> closeFn.apply(u));
      this.mappingFn = mappingFn;
      this.pendingResults = new ArrayDeque<>(executeAhead);
      this.lock = new FairAsyncLock();
      this.epoch = ObservableEpoch.newEpoch();
    }

    /* return whether we need to keep filling */
    private CompletionStage<Either<End, T>> fillMore() {
      if (pendingResults.size() >= executeAhead) {
        // don't call nextFuture, we already have enough stuff pending
        return AsyncIterators.endFuture();
      } else {
        // keep filling up the ahead queue
        CompletionStage<Either<End, T>> nxt =
            AsyncIterators.convertSynchronousException(backingIterator::nextFuture);
        pendingResults.add(nxt.thenCompose(mappingFn));
        return nxt;
      }
    }

    /* find someone for listener to listen to, kickoff a call to fill the queue */
    private CompletionStage<Either<End, T>> attachListener(
        final CompletableFuture<Either<End, U>> listener) {
      final CompletionStage<Either<End, U>> poll = pendingResults.poll();
      if (poll == null) {

        // there was nothing in the queue, associate our returned future with a new
        // safeNextFuture call
        CompletionStage<Either<End, T>> nxt =
            AsyncIterators.convertSynchronousException(backingIterator::nextFuture);

        // don't bother adding it to the queue, because we are already listening on it
        AsyncIterators.listen(nxt.thenCompose(mappingFn), listener);

        return nxt;
      } else {
        // let our future be tied to the first result that was in the queue
        AsyncIterators.listen(poll, listener);
        return fillMore();
      }
    }

    private CompletionStage<Either<End, U>> nextFutureImpl() {
      final CompletableFuture<Either<End, U>> retFuture = new CompletableFuture<>();

      // whether this simple future already has a buddy in the pendingResults queue
      // just need a local final reference, other things enforce memory barriers
      final boolean[] connected = new boolean[1];
      connected[0] = false;

      AsyncTrampoline.asyncWhile(
          () -> {
            CompletionStage<AsyncLock.LockToken> lockFuture = lock.acquireLock();
            return lockFuture.thenCompose(
                token -> {
                  CompletionStage<Either<End, T>> next;
                  if (connected[0]) {
                    next = fillMore();
                  } else {
                    connected[0] = true;
                    // find something for retFuture to listen to
                    next = attachListener(retFuture);
                  }
                  return next.thenApply(Either::isRight)
                      // exceptional futures get added to the queue same as normal ones,
                      // we may continue filling
                      .exceptionally(e -> true)
                      .whenComplete((t, ex) -> token.releaseLock());
                });
          });
      return retFuture;
    }

    /*
     * wait for all pending results and then call close. epoch guarantees no more new results will come in
     */
    @Override
    public CompletionStage<Void> close() {
      return FutureSupport.thenComposeOrRecover(
          epoch.terminate(),
          (b, epochError) -> {
            // call closeFn on all extra eagerly evaluated results
            CompletableFuture[] closeFutures =
                pendingResults
                    .stream()
                    .map(
                        f ->
                            f.thenCompose(
                                either -> either.fold(end -> FutureSupport.voidFuture(), closeFn)))
                    .map(CompletionStage::toCompletableFuture)
                    .toArray(CompletableFuture[]::new);

            // wait for all to complete
            CompletableFuture<Void> extraClose = CompletableFuture.allOf(closeFutures);
            return FutureSupport.thenComposeOrRecover(
                extraClose,
                (ig, extraCloseError) -> {
                  // call close on the source iterator
                  return FutureSupport.thenComposeOrRecover(
                      convertSynchronousException(backingIterator::close),
                      (ig2, backingCloseError) -> {
                        if (epochError != null) {
                          return FutureSupport.<Void>errorStage(epochError);
                        } else if (extraCloseError != null) {
                          return FutureSupport.<Void>errorStage(extraCloseError);
                        } else if (backingCloseError != null) {
                          return FutureSupport.<Void>errorStage(backingCloseError);
                        }
                        return FutureSupport.voidFuture();
                      });
                });
          });
    }

    @Override
    public CompletionStage<Either<End, U>> nextFuture() {
      return epoch
          .enter()
          .map(
              epochToken -> {
                try (ObservableEpoch.EpochToken temp = epochToken) {
                  return nextFutureImpl();
                }
              })
          .orElse(AsyncIterators.endFuture());
    }
  }

  private static class FailOnceAsyncIterator<T> implements AsyncIterator<T> {
    private Throwable exception;

    FailOnceAsyncIterator(final Throwable e) {
      this.exception = Objects.requireNonNull(e);
    }

    @Override
    public CompletionStage<Either<End, T>> nextFuture() {
      if (this.exception != null) {
        final Throwable e = this.exception;
        this.exception = null;
        return FutureSupport.errorStage(e);
      } else {
        return endFuture();
      }
    }
  }

  static <T> AsyncIterator<T> errorOnce(final Throwable ex) {
    return new FailOnceAsyncIterator<>(ex);
  }

  static <T> CompletionStage<T> asyncWhileAsyncInitial(
      final Predicate<T> shouldContinue,
      final Function<T, CompletionStage<T>> loopBody,
      final CompletionStage<T> initialValue) {
    return initialValue.thenCompose(t -> AsyncTrampoline.asyncWhile(shouldContinue, loopBody, t));
  }
}