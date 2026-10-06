(ns borba.handlers.component-test
  (:require
   [borba.handlers.component :as component]
   [borba.handlers.logging :as logging]
   [borba.handlers.registry :as registry]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [integrant.core :as ig]
   [io.pedestal.connector.test :as test]
   [io.pedestal.interceptor :as interceptor]
   [jsonista.core :as jsonista])
  (:import
   (clojure.lang MultiFn)))

(set! *warn-on-reflection* true)

(defn- with-registered
  "Registers handlers for the duration of a function, and removes them after
   it. A handler is a function of the components that returns the function
   that handles the requests; the interceptors it asks for are listed under
   :interceptors.
   - handlers: a map from the handler key to a map of :handler and, optionally,
     :interceptors
   - f: a function of no arguments"
  [handlers
   f]
  (doseq [[handler-key {:keys [handler interceptors]}] handlers]
    (.addMethod ^MultiFn registry/handler
                handler-key
                (fn [_ components] (handler components)))
    (when interceptors
      (.addMethod ^MultiFn registry/handler-interceptors
                  handler-key
                  (fn [_] interceptors))))
  (try
    (f)
    (finally
      (doseq [handler-key (keys handlers)]
        (remove-method registry/handler handler-key)
        (remove-method registry/handler-interceptors handler-key)))))

(defn- thrown-data
  [f]
  (try (f)
       nil
       (catch clojure.lang.ExceptionInfo e (ex-data e))))

(defn- call
  "Sends a request through the chain of a handler and returns the response,
   with its body read as JSON when it is."
  [handlers handler-key request]
  (let [{:keys [chain handler]} (get handlers handler-key)
        response (test/execute-interceptor-chain
                  {}
                  (mapv interceptor/interceptor (conj chain handler))
                  (merge {:request-method :get :uri "/x" :headers {}}
                         request))]
    (cond-> response
      (some? (:body response))
      (update :body #(jsonista/read-value
                      % jsonista/keyword-keys-object-mapper)))))

(defn- json-post
  [^String text]
  {:request-method :post
   :uri            "/v1/things"
   :headers        {"content-type" "application/json"}
   :body           text})

(def ^:private echo
  {:handler (fn [_components]
              (fn [request]
                {:status 200
                 :body   (select-keys request [:body-params
                                               :query-params
                                               :path-params
                                               :header-params
                                               :request-id])}))})

(deftest handler-request-test
  (with-registered
    {::echo echo}
    (fn []
      (let [handlers (component/build {})]
        (testing "hands the handler the request, parsed"
          (let [headers  {"content-type" "application/json"
                          "x-request-id" "abc-1"
                          "accept"       "*/*"}
                response (call handlers
                               ::echo
                               {:request-method :post
                                :uri            "/v1/things/7"
                                :query-string   "page=2"
                                :path-params    {:id "7"}
                                :headers        headers
                                :body           "{\"name\":\"Ana\"}"})]
            (is (= 200 (:status response)))
            (is (= {:body-params   {:name "Ana"}
                    :query-params  {:page "2"}
                    :path-params   {:id "7"}
                    :header-params {:content-type "application/json"
                                    :x-request-id "abc-1"
                                    :accept       "*/*"}
                    :request-id    "abc-1"}
                   (:body response)))
            (is (= "abc-1" (get-in response [:headers "X-Request-Id"])))
            (is (= "application/json; charset=utf-8"
                   (get-in response [:headers "Content-Type"])))))

        (testing "hands the handler the components of the service"
          (with-registered
            {::components {:handler (fn [components]
                                      (fn [_request]
                                        {:status 200 :body components}))}}
            (fn []
              (let [handlers (component/build {:components {:db "pool"}})]
                (is (= {:db "pool"}
                       (:body (call handlers ::components {}))))))))))))

