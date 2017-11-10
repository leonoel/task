(ns task.host
  (:import (clojure.lang Agent PersistentQueue)
           (java.util.concurrent ScheduledThreadPoolExecutor CountDownLatch Future TimeUnit)
           (java.util.concurrent.atomic AtomicReference)))

(defn throwable? [x] (instance? Throwable x))

(def empty-queue PersistentQueue/EMPTY)

(defn do!!
  ([t]
   (let [a (AtomicReference. nil)
         l (CountDownLatch. 1)
         c (fn [f]
             (fn [x]
               (when (.compareAndSet a nil (f x))
                 (.countDown l))))
         ! (t (c (fn [x] #(do x))) (c (fn [e] #(throw e))))]
     (try (.await l) ((.get a))
          (catch InterruptedException e (!) (throw e))))))

(defn exec-off! [^Runnable action]
  (.execute Agent/soloExecutor action))

(defn exec! [^Runnable action]
  (.execute Agent/pooledExecutor action))

(def ^ScheduledThreadPoolExecutor scheduler
  (ScheduledThreadPoolExecutor. 1))

(defn timeout
  ([t x]
   (fn [success! _]
     (let [fut (.schedule scheduler
                          (reify Runnable (run [_] (success! x)))
                          (long t) TimeUnit/MILLISECONDS)]
       #(.cancel ^Future fut false)))))
