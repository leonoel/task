# task

The purpose of this specification is to provide a Clojure standard to describe asynchronous computations in a functional, generic, composable and portable way.
The toolkit is provided as a proof-of-concept to perform basic operations on tasks, however it is not a mandatory requirement to define your own handlers according to the semantics provided by the specification.

## Rationale


* generic : relying on conventions around language primitives simplifies design and promotes various compliant implementations.
* functional : futures and channels, being stateful primitives, promote imperative style. The only way to deal with effects in a functional style is to wrap them in a lazy construct.
* composable : a small set of combinators should be sufficient to arbitrarily compose simple tasks, at least on par with various kinds of future/promise
* portable : semantics must not rely on specificities of various host platforms, especially concerning threading policy.

## Specification

### Task
A task is a 2-arity function taking a success continuation as first argument and a failure continuation as second argument. It must return a canceller, must not throw and must not block the calling thread. A call to a task function triggers a computation with optional side-effects, eventually completing with a status (success or failure) and a value.

### Continuation
A continuation is a 1-arity function taking the result of the task as argument. Its return value should be ignored, it must not throw and must not block the calling thread. Continuations must be thread-safe and may be called any number of times. The first call to any of both continuations notify task termination to the caller, and subsequent calls must be ignored. Continuations may be called synchronously (ie before the task function returns).

### Canceller
A canceller is a 0-arity function. Its return value should be ignored, must not throw, must not block the calling thread, and must be thread-safe. A call to this function notifies the task runner that the caller is not expecting a result anymore. If the task is still pending when the cancellation is received, then it must be cancelled if possible and allocated resources must be cleaned up.

## Toolkit

### Installation

Coordinates

### API

Full API documentation

### Usage
Single namespace entry point
```clj
(require '[task.core :as t])
```

### Examples
From a synchronous computation
```clj
(def random (t/just (rand)))
(random println println)    ;; prints a random number
(random println println)    ;; prints another random number
```

Future-like behaviour (eager and memoized)
```clj
(def not-so-random (t/do! (t/just (rand))))
(not-so-random println println)    ;; prints a random number
(not-so-random println println)    ;; prints the same number
```

Delayed computation
```clj
(def delayed (t/timeout 1000 42))
(delayed println println)    ;; prints 42 after 1 second
```

Parallel computation
```clj
(def parallel (t/zip * (t/timeout 1000 6) (t/timeout 1000 7)))
(parallel println println)    ;; prints 42 after 1 second
```

Sequential computation
```clj
(def sequential (t/bind (t/timeout 1000 6) #(t/timeout 1000 (* % (inc %)))))
(sequential println println)    ;; prints 42 after 2 seconds
```

Alternatively, using monadic notation
```clj
(def sequential
  (t/alet [a (t/timeout 1000 6)
           b (t/timeout 1000 (inc a))]
    (* a b)))
(sequential println println)    ;; prints 42 after 2 seconds
```

Error recovery
```clj
(def failure (t/just (/ 1 0)))
(def recover (t/recover failure (constantly :oops)))
(failure println println)    ;; dumps ArithmeticException
(recover println println)    ;; prints :oops
```

Race
```clj
(def race (t/race (t/timeout 1000 :turtle) (t/timeout 2000 :rabbit)))
(race println println)    ;; prints :turtle after 1 second
```

