(ns borba.handlers.json-test
  (:require
   [borba.handlers.json :as json]
   [clojure.test :refer [deftest is testing]]
   [clojure.test.check.clojure-test :as tct]
   [clojure.test.check.generators :as gen]
   [clojure.test.check.properties :as prop])
  (:import
   (com.fasterxml.jackson.core JsonProcessingException)
   (java.time Instant LocalDate)))

(set! *warn-on-reflection* true)

(defn- rejected?
  "Returns true when reading the source throws a JSON processing failure."
  [source]
  (try (json/read-json source)
       false
       (catch JsonProcessingException _ true)))

(deftest read-json-test
  (testing "reads objects with keyword keys, at any depth"
    (is (= {:a 1 :b {:c [1 2 {:d nil}]}}
           (json/read-json "{\"a\":1,\"b\":{\"c\":[1,2,{\"d\":null}]}}"))))

  (testing "reads a string or bytes"
    (is (= {:a 1} (json/read-json "{\"a\":1}")))
    (is (= {:a 1} (json/read-json (.getBytes "{\"a\":1}" "UTF-8")))))

  (testing "reads any value at the top"
    (is (= [1 2] (json/read-json "[1,2]")))
    (is (= "text" (json/read-json "\"text\"")))
    (is (= 42 (json/read-json "42")))
    (is (nil? (json/read-json "null"))))

  (testing "keeps the size of an integer and the precision of a decimal"
    (is (= 12345678901234567890123N
           (:a (json/read-json "{\"a\":12345678901234567890123}"))))
    (is (= 1.5 (:a (json/read-json "{\"a\":1.5}")))))

  (testing "reads what is not ASCII"
    (is (= {:name "Ação"}
           (json/read-json (.getBytes "{\"name\":\"Ação\"}" "UTF-8"))))))

(deftest strictness-test
  (testing "refuses text after the value"
    (is (rejected? "{\"a\":1} garbage"))
    (is (rejected? "{\"a\":1}{\"b\":2}"))
    (is (rejected? "[1] 2")))

  (testing "refuses an object with a key twice, which one parser would read as
            the first and another as the last"
    (is (rejected? "{\"a\":1,\"a\":2}"))
    (is (rejected? "{\"o\":{\"a\":1,\"a\":2}}")))

  (testing "refuses what is not JSON"
    (is (rejected? ""))
    (is (rejected? "{"))
    (is (rejected? "{'a':1}"))
    (is (rejected? "{\"a\":1 /* comment */}"))
    (is (rejected? "{\"a\":NaN}")))

  (testing "refuses nesting beyond what Jackson allows"
    (is (rejected? (str (apply str (repeat 2000 "["))
                        (apply str (repeat 2000 "]")))))))

(deftest write-json-test
  (testing "writes data with keywords as names"
    (is (= "{\"a\":1,\"b\":[true,null,\"x\"]}"
           (json/write-json {:a 1 :b [true nil "x"]})))
    (is (= "{\"error\":\"not-found\"}"
           (json/write-json {:error :not-found}))))

  (testing "writes the namespace of a keyword"
    (is (= "{\"a/b\":\"c/d\"}" (json/write-json {:a/b :c/d}))))

  (testing "writes instants and dates as ISO-8601"
    (is (= "{\"at\":\"2026-10-06T12:00:00Z\",\"on\":\"2026-10-06\"}"
           (json/write-json {:at (Instant/parse "2026-10-06T12:00:00Z")
                             :on (LocalDate/of 2026 10 6)})))))

(def ^:private json-value
  "Generates what survives a round trip through JSON."
  (gen/recursive-gen
   (fn [inner]
     (gen/one-of [(gen/vector inner)
                  (gen/map gen/keyword inner)]))
   (gen/one-of [gen/small-integer
                gen/string-alphanumeric
                gen/boolean
                (gen/return nil)])))

(tct/defspec round-trip-property
  (prop/for-all [value json-value]
    (= value (json/read-json (json/write-json value)))))
