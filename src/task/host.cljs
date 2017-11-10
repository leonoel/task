(ns task.host
  (:require [goog.async.nextTick]))

(defn throwable? [_] true)

(def empty-queue #queue [])

(defn- fail-blocking! []
  (throw (js/Error. "Thread suspension is not supported.")))

(defn do!! [_]
  (fail-blocking!))

(defn exec-off! [_]
  (fail-blocking!))

(def exec! goog.async.nextTick)

(defn timeout [t x]
  (fn [success! _]
    (partial js/clearTimeout (js/setTimeout (partial success! x) t))))
