(ns task.core-test
  (:require
    #?(:clj  [clojure.test :refer [deftest is]]
       :cljs [cljs.test :refer-macros [deftest is async]])
             [task.core :as t]))

(defn expect [t r]
  #?(:clj
     (is (= r (t/safe [e (t/do!! t)] ::failure)))
     :cljs
     (async done (t (fn [x] (is (= r x)) (done)) (fn [_] (is (= r ::failure)) (done))))))

(def error (ex-info "Something wrong happened." {}))
(defn fail [& _] (throw error))

(deftest timeout
  (expect (t/timeout 0 42) 42))

(deftest success
  (expect (t/success 42) 42))

(deftest failure
  (expect (t/failure error) ::failure))

(deftest do!-success
  (expect (t/do! (t/success 42)) 42))

(deftest do!-failure
  (expect (t/do! (t/failure error)) ::failure))

(deftest join0-success
  (expect (t/join #(do 42)) 42))

(deftest join0-failure
  (expect (t/join fail) ::failure))

(deftest join1-success
  (expect (t/join identity (t/success 42)) 42))

(deftest join1-failure
  (expect (t/join fail (t/success 42)) ::failure))

(deftest join2-success
  (expect (t/join * (t/success 6) (t/success 7)) 42))

(deftest join2-failure
  (expect (t/join fail (t/success 6) (t/success 7)) ::failure))

(deftest race0
  (expect (t/race) ::failure))

(deftest race1-success
  (expect (t/race (t/success 42)) 42))

(deftest race1-failure
  (expect (t/race (t/failure error)) ::failure))

(deftest race2-success
  (expect (t/race (t/failure error) (t/success 42)) 42))

(deftest race2-failure
  (expect (t/race (t/failure error) (t/failure error)) ::failure))

(deftest then-success
  (expect (t/then (t/success 6) (comp t/success *) 7) 42))

(deftest then-failure
  (expect (t/then (t/success 42) fail) ::failure))

(deftest tlet-success
  (expect (t/tlet [a (t/success 6) b (t/success (inc a))] (* a b)) 42))

(deftest tlet-failure
  (expect (t/tlet [a (t/success 6) b (t/success (inc a))] (fail b a)) ::failure))

(deftest else-success
  (expect (t/else (t/failure error) t/success) error))

(deftest else-failure
  (expect (t/else (t/failure error) t/failure) ::failure))