(deftest handler-failure-test
  (with-registered
    {::bad-json echo
     ::crash    {:handler (fn [_components]
                            (fn [_request]
                              (throw (IllegalStateException.
                                      "password=hunter2"))))}
     ::denied   {:handler (fn [_components]
                            (fn [_request]
                              (throw (ex-info "Not allowed."
                                              {:status 403}))))}
     ::nothing  {:handler (fn [_components] (fn [_request] nil))}}
    (fn []
      (let [handlers (component/build {:max-body-bytes 32})]
        (testing "a body that is not valid JSON is a 400 with the request id"
          (let [response (call handlers ::bad-json (json-post "{nope"))]
            (is (= 400 (:status response)))
            (is (= "invalid-json" (get-in response [:body :error])))
            (is (= (get-in response [:headers "X-Request-Id"])
                   (get-in response [:body :request-id])))))

        (testing "a body that is too large is a 413, and is not parsed"
          (let [response (call handlers
                               ::bad-json
                               (json-post (str "{\"a\":\""
                                               (apply str (repeat 64 "x"))
                                               "\"}")))]
            (is (= 413 (:status response)))
            (is (= "payload-too-large" (get-in response [:body :error])))))

        (testing "a body that is not JSON is a 415"
          (let [response (call handlers
                               ::bad-json
                               {:request-method :post
                                :headers {"content-type" "text/plain"}
                                :body    "hello"})]
            (is (= 415 (:status response)))))

        (testing "a crash is a 500 that says nothing about it, and is logged"
          (let [result  (atom nil)
                entries (logging/call-capturing
                         #(reset! result (call handlers ::crash {})))
                response @result]
            (is (= 500 (:status response)))
            (is (= "An internal error occurred."
                   (get-in response [:body :message])))
            (is (not (str/includes? (pr-str response) "hunter2")))
            (is (some #(= :error (:level %)) entries))))

        (testing "an error of its author keeps its status and message"
          (let [response (call handlers ::denied {})]
            (is (= 403 (:status response)))
            (is (= "Not allowed." (get-in response [:body :message])))))

        (testing "a handler that returns no response is a 500"
          (let [result   (atom nil)
                entries  (logging/call-capturing
                          #(reset! result (call handlers ::nothing {})))
                response @result]
            (is (= 500 (:status response)))
            (is (some #(str/includes? (:message %) "did not return a response")
                      (map #(update % :message str)
                           (map #(assoc % :message
                                        (str (some-> (:cause %) ex-message)))
                                entries))))))))))

(deftest not-found-test
  (testing "has a handler for a request that no route matches"
    (let [handlers (component/build {})
          response (call handlers
                         component/not-found-key
                         {:request-method :post
                          :uri            "/nowhere"
                          :headers        {"x-request-id" "abc"}
                          :body           "{not even json"})]
      (is (= 404 (:status response)))
      (is (= {:error "not-found" :request-id "abc"}
             (select-keys (:body response) [:error :request-id])))
      (is (= "abc" (get-in response [:headers "X-Request-Id"])))))

  (testing "the key is reserved"
    (with-registered
      {component/not-found-key echo}
      (fn []
        (is (= {:error       :borba.handlers.component/reserved-handler-key
                :handler-key :borba/not-found}
               (thrown-data #(component/build {}))))))))

(deftest handler-interceptors-test
  (let [stamp   {:name  ::stamp
                 :enter (fn [ctx] (assoc-in ctx [:request :stamped] true))}
        stamped {:handler      (fn [_components]
                                 (fn [{:keys [request]}]
                                   {:status 200
                                    :body   {:stamped (:stamped request)}}))
                 :interceptors [:test/stamp]}]
    (testing "runs the interceptors a handler asks for, before the handler"
      (with-registered
        {::stamped stamped}
        (fn []
          (let [handlers (component/build {:interceptors {:test/stamp stamp}})]
            (is (= {:stamped true}
                   (:body (call handlers ::stamped {}))))))))

    (testing "fails the start naming an interceptor that is not there"
      (with-registered
        {::stamped stamped}
        (fn []
          (is (= {:error       :borba.handlers.component/unknown-interceptor
                  :handler-key ::stamped
                  :interceptor :test/stamp}
                 (thrown-data #(component/build {})))))))

    (testing "fails the start when the interceptors are not a collection"
      (with-registered
        {::stamped (assoc stamped :interceptors :test/stamp)}
        (fn []
          (is (= :borba.handlers.component/invalid-handler-interceptors
                 (:error (thrown-data #(component/build {}))))))))))

(deftest invalid-registration-test
  (testing "a handler that is not a function fails the start"
    (with-registered
      {::not-a-fn {:handler (fn [_components] {:status 200})}}
      (fn []
        (is (= {:error       :borba.handlers.component/invalid-handler
                :handler-key ::not-a-fn}
               (thrown-data #(component/build {})))))))

  (testing "a limit that is not a positive integer fails the start"
    (doseq [limit [0 -1 1.5 "1mb" nil]]
      (is (= :borba.handlers.component/invalid-max-body-bytes
             (:error (thrown-data
                      #(component/build {:max-body-bytes limit})))))))

  (testing "a handler that is not registered says so"
    (is (= :borba.handlers.registry/no-handler
           (:error (thrown-data #(registry/handler ::nowhere {})))))))

(deftest registry-defaults-test
  (testing "a handler asks for no interceptors unless it says so"
    (is (= [] (registry/handler-interceptors ::anything)))))

(deftest component-test
  (testing "initialises into the map of handlers, and logs them"
    (with-registered
      {::echo echo}
      (fn []
        (let [system  (atom nil)
              entries (logging/call-capturing
                       #(reset! system (ig/init {:service/handlers {}})))
              handlers (:service/handlers @system)]
          (is (= #{::echo component/not-found-key} (set (keys handlers))))
          (is (= [(str "registered 1 handler(s): "
                       ":borba.handlers.component-test/echo")]
                 (logging/messages entries)))))))

  (testing "warns when no handler is registered"
    (let [entries (logging/call-capturing
                   #(ig/init {:service/handlers {}}))]
      (is (= :warn (:level (first entries))))
      (is (str/includes? (:message (first entries))
                         "no handlers are registered")))))
