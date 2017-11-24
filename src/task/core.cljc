(ns task.core
  (:refer-clojure :exclude [promise])
  (:require [task.host :as h]
            [plop.core :refer [place]])
  #?(:cljs (:require-macros [task.core :refer [safe]])))

(defn ^:no-doc nop [])

(defn ^:no-doc throw! [e] (throw e))

(defmacro ^:no-doc safe [[error success] & failure]
  `(try ~success (catch ~(if (:js-globals &env) :default 'Throwable) ~error ~@failure)))

(defmacro
  ^{:arglists '([& body])
    :doc "Returns a task completing with result of body evaluation, which must be free of blocking calls."}
  effect [& body]
  `(fn [success!# failure!#]
     (h/exec! #(safe [error# (success!# (do ~@body))]
                 (failure!# error#))) nop))

(defmacro
  ^{:arglists '([& body])
    :doc "Returns a task completing with result of body evaluation, which may have blocking calls."}
  effect-off [& body]
  `(fn [success!# failure!#]
     (h/exec-off! #(safe [error# (success!# (do ~@body))]
                     (failure!# error#))) nop))

(defn
  ^{:arglists '([value])
    :doc "Returns a task immediately succeeding with given value."}
  success [value]
  (fn [success! _] (success! value) nop))

(defn
  ^{:arglists '([error])
    :doc "Returns a task immediately failing with given error."}
  failure [error]
  (assert (h/throwable? error))
  (fn [_ failure!] (failure! error) nop))


(def cancelled (ex-info "Task cancelled." {}))

(defn
  ^{:arglists '([task] [success! failure!])
    :doc "Returns a completable and memoized task. The task completes with the first result of any task given to the extra 1-arity."}
  promise []
  (let [a (atom {})
        c (fn [f]
            (fn [x]
              (let [s @a t (f x)]
                (when (map? s)
                  (when (compare-and-set! a s t)
                    (reduce-kv (fn [_ _ f] (f t)) nil s))))))
        s! (c success)
        f! (c failure)]
    (fn
      ([task] (task s! f!))
      ([success! failure!]
       (loop []
         (let [s @a]
           (if (map? s)
             (letfn [(! []
                       (let [s @a]
                         (when (map? s)
                           (if (compare-and-set! a s (dissoc s !))
                             (failure! cancelled)
                             (recur)))))]
               (if (compare-and-set! a s (assoc s ! #(% success! failure!)))
                 ! (recur)))
             (do (s success! failure!) nop))))))))

(defn
  ^{:arglists '([task])
    :doc "Starts given task, memoizes result and returns a task always completing with that result."}
  do! [t]
  (let [p (promise) ! (p t)]
    (fn
      ([] (!))
      ([s f] (p s f)))))

(def
  ^{:arglists '([task])
    :doc "Runs given task, blocking thread until termination. Returns on success, throws on failure. Host platform must support thread suspension."}
  do!! h/do!!)

(def
  ^{:arglists '([delay value])
    :doc "Returns a task succeeding with given value after given delay, in milliseconds."}
  timeout h/timeout)

(def ^:no-doc init-queue
  (conj h/empty-queue nil))

(defn ^:no-doc conj-some [c x]
  (when (some? c) (conj c x)))

(defn
  ^{:arglists '([x])
    :doc "Sentinel function. Returns true if called on itself, false otherwise."}
  pending [x]
  (identical? pending x))

(defn
  ^{:arglists '([exec! boot & args])
    :doc "Returns a task backed by an event loop, running on supplied executor (cf task)."}
  task-via
  ([exec! boot]
   (fn [success! failure!]
     (let [queue (atom init-queue)
           pump! #(safe [error (loop [jobs @queue]
                                 (let [x ((peek jobs))]
                                   (if (pending x)
                                     (let [jobs (swap! queue pop)]
                                       (when (pos? (count jobs))
                                         (recur jobs)))
                                     (do (success! x)
                                         (reset! queue nil)))))]
                    (failure! error)
                    (reset! queue nil))
           post! (fn [job]
                   (when (== 1 (count (swap! queue conj-some job))) (exec! pump!)))
           event (fn [f]
                   (comp post! (partial partial f)))
           stop! (safe [error (boot event)]
                   (failure! error)
                   (reset! queue nil))]
       (when (pos? (count (swap! queue pop))) (exec! pump!))
       (event stop!))))
  ([exec! boot a]
   (task-via exec! #(boot % a)))
  ([exec! boot a b]
   (task-via exec! #(boot % a b)))
  ([exec! boot a b c]
   (task-via exec! #(boot % a b c)))
  ([exec! boot a b c & ds]
   (task-via exec! #(apply boot % a b c ds))))

(def
  ^{:arglists '([boot & args])
    :doc "Returns a task backed by an event loop. This helper provides a basis for definition of asynchronous stateful processes, enforcing sequential handling of possibly concurrent events.
When the task is started, boot function is first called with a fresh event wrapper along with optional extra arguments, and is expected to return the handler function for the task's cancellation signal. The event wrapper is a 1-arity function taking a handler function and returning a signal function. The signal function is thread-safe, non-blocking, non-throwing, returns nil, and its effect is to schedule the execution of the handler function in the event loop. A handler function is called at most once for each call to its signal function.
The result of a handler function defines the task's current status. Throwing an exception signals task failure, returning the sentinel value `pending` signals the task is still waiting for an event, returning any other value signals task success. Success and failure trigger a call to their associated continuation and stop event processing, discarding subsequent events.
Events occuring during execution of the boot function are delayed until the boot function returns. All handler functions wrapped by the same event wrapper will be run on a cpu-bound thread pool sequentially, so they may safely share unsynchronized mutable state."}
  task (partial task-via h/exec!))

(defn
  ^{:arglists '([event f & tasks])
    :doc "Join task boot function (cf join)."}
  join* [event f & ts]
  (if (seq ts)
    (let [size (count ts)
          args (object-array (repeat size event))]
      (place [^int completed 0
              !s (into []
                       (map-indexed
                         (fn [i t]
                           (t (event
                                (fn [x]
                                  (if (identical? (aget args i) event)
                                    (do
                                      (set! completed (inc completed))
                                      (aset args i x)
                                      (if (== size completed)
                                        (apply f args) pending)) pending)))
                              (event (fn [x] (!) (throw x)))))) ts)
              ! #(reduce (fn [_ !] (!)) nil !s)] !))
    (do ((event f)) nop)))

(def
  ^{:arglists '([f & tasks])
    :doc "Returns a task running given tasks in parallel and completing with application of function f to results. Input task failures propagate to returned task and cancel tasks still pending."}
  join (partial task join*))

(defn ^:no-doc race-error [errors]
  (ex-info "Race error : no competitor succeeded." {:errors (vec errors)}))

(defn
  ^{:arglists '([event & tasks])
    :doc "Race task boot function (cf race)."}
  race* [event & ts]
  (if (seq ts)
    (let [size (count ts)
          errors (object-array (repeat size event))]
      (place [^int failed 0
              !s (into []
                       (map-indexed
                         (fn [i t]
                           (t (event (fn [x] (!) x))
                              (event (fn [e]
                                       (if (identical? (aget errors i) event)
                                         (do (aset errors i e)
                                             (set! failed (inc failed))
                                             (if (== size failed)
                                               (throw (race-error errors))
                                               pending)) pending)))))) ts)
              ! #(reduce (fn [_ !] (!)) nil !s)] !))
    (do ((event throw!) (race-error nil)) nop)))

(def
  ^{:arglists '([& tasks])
    :doc "Returns a task running given tasks in parallel and completing with first succeeding result, cancelling late tasks."}
  race (partial task race*))

(defn
  ^{:arglists '([event task f & args])
    :doc "Then task boot function (cf then)."}
  then*
  ([event t f]
   (let [failure (event throw!)]
     (place [! (t (event (fn [x] (set! ! ((f x) (event identity) failure)) pending)) failure)] #(!))))
  ([event t f a] (then* event t #(f % a)))
  ([event t f a b] (then* event t #(f % a b)))
  ([event t f a b c] (then* event t #(f % a b c)))
  ([event t f a b c & ds] (then* event t #(apply f % a b c ds))))

(def
  ^{:arglists '([task f & args])
    :doc "Returns a task running input task, then if successful passing result to function f along with optional extra arguments. f must return a task that will be runned to yield the final result."}
  then (partial task then*))

(defmacro
  ^{:arglists '([bindings & body])
    :doc "Task let. Returns a task sequentially running each task returned by evaluation of right expressions, binding each result to left symbol if successful, then completing with body evaluation."}
  tlet [bindings & body]
  (if-some [[s t & bindings] (seq bindings)]
    `(then ~t (fn [~s] (tlet ~bindings ~@body)))
    `(success (do ~@body))))

(defn
  ^{:arglists '([event task f & args])
    :doc "Else task boot function (cf else)."}
  else*
  ([event t f]
   (let [success (event identity)]
     (place [! (t success (event (fn [e] (set! ! ((f e) success (event throw!))) pending)))] #(!))))
  ([event t f a] (else* event t #(f % a)))
  ([event t f a b] (else* event t #(f % a b)))
  ([event t f a b c] (else* event t #(f % a b c)))
  ([event t f a b c & ds] (else* event t #(apply f % a b c ds))))

(def
  ^{:arglists '([task f & args])
    :doc "Returns a task completing with result of input task if successful, otherwise applying f to the error along with optional extra arguments. f must return a task that will be run to yield the final result."}
  else (partial task else*))
