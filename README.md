# Task

This project aims to define a standard to represent effects as values in clojure, in order to allow various functional composition strategies on top of an unified interface with reliable semantics.

A task is the definition of an operation that can be executed an arbitrary number of times. An execution eventually succeeds or fails, produces a single value, and supports early termination.

The specification relies on plain clojure functions and is fully asynchronous. Asynchronicity allows targeting single-threaded host platforms (e.g js engines, gui frameworks) and makes efficient usage of system resources on multi-threaded host platforms.


## Rationale

This initiative is motivated by the lack of consistency of currently popular solutions to this problem in clojure ecosystem, including :
* futures and promises, representing eager and memoized computations as a stateful object and supporting composition. `clojure.core`'s `future` relies on host platform's ability to suspend threads to provide compositionality, which makes it unreachable to javascript hosts. Various improvements over the same idea were made in Java and Javascript, most notably the ability to register callbacks allowing non-blocking composition with functional-style operators. There is still no consensus on whether and how cancellation handling should be propagated along derivated values.
* `core.async` channels, a generic abstraction to model backpressured streams of values across asynchronous boundaries. They're commonly used as an alternative to futures to represent single result pending computations, mainly because they're required to leverage `go` coroutine syntax for sequential processes involving asynchronicity. Channels are unaware of failure, so additional care has to be taken during composition, which has led to creative workarounds.

Additionally, neither channels nor futures are pure values, which tends to make the imperative parts of the program contaminate the functional ones. It's always possible to make these constructs functional by wrapping them in thunks to delay execution, but at this point it is more straightforward to work with continuations directly.

The idea of representing effects as values is inspired by Haskell's IO and various derivative works from Scala ecosystem, including Monix, Scalaz and Cats. The task specification differs from these projects in that it doesn't introduce new types, relying solely on a convention on language primitives and delegating implementation to third-party libraries.


## Specification

### Task
A task is a 2-arity function taking a success continuation as first argument and a failure continuation as second argument. It must return a canceller, must not throw and must not block the calling thread. A call to a task function starts the execution of underlying operation, eventually calling one of the two continuations with a result.

### Continuation
A continuation is a 1-arity function taking the result of the task as argument. Its return value should be ignored, it must not throw and must not block the calling thread. Calling either continuation notifies termination to the caller. The task executor must not make a subsequent call to either continuation. A continuation may be called synchronously with the task call if result is immediately available.

### Canceller
A canceller is a 0-arity function. Its return value should be ignored, it must not throw and must not block the calling thread. A call to this function notifies the task executor that the caller wants the operation to be terminated as soon as possible. Cancellation is a best-effort operation and it is up to the task designer to be explicit in its cancellation strategy. Calls to a canceller are expected to be idempotent, eventually becoming no-ops when execution terminates.


## Examples

Building a task from a result known in advance is trivial. Continuation is called synchronously, cancellation is a no-op.
```clojure
(defn success [x]
  (fn [! _] (! x) #(do)))

(defn failure [x]
  (fn [_ !] (! x) #(do)))

(success 42)                        ;; returns a task immediately succeeding with 42
(failure (NullPointerException.))   ;; returns a task immediately failing with NPE
```

Wrapping an asynchronous effect into a task is generally straightforward as long as the library used provides a low-level callback-based API. For example, [clj-http](https://github.com/dakrone/clj-http) requests can be made task-compliant.
```clojure
(require '[clj-http.client :as h])

(defn http-get [url]
  (fn [success! failure!]
    (let [fut (h/get url {:async? true
                          :oncancel #(failure! (ex-info "GET request interrupted." {:url url}))}
                     success! failure!)]
      #(.cancel ^java.util.concurrent.Future fut true))))

(http-get "https://clojure.org")      ;; returns a task performing request and completing with response
```


## Implementations

* [missionary](https://github.com/leonoel/missionary)

Compliant implementations are welcome, please submit a PR to expand this list.
