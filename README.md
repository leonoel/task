# task

This project aims to define a standard to represent generic computations as values in Clojure. The goal is to promote functional programming by allowing various composition strategies around the unified *task* abstraction. A task is the definition of a process that can be started, eventually terminating with success or failure, producing a single value, and able to receive interruption requests. Tasks may be asynchronous, a mandatory requirement on single-threaded host platforms (e.g js engines, gui frameworks) and a good practice when high scalability is required.

Along with the [specification](#Specification), a [toolkit](#Toolkit) is provided as a reference implementation to perform basic operations on tasks.

## Rationale

This initiative is motivated by the lack of consistency of currently popular solutions to this problem in clojure ecosystem, including :
* futures, representing eager and memoized computations as a stateful object and supporting composition. clojure.core future relies on host platform's ability to suspend threads to provide compositionality, which makes it unreachable to javascript hosts. Various improvements over the same idea were made in Java and Javascript, most notably the ability to register callbacks allowing non-blocking composition with functional-style operators.
* core.async, a framework providing an universal abstraction to model backpressured streams of values across asynchronous boundaries, and some syntactic sugar to represent asynchronous processing logic in sequential imperative style. It is often considered as the default tool to solve callback hell in clojure, and is commonly used as an alternative to futures to represent single result pending computations, which has led to creative workarounds to compensate for lack of error handling and cancellation mechanism. This situation is unsatisfactory, because a heavy dependency is still needed for every part of the program designed this way, although in many cases the backpressured streaming feature of core.async is not needed at all.

Moreover, neither channels nor futures are pure values, and thus contaminate other parts of the program by promoting imperative programming. Actually, the only way to represent effects as values is to delay their execution by wrapping them in a lazy construct.

## Specification

All effects are represented as clojure functions, as they are well defined and generic enough to represent any computation (including impure ones). Choosing to rely on a language convention instead of introducing new types or protocols simplifies design and promotes development of various compliant implementations.

### Task
A task is a 2-arity function taking a success continuation as first argument and a failure continuation as second argument. It must return a canceller, must not throw and must not block the calling thread. A call to a task function starts a computation performing side effects, eventually calling one of the two continuations with a result.

### Continuation
A continuation is a 1-arity function taking the result of the task as argument. Its return value should be ignored, it must not throw and must not block the calling thread. The first call to any of both continuations of a task notify termination to the caller, and subsequent calls must be ignored. The task caller should expect continuations to be called synchronously or asynchronously.

### Canceller
A canceller is a 0-arity function. Its return value should be ignored, must not throw and must not block the calling thread. A call to this function notifies the task runner that the caller is not expecting a result anymore. If the task is still pending, then it must be cancelled if possible and allocated resources must be cleaned up. If the task is already terminated or cancelled, then the cancellation must be ignored.

## Toolkit

### Maturity
Alpha. Users should expect breaking changes in future versions.

### Installation
Artifacts are released to clojars.

Leiningen coordinates :
```clj
[task "a.1"]
```

### Support
All functions and macros work the same way on Clojure and Clojurescript, except for `effect-off` and `do!!` not working in Clojurescript due to host thread suspension requirement.

### Documentation
The API stands in the single namespace `task.core`.
```clj
(require '[task.core :as t])
```

#### Task creation & usage
`(success value)` and `(failure error)` return tasks completing immediately with given result.
```clj
(defn err [e] (binding [*out* *err*] (prn e)))
((t/success 42) prn err)                                     ;; prints 42 on standard output
((t/failure (ex-info "Something went wrong." {})) prn err)   ;; prints exception info on standard error
```

Because tasks are just functions, many asynchronous APIs can be made task-compliant simply by returning a 2-arity function starting the process using input continuations as callbacks and returning a canceller.

A synchronous effect can be wrapped in a task with macro `(effect & body)`, returning a task scheduling the evaluation the body in a cpu-bound thread pool and completing with the value of last expression.
```clj
(def random (t/effect (rand)))
(random prn err)           ;; prints a random number
(random prn err)           ;; prints another random number
```

Blocking effects should be wrapped using macro `(effect-off & body)`, performing the evaluation on an unbounded thread pool.
```clj
((t/effect-off (slurp "https://clojure.org")) prn err)    ;; prints clojure.org home page
```

`(timeout delay value)` returns a task succeeding with a given value after a given delay (in milliseconds).
```clj
((t/timeout 1000 42) prn err)          ;; prints 42 after 1 second
```

Tasks are lazy by default, but can be realized and memoized with `(do! task)` to provide future-like behaviour.
```clj
(def not-so-random (t/do! random))
(not-so-random prn err)                ;; prints a random number
(not-so-random prn err)                ;; prints the same number
```

`(do!! task)` realizes a task synchronously, blocking calling thread until completion.
```clj
(t/do!! random)                        ;; returns a random number
```

#### Basic task composition
`(join f & tasks)` returns a task running multiple tasks in parallel, then merging results with function f. If any of input tasks fails, others are cancelled and error is propagated to output task.
```clj
(t/do!! (t/join * (t/timeout 1000 6) (t/timeout 1000 7)))               ;; returns 42 after 1 second
```

`(race & tasks)` returns a task running multiple tasks in parallel, completing with first succeeding result and cancelling late tasks.
```clj
(t/do!! (t/race (t/timeout 1000 :turtle) (t/timeout 2000 :rabbit)))     ;; returns :turtle after 1 second
```

`(then task f & args)` returns a task running input task, then if successful passing result to function f along with optional extra arguments. f must return a task that will be runned to yield the final result.
```clj
(t/do!! (t/then (t/timeout 1000 6) #(t/timeout 1000 (* % (inc %)))))    ;; returns 42 after 2 seconds
```

`then` chains can be flattened using macro `(tlet bindings & body)` providing let-style notation.
```clj
(t/do!! (t/tlet [a (t/timeout 1000 6)
                 b (t/timeout 1000 (inc a))]
          (* a b)))                              ;; returns 42 after 2 seconds
```

`(else task f & args)` returns a task completing with result of input task if successful, otherwise applying f to the error along with optional extra arguments. f must return a task that will be run to yield the final result.
```clj
(def failure (t/effect (/ 1 0)))
(t/do!! failure)                                 ;; throws ArithmeticException
(t/do!! (t/else failure t/success))              ;; returns ArithmeticException
```

#### Writing custom combinators
Combinators described above are built with the `(task boot & args)` function, returning a task backed by an event loop. This helper provides a basis for definition of asynchronous stateful processes, enforcing sequential handling of possibly concurrent events.

When the task is started, boot function is first called with a fresh event wrapper along with optional extra arguments, and is expected to return the handler function for the task's cancellation signal. The event wrapper is a 1-arity function taking a handler function and returning a signal function. The signal function is thread-safe, non-blocking, non-throwing, returns nil, and its effect is to schedule the execution of the handler function in the event loop. A handler function is called at most once for each call to its signal function.

The result of a handler function defines the task's current status. Throwing an exception signals task failure, returning the sentinel value `pending` signals the task is still waiting for an event, returning any other value signals task success. Success and failure trigger a call to their associated continuation and stop event processing, discarding subsequent events.

Events occuring during execution of the boot function are delayed until the boot function returns. All handler functions wrapped by the same event wrapper will be run on a cpu-bound thread pool sequentially, so they may safely share unsynchronized mutable state.

`(task-via executor boot & args)` is a variant of `task` allowing to bind the event loop to a custom executor.

### License
Licensed under the [Eclipse Public License](http://www.eclipse.org/legal/epl-v10.html) (the same as Clojure)
