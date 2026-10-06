(ns borba.handlers.interceptors-test
  (:require
   [borba.handlers.interceptors :as i]
   [borba.handlers.logging :as logging]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [jsonista.core :as jsonista])
  (:import
   (java.io ByteArrayInputStream InputStream)))

(set! *warn-on-reflection* true)

(defn- enter
  "Runs the :enter of an interceptor on a context."
  [interceptor ctx]
  ((:enter interceptor) ctx))

(defn- leave
  "Runs the :leave of an interceptor on a context."
  [interceptor ctx]
  ((:leave interceptor) ctx))

(defn- stream
  [^String text]
  (ByteArrayInputStream. (.getBytes text "UTF-8")))

(defn- body-request
  "Returns a request with a body, and headers that say it is JSON."
  [^String text headers]
  {:body    (stream text)
   :headers (merge {"content-type" "application/json"} headers)})

(defn- thrown-data
  "Returns the data of the exception a function throws, or nil."
  [f]
  (try (f)
       nil
       (catch clojure.lang.ExceptionInfo e (ex-data e))))

(deftest request-id-test
  (testing "makes an id when the client sends none"
    (let [id (get-in (enter i/request-id {:request {:headers {}}})
                     [:request :request-id])]
      (is (re-matches #"[0-9a-f-]{36}" id))))

  (testing "keeps the id a client sends, when it is safe to log"
    (doseq [supplied ["abc-123" "A.b_c-9" (apply str (repeat 128 "a"))]]
      (is (= supplied
             (get-in (enter i/request-id
                            {:request {:headers {"x-request-id" supplied}}})
                     [:request :request-id])))))

  (testing "replaces an id that could forge a line of the log"
    (doseq [supplied ["abc\nINFO forged" "has space" "" "é"
                      (apply str (repeat 129 "a"))]]
      (is (re-matches
           #"[0-9a-f-]{36}"
           (get-in (enter i/request-id
                          {:request {:headers {"x-request-id" supplied}}})
                   [:request :request-id])))))

  (testing "puts the id in the response"
    (is (= "abc"
           (get-in (leave i/request-id
                          {:request  {:request-id "abc"}
                           :response {:status 200}})
                   [:response :headers "X-Request-Id"]))))

  (testing "does not invent a response"
    (is (= {:request {:request-id "abc"}}
           (leave i/request-id {:request {:request-id "abc"}})))))

(deftest access-log-test
  (testing "logs the method, the path, the status, the time and the id"
    (let [ctx     (enter i/access-log {})
          entries (logging/call-capturing
                   #(leave i/access-log
                           (assoc ctx
                                  :request  {:request-method :get
                                             :uri            "/v1/users"
                                             :request-id     "abc"}
                                  :response {:status 200})))]
      (is (= 1 (count entries)))
      (is (re-matches #"GET /v1/users 200 \d+ ms request-id=abc"
                      (first (logging/messages entries))))))

  (testing "never logs the query string"
    (let [ctx     (enter i/access-log {})
          entries (logging/call-capturing
                   #(leave i/access-log
                           (assoc ctx
                                  :request  {:request-method :get
                                             :uri            "/v1/users"
                                             :query-string   "token=secret"
                                             :request-id     "abc"}
                                  :response {:status 200})))]
      (is (not (str/includes? (first (logging/messages entries)) "secret")))))

  (testing "says so when there is no status"
    (let [ctx     (enter i/access-log {})
          entries (logging/call-capturing
                   #(leave i/access-log
                           (assoc ctx
                                  :request {:request-method :get
                                            :uri            "/x"
                                            :request-id     "abc"})))]
      (is (re-find #"GET /x - " (first (logging/messages entries)))))))

(deftest http-error-handler-test
  (let [handle (:error i/http-error-handler)
        ctx    {:request {:request-id "abc"}}]
    (testing "answers an error of its author with its status and message"
      (let [entries  (logging/call-capturing
                      #(handle ctx (ex-info "No." {:status 403})))
            response (:response (handle ctx (ex-info "No." {:status 403})))]
        (is (= 403 (:status response)))
        (is (= {:error "forbidden" :message "No." :request-id "abc"}
               (jsonista/read-value (:body response)
                                    jsonista/keyword-keys-object-mapper)))
        (is (empty? entries))))

    (testing "answers a failure of the server with nothing about it, and logs
              what went wrong with the id"
      (let [thrown   (IllegalStateException. "password=hunter2")
            result   (atom nil)
            entries  (logging/call-capturing
                      #(reset! result (handle ctx thrown)))
            response (:response @result)]
        (is (= 500 (:status response)))
        (is (not (str/includes? (:body response) "hunter2")))
        (is (str/includes? (:body response) "\"request-id\":\"abc\""))
        (is (= ["request abc failed"] (logging/messages entries)))
        (is (identical? thrown (:cause (first entries))))))))

(defn- query-of
  "Runs parse-query on a query string and returns :query-params."
  [query-string]
  (get-in (enter i/parse-query {:request {:query-string query-string}})
          [:request :query-params]))

(deftest request-parsers-test
  (testing "inject-components puts the components in the request"
    (is (= {:db :pool}
           (get-in (enter (i/inject-components {:db :pool}) {:request {}})
                   [:request :components]))))

  (testing "parse-query reads the query string into keywords"
    (is (= {:page "2" :q "a b"}
           (get-in (enter i/parse-query
                          {:request {:query-string "page=2&q=a%20b"}})
                   [:request :query-params]))))

  (testing "parse-query has an empty map without a query string"
    (is (= {}
           (get-in (enter i/parse-query {:request {}})
                   [:request :query-params]))))

  (testing "parse-query keeps a repeated parameter as a vector"
    (is (= {:a ["1" "2"] :b "3"} (query-of "a=1&a=2&b=3"))))

  (testing "parse-query gives a parameter without a value an empty one"
    (is (= {:flag "" :page "2"} (query-of "flag&page=2")))
    (is (= {:a "" :b ""} (query-of "a&b"))))

  (testing "parse-query refuses a query string that cannot be decoded"
    (is (= {:status 400 :error :invalid-query}
           (thrown-data #(enter i/parse-query
                                {:request {:query-string "a=%zz"}})))))

  (testing "parse-path-params keeps the params and defaults to none"
    (is (= {:id "7"}
           (get-in (enter i/parse-path-params
                          {:request {:path-params {:id "7"}}})
                   [:request :path-params])))
    (is (= {}
           (get-in (enter i/parse-path-params {:request {}})
                   [:request :path-params]))))

  (testing "parse-headers copies the headers"
    (is (= {"accept" "*/*"}
           (get-in (enter i/parse-headers
                          {:request {:headers {"accept" "*/*"}}})
                   [:request :headers-map])))
    (is (= {}
           (get-in (enter i/parse-headers {:request {}})
                   [:request :headers-map])))))

(defn- parsed
  "Runs the default body parser on a request and returns :body-params."
  [request]
  (get-in (enter i/parse-body {:request request}) [:request :body-params]))

(deftest parse-body-test
  (testing "reads a JSON body, strictly, with keyword keys"
    (is (= {:name "Ana" :tags ["a" "b"]}
           (parsed (body-request "{\"name\":\"Ana\",\"tags\":[\"a\",\"b\"]}"
                                 {})))))

  (testing "has an empty map without a body, with an empty one, or with null"
    (is (= {} (parsed {:headers {}})))
    (is (= {} (parsed {:body (stream "") :headers {}})))
    (is (= {} (parsed (body-request "null" {})))))

  (testing "keeps a JSON value that is not an object"
    (is (= [1 2] (parsed (body-request "[1,2]" {}))))
    (is (= false (parsed (body-request "false" {})))))

  (testing "accepts the media types of JSON"
    (doseq [content-type ["application/json"
                          "application/json; charset=utf-8"
                          "APPLICATION/JSON"
                          "application/vnd.api+json"
                          "application/merge-patch+json; charset=utf-8"]]
      (is (= {:a 1}
             (parsed (body-request "{\"a\":1}"
                                   {"content-type" content-type}))))))

  (testing "refuses a body that is not JSON by its media type, with 415"
    (doseq [headers [{"content-type" "text/plain"}
                     {"content-type" "application/x-www-form-urlencoded"}
                     {"content-type" "application/jsonp"}
                     {"content-type" nil}]]
      (is (= {:status 415 :error :unsupported-media-type}
             (thrown-data
              #(parsed {:body (stream "{\"a\":1}") :headers headers}))))))

  (testing "refuses what is not valid JSON, with 400"
    (doseq [text ["{" "not json" "{\"a\":1} trailing" "{\"a\":1,\"a\":2}"]]
      (is (= {:status 400 :error :invalid-json}
             (thrown-data #(parsed (body-request text {}))))))))

(deftest body-limit-test
  (let [limited (i/body-parser {:max-body-bytes 16})
        run     (fn [request]
                  (get-in (enter limited {:request request})
                          [:request :body-params]))]
    (testing "accepts a body at the limit"
      (is (= {:k "01234567"}
             (run (body-request "{\"k\":\"01234567\"}" {})))))

    (testing "refuses a body past the limit, with 413"
      (is (= {:status 413 :error :payload-too-large}
             (thrown-data
              #(run (body-request "{\"k\":\"012345678\"}" {}))))))

    (testing "refuses by what the request declares, without reading it"
      (let [unread (proxy [InputStream] []
                     (read
                       ([] (throw (AssertionError. "the body was read")))
                       ([_buffer] (throw (AssertionError. "the body was read")))
                       ([_buffer _offset _length]
                        (throw (AssertionError. "the body was read")))))]
        (is (= {:status 413 :error :payload-too-large}
               (thrown-data
                #(run {:body    unread
                       :headers {"content-type"   "application/json"
                                 "content-length" "17"}}))))))

    (testing "refuses by what the request sends when it declares nothing"
      (is (= {:status 413 :error :payload-too-large}
             (thrown-data
              #(run {:body    (stream (apply str (repeat 1000 "x")))
                     :headers {"content-type" "application/json"}})))))

    (testing "ignores a length that is not a number"
      (is (= {:a 1}
             (run (body-request "{\"a\":1}"
                                {"content-length" "many"})))))))

(deftest json-response-test
  (testing "writes a map or a collection as JSON, with the content type"
    (doseq [[body json] [[{:a 1} "{\"a\":1}"]
                         [[1 2] "[1,2]"]
                         [#{:x} "[\"x\"]"]]]
      (let [response (:response (leave i/json-response
                                       {:response {:status 200 :body body}}))]
        (is (= json (:body response)))
        (is (= "application/json; charset=utf-8"
               (get-in response [:headers "Content-Type"]))))))

  (testing "leaves a string, nil or no response as they are"
    (is (= {:response {:status 200 :body "text"}}
           (leave i/json-response {:response {:status 200 :body "text"}})))
    (is (= {:response {:status 204}}
           (leave i/json-response {:response {:status 204}})))
    (is (= {} (leave i/json-response {})))))
